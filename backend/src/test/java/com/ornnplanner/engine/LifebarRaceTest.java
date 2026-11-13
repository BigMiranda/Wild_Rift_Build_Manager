package com.ornnplanner.engine;

import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.EffectModel;
import com.ornnplanner.arena.EffectTestService;
import com.ornnplanner.arena.calc.BuildScore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rodada 15, the lifebar race: the time each side takes to go to zero (its death, or its health at the end over the pace
 * it was losing it in the last seconds). Sustain that holds its health at the end never goes to zero, even near zero.
 */
class LifebarRaceTest {

    /** A fighter whose health goes linearly from {@code from} to {@code to} over {@code seconds}, then stays. */
    private static ArenaModel.FighterStats fighter(double max, double[] trail, Double death) {
        ArenaModel.FighterStats f = new ArenaModel.FighterStats();
        f.maxHealth = max;
        f.healthTrail = new ArrayList<>();
        for (double h : trail) {
            f.healthTrail.add(h);
        }
        f.health = trail.length == 0 ? max : trail[trail.length - 1];
        f.deathTime = death;
        return f;
    }

    private static double[] line(double from, double to, int seconds) {
        double[] t = new double[seconds];
        for (int k = 0; k < seconds; k++) {
            t[k] = from + (to - from) * (k + 1) / seconds;
        }
        return t;
    }

    private static EffectModel.Measures race(double duration, ArenaModel.FighterStats me, ArenaModel.FighterStats... enemies) {
        ArenaModel.Result r = new ArenaModel.Result();
        r.duration = duration;
        r.fighters = new ArrayList<>();
        r.fighters.add(me);
        r.fighters.addAll(List.of(enemies));
        return EffectTestService.measure(r, enemies.length, 0, duration);
    }

    @Test
    void bothLosingAtTheSamePaceIsFifty() {
        EffectModel.Measures m = race(60, fighter(3000, line(3000, 1500, 60), null), fighter(3000, line(3000, 1500, 60), null));
        assertEquals(50, m.race, 1e-6);
        assertEquals(120, m.raceMine, 1e-6);
    }

    @Test
    void sustainNearZeroThatHoldsWins() {
        // the build drops to about 200 in 4 s and swings between 1 and 400 until the end; the enemy keeps losing
        double[] trail = new double[60];
        for (int k = 0; k < 60; k++) {
            trail[k] = k < 4 ? 3000 - 700 * (k + 1) : (k % 2 == 0 ? 1 : 400);
        }
        EffectModel.Measures m = race(60, fighter(3000, trail, null), fighter(3000, line(3000, 1500, 60), null));
        assertNull(m.raceMine, "it never goes to zero");
        assertEquals(100, m.race, 1e-9);
    }

    @Test
    void aKillEndsTheirClock() {
        EffectModel.Measures m = race(12, fighter(2000, line(2000, 1600, 12), null), fighter(4000, line(4000, 0, 12), 12.0));
        assertEquals(12, m.raceEnemy, 1e-9);
        assertTrue(m.race > 80, "won the race: " + m.race);
    }

    @Test
    void dyingFirstLoses() {
        EffectModel.Measures m = race(20, fighter(2000, line(2000, 0, 20), 20.0), fighter(4000, line(4000, 3000, 20), null));
        assertEquals(20, m.raceMine, 1e-9);
        assertTrue(m.race < 30, "lost the race: " + m.race);
    }

    @Test
    void bothHoldingIsEven() {
        EffectModel.Measures m = race(60, fighter(3000, line(3000, 2500, 60), null), fighter(3000, line(3000, 2500, 60), null));
        // both lose 500 in 60 s, but at a steady pace: neither holds, the race is even
        assertEquals(50, m.race, 1e-6);
        assertEquals(50, EffectTestService.raceOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY), 1e-9);
        assertEquals(0, EffectTestService.raceOf(30, Double.POSITIVE_INFINITY), 1e-9);
    }

    @Test
    void pointsAreTheGapClosedFromTheReference() {
        assertEquals(0, BuildScore.racePoints(30, 30), 1e-9);
        assertEquals(50, BuildScore.racePoints(60, 20), 1e-9);
        assertEquals(100, BuildScore.racePoints(100, 20), 1e-9);
        assertTrue(BuildScore.racePoints(10, 20) < 0);
    }
}
