package in.pandac.rag.ingest;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Tracks which (persona, source file) pairs currently have vectors in the
 * store, so {@link KnowledgeReconciliationScheduler} can tell which ones no
 * longer exist on disk and need their vectors removed — the vector store
 * itself has no "list distinct sources" query, and the Camel file consumer
 * only ever sees files that still exist, never ones that were deleted.
 *
 * <p>Also stores each file's {@code content_hash} (see
 * DocumentParsingProcessor), which {@link DuplicateDocumentGuard} uses to
 * detect the same content ingested under two different filenames — a plain
 * lookaside table beats coercing the vector store into exact-match metadata
 * lookups for this.
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
        jdbcTemplate.execute("ALTER TABLE ingested_files ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64)");
    }

    public void markIngested(String personaId, String relativePath, String contentHash) {
        jdbcTemplate.update("""
                INSERT INTO ingested_files (persona_id, relative_path, content_hash, last_seen_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (persona_id, relative_path) DO UPDATE SET content_hash = ?, last_seen_at = now()
                """, personaId, relativePath, contentHash, contentHash);
    }

    public List<TrackedFile> allTracked() {
        return jdbcTemplate.query(
                "SELECT persona_id, relative_path FROM ingested_files",
                (rs, rowNum) -> new TrackedFile(rs.getString("persona_id"), rs.getString("relative_path")));
    }

    /** Finds another file already tracked under the same persona with identical content, if any. */
    public Optional<String> findDuplicate(String personaId, String contentHash, String excludingRelativePath) {
        List<String> matches = jdbcTemplate.query(
                """
                SELECT relative_path FROM ingested_files
                WHERE persona_id = ? AND content_hash = ? AND relative_path != ?
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getString("relative_path"), personaId, contentHash, excludingRelativePath);
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }

    public void remove(String personaId, String relativePath) {
        jdbcTemplate.update(
                "DELETE FROM ingested_files WHERE persona_id = ? AND relative_path = ?",
                personaId, relativePath);
    }
}
