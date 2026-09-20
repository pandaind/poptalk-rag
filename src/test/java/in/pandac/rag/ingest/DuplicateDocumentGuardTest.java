package in.pandac.rag.ingest;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.component.file.FileConstants;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DuplicateDocumentGuardTest {

    private final IngestedFileTracker tracker = mock(IngestedFileTracker.class);
    private final VectorStore vectorStore = mock(VectorStore.class);
    private final DuplicateDocumentGuard guard = new DuplicateDocumentGuard(tracker, vectorStore);

    @Test
    void stopsTheRouteAndCleansUpVectorsWhenContentIsADuplicate() {
        when(tracker.findDuplicate("alice", "hash1", "alice/copy.md"))
                .thenReturn(Optional.of("alice/original.md"));
        Exchange exchange = exchangeFor("alice", "alice/copy.md", "hash1");

        guard.process(exchange);

        verify(vectorStore).delete(any(Filter.Expression.class));
        verify(tracker).markIngested("alice", "alice/copy.md", "hash1");
        verify(exchange).setRouteStop(true);
    }

    @Test
    void leavesTheRouteRunningWhenContentIsNotADuplicate() {
        when(tracker.findDuplicate("alice", "hash1", "alice/copy.md")).thenReturn(Optional.empty());
        Exchange exchange = exchangeFor("alice", "alice/copy.md", "hash1");

        guard.process(exchange);

        verify(vectorStore, never()).delete(any(Filter.Expression.class));
        verify(tracker, never()).markIngested(any(), any(), any());
        verify(exchange, never()).setRouteStop(true);
    }

    private static Exchange exchangeFor(String personaId, String relativePath, String contentHash) {
        Message message = mock(Message.class);
        when(message.getHeader(FileConstants.FILE_RELATIVE_PATH, String.class)).thenReturn(relativePath);
        Exchange exchange = mock(Exchange.class);
        when(exchange.getIn()).thenReturn(message);
        when(exchange.getProperty("personaId", String.class)).thenReturn(personaId);
        when(exchange.getProperty("contentHash", String.class)).thenReturn(contentHash);
        return exchange;
    }
}
