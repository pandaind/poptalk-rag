package in.pandac.rag.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * The only source of tenant identity in this system. Resolves which persona
 * is calling from the API key that authenticated the request — never from a
 * client-supplied header or a tool argument the model could be tricked into
 * setting. See {@link PersonaRequestContext} and
 * {@link in.pandac.rag.mcp.KnowledgeBaseTool}.
 *
 * <p>Guards {@code /mcp/**} and {@code /admin/**}; everything else (health
 * checks, etc.) passes through unauthenticated.
 */
@Component
public class PersonaApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PersonaApiKeyAuthFilter.class);

    /** apiKey -> personaId, parsed once from "personaId:key,personaId2:key2". */
    private final Map<String, String> personaByApiKey = new HashMap<>();

    public PersonaApiKeyAuthFilter(@Value("${app.persona-api-keys:}") String personaApiKeys) {
        for (String entry : personaApiKeys.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int sep = trimmed.indexOf(':');
            if (sep <= 0 || sep == trimmed.length() - 1) {
                log.warn("Ignoring malformed PERSONA_API_KEYS entry (expected personaId:key): '{}'", trimmed);
                continue;
            }
            String personaId = trimmed.substring(0, sep);
            String apiKey = trimmed.substring(sep + 1);
            personaByApiKey.put(apiKey, personaId);
        }
        log.info("Loaded API keys for {} persona(s): {}", personaByApiKey.size(), personaByApiKey.values());
    }

    private boolean requiresAuth(String path) {
        return path.startsWith("/mcp") || path.startsWith("/admin");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!requiresAuth(path)) {
            chain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        String apiKey = (authHeader != null && authHeader.startsWith("Bearer "))
                ? authHeader.substring(7).trim()
                : null;

        String personaId = (apiKey != null) ? personaByApiKey.get(apiKey) : null;
        if (personaId == null) {
            log.warn("Rejected unauthenticated/invalid request to {}", path);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Missing or invalid API key\"}");
            return;
        }

        try {
            PersonaRequestContext.set(personaId);
            chain.doFilter(request, response);
        } finally {
            PersonaRequestContext.clear();
        }
    }
}
