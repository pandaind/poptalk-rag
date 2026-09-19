package in.pandac.rag.config;

import com.openai.client.OpenAIClient;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * See OllamaRerankChatConfig for why this exists separately from
 * OpenAiEmbeddingConfig. Guarded on a non-blank API key for the same reason
 * as poptalk-backend's OpenAiConfig — some providers' client setup throws on
 * construction with a blank key. See OpenAiEmbeddingConfig for why this uses
 * {@link OpenAiSetup} instead of the now-removed {@code OpenAiApi}.
 */
@Configuration
@ConditionalOnExpression("'${app.rerank.provider:ollama}' == 'openai' and '${spring.ai.openai.api-key:}'.length() > 0")
public class OpenAiRerankChatConfig {

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.model:gpt-4o-mini}")
    private String modelName;

    @Bean
    public OpenAIClient rerankOpenAiClient() {
        return OpenAiSetup.setupSyncClient(baseUrl, apiKey, null, null, null, null, false, false, modelName,
                Duration.ofSeconds(60), 3, null, null, ObservationRegistry.NOOP, null, List.of());
    }

    @Bean
    public OpenAiChatModel rerankChatModel(OpenAIClient rerankOpenAiClient) {
        return OpenAiChatModel.builder()
                .openAiClient(rerankOpenAiClient)
                .options(OpenAiChatOptions.builder()
                        .model(modelName)
                        .temperature(0.0)
                        .build())
                .build();
    }
}
