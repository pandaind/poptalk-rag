package in.pandac.rag.ingest;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.component.file.FileConstants;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the add-then-delete-old-batch ordering this processor depends on
 * for failure-safety: a failed {@code add()} (e.g. the embedding provider
 * going down mid-request) must never reach the delete step, so a file's
 * existing, still-good chunks survive the failure instead of being removed
 * before a replacement could be written.
 */
class EmbeddingUpsertProcessorTest {

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final IngestedFileTracker tracker = mock(IngestedFileTracker.class);
    private final EmbeddingUpsertProcessor processor = new EmbeddingUpsertProcessor(vectorStore, tracker);

    @Test
    void deletesThePreviousBatchOnlyAfterASuccessfulAdd() {
        Exchange exchange = exchangeFor(List.of(chunk("hello")), "alice", "alice/doc.md", "hash1");

        processor.process(exchange);

        InOrder order = inOrder(vectorStore, tracker);
        order.verify(vectorStore).add(anyList());
        order.verify(vectorStore).delete(any(Filter.Expression.class));
        order.verify(tracker).markIngested("alice", "alice/doc.md", "hash1");
    }

    @Test
    void neverDeletesThePreviousBatchIfAddFails() {
        doThrow(new RuntimeException("embedding provider unreachable")).when(vectorStore).add(anyList());
        Exchange exchange = exchangeFor(List.of(chunk("hello")), "alice", "alice/doc.md", "hash1");

        assertThatThrownBy(() -> processor.process(exchange)).isInstanceOf(RuntimeException.class);

        verify(vectorStore, never()).delete(any(Filter.Expression.class));
        verify(tracker, never()).markIngested(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void taggedChunksCarryAFreshBatchId() {
        Exchange exchange = exchangeFor(List.of(chunk("hello")), "alice", "alice/doc.md", "hash1");

        processor.process(exchange);

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getMetadata()).containsKey("batch_id");
    }

    private static Document chunk(String text) {
        return Document.builder().text(text).metadata(Map.of("persona_id", "alice")).build();
    }

    @SuppressWarnings("unchecked")
    private static Exchange exchangeFor(List<Document> chunks, String personaId, String relativePath, String contentHash) {
        Message message = mock(Message.class);
        when(message.getBody(List.class)).thenReturn((List) chunks);
        when(message.getHeader(FileConstants.FILE_RELATIVE_PATH, String.class)).thenReturn(relativePath);

        Exchange exchange = mock(Exchange.class);
        when(exchange.getIn()).thenReturn(message);
        when(exchange.getProperty("personaId", String.class)).thenReturn(personaId);
        when(exchange.getProperty("contentHash", String.class)).thenReturn(contentHash);
        return exchange;
    }
}
