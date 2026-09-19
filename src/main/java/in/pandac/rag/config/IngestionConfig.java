package in.pandac.rag.config;

import org.apache.camel.processor.idempotent.jdbc.JdbcMessageIdRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
public class IngestionConfig {

    /**
     * Persistent (restart-safe) idempotent repository the ingestion route uses
     * to skip files it's already embedded, keyed by name+size+last-modified —
     * see IngestionRoute. Backed by the same Postgres database as pgvector.
     *
     * <p>The table is created explicitly here with {@code CREATE TABLE IF NOT
     * EXISTS} rather than via {@code setCreateTableIfNotExists(true)}: that
     * built-in option checks existence with a plain {@code SELECT} against
     * the table first, and on Postgres (unlike H2/MySQL) a failed statement
     * aborts the whole transaction, so the following {@code CREATE TABLE} in
     * the same transaction fails too — confirmed by actually booting this
     * against Postgres. {@code IF NOT EXISTS} sidesteps the failing SELECT
     * entirely.
     */
    @Bean
    public JdbcMessageIdRepository knowledgeIdempotentRepository(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS CAMEL_MESSAGEPROCESSED (
                    processorName VARCHAR(255),
                    messageId VARCHAR(100),
                    createdAt TIMESTAMP,
                    PRIMARY KEY (processorName, messageId)
                )
                """);

        JdbcMessageIdRepository repo = new JdbcMessageIdRepository(dataSource, "knowledgeIngestion");
        repo.setCreateTableIfNotExists(false);
        return repo;
    }
}
