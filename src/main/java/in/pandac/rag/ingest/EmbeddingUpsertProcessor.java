package in.pandac.rag.ingest;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.component.file.FileConstants;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Replaces (not appends) a file's vectors: on every (re-)ingest, first delete
 * whatever chunks already exist for this exact persona+source, then add the
 * freshly-chunked-and-about-to-be-embedded set. This is what makes editing a
 * knowledge file behave correctly — without the delete, a changed file would
 * leave its old, now-stale chunks in the store alongside the new ones.
 *
 * <p><b>Known trade-off</b> (found by actually exercising this against a
 * failing embedding provider): {@code delete()} runs before the embedding
 * call inside {@code add()}. If a file is updated right as the embedding
 * provider goes down, the old-but-still-good chunks are removed before the
 * new ones can be created, leaving that one file's search results empty
 * until a later poll succeeds — not a total outage, just that file, and only
 * for the provider's downtime window. Fixing this properly means embedding
 * the new chunks into a holding batch first and only deleting the old ones
 * once the new batch is confirmed written, which needs a batch/version
 * column to keep the delete from also catching the rows it just inserted.
 * Left as a documented limitation rather than added now — this only bites
 * a file that's both being edited and unlucky enough to catch the provider
 * mid-outage.
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

        FilterExpressionBuilder b = new FilterExpressionBuilder();
        vectorStore.delete(b.and(b.eq("persona_id", personaId), b.eq("source", relativePath)).build());

        if (!chunks.isEmpty()) {
            vectorStore.add(chunks);
        }

        tracker.markIngested(personaId, relativePath, contentHash);
    }
}
