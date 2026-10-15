package com.ornnplanner.engine;

import com.ornnplanner.arena.fight.AbilityParser;
import com.ornnplanner.seed.ChampionCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ability damage read from the game's texts (the arena's fallback for champions without a kit sheet). */
class AbilityParserTest {

    private static final double EPS = 1e-6;
    private static ChampionCatalog champions;

    @BeforeAll
    static void load() {
        champions = new ChampionCatalog(new ClassPathResource("seed/campeoes_7_3.yml"),
                new ClassPathResource("seed/precos_status.yml"));
    }

    @Test
    void abilityDamageFromTheTexts() {
        List<AbilityParser.Ability> garen = AbilityParser.abilities(champions.find("GAREN").orElseThrow());
        Map<String, Double> total = Map.of(Stats.ATTACK_DAMAGE, 100.0);
        Map<String, Double> base = Map.of(Stats.ATTACK_DAMAGE, 64.0);
        // Q: 40/80/120/160 + 40% AD, physical
        assertEquals("fisico", garen.get(0).damage.type);
        assertEquals(80 + 40, garen.get(0).damage.at(2, total, base), EPS);
        // W: a shield, no damage
        assertNull(garen.get(1).damage);
        // E: (13/17/21/25 + 25/30/35/40% AD) x 8 hits
        assertEquals(8, garen.get(2).damage.hits);
        assertEquals((21 + 0.35 * 100) * 8, garen.get(2).damage.at(3, total, base), EPS);
        assertEquals(8.5, garen.get(0).cooldown[1], EPS);
    }

    @Test
    void mostDamageTextsAreRead() {
        int withDamage = 0;
        int read = 0;
        for (Map<String, Object> c : champions.summaries()) {
            Map<String, Object> champ = champions.find(String.valueOf(c.get("code"))).orElseThrow();
            List<AbilityParser.Ability> abilities = AbilityParser.abilities(champ);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> raw = (List<Map<String, Object>>) champ.getOrDefault("habilidades", List.of());
            for (int i = 0; i < abilities.size(); i++) {
                if (String.valueOf(raw.get(i).get("texto")).matches("(?s).*Dano (Físico|Mágico|Verdadeiro).*")) {
                    withDamage++;
                    if (abilities.get(i).damage != null) {
                        read++;
                    }
                }
            }
        }
        System.out.println("Habilidades com dano no texto: " + withDamage + ", lidas: " + read);
        assertTrue(read >= withDamage * 0.75, read + " de " + withDamage);
    }

    @Test
    void ornnBreathReadsTheTargetsHealthNotTheMinionsDamage() {
        List<AbilityParser.Ability> ornn = AbilityParser.abilities(champions.find("ORNN").orElseThrow());
        AbilityParser.Damage w = ornn.get(1).damage;
        assertEquals("magico", w.type);
        assertEquals(0.12 * 3000, w.at(3, s -> 0.0, s -> 0.0, 3000), EPS);   // 10/11/12/13% of the target's health
        assertTrue(ornn.get(1).brittle);
        assertTrue(ornn.get(2).immobilizes);   // Investida Calcinante: knock-up
        assertTrue(ornn.get(3).immobilizes && ornn.get(3).brittle);
    }
}
