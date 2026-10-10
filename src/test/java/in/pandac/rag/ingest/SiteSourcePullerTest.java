package in.pandac.rag.ingest;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SiteSourcePullerTest {

    @TempDir
    Path knowledge;

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> etag = new AtomicReference<>("\"v1\"");
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> conditional = new ArrayList<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/knowledge.json", ex -> {
            requests.incrementAndGet();
            String inm = ex.getRequestHeaders().getFirst("If-None-Match");
            if (inm != null) {
                conditional.add(inm);
            }
            if (status.get() == 200 && inm != null && inm.equals(etag.get())) {
                ex.sendResponseHeaders(304, -1);
                ex.close();
                return;
            }
            byte[] b = body.get().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("ETag", etag.get());
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status.get(), b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private SiteSourcePuller puller(long maxBytes) {
        return new SiteSourcePuller(knowledge.toString(), "", maxBytes);
    }

    private SiteSourcePuller.Source source() {
        return new SiteSourcePuller.Source("acme",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/knowledge.json"));
    }

    private static String manifest(String digest, String... paths) {
        StringBuilder docs = new StringBuilder();
        for (String p : paths) {
            if (!docs.isEmpty()) {
                docs.append(',');
            }
            docs.append("{\"path\":\"").append(p).append("\",\"title\":\"").append(p)
                    .append("\",\"url\":\"https://x.test/").append(p).append("\",\"content\":\"# ").append(p).append("\"}");
        }
        return "{\"version\":1,\"digest\":\"" + digest + "\",\"documents\":[" + docs + "]}";
    }

    @Test
    void pullsWritesFilesAndThenUsesConditionalRequests() {
        body.set(manifest("d1", "a.md", "b.md"));
        SiteSourcePuller p = puller(1_000_000);

        assertThat(p.pull(source())).isEqualTo(SiteSourcePuller.Outcome.UPDATED);
        assertThat(knowledge.resolve("acme/a.md")).exists();
        assertThat(knowledge.resolve("acme/b.md")).exists();

        // The second pull sends If-None-Match and gets a 304: no download, no writes.
        assertThat(p.pull(source())).isEqualTo(SiteSourcePuller.Outcome.UNCHANGED);
        assertThat(conditional).containsExactly("\"v1\"");
    }

    @Test
    void aChangedSiteUpdatesAndRemovesDocuments() throws Exception {
        body.set(manifest("d1", "a.md", "b.md"));
        SiteSourcePuller p = puller(1_000_000);
        p.pull(source());

        body.set(manifest("d2", "a.md"));
        etag.set("\"v2\"");

        assertThat(p.pull(source())).isEqualTo(SiteSourcePuller.Outcome.UPDATED);
        assertThat(knowledge.resolve("acme/b.md")).doesNotExist();
        assertThat(Files.readString(knowledge.resolve("acme/a.md"))).contains("https://x.test/a.md");
    }

    @Test
    void anOutageOrABadResponseNeverRemovesExistingKnowledge() {
        body.set(manifest("d1", "a.md"));
        SiteSourcePuller p = puller(1_000_000);
        p.pull(source());
        assertThat(knowledge.resolve("acme/a.md")).exists();
        etag.set("\"v2\"");

        status.set(500);
        assertThat(p.pull(source())).isEqualTo(SiteSourcePuller.Outcome.FAILED);

        status.set(200);
        body.set("<html>maintenance page</html>");
        assertThat(p.pull(source())).isEqualTo(SiteSourcePuller.Outcome.FAILED);

        body.set("{\"version\":1,\"documents\":[]}");
        assertThat(p.pull(source())).isEqualTo(SiteSourcePuller.Outcome.FAILED);

        body.set(manifest("d3", "../escape.md"));
        assertThat(p.pull(source())).isEqualTo(SiteSourcePuller.Outcome.FAILED);

        assertThat(knowledge.resolve("acme/a.md")).exists();
        assertThat(knowledge.resolve("escape.md")).doesNotExist();
        assertThat(knowledge.getParent().resolve("escape.md")).doesNotExist();
    }

    @Test
    void anOversizedResponseIsRejected() {
        body.set(manifest("d1", "a.md"));
        SiteSourcePuller small = puller(50);

        assertThat(small.pull(source())).isEqualTo(SiteSourcePuller.Outcome.FAILED);
        assertThat(knowledge.resolve("acme")).doesNotExist();
    }

    @Test
    void unreachableHostsFailQuietly() {
        SiteSourcePuller p = puller(1_000_000);
        SiteSourcePuller.Source dead = new SiteSourcePuller.Source("acme", URI.create("http://127.0.0.1:9/knowledge.json"));

        assertThat(p.pull(dead)).isEqualTo(SiteSourcePuller.Outcome.FAILED);
    }

    @Test
    void sourcesAreParsedAndValidated() {
        List<SiteSourcePuller.Source> s = SiteSourcePuller.parseSources(
                " alice=https://a.test/knowledge.json , bob=http://b.test/k.json ,, ");
        assertThat(s).extracting(SiteSourcePuller.Source::persona).containsExactly("alice", "bob");
        assertThat(SiteSourcePuller.parseSources("")).isEmpty();
        assertThatThrownBy(() -> SiteSourcePuller.parseSources("nourl")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SiteSourcePuller.parseSources("a=file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SiteSourcePuller.parseSources("../x=https://a.test/k.json"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
