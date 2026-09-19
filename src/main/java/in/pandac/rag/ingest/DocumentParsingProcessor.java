package in.pandac.rag.ingest;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.component.file.FileConstants;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Parses whatever file type Camel handed us (PDF/DOCX/HTML/TXT/MD/...) into
 * plain text via Apache Tika — one reader handles every format Tika supports,
 * so no per-extension branching is needed here.
 */
@Component("documentParsingProcessor")
public class DocumentParsingProcessor implements Processor {

    @Override
    public void process(Exchange exchange) {
        String absolutePath = exchange.getIn().getHeader(FileConstants.FILE_ABSOLUTE_PATH, String.class);
        TikaDocumentReader reader = new TikaDocumentReader("file:" + absolutePath);
        List<Document> documents = reader.get();
        exchange.getIn().setBody(documents);
    }
}
