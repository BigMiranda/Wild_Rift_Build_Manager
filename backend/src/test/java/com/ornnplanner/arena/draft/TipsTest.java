package com.ornnplanner.arena.draft;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rodada 20, Parte 6: what the tips read from a sheet (control, rank values, estimates) and the words of a race. */
class TipsTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ability(String champion, String slot) throws Exception {
        try (InputStream in = new ClassPathResource("seed/kits_7_3f.yml").getInputStream()) {
            Map<String, Object> kits = (Map<String, Object>) ((Map<String, Object>) new Yaml().load(in)).get("campeoes");
            Map<String, Object> sheet = (Map<String, Object>) kits.get(champion);
            return (Map<String, Object>) ((Map<String, Object>) sheet.get("habilidades")).get(slot);
        }
    }

    @Test
    void controlComesFromTheAbilityAndItsZones() throws Exception {
        // Darius's E pulls (in a zone); his Q only slows and heals: no window from it
        assertEquals(List.of("puxa"), TipsService.control(ability("Darius", "3")));
        assertTrue(TipsService.control(ability("Darius", "1")).isEmpty());
    }

    @Test
    void cooldownAtTheRank() throws Exception {
        Map<String, Object> e = ability("Darius", "3");
        assertEquals(18.0, TipsService.rankValue(e.get("recarga"), 1));
        assertEquals(12.0, TipsService.rankValue(e.get("recarga"), 4));
        assertEquals(12.0, TipsService.rankValue(e.get("recarga"), 9), "past the last rank: the last value");
        assertEquals(5.5, TipsService.rankValue(List.of(5.5), 3));
    }

    @Test
    void estimatesAreMarked() {
        assertTrue(TipsService.estimated(Map.of("fontes", "WR; raios: PC")));
        assertTrue(TipsService.estimated(Map.of("fontes", "WR", "lacuna", "tempo da puxada")));
        assertFalse(TipsService.estimated(Map.of("fontes", "WR")));
    }

    @Test
    void theRaceInWords() {
        assertEquals("voce", TipsService.who(62));
        assertEquals("ele", TipsService.who(30));
        assertEquals("empate", TipsService.who(50));
    }
}
