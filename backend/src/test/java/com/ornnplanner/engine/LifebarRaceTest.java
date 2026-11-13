package com.ornnplanner.engine;

import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.EffectModel;
import com.ornnplanner.arena.EffectTestService;
import com.ornnplanner.arena.calc.BuildScore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rodada 15, the lifebar race: the game is about the enemy lifebar going to zero before yours. Damage dealt (reflected
 * included), healing cut by Grievous Wounds and kills take enemy lifebar; the race compares it with the own lifebar lost.
 */
class LifebarRaceTest {

    private static ArenaModel.FighterStats fighter(double max, double taken, double healed, Double death) {
        ArenaModel.FighterStats f = new ArenaModel.FighterStats();
        f.maxHealth = max;
        f.taken = taken;
        f.healed = healed;
        f.deathTime = death;
        return f;
    }

    private static EffectModel.Measures race(ArenaModel.FighterStats me, ArenaModel.FighterStats... enemies) {
        ArenaModel.Result r = new ArenaModel.Result();
        r.duration = 30;
        r.fighters = new ArrayList<>();
        r.fighters.add(me);
        r.fighters.addAll(List.of(enemies));
        return EffectTestService.measure(r, enemies.length, 0, 30);
    }

    @Test
    void evenPaceIsFifty() {
        EffectModel.Measures m = race(fighter(2000, 1000, 0, null), fighter(4000, 2000, 0, null));
        assertEquals(0.5, m.enemyLost, 1e-9);
        assertEquals(0.5, m.teamLost, 1e-9);
        assertEquals(50, m.race, 1e-9);
    }

    @Test
    void healingCutTakesEnemyLifebar() {
        // the same damage; with Grievous Wounds the enemy healed 600 instead of 1200
        double without = race(fighter(2000, 1000, 0, null), fighter(4000, 2400, 1200, null)).race;
        double with = race(fighter(2000, 1000, 0, null), fighter(4000, 2400, 600, null)).race;
        assertTrue(with > without, with + " > " + without);
    }

    @Test
    void aKillCountsWhole() {
        EffectModel.Measures m = race(fighter(2000, 500, 0, null), fighter(4000, 4000, 0, 12.0));
        assertEquals(1, m.enemyLost, 1e-9);
        assertEquals(100 * 1 / 1.25, m.race, 1e-9);
    }

    @Test
    void dyingLosesTheWholeLifebar() {
        EffectModel.Measures m = race(fighter(2000, 2600, 0, 20.0), fighter(4000, 2000, 0, null));
        assertEquals(1, m.teamLost, 1e-9);
        assertEquals(100 * 0.5 / 1.5, m.race, 1e-9);
    }

    @Test
    void immortalAttackersProjectPastOneLifebar() {
        // the durability scenarios: the attacker never dies, the build took two of its lifebars before dying
        EffectModel.Measures m = race(fighter(2000, 2500, 0, 60.0), fighter(4000, 8000, 0, null));
        assertEquals(2, m.enemyLost, 1e-9);
        assertTrue(m.race > 50, "it would have won: " + m.race);
    }

    @Test
    void pointsAreTheGapClosedFromTheReference() {
        assertEquals(0, BuildScore.racePoints(30, 30), 1e-9);
        assertEquals(50, BuildScore.racePoints(60, 20), 1e-9);
        assertEquals(100, BuildScore.racePoints(100, 20), 1e-9);
        assertTrue(BuildScore.racePoints(10, 20) < 0);
    }
}
