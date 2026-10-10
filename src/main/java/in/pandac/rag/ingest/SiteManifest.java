package in.pandac.rag.ingest;

import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The single-file knowledge base a static site publishes (Meteorite writes {@code knowledge.json}
 * when {@code chat.knowledge.publish} is on): a versioned list of Markdown documents with their
 * public URLs. Parsing is strict about the things that decide which files get written and where,
 * and lenient about everything else, so newer publishers that add fields keep working.
 */
public record SiteManifest(int version, String digest, List<Document> documents) {

    /** The layout version this reader understands. */
    public static final int SUPPORTED_VERSION = 1;

    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._ -]{0,127}");
    private static final int MAX_PATH_LENGTH = 240;

    public record Document(
            String path,
            String title,
            String url,
            String section,
            String date,
            String description,
            List<String> tags,
            String hash,
            String content) {
    }

    /** Thrown for a manifest that must not be applied; nothing on disk is changed in that case. */
    public static class InvalidManifestException extends RuntimeException {
        public InvalidManifestException(String message) {
            super(message);
        }
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @SuppressWarnings("unchecked")
    public static SiteManifest parse(String json) {
        Map<String, Object> root;
        try {
            root = MAPPER.readValue(json, Map.class);
        } catch (RuntimeException e) {
            throw new InvalidManifestException("not valid JSON: " + e.getMessage());
        }
        if (root == null) {
            throw new InvalidManifestException("empty document");
        }
        Object v = root.get("version");
        if (!(v instanceof Number n) || n.intValue() != SUPPORTED_VERSION) {
            throw new InvalidManifestException(
                    "unsupported manifest version " + v + " (this server reads version " + SUPPORTED_VERSION + ")");
        }
        if (!(root.get("documents") instanceof List<?> raw)) {
            throw new InvalidManifestException("missing `documents` array");
        }
        List<Document> docs = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Object o : raw) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new InvalidManifestException("a document is not an object");
            }
            String path = str(m.get("path"));
            validatePath(path);
            if (!seen.add(path)) {
                throw new InvalidManifestException("duplicate document path " + path);
            }
            String content = str(m.get("content"));
            if (content == null) {
                throw new InvalidManifestException("document " + path + " has no content");
            }
            List<String> tags = new ArrayList<>();
            if (m.get("tags") instanceof List<?> t) {
                for (Object tag : t) {
                    if (tag != null) {
                        tags.add(tag.toString());
                    }
                }
            }
            docs.add(new Document(path, str(m.get("title")), str(m.get("url")), str(m.get("section")),
                    str(m.get("date")), str(m.get("description")), tags, str(m.get("hash")), content));
        }
        return new SiteManifest(SUPPORTED_VERSION, str(root.get("digest")), List.copyOf(docs));
    }

    /**
     * A document path becomes a file name under a persona folder. It must be a plain relative
     * Markdown path: no traversal, no absolute paths, no hidden segments, no odd characters.
     */
    static void validatePath(String path) {
        if (path == null || path.isEmpty() || path.length() > MAX_PATH_LENGTH) {
            throw new InvalidManifestException("document path is missing or too long: " + path);
        }
        if (path.startsWith("/") || path.contains("\\") || path.contains("\0") || !path.endsWith(".md")) {
            throw new InvalidManifestException("unsafe document path: " + path);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..") || !SAFE_SEGMENT.matcher(segment).matches()) {
                throw new InvalidManifestException("unsafe document path: " + path);
            }
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
