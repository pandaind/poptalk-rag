package in.pandac.rag.config;

import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chat model used only for reranking search candidates (see
 * in.pandac.rag.mcp.LlmReranker) — a separate concern from the embedding
 * model in OllamaEmbeddingConfig, since reranking needs an instruction-
 * following chat model, not an embedding encoder. Wired manually for the
 * same reason as every other provider config in this project: coexists
 * with the OpenAI variant, and Spring AI's own auto-selection is disabled
 * (spring.ai.model.chat: none in application.yml).
 */
@Configuration
@ConditionalOnExpression("'${app.rerank.provider:ollama}' == 'ollama'")
public class OllamaRerankChatConfig {

    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${spring.ai.ollama.chat.model:llama3.2}")
    private String modelName;

    @Bean
    public OllamaApi rerankOllamaApi() {
        return OllamaApi.builder().baseUrl(baseUrl).build();
    }

    @Bean
    public OllamaChatModel rerankChatModel(OllamaApi rerankOllamaApi) {
        return OllamaChatModel.builder()
                .ollamaApi(rerankOllamaApi)
                .options(OllamaChatOptions.builder()
                        .model(modelName)
                        .temperature(0.0)
                        .build())
                .build();
    }
}
