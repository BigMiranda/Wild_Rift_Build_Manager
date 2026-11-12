package com.ornnplanner.engine;

import com.ornnplanner.arena.EffectModel;
import com.ornnplanner.arena.calc.BuildScore;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rodada 14: what a support does for the carry, against the reference (the champion with no items): the carry's time
 * alive, its damage, the control and the enemy damage cut, each -100..100, summed by the weights of nota_builds.
 */
class SupportScoreTest {

    private static final Map<String, Object> W = Map.of("carry_vivo", 0.35, "carry_dano", 0.25, "controle", 0.2, "dano_cortado", 0.2);

    private static EffectModel.Measures m(double carryAlive, double carryDealt, double control, double enemyDealt) {
        EffectModel.Measures x = new EffectModel.Measures();
        x.fightLength = 60;
        x.alliesAlive = carryAlive;
        x.allyDealt = carryDealt;
        x.controlApplied = control;
        x.enemyDealt = enemyDealt;
        return x;
    }

    @Test
    void asTheReferenceIsZero() {
        EffectModel.Measures ref = m(20, 6000, 0, 9000);
        assertEquals(0, BuildScore.supportPoints(m(20, 6000, 0, 9000), ref, 60, 25, W), 1e-9);
    }

    @Test
    void keepingTheCarryAliveAndControllingScores() {
        EffectModel.Measures ref = m(20, 6000, 0, 9000);
        // the carry lives 20 s more (half of the 40 s it was missing), deals 50% more per second, 6 s of control in 60 s
        // (1 s per 10 s = 25), the enemies deal 10% less
        double p = BuildScore.supportPoints(m(40, 9000, 6, 8100), ref, 60, 25, W);
        assertEquals(0.35 * 50 + 0.25 * 50 + 0.2 * 25 + 0.2 * 10, p, 1e-9);
    }

    @Test
    void eachPartIsCapped() {
        EffectModel.Measures ref = m(5, 100, 0, 9000);
        double p = BuildScore.supportPoints(m(60, 100000, 60, 0), ref, 60, 25, W);
        assertTrue(p <= 100 + 1e-9, "at most 100: " + p);
    }
}
