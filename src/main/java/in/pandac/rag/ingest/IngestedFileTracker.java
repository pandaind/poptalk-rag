package in.pandac.rag.ingest;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Tracks which (persona, source file) pairs currently have vectors in the
 * store, so {@link KnowledgeReconciliationScheduler} can tell which ones no
 * longer exist on disk and need their vectors removed — the vector store
 * itself has no "list distinct sources" query, and the Camel file consumer
 * only ever sees files that still exist, never ones that were deleted.
 */
@Component
public class IngestedFileTracker {

    public record TrackedFile(String personaId, String relativePath) {}

    private final JdbcTemplate jdbcTemplate;

    public IngestedFileTracker(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void ensureTableExists() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ingested_files (
                    persona_id     VARCHAR(255) NOT NULL,
                    relative_path  VARCHAR(1024) NOT NULL,
                    last_seen_at   TIMESTAMP NOT NULL DEFAULT now(),
                    PRIMARY KEY (persona_id, relative_path)
                )
                """);
    }

    public void markIngested(String personaId, String relativePath) {
        jdbcTemplate.update("""
                INSERT INTO ingested_files (persona_id, relative_path, last_seen_at)
                VALUES (?, ?, now())
                ON CONFLICT (persona_id, relative_path) DO UPDATE SET last_seen_at = now()
                """, personaId, relativePath);
    }

    public List<TrackedFile> allTracked() {
        return jdbcTemplate.query(
                "SELECT persona_id, relative_path FROM ingested_files",
                (rs, rowNum) -> new TrackedFile(rs.getString("persona_id"), rs.getString("relative_path")));
    }

    public void remove(String personaId, String relativePath) {
        jdbcTemplate.update(
                "DELETE FROM ingested_files WHERE persona_id = ? AND relative_path = ?",
                personaId, relativePath);
    }
}
