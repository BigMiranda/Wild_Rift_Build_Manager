package com.ornnplanner.arena;

import com.ornnplanner.arena.fight.FighterBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The attack speed formula of patch 7.3 (total = base + multiplier x bonus), against the game's stats panel: Ornn with
 * +100% (five Recurve Bows) shows 1,36 at level 2 and 1,46 at level 15 (docs/rodada-19.md).
 */
class AttackSpeedFormulaTest {

    private static Map<String, Object> champions;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() throws Exception {
        try (InputStream in = new ClassPathResource("seed/campeoes_7_3f.yml").getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            champions = root.containsKey("campeoes") ? (Map<String, Object>) root.get("campeoes") : root;
        }
    }

    @SuppressWarnings("unchecked")
    private static Arena.Input at(String name, int level) {
        Map<String, Object> c = (Map<String, Object>) champions.get(name);
        Map<String, Object> st = (Map<String, Object>) c.get("status");
        double a1 = ((Number) ((Map<String, Object>) st.get("nv1")).get("VdA")).doubleValue();
        double a15 = ((Number) ((Map<String, Object>) st.get("nv15")).get("VdA")).doubleValue();
        Arena.Input in = new Arena.Input();
        in.baseAttackSpeed = a1 + (a15 - a1) * FighterBuilder.attackSpeedGrowth(level) / 14.0;
        in.attackSpeedRatio = FighterBuilder.attackSpeedRatio(c);
        return in;
    }

    @Test
    void ornnWithFiveRecurveBowsMatchesThePanel() {
        assertEquals(1.36, at("Ornn", 2).attackSpeed(100), 0.005);
        assertEquals(1.46, at("Ornn", 15).attackSpeed(100), 0.005);
    }

    @Test
    void levelCurveIsThePatchNotes() {
        assertEquals(0, FighterBuilder.attackSpeedGrowth(1), 1e-12);
        assertEquals(0.74, FighterBuilder.attackSpeedGrowth(2), 1e-12);      // 1 -> 2 gives 74% of "per level"
        assertEquals(14, FighterBuilder.attackSpeedGrowth(15), 1e-12);       // 1 -> 15 gives 14 x "per level"
    }

    @Test
    void bonusDoesNotGrowWithTheLevelAndRagdollsKeepTheOldRule() {
        Arena.Input low = at("Ornn", 2);
        Arena.Input high = at("Ornn", 15);
        assertEquals(low.attackSpeed(40) - low.baseAttackSpeed, high.attackSpeed(40) - high.baseAttackSpeed, 1e-12);
        Arena.Input dummy = new Arena.Input();
        dummy.baseAttackSpeed = 0.8;
        assertEquals(0.8 * 1.5, dummy.attackSpeed(50), 1e-12);
    }

    @Test
    void everyChampionHasAMultiplierNearItsBase() {
        int with = 0;
        for (Map.Entry<String, Object> e : champions.entrySet()) {
            if (!(e.getValue() instanceof Map) || !((Map<?, ?>) e.getValue()).containsKey("status")) {
                continue;
            }
            @SuppressWarnings("unchecked")
            double ratio = FighterBuilder.attackSpeedRatio((Map<String, Object>) e.getValue());
            assertTrue(ratio >= 0.4 && ratio <= 0.75, e.getKey() + " " + ratio);
            if (((Map<?, ?>) e.getValue()).containsKey("velocidade_ataque")) {
                with++;
            }
        }
        assertEquals(141, with);
    }
}
