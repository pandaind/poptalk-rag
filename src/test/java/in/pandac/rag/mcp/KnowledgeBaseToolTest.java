package in.pandac.rag.mcp;

import in.pandac.rag.search.HybridSearchService;
import in.pandac.rag.security.PersonaRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeBaseToolTest {

    private final HybridSearchService hybridSearchService = mock(HybridSearchService.class);
    private final LlmReranker reranker = mock(LlmReranker.class);
    private final KnowledgeBaseTool tool = new KnowledgeBaseTool(hybridSearchService, reranker);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(tool, "similarityThreshold", 0.5);
        ReflectionTestUtils.setField(tool, "candidatePoolMultiplier", 4);
        ReflectionTestUtils.setField(tool, "maxCandidatePool", 20);
        ReflectionTestUtils.setField(tool, "sharedPersonaId", "_shared");
        ReflectionTestUtils.setField(tool, "maxContextChars", 6000);
        PersonaRequestContext.set("alice");
    }

    @AfterEach
    void tearDown() {
        PersonaRequestContext.clear();
    }

    @Test
    void returnsAFixedMessageWhenNoCandidatesAreFound() {
        when(hybridSearchService.search(any(), any(), any(), anyInt(), anyDouble())).thenReturn(List.of());

        String result = tool.search("anything", null);

        assertThat(result).isEqualTo("No relevant information found in the knowledge base.");
    }

    @Test
    void citesEachResultBySourceAndMergesConsecutiveChunksFromTheSameSource() {
        Document a = chunk("alice/faq.md", 0, "Pricing > Enterprise", "Enterprise costs $499/mo.");
        Document b = chunk("alice/faq.md", 1, "Pricing > Enterprise", "It includes unlimited seats.");
        Document c = chunk("alice/returns.md", 0, null, "Returns accepted within 30 days.");
        when(hybridSearchService.search(any(), any(), any(), anyInt(), anyDouble())).thenReturn(List.of(a, b, c));
        when(reranker.rerank(any(), any(), anyInt())).thenReturn(new LlmReranker.RerankOutcome(List.of(a, b, c), true));

        String result = tool.search("pricing and returns", 3);

        assertThat(result).contains("[alice/faq.md — Pricing > Enterprise]")
                .contains("Enterprise costs $499/mo.\n\nIt includes unlimited seats.")
                .contains("[alice/returns.md]")
                .contains("Returns accepted within 30 days.");
        // Two rendered blocks (the faq.md pair merged, returns.md separate) -> one separator.
        assertThat(result.split("\n---\n", -1)).hasSize(2);
    }

    @Test
    void prefixesACaveatWhenTheRerankerJudgesTheCandidatesInsufficient() {
        Document a = chunk("alice/faq.md", 0, null, "Loosely related text.");
        when(hybridSearchService.search(any(), any(), any(), anyInt(), anyDouble())).thenReturn(List.of(a));
        when(reranker.rerank(any(), any(), anyInt())).thenReturn(new LlmReranker.RerankOutcome(List.of(a), false));

        String result = tool.search("something unrelated", 1);

        assertThat(result).startsWith("[Note:");
    }

    @Test
    void truncatesResultsLongerThanMaxContextChars() {
        ReflectionTestUtils.setField(tool, "maxContextChars", 50);
        Document a = chunk("alice/faq.md", 0, null, "x".repeat(200));
        when(hybridSearchService.search(any(), any(), any(), anyInt(), anyDouble())).thenReturn(List.of(a));
        when(reranker.rerank(any(), any(), anyInt())).thenReturn(new LlmReranker.RerankOutcome(List.of(a), true));

        String result = tool.search("query", 1);

        assertThat(result).endsWith("[...truncated]");
        assertThat(result.length()).isLessThan(100);
    }

    @Test
    void returnsAnUnavailableMessageWhenNoPersonaIsResolved() {
        PersonaRequestContext.clear();

        String result = tool.search("anything", null);

        assertThat(result).isEqualTo("Knowledge base unavailable.");
    }

    private static Document chunk(String source, int chunkIndex, String headingPath, String text) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", source);
        metadata.put("chunk_index", chunkIndex);
        if (headingPath != null) {
            metadata.put("heading_path", headingPath);
        }
        return Document.builder().text(text).metadata(metadata).build();
    }
}
