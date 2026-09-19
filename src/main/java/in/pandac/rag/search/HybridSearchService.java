package in.pandac.rag.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.util.JacksonUtils;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Combines pgvector's cosine-similarity search with a Postgres full-text
 * keyword search over the same {@code vector_store} table (see
 * {@link HybridSearchSchemaInitializer}) — vector search alone misses
 * exact-term queries an embedding doesn't represent well. The two ranked
 * lists are merged with Reciprocal Rank Fusion, a simple, well-known way to
 * combine differently-scored rankings without needing comparable scores.
 *
 * <p>The keyword leg is a quality improvement, not a hard dependency: if the
 * FTS column/index is somehow missing or the query fails for any reason,
 * this falls back to vector-only results rather than breaking search.
 */
@Component
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);
    private static final int RRF_K = 60;

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper = JacksonUtils.getDefaultJsonMapper();

    @Value("${app.search.hybrid.enabled:true}")
    private boolean hybridEnabled;

    public HybridSearchService(VectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Document> search(String query, String personaId, String sharedPersonaId,
                                  int candidatePoolSize, double similarityThreshold) {
        String filterExpression = "(persona_id == '" + personaId + "' OR persona_id == '" + sharedPersonaId + "')";

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(candidatePoolSize)
                .similarityThreshold(similarityThreshold)
                .filterExpression(filterExpression)
                .build();

        List<Document> vectorResults = vectorStore.similaritySearch(request);
        if (vectorResults == null) {
            vectorResults = List.of();
        }
        if (!hybridEnabled) {
            return vectorResults;
        }

        List<Document> keywordResults = keywordSearch(query, personaId, sharedPersonaId, candidatePoolSize);
        return fuse(vectorResults, keywordResults, candidatePoolSize);
    }

    private List<Document> keywordSearch(String query, String personaId, String sharedPersonaId, int limit) {
        try {
            return jdbcTemplate.query(
                    """
                    SELECT id, content, metadata FROM vector_store
                    WHERE (metadata->>'persona_id' = ? OR metadata->>'persona_id' = ?)
                      AND content_tsv @@ plainto_tsquery('english', ?)
                    ORDER BY ts_rank(content_tsv, plainto_tsquery('english', ?)) DESC
                    LIMIT ?
                    """,
                    (rs, rowNum) -> toDocument(rs.getString("id"), rs.getString("content"), rs.getString("metadata")),
                    personaId, sharedPersonaId, query, query, limit);
        } catch (Exception e) {
            log.warn("Keyword search leg failed ({}) — continuing with vector-only results", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Document toDocument(String id, String content, String metadataJson) {
        Map<String, Object> metadata;
        try {
            metadata = metadataJson == null ? Map.of() : jsonMapper.readValue(metadataJson, Map.class);
        } catch (Exception e) {
            metadata = Map.of();
        }
        return Document.builder().id(id).text(content).metadata(metadata).build();
    }

    private List<Document> fuse(List<Document> vectorResults, List<Document> keywordResults, int limit) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, Document> byId = new LinkedHashMap<>();

        addRankScores(vectorResults, scores, byId);
        addRankScores(keywordResults, scores, byId);

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(e -> byId.get(e.getKey()))
                .toList();
    }

    private void addRankScores(List<Document> results, Map<String, Double> scores, Map<String, Document> byId) {
        for (int rank = 0; rank < results.size(); rank++) {
            Document doc = results.get(rank);
            scores.merge(doc.getId(), 1.0 / (RRF_K + rank + 1), Double::sum);
            byId.putIfAbsent(doc.getId(), doc);
        }
    }
}
