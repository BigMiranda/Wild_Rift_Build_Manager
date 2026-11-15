package com.ornnplanner.arena;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Rodada 18, part 3 prep: the user's "revisar" marks on the Arena sheets (Admin › Fichas), a champion or one of its
 * abilities flagged with a note, for the next sheet review to start from. Kept in a JSON file next to the database
 * (not in it), so marks made on a test copy of the app land in the same file and a review session reads them as text:
 * {"campeoes": {"Ornn": {"revisar": true, "nota": "...", "atualizado": "...", "habilidades": {"R": {"revisar": true, "nota": "..."}}}}}.
 */
@Component
public class KitReviewMarks {

    private final Path file;
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public KitReviewMarks(@Value("${planner.kit-review-path:data/revisao-fichas.json}") String path) {
        this.file = Paths.get(path);
    }

    /** Every champion's marks, by champion name. */
    public synchronized Map<String, Object> all() {
        if (!Files.exists(file)) {
            return new TreeMap<>();
        }
        try {
            Map<String, Object> root = json.readValue(file.toFile(), new TypeReference<Map<String, Object>>() { });
            Object champions = root.get("campeoes");
            @SuppressWarnings("unchecked")
            Map<String, Object> out = champions instanceof Map ? new TreeMap<>((Map<String, Object>) champions) : new TreeMap<>();
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Replaces one champion's marks; an entry with nothing flagged and no note anywhere is removed. Returns them all.
     */
    public synchronized Map<String, Object> put(String champion, Map<String, Object> mark) {
        Map<String, Object> champions = all();
        Map<String, Object> clean = clean(mark);
        if (clean == null) {
            champions.remove(champion);
        } else {
            clean.put("atualizado", Instant.now().toString());
            champions.put(champion, clean);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("sobre", "Marcas 'revisar' da tela Admin › Fichas: campeões e habilidades (P, AA, 1, 2, 3, R) a revisar, com observações.");
        root.put("campeoes", champions);
        try {
            Path dir = file.toAbsolutePath().getParent();
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "revisao-fichas", ".tmp");
            json.writeValue(tmp.toFile(), root);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return champions;
    }

    /** Keeps only {revisar, nota} of the champion and of each ability that says something; null when nothing does. */
    private static Map<String, Object> clean(Map<String, Object> mark) {
        if (mark == null) {
            return null;
        }
        Map<String, Object> out = flag(mark);
        Map<String, Object> abilities = new LinkedHashMap<>();
        if (mark.get("habilidades") instanceof Map) {
            ((Map<?, ?>) mark.get("habilidades")).forEach((k, v) -> {
                if (v instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> f = flag((Map<String, Object>) v);
                    if (f != null) {
                        abilities.put(String.valueOf(k), f);
                    }
                }
            });
        }
        if (out == null && abilities.isEmpty()) {
            return null;
        }
        if (out == null) {
            out = new LinkedHashMap<>();
            out.put("revisar", false);
        }
        if (!abilities.isEmpty()) {
            out.put("habilidades", abilities);
        }
        return out;
    }

    private static Map<String, Object> flag(Map<String, Object> m) {
        boolean review = Boolean.TRUE.equals(m.get("revisar"));
        String note = m.get("nota") == null ? "" : String.valueOf(m.get("nota")).trim();
        if (!review && note.isEmpty()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("revisar", review);
        if (!note.isEmpty()) {
            out.put("nota", note);
        }
        return out;
    }
}
