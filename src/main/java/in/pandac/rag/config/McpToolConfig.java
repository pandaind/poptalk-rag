package in.pandac.rag.config;

import in.pandac.rag.mcp.KnowledgeBaseTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider knowledgeBaseToolCallbackProvider(KnowledgeBaseTool knowledgeBaseTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(knowledgeBaseTool)
                .build();
    }
}
