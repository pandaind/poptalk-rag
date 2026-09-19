package in.pandac.rag.config;

import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Local, free embedding provider — the default. Wired manually rather than
 * through Spring AI's own auto-configuration so it can coexist with the
 * OpenAI embedding config (see OpenAiEmbeddingConfig), same reasoning as
 * poptalk-backend's per-provider chat configs.
 */
@Configuration
@ConditionalOnExpression("'${app.embedding.provider:ollama}' == 'ollama'")
public class OllamaEmbeddingConfig {

    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${spring.ai.ollama.embedding.options.model:nomic-embed-text}")
    private String modelName;

    @Bean
    public OllamaApi ollamaEmbeddingApi() {
        return OllamaApi.builder().baseUrl(baseUrl).build();
    }

    @Bean
    public OllamaEmbeddingModel embeddingModel(OllamaApi ollamaEmbeddingApi) {
        return OllamaEmbeddingModel.builder()
                .ollamaApi(ollamaEmbeddingApi)
                .defaultOptions(OllamaEmbeddingOptions.builder()
                        .model(modelName)
                        .build())
                .build();
    }
}
