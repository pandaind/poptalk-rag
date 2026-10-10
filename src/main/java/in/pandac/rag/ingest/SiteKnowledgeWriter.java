package in.pandac.rag.ingest;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns a {@link SiteManifest} into Markdown files under {@code <knowledge-dir>/<persona>/}, where
 * the existing file ingestion (parsing, chunking, embedding, change detection, deletion) takes
 * over. Reusing that pipeline means a published site gets exactly the behaviour of hand-placed
 * files, with no second code path to keep correct.
 *
 * <p>Only files this class created are ever replaced or removed (it keeps a {@code .site-source}
 * list), so documents a person placed in the same folder are left alone.
 */
public class SiteKnowledgeWriter {

    static final String LIST_FILE = ".site-source";

    public record Result(int written, int unchanged, int removed, int skipped) {
    }

    public Result write(Path knowledgeDir, String persona, SiteManifest manifest) throws IOException {
        if (!persona.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("invalid persona id: " + persona);
        }
        Path personaDir = knowledgeDir.resolve(persona).normalize();
        if (!personaDir.startsWith(knowledgeDir.normalize())) {
            throw new IllegalArgumentException("persona folder escapes the knowledge directory");
        }
        Files.createDirectories(personaDir);

        Path listFile = personaDir.resolve(LIST_FILE);
        Set<String> previous = new TreeSet<>();
        if (Files.isRegularFile(listFile)) {
            for (String line : Files.readAllLines(listFile, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    previous.add(line.trim());
                }
            }
        }

        Set<String> current = new TreeSet<>();
        int written = 0;
        int unchanged = 0;
        int skipped = 0;
        for (SiteManifest.Document doc : manifest.documents()) {
            Path target = personaDir.resolve(doc.path()).normalize();
            if (!target.startsWith(personaDir)) {
                throw new IllegalArgumentException("document path escapes the persona folder: " + doc.path());
            }
            String text = render(doc);
            if (Files.exists(target)) {
                String existing = Files.readString(target, StandardCharsets.UTF_8);
                if (existing.equals(text)) {
                    unchanged++;
                    current.add(doc.path());
                    continue;
                }
                if (!previous.contains(doc.path())) {
                    skipped++; // a file someone placed there: never overwrite it
                    continue;
                }
            }
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling("." + target.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            written++;
            current.add(doc.path());
        }

        int removed = 0;
        for (String stale : previous) {
            if (current.contains(stale)) {
                continue;
            }
            Path p = personaDir.resolve(stale).normalize();
            if (p.startsWith(personaDir) && Files.isRegularFile(p)) {
                Files.delete(p);
                removed++;
                Path dir = p.getParent();
                while (dir != null && !dir.equals(personaDir) && isEmpty(dir)) {
                    Files.delete(dir);
                    dir = dir.getParent();
                }
            }
        }

        Files.writeString(listFile, String.join("\n", current) + (current.isEmpty() ? "" : "\n"),
                StandardCharsets.UTF_8);
        return new Result(written, unchanged, removed, skipped);
    }

    private static boolean isEmpty(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return s.findAny().isEmpty();
        }
    }

    /** Front matter (so citations carry the public URL) followed by the Markdown body. */
    static String render(SiteManifest.Document d) {
        Map<String, Object> front = new LinkedHashMap<>();
        put(front, "title", d.title());
        put(front, "url", d.url());
        put(front, "section", d.section());
        put(front, "date", d.date());
        put(front, "description", d.description());
        List<String> tags = d.tags();
        if (tags != null && !tags.isEmpty()) {
            front.put("tags", tags);
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setSplitLines(false);
        String yaml = new Yaml(options).dump(front);
        return "---\n" + yaml + "---\n\n" + d.content();
    }

    private static void put(Map<String, Object> m, String key, String value) {
        if (value != null && !value.isBlank()) {
            m.put(key, value);
        }
    }
}
