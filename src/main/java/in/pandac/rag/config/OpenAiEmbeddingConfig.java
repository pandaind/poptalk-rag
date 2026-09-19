package in.pandac.rag.config;

import com.openai.client.OpenAIClient;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * See OllamaEmbeddingConfig for why this is wired manually. Only one of the
 * two embedding configs is ever active — both are gated on app.embedding.provider
 * so exactly one EmbeddingModel bean exists, never zero or two.
 *
 * <p>Spring AI 2.0 removed {@code OpenAiApi} entirely — {@code OpenAiChatModel}/
 * {@code OpenAiEmbeddingModel} now wrap the official {@code com.openai:openai-java}
 * SDK's {@link OpenAIClient} directly. {@link OpenAiSetup#setupSyncClient} is the
 * same factory Spring AI's own auto-configuration uses to build that client from
 * plain baseUrl/apiKey — reused here instead of hand-rolling the SDK's lower-level
 * {@code ClientOptions} wiring ourselves.
 */
@Configuration
@ConditionalOnExpression("'${app.embedding.provider:ollama}' == 'openai'")
public class OpenAiEmbeddingConfig {

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String baseUrl;

    @Value("${spring.ai.openai.embedding.model:text-embedding-3-small}")
    private String modelName;

    @Bean
    public OpenAIClient openAiEmbeddingClient() {
        return OpenAiSetup.setupSyncClient(baseUrl, apiKey, null, null, null, null, false, false, modelName,
                Duration.ofSeconds(60), 3, null, null, ObservationRegistry.NOOP, null, List.of());
    }

    @Bean
    public OpenAiEmbeddingModel embeddingModel(OpenAIClient openAiEmbeddingClient) {
        return OpenAiEmbeddingModel.builder()
                .openAiClient(openAiEmbeddingClient)
                .metadataMode(MetadataMode.EMBED)
                .options(OpenAiEmbeddingOptions.builder().model(modelName).build())
                .build();
    }
}
