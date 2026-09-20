package in.pandac.rag.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmRerankerTest {

    @Test
    void skipsRerankingWhenThereAreAlreadyFewerCandidatesThanTopK() {
        LlmReranker reranker = rerankerReturning("{\"rankedIndices\":[],\"sufficient\":true}");
        List<Document> candidates = List.of(doc("a"), doc("b"));

        LlmReranker.RerankOutcome outcome = reranker.rerank("query", candidates, 5);

        assertThat(outcome.documents()).containsExactly(doc("a"), doc("b"));
        assertThat(outcome.sufficient()).isTrue();
    }

    @Test
    void reordersCandidatesAccordingToTheModelsRankedIndices() {
        LlmReranker reranker = rerankerReturning("{\"rankedIndices\":[1,0],\"sufficient\":true}");
        List<Document> candidates = List.of(doc("a"), doc("b"), doc("c"));

        LlmReranker.RerankOutcome outcome = reranker.rerank("query", candidates, 2);

        assertThat(outcome.documents()).containsExactly(doc("b"), doc("a"));
        assertThat(outcome.sufficient()).isTrue();
    }

    @Test
    void surfacesAnInsufficientJudgementFromTheModel() {
        LlmReranker reranker = rerankerReturning("{\"rankedIndices\":[0],\"sufficient\":false}");
        List<Document> candidates = List.of(doc("a"), doc("b"), doc("c"));

        LlmReranker.RerankOutcome outcome = reranker.rerank("query", candidates, 1);

        assertThat(outcome.sufficient()).isFalse();
    }

    @Test
    void fallsBackToVectorOrderWhenTheModelResponseIsUnparseable() {
        LlmReranker reranker = rerankerReturning("not json at all");
        List<Document> candidates = List.of(doc("a"), doc("b"), doc("c"));

        LlmReranker.RerankOutcome outcome = reranker.rerank("query", candidates, 2);

        assertThat(outcome.documents()).containsExactly(doc("a"), doc("b"));
        assertThat(outcome.sufficient()).isTrue();
    }

    @Test
    void isUnavailableWhenNoChatModelIsConfigured() {
        ObjectProvider<ChatModel> noModel = mock(ObjectProvider.class);
        when(noModel.getIfAvailable()).thenReturn(null);
        LlmReranker reranker = new LlmReranker(noModel, true);

        assertThat(reranker.isAvailable()).isFalse();
        List<Document> candidates = List.of(doc("a"), doc("b"), doc("c"));
        LlmReranker.RerankOutcome outcome = reranker.rerank("query", candidates, 1);
        assertThat(outcome.documents()).containsExactly(doc("a"));
    }

    @Test
    void isUnavailableWhenExplicitlyDisabled() {
        LlmReranker reranker = rerankerReturning("{\"rankedIndices\":[0],\"sufficient\":true}", false);

        assertThat(reranker.isAvailable()).isFalse();
    }

    private static LlmReranker rerankerReturning(String modelResponseText) {
        return rerankerReturning(modelResponseText, true);
    }

    @SuppressWarnings("unchecked")
    private static LlmReranker rerankerReturning(String modelResponseText, boolean enabled) {
        ChatModel fake = prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage(modelResponseText))));
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(fake);
        return new LlmReranker(provider, enabled);
    }

    private static Document doc(String id) {
        return Document.builder().id(id).text("text " + id).build();
    }
}
