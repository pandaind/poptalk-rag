package in.pandac.rag.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Re-scores the vector search's candidate set with an instruction-following
 * chat model, since cosine similarity alone is a fairly blunt relevance
 * signal (see the README's "ranking system" note). {@link KnowledgeBaseTool}
 * fetches a wider candidate pool than the requested topK specifically so
 * this has something to rerank.
 *
 * <p>Only one of {@link org.springframework.ai.ollama.OllamaChatModel} /
 * {@link org.springframework.ai.openai.OpenAiChatModel} is ever registered
 * (see OllamaRerankChatConfig / OpenAiRerankChatConfig — mutually exclusive
 * by {@code app.rerank.provider}), so injecting the common {@link ChatModel}
 * type is unambiguous. Absent, disabled, or on any failure, this falls back
 * to the candidates' existing vector-similarity order rather than breaking
 * the search — reranking is a quality improvement, not a hard dependency.
 */
@Component
public class LlmReranker {

    private static final Logger log = LoggerFactory.getLogger(LlmReranker.class);

    private final boolean enabled;
    private final ChatClient chatClient;

    public LlmReranker(ObjectProvider<ChatModel> chatModel, @Value("${app.rerank.enabled:true}") boolean enabled) {
        this.enabled = enabled;
        ChatModel model = chatModel.getIfAvailable();
        this.chatClient = model != null ? ChatClient.builder(model).build() : null;
        log.info("LLM reranking: {}", isAvailable() ? "enabled" : "disabled (enabled=" + enabled + ", chat model configured=" + (model != null) + ")");
    }

    public boolean isAvailable() {
        return enabled && chatClient != null;
    }

    /** Returns up to {@code topK} of {@code candidates}, reordered by relevance to {@code query}. */
    public List<Document> rerank(String query, List<Document> candidates, int topK) {
        if (!isAvailable() || candidates.size() <= topK) {
            return candidates.stream().limit(topK).toList();
        }

        try {
            StringBuilder candidateBlock = new StringBuilder();
            for (int i = 0; i < candidates.size(); i++) {
                String text = candidates.get(i).getText();
                if (text != null && text.length() > 500) {
                    text = text.substring(0, 500) + "...";
                }
                candidateBlock.append(i).append(": ").append(text).append("\n\n");
            }

            RerankResult result = chatClient.prompt()
                    .system("You are a search relevance ranking assistant. Given a query and a list of "
                            + "numbered candidate passages, return the indices of the passages most "
                            + "relevant to the query, ordered from most to least relevant. Return at most "
                            + topK + " indices. Only return indices that appear in the candidate list.")
                    .user("Query: " + query + "\n\nCandidates:\n" + candidateBlock)
                    .call()
                    .entity(RerankResult.class);

            List<Document> reranked = toDocuments(result, candidates, topK);
            if (!reranked.isEmpty()) {
                return reranked;
            }
            log.warn("Reranker returned no usable indices — falling back to vector-similarity order");
        } catch (Exception e) {
            log.warn("Reranking failed ({}) — falling back to vector-similarity order", e.getMessage());
        }

        return candidates.stream().limit(topK).toList();
    }

    private List<Document> toDocuments(RerankResult result, List<Document> candidates, int topK) {
        List<Document> reranked = new ArrayList<>();
        if (result == null || result.rankedIndices() == null) {
            return reranked;
        }
        for (Integer index : result.rankedIndices()) {
            if (index != null && index >= 0 && index < candidates.size()) {
                reranked.add(candidates.get(index));
            }
            if (reranked.size() >= topK) {
                break;
            }
        }
        return reranked;
    }

    public record RerankResult(List<Integer> rankedIndices) {}
}
