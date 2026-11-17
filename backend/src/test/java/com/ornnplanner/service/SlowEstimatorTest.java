package com.ornnplanner.service;

import com.ornnplanner.engine.Model.ItemContribution;
import com.ornnplanner.engine.Model.MinutePoint;
import com.ornnplanner.engine.Model.SlowEstimate;
import com.ornnplanner.engine.Model.SlowSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlowEstimatorTest {

    private final SlowEstimator slows = new SlowEstimator(new ClassPathResource("seed/kits_7_3f.yml"),
            new ClassPathResource("seed/lentidao_7_3f.yml"));

    @SuppressWarnings("unchecked")
    private Map<String, Object> ornn() throws Exception {
        java.lang.reflect.Method m = SlowEstimator.class.getDeclaredMethod("abilities", String.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(slows, "Ornn");
    }

    private static MinutePoint point(int level, double haste, String... items) {
        MinutePoint p = new MinutePoint();
        p.level = level;
        p.total.put("Ability Haste", haste);
        p.total.put("Max Health", 2000.0);
        p.base.put("Max Health", 1000.0);
        for (String name : items) {
            ItemContribution c = new ItemContribution();
            c.itemName = name;
            p.contributions.add(c);
        }
        return p;
    }

    private static SlowSource source(SlowEstimate e, String prefix) {
        return e.sources.stream().filter(s -> s.name.startsWith(prefix)).findFirst().orElse(null);
    }

    @Test
    void strengthDurationCooldownAndAreaMakeTheAverageSlow() throws Exception {
        Map<String, Object> kit = ornn();
        // level 1, only Q at rank 1: 55% for 2s every 9s on one target (the line is narrow) = 12.2%
        SlowEstimate e = slows.estimate(point(1, 0), kit, new int[] {1, 0, 0, 0}, Set.of(), false, true);
        SlowSource q = source(e, "Ruptura");
        assertEquals(100 * 0.55 * 2 / 9, q.value, 1e-9);
        assertEquals(1, q.targets, 1e-9);
        // haste cuts the cooldown: 100 haste halves it, so the same slow is on twice as often
        SlowEstimate fast = slows.estimate(point(1, 100), kit, new int[] {1, 0, 0, 0}, Set.of(), false, true);
        assertEquals(2 * q.value, source(fast, "Ruptura").value, 1e-9);
        // the ultimate (a wide wave) counts more than one target
        SlowEstimate ult = slows.estimate(point(6, 0), kit, new int[] {1, 0, 0, 1}, Set.of(), false, true);
        assertTrue(source(ult, "Chamado").targets > 1, "ult targets " + source(ult, "Chamado").targets);

        // Glacial Augment: (15% + 1% per 100 bonus Health) for 1.5s every 20s in a 300 circle (1.44 targets)
        SlowEstimate glacial = slows.estimate(point(1, 0), kit, new int[] {1, 0, 0, 0}, Set.of("Suserano do Gelo"), false, true);
        SlowSource g = source(glacial, "Suserano");
        assertEquals(0.25, g.slow, 1e-9);
        assertEquals(100 * 0.25 * 1.5 / 20 * (300.0 * 300) / (250.0 * 250), g.value, 1e-9);
        assertNull(source(slows.estimate(point(1, 0), kit, new int[] {1, 0, 0, 0}, Set.of("Suserano do Gelo"), false, false),
                "Suserano"), "no immobilize, no Glacial Augment");

        // Rylai: 30% for 0.75s with each damaging ability, at the abilities' combined rate
        SlowEstimate rylai = slows.estimate(point(1, 0), kit, new int[] {1, 0, 0, 0}, Set.of(), false, true);
        assertNull(source(rylai, "Cetro"));
        rylai = slows.estimate(point(1, 0, "Cetro de Cristal de Rylai"), kit, new int[] {1, 0, 0, 0}, Set.of(), false, true);
        assertNotNull(source(rylai, "Cetro"));
        assertEquals(100 * 0.30 * 0.75 / 9, source(rylai, "Cetro").value, 1e-9);
        // melee-only items are dropped for ranged champions
        assertNull(source(slows.estimate(point(1, 0, "Couraça do Defunto"), kit, new int[] {1, 0, 0, 0}, Set.of(), true, true),
                "Couraça"));
        assertEquals(e.total, e.sources.stream().mapToDouble(s -> s.value).sum(), 1e-9);
    }

    @Test
    void shapesGiveTheirAreas() {
        assertEquals(-1, SlowEstimator.area(Map.of("forma", "alvo")), 1e-9);
        assertEquals(-1, SlowEstimator.area(Map.of("forma", "linha", "alcance", 1000, "largura", 100)), 1e-9);
        assertEquals(100000, SlowEstimator.area(Map.of("forma", "linha", "alcance", 1000, "largura", 100, "atravessa", true)), 1e-9);
        assertEquals(Math.PI * 300 * 300, SlowEstimator.area(Map.of("forma", "circulo", "raio", 300)), 1e-6);
        assertEquals(Math.PI * 400 * 400 / 4, SlowEstimator.area(Map.of("forma", "setor", "angulo", 90, "raio", 400)), 1e-6);
    }
}
