package in.pandac.rag.ingest;

import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort structure extraction for markdown knowledge files: an optional
 * YAML frontmatter block, and a list of heading breakpoints used to tag each
 * chunk with the section it falls under. Deliberately markdown-only — other
 * formats (PDF/DOCX/HTML) are normalized to plain text by Tika before this
 * pipeline sees them, so there's no reliable heading structure left to
 * recover for them.
 */
public final class MarkdownParser {

    private static final Pattern FRONTMATTER = Pattern.compile("\\A---\\r?\\n(.*?\\r?\\n)---\\r?\\n?", Pattern.DOTALL);
    private static final Pattern HEADING = Pattern.compile("(?m)^(#{1,6})\\s+(.+?)\\s*$");

    private MarkdownParser() {
    }

    public record HeadingBreak(int offset, String path) {
    }

    public record ParseResult(String body, Map<String, Object> frontmatter, List<HeadingBreak> headings) {
    }

    public static ParseResult parse(String raw) {
        String body = raw;
        Map<String, Object> frontmatter = Map.of();

        Matcher fmMatcher = FRONTMATTER.matcher(raw);
        if (fmMatcher.find()) {
            frontmatter = parseYaml(fmMatcher.group(1));
            body = raw.substring(fmMatcher.end());
        }

        return new ParseResult(body, frontmatter, extractHeadings(body));
    }

    /** Returns the path of the last heading at or before {@code offset}, or null if none. */
    public static String headingPathAt(List<HeadingBreak> headings, int offset) {
        String result = null;
        for (HeadingBreak h : headings) {
            if (h.offset() > offset) {
                break;
            }
            result = h.path();
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseYaml(String yaml) {
        try {
            Object loaded = new Yaml().load(yaml);
            if (loaded instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        } catch (Exception e) {
            // Malformed frontmatter shouldn't fail ingestion of the whole file —
            // just proceed without the extra metadata.
        }
        return Map.of();
    }

    private static List<HeadingBreak> extractHeadings(String body) {
        List<HeadingBreak> headings = new ArrayList<>();
        String[] pathByLevel = new String[7];
        Matcher m = HEADING.matcher(body);
        while (m.find()) {
            int level = m.group(1).length();
            pathByLevel[level] = m.group(2).trim();
            for (int i = level + 1; i < pathByLevel.length; i++) {
                pathByLevel[i] = null;
            }

            StringBuilder path = new StringBuilder();
            for (int i = 1; i <= level; i++) {
                if (pathByLevel[i] != null) {
                    if (path.length() > 0) {
                        path.append(" > ");
                    }
                    path.append(pathByLevel[i]);
                }
            }
            headings.add(new HeadingBreak(m.start(), path.toString()));
        }
        return headings;
    }
}
