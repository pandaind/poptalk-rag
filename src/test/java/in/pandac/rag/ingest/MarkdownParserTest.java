package in.pandac.rag.ingest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownParserTest {

    @Test
    @SuppressWarnings("unchecked")
    void parsesFrontmatterAndStripsItFromTheBody() {
        String raw = """
                ---
                department: sales
                tags: [pricing, contracts]
                ---

                # Pricing
                Enterprise plans start at $499.
                """;

        MarkdownParser.ParseResult result = MarkdownParser.parse(raw);

        assertThat(result.frontmatter()).containsEntry("department", "sales");
        assertThat((List<String>) result.frontmatter().get("tags")).containsExactly("pricing", "contracts");
        assertThat(result.body()).doesNotContain("---").contains("# Pricing");
    }

    @Test
    void malformedFrontmatterFallsBackToNoMetadataInsteadOfFailingIngestion() {
        String raw = """
                ---
                tags: [pricing, contracts
                ---
                # Body
                """;

        MarkdownParser.ParseResult result = MarkdownParser.parse(raw);

        assertThat(result.frontmatter()).isEmpty();
    }

    @Test
    void noFrontmatterLeavesTheWholeTextAsTheBody() {
        String raw = "# Just a heading\nSome text.";

        MarkdownParser.ParseResult result = MarkdownParser.parse(raw);

        assertThat(result.frontmatter()).isEmpty();
        assertThat(result.body()).isEqualTo(raw);
    }

    @Test
    void headingPathTracksNestingAndResetsOnASiblingHeading() {
        String body = """
                # Pricing
                intro

                ## Enterprise
                enterprise text

                ## Starter
                starter text
                """;

        MarkdownParser.ParseResult result = MarkdownParser.parse(body);

        assertThat(result.headings()).extracting(MarkdownParser.HeadingBreak::path)
                .containsExactly("Pricing", "Pricing > Enterprise", "Pricing > Starter");
    }

    @Test
    void headingPathAtReturnsTheNearestPrecedingHeading() {
        List<MarkdownParser.HeadingBreak> headings = List.of(
                new MarkdownParser.HeadingBreak(0, "Pricing"),
                new MarkdownParser.HeadingBreak(20, "Pricing > Enterprise"),
                new MarkdownParser.HeadingBreak(50, "Pricing > Starter"));

        assertThat(MarkdownParser.headingPathAt(headings, 5)).isEqualTo("Pricing");
        assertThat(MarkdownParser.headingPathAt(headings, 25)).isEqualTo("Pricing > Enterprise");
        assertThat(MarkdownParser.headingPathAt(headings, 100)).isEqualTo("Pricing > Starter");
        assertThat(MarkdownParser.headingPathAt(List.of(), 5)).isNull();
    }
}
