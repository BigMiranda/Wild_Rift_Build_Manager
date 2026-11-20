package com.ornnplanner.arena.draft;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rodada 20.b, Parte 4: the loading screen's spells and keystone without the lane fixed (the seed's rules, EST). */
class LoadoutTest {

    private static Map<String, Object> cfg() {
        return new DraftCatalog(new ClassPathResource("seed/draft_7_3f.yml")).section("carregamento");
    }

    private static Map<String, Double> lanes(Object... kv) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], ((Number) kv[i + 1]).doubleValue());
        }
        return m;
    }

    @Test
    void eachLikelyLaneGetsItsBestAndTheRobustOneFitsBoth() {
        // a Barão / Selva flex: the jungle needs Smite, the top cannot take it
        LoadoutService.Result r = LoadoutService.recommend(cfg(), lanes("Barão", 0.6, "Selva", 0.4), List.of("Flash", "Teleporte"),
                "Conquistador", List.of("Lutador"));
        assertEquals(2, r.byLane.size());
        assertEquals("Barão", r.byLane.get(0).lane);
        assertEquals(List.of("Flash", "Teleporte"), r.byLane.get(0).spells, "its own pair fits the top");
        assertTrue(r.byLane.get(1).spells.contains("Golpear"), "the jungle: Smite");
        assertFalse(r.byLane.get(0).spells.contains("Golpear"));
        assertEquals("Conquistador", r.byLane.get(1).keystone, "a fighter's own keystone suits the jungle");
        assertEquals(List.of("Flash", "Teleporte"), r.weighted.spells, "weighted: the likelier lane wins");
        assertTrue(r.robust.score <= r.weighted.score + 1e-9);
    }

    @Test
    void aSupportLaneSwapsAKeystoneThatDoesNotSuitIt() {
        LoadoutService.Result r = LoadoutService.recommend(cfg(), lanes("Suporte", 1.0), List.of("Flash", "Incendiar"), "Ritmo Fatal",
                List.of("Mago"));
        assertEquals("Aery", r.byLane.get(0).keystone);
        assertFalse(r.byLane.get(0).ownKeystone);
        assertEquals(List.of("Flash", "Incendiar"), r.byLane.get(0).spells, "Ignite is a support pair and its own");
        // a fixed lane: one lane, weighted = robust = it
        assertEquals(r.byLane.get(0).spells, r.robust.spells);
    }
}
