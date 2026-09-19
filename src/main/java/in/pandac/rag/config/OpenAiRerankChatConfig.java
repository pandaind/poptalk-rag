package in.pandac.rag.config;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * See OllamaRerankChatConfig for why this exists separately from
 * OpenAiEmbeddingConfig. Guarded on a non-blank API key for the same reason
 * as poptalk-backend's OpenAiConfig — some providers' Api builders throw on
 * construction with a blank key.
 */
@Configuration
@ConditionalOnExpression("'${app.rerank.provider:ollama}' == 'openai' and '${spring.ai.openai.api-key:}'.length() > 0")
public class OpenAiRerankChatConfig {

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}")
    private String modelName;

    @Bean
    public OpenAiApi rerankOpenAiApi() {
        return OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
    }

    @Bean
    public OpenAiChatModel rerankChatModel(OpenAiApi rerankOpenAiApi) {
        return OpenAiChatModel.builder()
                .openAiApi(rerankOpenAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(modelName)
                        .temperature(0.0)
                        .build())
                .build();
    }
}
