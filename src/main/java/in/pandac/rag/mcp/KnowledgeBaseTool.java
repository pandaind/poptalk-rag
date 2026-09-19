package in.pandac.rag.mcp;

import in.pandac.rag.search.HybridSearchService;
import in.pandac.rag.security.PersonaRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The one MCP tool this server exposes. Tenant scoping comes exclusively
 * from {@link PersonaRequestContext} (set by PersonaApiKeyAuthFilter from the
 * authenticated API key) — the {@code query}/{@code topK} parameters below
 * are the only inputs the calling model controls, deliberately excluding any
 * persona/tenant field it could be prompt-injected into supplying.
 *
 * <p>Retrieval is two-stage: {@link HybridSearchService} first pulls a wider
 * candidate pool (vector similarity + keyword search, fused) than the caller
 * actually asked for, then {@link LlmReranker} re-scores that pool with an
 * instruction-following model and returns the requested topK — a blunt
 * similarity/keyword signal alone doesn't always surface the most useful
 * passages. If reranking is disabled or unavailable, the candidate order is
 * used directly (equivalent to skipping this stage).
 *
 * <p>Every search also matches documents ingested under the reserved
 * shared-persona-id folder ({@code knowledge/_shared/} by default,
 * {@code app.rag.shared-persona-id}), so multiple personas can draw on a
 * common set of documents without duplicating files per persona.
 *
 * <p>Returned text is broken into citation-headed blocks (source file, plus
 * heading path when known), consecutive chunks from the same document are
 * merged under one header instead of repeating it, and the whole result is
 * capped at {@code app.rag.max-context-chars} regardless of how {@code topK}
 * is configured.
 */
@Component
public class KnowledgeBaseTool {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseTool.class);

    private final HybridSearchService hybridSearchService;
    private final LlmReranker reranker;

    @Value("${app.rag.similarity-threshold:0.5}")
    private double similarityThreshold;

    @Value("${app.rerank.candidate-pool-multiplier:4}")
    private int candidatePoolMultiplier;

    @Value("${app.rerank.max-candidate-pool:20}")
    private int maxCandidatePool;

    @Value("${app.rag.shared-persona-id:_shared}")
    private String sharedPersonaId;

    @Value("${app.rag.max-context-chars:6000}")
    private int maxContextChars;

    public KnowledgeBaseTool(HybridSearchService hybridSearchService, LlmReranker reranker) {
        this.hybridSearchService = hybridSearchService;
        this.reranker = reranker;
    }

    @Tool(name = "search_knowledge_base",
          description = "Search the persona's knowledge base for information relevant to the given query. "
                  + "Use this when the conversation needs more detail than you already have. It's fine to "
                  + "call this more than once in the same turn — e.g. with different sub-queries — when a "
                  + "question needs information from more than one document or topic.")
    public String search(
            @ToolParam(description = "What to search for") String query,
            @ToolParam(description = "Max number of results to return (default 5)", required = false) Integer topK) {

        String personaId = PersonaRequestContext.get();
        if (personaId == null) {
            // Should be unreachable — the auth filter rejects unauthenticated
            // requests before they ever reach a tool call — but never search
            // unscoped if it somehow happens.
            log.error("search_knowledge_base invoked with no resolved persona in context");
            return "Knowledge base unavailable.";
        }

        int effectiveTopK = (topK != null && topK > 0) ? topK : 5;
        int candidatePoolSize = Math.min(effectiveTopK * candidatePoolMultiplier, maxCandidatePool);
        // Never fetch fewer candidates than the final topK, however the
        // multiplier/cap are configured.
        candidatePoolSize = Math.max(candidatePoolSize, effectiveTopK);

        List<Document> candidates = hybridSearchService.search(
                query, personaId, sharedPersonaId, candidatePoolSize, similarityThreshold);
        if (candidates == null || candidates.isEmpty()) {
            return "No relevant information found in the knowledge base.";
        }

        LlmReranker.RerankOutcome outcome = reranker.rerank(query, candidates, effectiveTopK);

        String body = render(outcome.documents());
        if (!outcome.sufficient()) {
            body = "[Note: the knowledge base may not contain a strong answer to this query — treat the "
                    + "passages below as partial context and say so rather than guessing.]\n\n" + body;
        }
        return body;
    }

    /**
     * Joins results into citation-headed blocks, merging consecutive chunks
     * from the same source (adjacent {@code chunk_index}) under one header
     * instead of repeating it, then hard-truncates to {@code maxContextChars}.
     */
    private String render(List<Document> results) {
        StringBuilder out = new StringBuilder();
        String lastSource = null;
        Integer lastChunkIndex = null;

        for (Document doc : results) {
            Map<String, Object> metadata = doc.getMetadata();
            String source = asString(metadata.get("source"));
            Integer chunkIndex = asInteger(metadata.get("chunk_index"));
            String headingPath = asString(metadata.get("heading_path"));

            boolean continuesPrevious = lastSource != null && lastSource.equals(source)
                    && lastChunkIndex != null && chunkIndex != null && chunkIndex == lastChunkIndex + 1;

            if (continuesPrevious) {
                out.append("\n\n").append(doc.getText());
            } else {
                if (!out.isEmpty()) {
                    out.append("\n---\n");
                }
                out.append(citationHeader(source, headingPath)).append("\n").append(doc.getText());
            }

            lastSource = source;
            lastChunkIndex = chunkIndex;
        }

        if (out.length() > maxContextChars) {
            out.setLength(maxContextChars);
            out.append("\n[...truncated]");
        }
        return out.toString();
    }

    private static String citationHeader(String source, String headingPath) {
        if (source == null) {
            return "[unknown source]";
        }
        return headingPath != null ? "[" + source + " — " + headingPath + "]" : "[" + source + "]";
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
