package in.pandac.rag.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Removes vectors for files that no longer exist on disk. The ingestion
 * route (see IngestionRoute) only ever sees files that ARE present — a
 * Camel file consumer has no way to notice a deletion — so this is a
 * separate periodic diff between {@link IngestedFileTracker}'s record of
 * what's been ingested and what's actually still there.
 */
@Component
public class KnowledgeReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeReconciliationScheduler.class);

    @Value("${app.knowledge.dir}")
    private String knowledgeDir;

    private final IngestedFileTracker tracker;
    private final VectorStore vectorStore;

    public KnowledgeReconciliationScheduler(IngestedFileTracker tracker, VectorStore vectorStore) {
        this.tracker = tracker;
        this.vectorStore = vectorStore;
    }

    @Scheduled(fixedDelayString = "${app.knowledge.poll-interval-ms:60000}")
    public void reconcile() {
        // tracked.relativePath() is Camel's CamelFileRelativePath, which is
        // already relative to the knowledge root and so already includes the
        // persona segment (e.g. "test-persona/doc.md") — do not prefix it
        // with personaId again here, or every tracked file would appear
        // "missing" and get its vectors deleted on every single cycle.
        Set<String> onDisk = currentlyOnDisk();

        for (IngestedFileTracker.TrackedFile tracked : tracker.allTracked()) {
            if (onDisk.contains(tracked.relativePath())) {
                continue;
            }

            log.info("Removing vectors for deleted file {}", tracked.relativePath());
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            vectorStore.delete(b.and(
                    b.eq("persona_id", tracked.personaId()),
                    b.eq("source", tracked.relativePath())).build());
            tracker.remove(tracked.personaId(), tracked.relativePath());
        }
    }

    private Set<String> currentlyOnDisk() {
        Path root = Path.of(knowledgeDir);
        Set<String> result = new HashSet<>();
        if (!Files.isDirectory(root)) {
            return result;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .forEach(path -> result.add(root.relativize(path).toString().replace('\\', '/')));
        } catch (IOException e) {
            log.error("Failed to scan knowledge directory '{}' during reconciliation: {}", knowledgeDir, e.getMessage());
        }
        return result;
    }
}
