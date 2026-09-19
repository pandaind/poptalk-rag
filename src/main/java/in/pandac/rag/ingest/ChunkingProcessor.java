package in.pandac.rag.ingest;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.component.file.FileConstants;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits each parsed document into embeddable chunks and tags every chunk
 * with the metadata the rest of the system depends on: {@code persona_id}
 * (query-time tenant filter — see KnowledgeBaseTool) and {@code source}
 * (lets KnowledgeReconciliationScheduler find and remove a deleted file's
 * vectors, and lets EmbeddingUpsertProcessor replace a changed file's old
 * chunks instead of just appending new ones alongside them).
 */
@Component("chunkingProcessor")
public class ChunkingProcessor implements Processor {

    private final TokenTextSplitter splitter = new TokenTextSplitter();

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        List<Document> documents = exchange.getIn().getBody(List.class);
        String personaId = exchange.getProperty("personaId", String.class);
        String relativePath = exchange.getIn().getHeader(FileConstants.FILE_RELATIVE_PATH, String.class);

        List<Document> chunks = splitter.apply(documents);
        List<Document> tagged = new ArrayList<>(chunks.size());
        int index = 0;
        for (Document chunk : chunks) {
            Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
            metadata.put("persona_id", personaId);
            metadata.put("source", relativePath);
            metadata.put("chunk_index", index++);
            tagged.add(Document.builder()
                    .text(chunk.getText())
                    .metadata(metadata)
                    .build());
        }

        exchange.getIn().setBody(tagged);
    }
}
