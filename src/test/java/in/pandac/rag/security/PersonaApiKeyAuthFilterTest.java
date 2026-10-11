package in.pandac.rag.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersonaApiKeyAuthFilterTest {

    private final PersonaApiKeyAuthFilter filter =
            new PersonaApiKeyAuthFilter("alice:key1,acme-support:key2");

    @Test
    void resolvesThePersonaFromAValidKeyAndClearsItAfterward() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getRequestURI()).thenReturn("/mcp");
        when(request.getHeader("Authorization")).thenReturn("Bearer key1");
        doAnswer(inv -> {
            assertThat(PersonaRequestContext.get()).isEqualTo("alice");
            return null;
        }).when(chain).doFilter(request, response);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(PersonaRequestContext.get()).isNull();
    }

    @Test
    void rejectsAMissingOrInvalidKeyWith401() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getRequestURI()).thenReturn("/mcp");
        when(request.getHeader("Authorization")).thenReturn("Bearer wrong-key");
        StringWriter body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        filter.doFilterInternal(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(body.toString()).contains("Missing or invalid API key");
    }

    @Test
    void pathsOutsideMcpAndAdminPassThroughWithoutAuth() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getRequestURI()).thenReturn("/actuator/health");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(anyInt());
    }

    private static java.util.List<ILoggingEvent> logsFrom(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(PersonaApiKeyAuthFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
    }

    @Test
    void anEntryWithAnEqualsSignIsIgnoredWithoutLoggingItsSecret() {
        var events = logsFrom(() -> new PersonaApiKeyAuthFilter("alice=super-secret-key-123"));

        assertThat(events).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("personaId:key"));
        assertThat(events).noneMatch(e -> e.getFormattedMessage().contains("super-secret-key-123"));
    }

    @Test
    void noUsableKeysIsALoudError() {
        for (String value : new String[] {"", "alice=oops", ",,"}) {
            var events = logsFrom(() -> new PersonaApiKeyAuthFilter(value));
            assertThat(events).as("value %s", value)
                    .anyMatch(e -> e.getLevel() == Level.ERROR
                            && e.getFormattedMessage().contains("PERSONA_API_KEYS"));
        }
    }

    @Test
    void validKeysDoNotRaiseAnError() {
        var events = logsFrom(() -> new PersonaApiKeyAuthFilter("alice:k1"));

        assertThat(events).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN));
    }
}
