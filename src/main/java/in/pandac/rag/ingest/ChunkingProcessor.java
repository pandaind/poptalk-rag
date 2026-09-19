package in.pandac.rag.ingest;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.component.file.FileConstants;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits each parsed document into embeddable chunks and tags every chunk
 * with the metadata the rest of the system depends on: {@code persona_id}
 * (query-time tenant filter — see KnowledgeBaseTool), {@code source} (lets
 * KnowledgeReconciliationScheduler find and remove a deleted file's vectors,
 * and lets EmbeddingUpsertProcessor replace a changed file's old chunks
 * instead of just appending new ones alongside them), {@code content_hash}
 * and {@code ingested_at} (dedup/freshness — see DuplicateDocumentGuard and
 * KnowledgeBaseTool's citation headers), any markdown YAML frontmatter
 * fields set by DocumentParsingProcessor, and {@code heading_path} where
 * available.
 *
 * <p>{@link TokenTextSplitter} has no overlap of its own and returns no
 * positional information, so both are approximated here: overlap is a
 * simple trailing-character prefix carried from the previous chunk (not a
 * token-aware sliding window), and heading assignment locates each chunk's
 * text back in the original body via {@code indexOf(...)} — reliable in
 * practice since chunks are produced in document order.
 */
@Component("chunkingProcessor")
public class ChunkingProcessor implements Processor {

    @Value("${app.chunking.chunk-size:800}")
    private int chunkSize;

    @Value("${app.chunking.min-chunk-size-chars:350}")
    private int minChunkSizeChars;

    @Value("${app.chunking.overlap-chars:100}")
    private int overlapChars;

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        List<Document> documents = exchange.getIn().getBody(List.class);
        String personaId = exchange.getProperty("personaId", String.class);
        String relativePath = exchange.getIn().getHeader(FileConstants.FILE_RELATIVE_PATH, String.class);
        String contentHash = exchange.getProperty("contentHash", String.class);
        Map<String, Object> frontmatter = exchange.getProperty("frontmatter", Map.class);
        List<MarkdownParser.HeadingBreak> headings = exchange.getProperty("headings", List.class);
        String body = documents.size() == 1 ? documents.get(0).getText() : null;

        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(chunkSize)
                .withMinChunkSizeChars(minChunkSizeChars)
                .build();

        List<Document> chunks = splitter.apply(documents);
        List<Document> tagged = new ArrayList<>(chunks.size());
        String ingestedAt = Instant.now().toString();
        int index = 0;
        int searchFrom = 0;
        String previousRawText = null;

        for (Document chunk : chunks) {
            String rawText = chunk.getText();

            String headingPath = null;
            if (headings != null && !headings.isEmpty() && body != null) {
                int idx = body.indexOf(rawText, searchFrom);
                if (idx < 0) {
                    idx = searchFrom;
                }
                headingPath = MarkdownParser.headingPathAt(headings, idx);
                searchFrom = idx;
            }

            StringBuilder finalText = new StringBuilder();
            if (previousRawText != null && overlapChars > 0) {
                int start = Math.max(0, previousRawText.length() - overlapChars);
                finalText.append(previousRawText, start, previousRawText.length()).append("\n\n");
            }
            if (headingPath != null) {
                finalText.append("Section: ").append(headingPath).append("\n\n");
            }
            finalText.append(rawText);

            Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
            if (frontmatter != null) {
                metadata.putAll(frontmatter);
            }
            metadata.put("persona_id", personaId);
            metadata.put("source", relativePath);
            metadata.put("chunk_index", index++);
            metadata.put("ingested_at", ingestedAt);
            if (contentHash != null) {
                metadata.put("content_hash", contentHash);
            }
            if (headingPath != null) {
                metadata.put("heading_path", headingPath);
            }

            tagged.add(Document.builder()
                    .text(finalText.toString())
                    .metadata(metadata)
                    .build());

            previousRawText = rawText;
        }

        exchange.getIn().setBody(tagged);
    }
}
