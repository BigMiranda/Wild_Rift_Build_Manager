package com.ornnplanner.arena;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitReviewMarksTest {

    @Test
    void keepsFlagsAndNotesAndDropsEmptyEntries(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sub/revisao-fichas.json");
        KitReviewMarks marks = new KitReviewMarks(file.toString());
        assertTrue(marks.all().isEmpty());

        marks.put("Ornn", Map.of("revisar", true, "nota", " sem reconjuração ",
                "habilidades", Map.of("R", Map.of("revisar", true, "nota", "a cabeçada não sai"),
                        "1", Map.of("revisar", false, "nota", ""))));
        @SuppressWarnings("unchecked")
        Map<String, Object> ornn = (Map<String, Object>) new KitReviewMarks(file.toString()).all().get("Ornn");
        assertEquals(true, ornn.get("revisar"));
        assertEquals("sem reconjuração", ornn.get("nota"));
        assertTrue(ornn.containsKey("atualizado"));
        @SuppressWarnings("unchecked")
        Map<String, Object> abilities = (Map<String, Object>) ornn.get("habilidades");
        assertEquals(Map.of("revisar", true, "nota", "a cabeçada não sai"), abilities.get("R"));
        assertFalse(abilities.containsKey("1"));
        assertTrue(Files.readString(file).contains("\"campeoes\""));

        // only an ability flagged: the champion stays, not flagged itself
        marks.put("Jayce", Map.of("habilidades", Map.of("R", Map.of("revisar", true))));
        @SuppressWarnings("unchecked")
        Map<String, Object> jayce = (Map<String, Object>) marks.all().get("Jayce");
        assertEquals(false, jayce.get("revisar"));

        // nothing left: removed
        marks.put("Ornn", Map.of("revisar", false, "nota", "", "habilidades", Map.of("R", Map.of("revisar", false))));
        assertNull(marks.all().get("Ornn"));
        assertEquals(1, marks.all().size());
    }
}
