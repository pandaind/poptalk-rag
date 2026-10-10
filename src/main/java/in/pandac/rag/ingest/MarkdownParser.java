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
    private static final Pattern FENCE = Pattern.compile("(?m)^[ \\t]{0,3}(`{3,}|~{3,})");

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

    /**
     * Offsets {@code [start, end)} of fenced code blocks. A {@code # comment} line inside one is
     * code, not a heading, and must not become part of the heading path. An unclosed fence runs to
     * the end of the text, as in CommonMark.
     */
    private static List<int[]> fencedRanges(String body) {
        List<int[]> ranges = new ArrayList<>();
        Matcher m = FENCE.matcher(body);
        int openAt = -1;
        char openChar = 0;
        int openLen = 0;
        while (m.find()) {
            String marker = m.group(1);
            if (openAt < 0) {
                openAt = m.start();
                openChar = marker.charAt(0);
                openLen = marker.length();
            } else if (marker.charAt(0) == openChar && marker.length() >= openLen) {
                ranges.add(new int[] {openAt, m.end()});
                openAt = -1;
            }
        }
        if (openAt >= 0) {
            ranges.add(new int[] {openAt, body.length()});
        }
        return ranges;
    }

    private static boolean insideAny(List<int[]> ranges, int offset) {
        for (int[] r : ranges) {
            if (offset >= r[0] && offset < r[1]) {
                return true;
            }
        }
        return false;
    }

    private static List<HeadingBreak> extractHeadings(String body) {
        List<HeadingBreak> headings = new ArrayList<>();
        List<int[]> fences = fencedRanges(body);
        String[] pathByLevel = new String[7];
        Matcher m = HEADING.matcher(body);
        while (m.find()) {
            if (insideAny(fences, m.start())) {
                continue;
            }
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
