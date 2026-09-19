package in.pandac.rag.mcp;

import in.pandac.rag.security.PersonaRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The one MCP tool this server exposes. Tenant scoping comes exclusively
 * from {@link PersonaRequestContext} (set by PersonaApiKeyAuthFilter from the
 * authenticated API key) — the {@code query}/{@code topK} parameters below
 * are the only inputs the calling model controls, deliberately excluding any
 * persona/tenant field it could be prompt-injected into supplying.
 */
@Component
public class KnowledgeBaseTool {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseTool.class);

    private final VectorStore vectorStore;

    public KnowledgeBaseTool(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
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
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(effectiveTopK)
                .filterExpression("persona_id == '" + personaId + "'")
                .build();

        List<Document> results = vectorStore.similaritySearch(request);
        if (results == null || results.isEmpty()) {
            return "No relevant information found in the knowledge base.";
        }

        return results.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n---\n"));
    }
}
