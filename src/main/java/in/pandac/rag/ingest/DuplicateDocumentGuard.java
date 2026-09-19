package in.pandac.rag.ingest;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.component.file.FileConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Catches the same content ingested twice under different filenames within a
 * persona's own knowledge folder. Runs right after {@link DocumentParsingProcessor}
 * (which computes {@code contentHash}) and before chunking/embedding — if a
 * duplicate is found, this file's own vectors (if any exist from before it
 * became a duplicate — e.g. it was edited to now match another file) are
 * removed, the file is marked ingested (so it isn't retried every poll)
 * without spending an embedding call on it, and the route stops here.
 */
@Component("duplicateDocumentGuard")
public class DuplicateDocumentGuard implements Processor {

    private static final Logger log = LoggerFactory.getLogger(DuplicateDocumentGuard.class);

    private final IngestedFileTracker tracker;
    private final VectorStore vectorStore;

    public DuplicateDocumentGuard(IngestedFileTracker tracker, VectorStore vectorStore) {
        this.tracker = tracker;
        this.vectorStore = vectorStore;
    }

    @Override
    public void process(Exchange exchange) {
        String personaId = exchange.getProperty("personaId", String.class);
        String relativePath = exchange.getIn().getHeader(FileConstants.FILE_RELATIVE_PATH, String.class);
        String contentHash = exchange.getProperty("contentHash", String.class);

        Optional<String> duplicateOf = tracker.findDuplicate(personaId, contentHash, relativePath);
        if (duplicateOf.isEmpty()) {
            return;
        }

        log.info("Skipping {} — duplicate content of already-ingested {}", relativePath, duplicateOf.get());
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        vectorStore.delete(b.and(b.eq("persona_id", personaId), b.eq("source", relativePath)).build());
        tracker.markIngested(personaId, relativePath, contentHash);
        exchange.setRouteStop(true);
    }
}
