package com.ornnplanner.engine;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tenacity sources stack multiplicatively, as the game's shop shows (10% + 30% = 37%, + 20% = 50%, + 20% = 60%). */
class TenacityStackingTest {

    @Test
    void stacksLikeTheGame() {
        double t = Stats.combine(Stats.TENACITY, 10, 30);
        assertEquals(37, t, 1e-9);
        t = Stats.combine(Stats.TENACITY, t, 20);
        assertEquals(50, Math.round(t), 0);   // 49.6
        t = Stats.combine(Stats.TENACITY, t, 20);
        assertEquals(60, Math.round(t), 0);   // 59.68
        assertEquals(37, Stats.remove(Stats.TENACITY, Stats.combine(Stats.TENACITY, 37, 20), 20), 1e-9);
    }

    @Test
    void otherStatsAdd() {
        assertEquals(50, Stats.combine(Stats.ARMOR, 20, 30), 0);
        assertEquals(20, Stats.remove(Stats.ARMOR, 50, 30), 0);
        Map<String, Double> m = new LinkedHashMap<>();
        Stats.merge(m, Stats.TENACITY, 20);
        Stats.merge(m, Stats.TENACITY, 20);
        Stats.merge(m, Stats.ARMOR, 20);
        Stats.merge(m, Stats.ARMOR, 20);
        assertEquals(36, m.get(Stats.TENACITY), 1e-9);
        assertEquals(40, m.get(Stats.ARMOR), 0);
    }
}
