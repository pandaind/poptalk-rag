package in.pandac.rag.security;

/**
 * Holds the persona id resolved by {@link PersonaApiKeyAuthFilter} for the
 * current request thread. This is the ONLY source of tenant identity read by
 * {@link in.pandac.rag.mcp.KnowledgeBaseTool} — never a tool argument, never
 * a client-supplied header. See PersonaApiKeyAuthFilter for why.
 */
public final class PersonaRequestContext {

    private static final ThreadLocal<String> CURRENT_PERSONA = new ThreadLocal<>();

    private PersonaRequestContext() {
    }

    public static void set(String personaId) {
        CURRENT_PERSONA.set(personaId);
    }

    public static String get() {
        return CURRENT_PERSONA.get();
    }

    public static void clear() {
        CURRENT_PERSONA.remove();
    }
}
