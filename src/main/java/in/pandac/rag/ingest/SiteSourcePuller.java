package in.pandac.rag.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pulls a static site's published knowledge file (see {@link SiteManifest}) on a schedule and
 * materialises it into the knowledge folder, so a site on any host can feed this server without
 * anything being copied to it.
 *
 * <p>Configured with {@code KNOWLEDGE_SITE_SOURCES=persona=https://example.com/knowledge.json,...}.
 * Left unset, this does nothing at all.
 *
 * <p>Failure is safe by construction: a network error, a bad status, an oversized or malformed
 * file, or an empty document list changes nothing on disk, so an outage of the site can never wipe
 * a knowledge base.
 */
@Component
public class SiteSourcePuller {

    private static final Logger log = LoggerFactory.getLogger(SiteSourcePuller.class);

    public record Source(String persona, URI url) {
    }

    public enum Outcome { UPDATED, UNCHANGED, FAILED }

    private final Path knowledgeDir;
    private final List<Source> sources;
    private final long maxBytes;
    private final HttpClient http;
    private final SiteKnowledgeWriter writer = new SiteKnowledgeWriter();
    /** Last seen ETag and digest per source, so an unchanged site costs one conditional request. */
    private final Map<String, String> etags = new ConcurrentHashMap<>();
    private final Map<String, String> digests = new ConcurrentHashMap<>();

    public SiteSourcePuller(
            @Value("${app.knowledge.dir}") String knowledgeDir,
            @Value("${app.knowledge.site-sources:}") String sources,
            @Value("${app.knowledge.site-max-bytes:26214400}") long maxBytes) {
        this.knowledgeDir = Path.of(knowledgeDir);
        this.sources = parseSources(sources);
        this.maxBytes = maxBytes;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        if (!this.sources.isEmpty()) {
            log.info("Pulling knowledge from {} site source(s)", this.sources.size());
        }
    }

    /** {@code persona=https://host/knowledge.json,other=https://...}; invalid entries are rejected. */
    static List<Source> parseSources(String raw) {
        List<Source> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String entry : raw.split(",")) {
            String e = entry.trim();
            if (e.isEmpty()) {
                continue;
            }
            int eq = e.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("KNOWLEDGE_SITE_SOURCES entry must be persona=url: " + e);
            }
            String persona = e.substring(0, eq).trim();
            URI uri = URI.create(e.substring(eq + 1).trim());
            if (!persona.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException("invalid persona id in KNOWLEDGE_SITE_SOURCES: " + persona);
            }
            String scheme = uri.getScheme();
            if (!"https".equals(scheme) && !"http".equals(scheme)) {
                throw new IllegalArgumentException("site source must be an http(s) URL: " + uri);
            }
            out.add(new Source(persona, uri));
        }
        return out;
    }

    @Scheduled(
            initialDelayString = "${app.knowledge.site-initial-delay-ms:15000}",
            fixedDelayString = "${app.knowledge.site-poll-interval-ms:600000}")
    public void pullAll() {
        for (Source s : sources) {
            Outcome o = pull(s);
            if (o == Outcome.FAILED) {
                log.warn("Site source {} for persona {} failed; keeping existing knowledge", s.url(), s.persona());
            }
        }
    }

    /** One pull. Never throws; returns what happened. */
    public Outcome pull(Source source) {
        String key = source.persona() + "@" + source.url();
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(source.url())
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .header("User-Agent", "poptalk-rag")
                    .GET();
            String etag = etags.get(key);
            if (etag != null) {
                req.header("If-None-Match", etag);
            }
            HttpResponse<InputStream> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() == 304) {
                return Outcome.UNCHANGED;
            }
            if (resp.statusCode() != 200) {
                log.warn("{} answered HTTP {}", source.url(), resp.statusCode());
                return Outcome.FAILED;
            }
            String body = readCapped(resp.body());

            SiteManifest manifest = SiteManifest.parse(body);
            if (manifest.documents().isEmpty()) {
                // A published site with zero pages is far more likely a broken build than intent.
                log.warn("{} lists no documents; refusing to remove the existing knowledge base", source.url());
                return Outcome.FAILED;
            }
            resp.headers().firstValue("ETag").ifPresent(t -> etags.put(key, t));
            if (manifest.digest() != null && manifest.digest().equals(digests.get(key))) {
                return Outcome.UNCHANGED;
            }
            SiteKnowledgeWriter.Result r = writer.write(knowledgeDir, source.persona(), manifest);
            if (manifest.digest() != null) {
                digests.put(key, manifest.digest());
            }
            log.info("Site source {}: {} written, {} unchanged, {} removed, {} skipped",
                    source.url(), r.written(), r.unchanged(), r.removed(), r.skipped());
            return r.written() + r.removed() > 0 ? Outcome.UPDATED : Outcome.UNCHANGED;
        } catch (SiteManifest.InvalidManifestException e) {
            log.warn("{} is not a usable knowledge manifest: {}", source.url(), e.getMessage());
            return Outcome.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.FAILED;
        } catch (IOException | RuntimeException e) {
            log.warn("Could not pull {}: {}", source.url(), e.toString());
            return Outcome.FAILED;
        }
    }

    private String readCapped(InputStream in) throws IOException {
        try (in) {
            byte[] buf = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
            if (buf.length > maxBytes) {
                throw new IOException("response larger than " + maxBytes + " bytes");
            }
            return new String(buf, StandardCharsets.UTF_8);
        }
    }
}
