package in.pandac.rag.mcp;

import in.pandac.rag.security.PersonaRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The one MCP tool this server exposes. Tenant scoping comes exclusively
 * from {@link PersonaRequestContext} (set by PersonaApiKeyAuthFilter from the
 * authenticated API key) — the {@code query}/{@code topK} parameters below
 * are the only inputs the calling model controls, deliberately excluding any
 * persona/tenant field it could be prompt-injected into supplying.
 *
 * <p>Retrieval is two-stage: pgvector's cosine-similarity/HNSW search first
 * pulls a wider candidate pool (cheap, approximate) than the caller actually
 * asked for, then {@link LlmReranker} re-scores that pool with an
 * instruction-following model and returns the requested topK — cosine
 * similarity alone is a fairly blunt relevance signal, so this catches
 * cases where the nearest vectors aren't actually the most useful passages.
 * If reranking is disabled or unavailable, the vector-similarity order is
 * used directly (equivalent to skipping this stage).
 */
@Component
public class KnowledgeBaseTool {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseTool.class);

    private final VectorStore vectorStore;
    private final LlmReranker reranker;

    @Value("${app.rag.similarity-threshold:0.5}")
    private double similarityThreshold;

    @Value("${app.rerank.candidate-pool-multiplier:4}")
    private int candidatePoolMultiplier;

    @Value("${app.rerank.max-candidate-pool:20}")
    private int maxCandidatePool;

    public KnowledgeBaseTool(VectorStore vectorStore, LlmReranker reranker) {
        this.vectorStore = vectorStore;
        this.reranker = reranker;
    }

    @Tool(name = "search_knowledge_base",
          description = "Search the persona's knowledge base for information relevant to the given query. "
                  + "Use this when the conversation needs more detail than you already have.")
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

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(candidatePoolSize)
                .similarityThreshold(similarityThreshold)
                .filterExpression("persona_id == '" + personaId + "'")
                .build();

        List<Document> candidates = vectorStore.similaritySearch(request);
        if (candidates == null || candidates.isEmpty()) {
            return "No relevant information found in the knowledge base.";
        }

        List<Document> results = reranker.rerank(query, candidates, effectiveTopK);

        return results.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n---\n"));
    }
}
