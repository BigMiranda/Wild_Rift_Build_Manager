package com.ornnplanner.engine;

import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.EffectModel;
import com.ornnplanner.arena.EffectTestService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guardian Angel's stasis is time out of the fight (Rodada 14): the effectiveness readings of how long the build lasted
 * leave it out. Before, its 4s counted as time alive taking no damage, and the item led the Discovery's screening.
 */
class GuardianAngelStasisTest {

    private static ArenaModel.Result fight(Double death, Double out, double duration) {
        ArenaModel.Result r = new ArenaModel.Result();
        r.duration = duration;
        ArenaModel.FighterStats me = new ArenaModel.FighterStats();
        me.maxHealth = 1000;
        me.taken = 1500;
        me.healed = 500;
        me.deathTime = death;
        me.outOfFight = out;
        ArenaModel.FighterStats enemy = new ArenaModel.FighterStats();
        r.fighters = new java.util.ArrayList<>(java.util.List.of(me, enemy));
        return r;
    }

    @Test
    void stasisIsNotTimeAlive() {
        EffectModel.Measures m = EffectTestService.measure(fight(18.0, 4.0, 18), 1, 0, 30);
        assertEquals(14, m.timeToDeath, 1e-9);
        assertEquals(14, m.durability, 1e-9);
        assertEquals(100 / 14.0, m.healthRate, 1e-9);
    }

    @Test
    void withoutReviveNothingChanges() {
        EffectModel.Measures m = EffectTestService.measure(fight(18.0, null, 18), 1, 0, 30);
        assertEquals(18, m.timeToDeath, 1e-9);
    }

    @Test
    void aliveAtTheEndRatesOverTheTimeInTheFight() {
        EffectModel.Measures m = EffectTestService.measure(fight(null, 4.0, 30), 1, 0, 30);
        assertEquals(1000 / 26.0, m.netRate, 1e-9);
    }
}
