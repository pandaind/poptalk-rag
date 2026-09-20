package in.pandac.rag.ingest;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.component.file.FileConstants;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersonaPathResolverProcessorTest {

    private final PersonaPathResolverProcessor processor = new PersonaPathResolverProcessor();

    @Test
    void extractsThePersonaIdFromTheFirstPathSegment() {
        Exchange exchange = exchangeWithRelativePath("alice/faq.md");

        processor.process(exchange);

        verify(exchange).setProperty("personaId", "alice");
    }

    @Test
    void normalizesWindowsStyleBackslashes() {
        Exchange exchange = exchangeWithRelativePath("alice\\nested\\faq.md");

        processor.process(exchange);

        verify(exchange).setProperty("personaId", "alice");
    }

    @Test
    void rejectsAFileDirectlyInTheKnowledgeRoot() {
        Exchange exchange = exchangeWithRelativePath("readme.md");

        assertThatThrownBy(() -> processor.process(exchange)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAMissingRelativePathHeader() {
        Exchange exchange = exchangeWithRelativePath(null);

        assertThatThrownBy(() -> processor.process(exchange)).isInstanceOf(IllegalStateException.class);
    }

    private static Exchange exchangeWithRelativePath(String relativePath) {
        Message message = mock(Message.class);
        when(message.getHeader(FileConstants.FILE_RELATIVE_PATH, String.class)).thenReturn(relativePath);
        Exchange exchange = mock(Exchange.class);
        when(exchange.getIn()).thenReturn(message);
        return exchange;
    }
}
