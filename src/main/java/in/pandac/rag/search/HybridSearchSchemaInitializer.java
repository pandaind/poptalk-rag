package in.pandac.rag.search;

import jakarta.annotation.PostConstruct;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Adds a generated full-text-search column and index onto pgvector's own
 * {@code vector_store} table, so {@link HybridSearchService} can run a
 * keyword-search leg alongside vector similarity without a second store.
 *
 * <p>Depending on {@link VectorStore} (rather than just {@link JdbcTemplate})
 * forces this to run after Spring AI's own {@code initialize-schema} has
 * created the base table — the same ordering trick already relied on
 * elsewhere in this codebase for additive, idempotent migrations
 * (see IngestedFileTracker, IngestionConfig).
 */
@Component
public class HybridSearchSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;

    public HybridSearchSchemaInitializer(VectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void ensureFullTextSearchColumn() {
        jdbcTemplate.execute("""
                ALTER TABLE vector_store
                ADD COLUMN IF NOT EXISTS content_tsv tsvector
                GENERATED ALWAYS AS (to_tsvector('english', content)) STORED
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS vector_store_content_tsv_idx
                ON vector_store USING GIN (content_tsv)
                """);
    }
}
