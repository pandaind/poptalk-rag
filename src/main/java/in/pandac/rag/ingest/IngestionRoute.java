package in.pandac.rag.ingest;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Polls {@code app.knowledge.dir} for {@code <personaId>/*.{md,txt,pdf,docx,html}}
 * files and runs each new-or-changed one through parse → chunk → embed →
 * upsert (see the processors in this package).
 *
 * <p>{@code noop=true} means files are only ever read, never moved or
 * deleted. Change detection is name+size+last-modified (idempotentKey) backed
 * by a Postgres-persisted idempotent repository (see IngestionConfig) —
 * restart-safe, and cheap enough to run every poll without re-embedding
 * anything unchanged. File deletions are handled separately by
 * {@link KnowledgeReconciliationScheduler}, since a file consumer only ever
 * sees files that still exist.
 */
@Component
public class IngestionRoute extends RouteBuilder {

    @Value("${app.knowledge.dir}")
    private String knowledgeDir;

    @Value("${app.knowledge.poll-interval-ms:60000}")
    private long pollIntervalMs;

    @Override
    public void configure() {
        // Deliberately no onException(...).handled(true) here: leaving
        // exceptions unhandled lets Camel's idempotent consumer roll back the
        // tentative idempotent-key add for this file, so a transient failure
        // (e.g. the embedding provider being briefly unreachable) gets
        // retried on the next poll instead of being marked "already
        // processed" forever. Camel's default error handler still logs it.

        from("file://" + knowledgeDir
                + "?recursive=true"
                + "&noop=true"
                + "&idempotent=true"
                + "&idempotentKey=${file:name}-${file:size}-${file:modified}"
                + "&idempotentRepository=#knowledgeIdempotentRepository"
                + "&delay=" + pollIntervalMs
                + "&include=.*\\.(md|txt|pdf|docx|html|htm)$")
                .routeId("knowledge-ingestion")
                .process("personaPathResolver")
                .process("documentParsingProcessor")
                .process("chunkingProcessor")
                .process("embeddingUpsertProcessor")
                .log("Ingested ${exchangeProperty.personaId}/${header.CamelFileRelativePath} "
                        + "(${body.size()} chunks)");
    }
}
