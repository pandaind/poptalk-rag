package in.pandac.rag.ingest;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.component.file.FileConstants;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Replaces (not appends) a file's vectors: adds the freshly-chunked set
 * FIRST, tagged with a fresh {@code batch_id}, and only deletes the file's
 * previous chunks (any other {@code batch_id} for this persona+source) once
 * that add has actually succeeded.
 *
 * <p>This ordering — add-then-delete-old-batch rather than delete-then-add —
 * is deliberate: {@code add()} is what can fail partway through (e.g. the
 * embedding provider going down mid-request), and doing the delete first
 * used to mean such a failure left that file's search results empty until a
 * later poll succeeded. Tagging each batch and deleting only stale batches
 * means a failed add leaves the previous, still-good batch untouched instead.
 */
@Component("embeddingUpsertProcessor")
public class EmbeddingUpsertProcessor implements Processor {

    private final VectorStore vectorStore;
    private final IngestedFileTracker tracker;

    public EmbeddingUpsertProcessor(VectorStore vectorStore, IngestedFileTracker tracker) {
        this.vectorStore = vectorStore;
        this.tracker = tracker;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        List<Document> chunks = exchange.getIn().getBody(List.class);
        String personaId = exchange.getProperty("personaId", String.class);
        String relativePath = exchange.getIn().getHeader(FileConstants.FILE_RELATIVE_PATH, String.class);
        String contentHash = exchange.getProperty("contentHash", String.class);
        String batchId = UUID.randomUUID().toString();

        if (!chunks.isEmpty()) {
            // If this throws (embedding provider unreachable, etc.), execution
            // stops here — the previous batch below is never deleted, so this
            // file's existing search results survive the failure.
            vectorStore.add(tagWithBatch(chunks, batchId));
        }

        FilterExpressionBuilder b = new FilterExpressionBuilder();
        vectorStore.delete(b.and(
                b.and(b.eq("persona_id", personaId), b.eq("source", relativePath)),
                b.ne("batch_id", batchId)).build());

        tracker.markIngested(personaId, relativePath, contentHash);
    }

    private static List<Document> tagWithBatch(List<Document> chunks, String batchId) {
        List<Document> tagged = new ArrayList<>(chunks.size());
        for (Document chunk : chunks) {
            Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
            metadata.put("batch_id", batchId);
            tagged.add(Document.builder()
                    .text(chunk.getText())
                    .metadata(metadata)
                    .build());
        }
        return tagged;
    }
}
