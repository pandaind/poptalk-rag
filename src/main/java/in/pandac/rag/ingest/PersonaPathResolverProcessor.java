package in.pandac.rag.ingest;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.component.file.FileConstants;
import org.springframework.stereotype.Component;

/**
 * Every knowledge file lives at {@code <knowledge-dir>/<personaId>/...} —
 * this pulls the persona id out of the first path segment so downstream
 * steps can tag chunks with it. This is purely a data-organization convenience
 * for ingestion; it is NOT how tenant access is enforced at query time (see
 * PersonaApiKeyAuthFilter for that).
 */
@Component("personaPathResolver")
public class PersonaPathResolverProcessor implements Processor {

    @Override
    public void process(Exchange exchange) {
        String relativePath = exchange.getIn().getHeader(FileConstants.FILE_RELATIVE_PATH, String.class);
        if (relativePath == null) {
            throw new IllegalStateException("No relative path header on file exchange — is this route consuming from a file endpoint?");
        }

        String normalized = relativePath.replace('\\', '/');
        int slash = normalized.indexOf('/');
        if (slash <= 0) {
            throw new IllegalStateException(
                    "Knowledge files must live under <knowledge-dir>/<personaId>/..., found '"
                            + relativePath + "' directly in the knowledge root");
        }

        exchange.setProperty("personaId", normalized.substring(0, slash));
    }
}
