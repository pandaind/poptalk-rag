package in.pandac.rag.eval;

import in.pandac.rag.mcp.KnowledgeBaseTool;
import in.pandac.rag.security.PersonaRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval regression harness — not an LLM-as-judge answer-quality eval,
 * just "does search_knowledge_base actually surface the right document for
 * each fixture query" (Recall@K), so future chunking/ranking/threshold
 * changes can be checked against a fixed baseline. Tagged {@code eval} and
 * excluded from the default build (see pom.xml's {@code excludedGroups})
 * since it needs Docker for Testcontainers — run it explicitly with
 * {@code mvn test -DexcludedGroups=}.
 *
 * <p>Uses a small deterministic bag-of-words {@link EmbeddingModel} fake
 * instead of a live Ollama/OpenAI call: what's under test here is the
 * retrieval pipeline's wiring and ranking logic, not embedding quality, and
 * a fake keeps this test hermetic (Docker only, no external model
 * dependency). Reranking is disabled for the same reason — it would require
 * a live chat model too, and the hybrid vector+keyword leg is what this test
 * exercises.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Tag("eval")
class RetrievalRegressionTest {

    private static final Logger log = LoggerFactory.getLogger(RetrievalRegressionTest.class);
    private static final int TEST_DIMENSIONS = 32;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("app.embedding.provider", () -> "test-fake");
        registry.add("app.rerank.enabled", () -> "false");
        registry.add("spring.ai.vectorstore.pgvector.dimensions", () -> TEST_DIMENSIONS);
        registry.add("app.knowledge.dir", () -> System.getProperty("java.io.tmpdir") + "/poptalk-rag-eval-knowledge");
        registry.add("PERSONA_API_KEYS", () -> "");
    }

    @TestConfiguration
    static class FakeEmbeddingConfig {
        @Bean
        EmbeddingModel embeddingModel() {
            return new DeterministicEmbeddingModel(TEST_DIMENSIONS);
        }
    }

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private KnowledgeBaseTool knowledgeBaseTool;

    @BeforeEach
    void seedKnowledgeBase() {
        vectorStore.add(List.of(
                chunk("alice", "alice/pricing.md",
                        "Our Enterprise plan costs $499 per month and includes unlimited seats and priority support."),
                chunk("alice", "alice/returns.md",
                        "Returns are accepted within 30 days of purchase with a valid receipt."),
                chunk("acme-support", "acme-support/warranty.md",
                        "All hardware products come with a two-year limited warranty covering manufacturing defects."),
                chunk("_shared", "_shared/company.md",
                        "Acme Corp was founded in 2010 and is headquartered in Springfield.")));
    }

    @AfterEach
    void clearContext() {
        PersonaRequestContext.clear();
    }

    private static Document chunk(String personaId, String source, String text) {
        return Document.builder()
                .text(text)
                .metadata(Map.of("persona_id", personaId, "source", source, "chunk_index", 0))
                .build();
    }

    record EvalCase(String personaId, String query, List<String> expectedSourceContains) {}

    @Test
    void searchKnowledgeBaseFindsExpectedSources() throws Exception {
        List<EvalCase> cases = loadFixtures();
        int hits = 0;

        for (EvalCase evalCase : cases) {
            PersonaRequestContext.set(evalCase.personaId());
            String result = knowledgeBaseTool.search(evalCase.query(), 5);
            boolean found = evalCase.expectedSourceContains().stream().anyMatch(result::contains);
            if (found) {
                hits++;
            } else {
                log.warn("MISS — persona={}, query='{}', expected one of {}, got:\n{}",
                        evalCase.personaId(), evalCase.query(), evalCase.expectedSourceContains(), result);
            }
        }

        double recallAtK = (double) hits / cases.size();
        log.info("Retrieval regression: {}/{} cases matched (Recall@K = {})", hits, cases.size(), recallAtK);
        assertThat(recallAtK).isGreaterThanOrEqualTo(0.75);
    }

    @SuppressWarnings("unchecked")
    private static List<EvalCase> loadFixtures() throws Exception {
        try (InputStream in = new ClassPathResource("eval/retrieval-cases.yaml").getInputStream()) {
            List<Map<String, Object>> raw = new Yaml(new Constructor(new LoaderOptions())).load(in);
            List<EvalCase> cases = new ArrayList<>();
            for (Map<String, Object> entry : raw) {
                cases.add(new EvalCase(
                        (String) entry.get("personaId"),
                        (String) entry.get("query"),
                        (List<String>) entry.get("expectedSourceContains")));
            }
            return cases;
        }
    }

    /**
     * Deterministic bag-of-words embedding: hashes each lowercased token into
     * one of {@code dimensions} buckets and L2-normalizes the result, so
     * texts sharing more words score higher on cosine similarity. Enough to
     * exercise retrieval/ranking logic without a live embedding model.
     */
    static class DeterministicEmbeddingModel implements EmbeddingModel {

        private final int dimensions;

        DeterministicEmbeddingModel(int dimensions) {
            this.dimensions = dimensions;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = new ArrayList<>();
            List<String> inputs = request.getInstructions();
            for (int i = 0; i < inputs.size(); i++) {
                embeddings.add(new Embedding(vectorFor(inputs.get(i)), i));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return vectorFor(document.getText());
        }

        private float[] vectorFor(String text) {
            float[] vector = new float[dimensions];
            if (text != null) {
                for (String token : text.toLowerCase().split("\\W+")) {
                    if (token.isBlank()) {
                        continue;
                    }
                    vector[Math.floorMod(token.hashCode(), dimensions)] += 1f;
                }
            }
            double norm = 0;
            for (float v : vector) {
                norm += (double) v * v;
            }
            norm = Math.sqrt(norm);
            if (norm > 0) {
                for (int i = 0; i < vector.length; i++) {
                    vector[i] = (float) (vector[i] / norm);
                }
            }
            return vector;
        }
    }
}
