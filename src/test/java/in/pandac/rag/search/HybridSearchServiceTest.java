package in.pandac.rag.search;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HybridSearchServiceTest {

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final HybridSearchService service = new HybridSearchService(vectorStore, jdbcTemplate);

    @Test
    void fusesVectorAndKeywordResultsGivingPriorityToDocumentsFoundByBoth() {
        ReflectionTestUtils.setField(service, "hybridEnabled", true);
        Document docA = doc("a");
        Document docB = doc("b");
        Document docC = doc("c");
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(docA, docB));
        doReturn(List.of(docB, docC)).when(jdbcTemplate)
                .query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any());

        List<Document> results = service.search("query", "alice", "_shared", 10, 0.5);

        // b appears in both legs (highest combined RRF score), then a (rank 0
        // in vector alone) ahead of c (rank 1 in keyword alone).
        assertThat(results).extracting(Document::getId).containsExactly("b", "a", "c");
    }

    @Test
    void skipsTheKeywordLegEntirelyWhenHybridSearchIsDisabled() {
        ReflectionTestUtils.setField(service, "hybridEnabled", false);
        Document docA = doc("a");
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(docA));

        List<Document> results = service.search("query", "alice", "_shared", 10, 0.5);

        assertThat(results).containsExactly(docA);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void fallsBackToVectorOnlyResultsWhenTheKeywordLegFails() {
        ReflectionTestUtils.setField(service, "hybridEnabled", true);
        Document docA = doc("a");
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(docA));
        doThrow(new RuntimeException("no content_tsv column")).when(jdbcTemplate)
                .query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any());

        List<Document> results = service.search("query", "alice", "_shared", 10, 0.5);

        assertThat(results).extracting(Document::getId).containsExactly("a");
    }

    private static Document doc(String id) {
        return Document.builder().id(id).text("text " + id).build();
    }
}
