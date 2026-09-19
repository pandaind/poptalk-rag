package in.pandac.rag.config;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * See OllamaEmbeddingConfig for why this is wired manually. Only one of the
 * two embedding configs is ever active — both are gated on app.embedding.provider
 * so exactly one EmbeddingModel bean exists, never zero or two.
 */
@Configuration
@ConditionalOnExpression("'${app.embedding.provider:ollama}' == 'openai'")
public class OpenAiEmbeddingConfig {

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String baseUrl;

    @Value("${spring.ai.openai.embedding.options.model:text-embedding-3-small}")
    private String modelName;

    @Bean
    public OpenAiApi openAiEmbeddingApi() {
        return OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
    }

    @Bean
    public OpenAiEmbeddingModel embeddingModel(OpenAiApi openAiEmbeddingApi) {
        return new OpenAiEmbeddingModel(
                openAiEmbeddingApi,
                MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder().model(modelName).build());
    }
}
