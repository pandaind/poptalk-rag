package in.pandac.rag.ingest;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.component.file.FileConstants;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * Parses whatever file type Camel handed us into plain text, then computes a
 * content hash — used by {@link DuplicateDocumentGuard} to detect the same
 * content ingested under two different filenames — and carries it forward as
 * an exchange property for {@link ChunkingProcessor} to tag onto every chunk.
 *
 * <p>Markdown files bypass Tika entirely and are parsed as raw text via
 * {@link MarkdownParser}: Tika's markdown handling strips heading markers on
 * the way to plain text, which would make heading-path recovery unreliable,
 * and markdown is already close enough to plain text that embedding it as-is
 * is standard practice. This is also where optional YAML frontmatter
 * (department/version/tags/etc.) is extracted. Every other supported format
 * (PDF/DOCX/HTML/TXT) still goes through Tika — they have no comparable
 * in-band structure worth preserving here.
 */
@Component("documentParsingProcessor")
public class DocumentParsingProcessor implements Processor {

    @Override
    public void process(Exchange exchange) throws Exception {
        String absolutePath = exchange.getIn().getHeader(FileConstants.FILE_ABSOLUTE_PATH, String.class);

        List<Document> documents;
        if (absolutePath.toLowerCase().endsWith(".md")) {
            String raw = Files.readString(Path.of(absolutePath), StandardCharsets.UTF_8);
            MarkdownParser.ParseResult parsed = MarkdownParser.parse(raw);
            exchange.setProperty("frontmatter", parsed.frontmatter());
            exchange.setProperty("headings", parsed.headings());
            documents = List.of(Document.builder().text(parsed.body()).build());
        } else {
            TikaDocumentReader reader = new TikaDocumentReader("file:" + absolutePath);
            documents = reader.get();
        }

        String fullText = documents.stream().map(Document::getText).reduce("", (a, b) -> a + "\n" + b);
        exchange.setProperty("contentHash", sha256(fullText));
        exchange.getIn().setBody(documents);
    }

    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
