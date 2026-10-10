package in.pandac.rag.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SiteKnowledgeWriterTest {

    @TempDir
    Path knowledge;

    private final SiteKnowledgeWriter writer = new SiteKnowledgeWriter();

    private static SiteManifest.Document doc(String path, String content) {
        return new SiteManifest.Document(path, "Title of " + path, "https://x.test/" + path.replace(".md", "/"),
                "guide", "2026-10-10", "About: a \"quoted\" thing, with: colons", List.of("a", "b"), "h", content);
    }

    private static SiteManifest manifest(SiteManifest.Document... docs) {
        return new SiteManifest(1, "d", List.of(docs));
    }

    @Test
    void writtenFilesAreReadBackByTheExistingMarkdownParser() throws Exception {
        writer.write(knowledge, "acme", manifest(
                doc("guide/start.md", "# Start\n\n## Install\n\n```sh\n# a comment\n```\n")));

        String text = Files.readString(knowledge.resolve("acme/guide/start.md"), StandardCharsets.UTF_8);
        MarkdownParser.ParseResult parsed = MarkdownParser.parse(text);

        assertThat(parsed.frontmatter()).containsEntry("url", "https://x.test/guide/start/")
                .containsEntry("title", "Title of guide/start.md")
                .containsEntry("section", "guide");
        assertThat(parsed.frontmatter().get("description")).isEqualTo("About: a \"quoted\" thing, with: colons");
        assertThat(parsed.frontmatter().get("tags")).isEqualTo(List.of("a", "b"));
        assertThat(parsed.headings()).extracting(MarkdownParser.HeadingBreak::path)
                .containsExactly("Start", "Start > Install");
        assertThat(parsed.body().strip()).startsWith("# Start");
    }

    @Test
    void secondRunChangesNothingAndRemovedDocumentsAreDeleted() throws Exception {
        SiteKnowledgeWriter.Result first = writer.write(knowledge, "acme",
                manifest(doc("a.md", "# A"), doc("dir/b.md", "# B")));
        assertThat(first.written()).isEqualTo(2);

        SiteKnowledgeWriter.Result again = writer.write(knowledge, "acme",
                manifest(doc("a.md", "# A"), doc("dir/b.md", "# B")));
        assertThat(again.written()).isZero();
        assertThat(again.unchanged()).isEqualTo(2);

        SiteKnowledgeWriter.Result smaller = writer.write(knowledge, "acme", manifest(doc("a.md", "# A")));
        assertThat(smaller.removed()).isEqualTo(1);
        assertThat(knowledge.resolve("acme/dir/b.md")).doesNotExist();
        assertThat(knowledge.resolve("acme/dir")).doesNotExist();
        assertThat(knowledge.resolve("acme/a.md")).exists();
    }

    @Test
    void neverTouchesFilesItDidNotCreate() throws Exception {
        Path persona = Files.createDirectories(knowledge.resolve("acme"));
        Files.writeString(persona.resolve("handbook.pdf"), "someone else's");
        Files.writeString(persona.resolve("a.md"), "hand written");

        SiteKnowledgeWriter.Result r = writer.write(knowledge, "acme",
                manifest(doc("a.md", "# A"), doc("b.md", "# B")));

        assertThat(r.skipped()).isEqualTo(1);
        assertThat(Files.readString(persona.resolve("a.md"))).isEqualTo("hand written");
        assertThat(persona.resolve("b.md")).exists();

        // A later run that no longer lists b.md removes only b.md, never the foreign files.
        writer.write(knowledge, "acme", manifest(doc("c.md", "# C")));
        assertThat(persona.resolve("handbook.pdf")).exists();
        assertThat(persona.resolve("a.md")).exists();
        assertThat(persona.resolve("b.md")).doesNotExist();
    }

    @Test
    void refusesAPersonaIdThatCouldEscapeTheKnowledgeDirectory() {
        for (String bad : List.of("../evil", "a/b", "..", "", "x y")) {
            assertThatThrownBy(() -> writer.write(knowledge, bad, manifest(doc("a.md", "# A"))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void personasAreIsolatedFromEachOther() throws Exception {
        writer.write(knowledge, "alice", manifest(doc("a.md", "# A")));
        writer.write(knowledge, "bob", manifest(doc("b.md", "# B")));
        writer.write(knowledge, "alice", manifest(doc("c.md", "# C")));

        assertThat(knowledge.resolve("bob/b.md")).exists();
        assertThat(knowledge.resolve("alice/a.md")).doesNotExist();
        assertThat(knowledge.resolve("alice/c.md")).exists();
    }
}
