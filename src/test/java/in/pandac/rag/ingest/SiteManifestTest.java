package in.pandac.rag.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SiteManifestTest {

    private static String doc(String path) {
        return "{\"path\":\"" + path + "\",\"title\":\"T\",\"url\":\"https://x.test/a/\",\"content\":\"# T\\n\"}";
    }

    private static String manifest(String documents) {
        return "{\"version\":1,\"digest\":\"sha256:abc\",\"documents\":[" + documents + "]}";
    }

    @Test
    void parsesDocumentsAndIgnoresUnknownFields() {
        String json = "{\"version\":1,\"future\":true,\"digest\":\"sha256:abc\",\"documents\":["
                + "{\"path\":\"guide/start.md\",\"title\":\"Start\",\"url\":\"https://x.test/guide/start/\","
                + "\"section\":\"guide\",\"date\":\"2026-10-10\",\"tags\":[\"a\",\"b\"],\"hash\":\"sha256:1\","
                + "\"content\":\"# Start\\n\\nHello\",\"extra\":42}]}";

        SiteManifest m = SiteManifest.parse(json);

        assertThat(m.digest()).isEqualTo("sha256:abc");
        assertThat(m.documents()).hasSize(1);
        SiteManifest.Document d = m.documents().get(0);
        assertThat(d.path()).isEqualTo("guide/start.md");
        assertThat(d.url()).isEqualTo("https://x.test/guide/start/");
        assertThat(d.tags()).containsExactly("a", "b");
        assertThat(d.content()).isEqualTo("# Start\n\nHello");
    }

    @Test
    void rejectsAVersionItDoesNotKnow() {
        assertThatThrownBy(() -> SiteManifest.parse("{\"version\":2,\"documents\":[]}"))
                .isInstanceOf(SiteManifest.InvalidManifestException.class)
                .hasMessageContaining("unsupported manifest version");
        assertThatThrownBy(() -> SiteManifest.parse("{\"documents\":[]}"))
                .isInstanceOf(SiteManifest.InvalidManifestException.class);
    }

    @Test
    void rejectsGarbageAndMissingDocuments() {
        assertThatThrownBy(() -> SiteManifest.parse("<html>not json</html>"))
                .isInstanceOf(SiteManifest.InvalidManifestException.class);
        assertThatThrownBy(() -> SiteManifest.parse("{\"version\":1}"))
                .isInstanceOf(SiteManifest.InvalidManifestException.class)
                .hasMessageContaining("documents");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../evil.md", "a/../../evil.md", "/etc/passwd.md", "a\\\\b.md", ".hidden.md", "a/.git/x.md",
            "notmarkdown.txt", "a//b.md", "a/./b.md", ".site-source", "x.md/..", "spaces ok but/../x.md"})
    void rejectsUnsafePaths(String path) {
        assertThatThrownBy(() -> SiteManifest.parse(manifest(doc(path))))
                .isInstanceOf(SiteManifest.InvalidManifestException.class);
    }

    @Test
    void rejectsDuplicatePaths() {
        assertThatThrownBy(() -> SiteManifest.parse(manifest(doc("a.md") + "," + doc("a.md"))))
                .isInstanceOf(SiteManifest.InvalidManifestException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void acceptsNestedAndOrdinaryPaths() {
        SiteManifest m = SiteManifest.parse(manifest(doc("index.md") + "," + doc("guide/getting-started.md")
                + "," + doc("blog/2026/05/my post.md")));
        assertThat(m.documents()).extracting(SiteManifest.Document::path)
                .containsExactly("index.md", "guide/getting-started.md", "blog/2026/05/my post.md");
    }
}
