package in.pandac.rag.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression guard for a real bug caught during manual testing: comparing a
 * tracked file's relative path against the on-disk set must NOT re-prefix it
 * with the persona id, since {@code CamelFileRelativePath} already includes
 * it — doing so once made every tracked file look "missing" and wiped its
 * vectors on every single reconciliation cycle.
 */
class KnowledgeReconciliationSchedulerTest {

    private final IngestedFileTracker tracker = mock(IngestedFileTracker.class);
    private final VectorStore vectorStore = mock(VectorStore.class);
    private final KnowledgeReconciliationScheduler scheduler =
            new KnowledgeReconciliationScheduler(tracker, vectorStore);

    @Test
    void deletesVectorsOnlyForFilesActuallyMissingFromDisk(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("alice"));
        Files.writeString(tempDir.resolve("alice/keep.md"), "still here");
        // "alice/gone.md" is tracked but deliberately never written — simulates a deletion.

        ReflectionTestUtils.setField(scheduler, "knowledgeDir", tempDir.toString());
        when(tracker.allTracked()).thenReturn(List.of(
                new IngestedFileTracker.TrackedFile("alice", "alice/keep.md"),
                new IngestedFileTracker.TrackedFile("alice", "alice/gone.md")));

        scheduler.reconcile();

        verify(vectorStore).delete(any(Filter.Expression.class));
        verify(tracker).remove("alice", "alice/gone.md");
        verify(tracker, never()).remove("alice", "alice/keep.md");
    }

    @Test
    void touchesNothingWhenEveryTrackedFileIsStillOnDisk(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("alice"));
        Files.writeString(tempDir.resolve("alice/keep.md"), "still here");

        ReflectionTestUtils.setField(scheduler, "knowledgeDir", tempDir.toString());
        when(tracker.allTracked()).thenReturn(List.of(new IngestedFileTracker.TrackedFile("alice", "alice/keep.md")));

        scheduler.reconcile();

        verify(vectorStore, never()).delete(any(Filter.Expression.class));
        verify(tracker, never()).remove(any(), any());
    }
}
