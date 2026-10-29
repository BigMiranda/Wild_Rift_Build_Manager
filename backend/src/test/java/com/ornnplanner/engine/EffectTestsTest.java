package com.ornnplanner.engine;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.ArenaModel.Result;
import com.ornnplanner.arena.ComboBench;
import com.ornnplanner.arena.EffectModel.Measures;
import com.ornnplanner.arena.EffectTestService;
import com.ornnplanner.arena.SeenBy;
import com.ornnplanner.arena.ai.ActionSpace;
import com.ornnplanner.arena.ai.Policy;
import com.ornnplanner.engine.Model.EngineInput;
import com.ornnplanner.engine.Model.ItemDef;
import com.ornnplanner.engine.Model.ReferenceData;
import com.ornnplanner.engine.Model.StatGrowth;
import com.ornnplanner.engine.Model.StatLine;
import com.ornnplanner.engine.Model.UnitProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Effectiveness by test scenarios: the test ragdolls, their pulses and what a fight measures, by the arena's rules. */
class EffectTestsTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** A defender that only stands and takes it (no damage, no healing): its time to die is the attackers' alone. */
    private static Arena.Input defender(double hp, double armor, double mr) {
        Arena.Input d = ArenaScenarios.champion("Darius", 0, 1000, 0, hp, 100, armor);
        d.f.total.put(Stats.MAGIC_RESIST, mr);
        d.f.base.put(Stats.MAGIC_RESIST, mr);
        d.autoAbilities = false;
        d.policy = (o, m) -> Policy.Decision.of(ActionSpace.IDLE);
        return d;
    }

    /** A test attacker: walks to the defender and attacks; never dies. */
    private static Arena.Input attacker(ArenaModel.Dummy d) {
        d.behavior = "ataca";
        d.immortal = true;
        d.spells = new ArrayList<>();
        Arena.Input a = ComboBench.dummyInput(d);
        a.f.team = 1;
        a.x = 1300;
        a.y = 0;
        a.policy = (o, m) -> Policy.Decision.of(ActionSpace.IDLE);
        return a;
    }

    private static ArenaModel.Dummy crit() {
        ArenaModel.Dummy d = new ArenaModel.Dummy();
        d.name = "Crítico";
        d.attackDamage = 300;
        d.attackSpeed = 2.5;
        d.attackRange = 500.0;
        d.stats.put("% Critical Rate", 100.0);
        return d;
    }

    /** 1000 ability power, the Void Staff's 40% magic penetration, abilities only (no attack damage). */
    private static ArenaModel.Dummy mage() {
        ArenaModel.Dummy d = new ArenaModel.Dummy();
        d.name = "1000 de PdH";
        d.attackDamage = 0;
        d.attackRange = 500.0;
        d.stats.put(Stats.ABILITY_POWER, 1000.0);
        d.stats.put("% Magic Penetration", 40.0);
        ArenaModel.Pulse combo = new ArenaModel.Pulse();
        combo.base = 350;
        combo.ap = 0.9;
        combo.every = 5;
        combo.range = 650;
        d.pulses.add(combo);
        return d;
    }

    private static Measures fight(Arena.Input defender, Arena.Input attacker, double duration) {
        Result r = new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).withoutFrames().stopWhenDead(0).run(List.of(defender, attacker),
                List.of(), duration);
        return EffectTestService.measure(r, 1, 0, duration);
    }

    /**
     * 🎯 An attacker with attacks only and no penetration: the effective health the fight measures (the raw damage
     * taken until death) is the "visão sob efeitos" one, to within the attack that killed.
     */
    @Test
    void attacksOnlyGiveTheSeenByEffectiveHealth() {
        ArenaModel.Dummy d = new ArenaModel.Dummy();
        d.attackDamage = 180;
        d.attackSpeed = 1.0;
        Arena.Input a = attacker(d);
        Arena.Input def = defender(3000, 120, 84);
        Measures m = fight(def, a, 180);
        SeenBy s = SeenBy.under(ArenaScenarios.CATALOG, ArenaScenarios.MAP, defender(3000, 120, 84), attacker(d), List.of());
        System.out.printf("measured effective health %.1f, seen by %.1f (attacks of 180)%n", m.effectiveHealth, s.effectiveHpAttacks);
        assertTrue(m.died);
        assertTrue(m.effectiveHealth >= s.effectiveHpAttacks - 1e-6 && m.effectiveHealth - 180 < s.effectiveHpAttacks,
                "the killing attack is the one that passes the effective health");
        assertEquals(100 * 120 / 220.0, m.mitigatedPct, 1e-6);
    }

    /** More armor: a higher index against Critical (physical attacks), the same against 1000 AP + Void Staff (magic only). */
    @Test
    void armorRaisesTheIndexAgainstCritOnly() {
        double ref = 2500;
        for (String vs : List.of("crit", "mage")) {
            double[] ttd = new double[3];
            double[] armor = {50, 150, 300};
            for (int k = 0; k < 3; k++) {
                Arena.Input a = attacker("crit".equals(vs) ? crit() : mage());
                ttd[k] = fight(defender(ref, armor[k], 60), a, 180).timeToDeath;
            }
            double idx150 = 100 * ttd[1] / ttd[0];
            double idx300 = 100 * ttd[2] / ttd[0];
            System.out.printf("%s: time to die %.2f / %.2f / %.2f s; index %.0f -> %.0f%n", vs, ttd[0], ttd[1], ttd[2], idx150, idx300);
            if ("crit".equals(vs)) {
                assertTrue(idx300 > idx150 && idx150 > 100, "armor helps against physical attacks");
            } else {
                assertEquals(ttd[0], ttd[1], 1e-9, "armor does nothing against magic damage");
                assertEquals(ttd[0], ttd[2], 1e-9);
            }
        }
    }

    /** An item with only flat stats: its "only the flat stats" variant is the same item, so its passive is worth 0. */
    @Test
    void anItemOfStatsOnlyHasAPassiveWorthZero() {
        ItemDef chain = new ItemDef();
        chain.id = 7;
        chain.name = "Cota de Malha";
        chain.cost = 800;
        chain.stats.add(StatLine.flat(Stats.ARMOR, 40));
        ItemDef flat = EffectTestService.flatOnly(chain);
        assertEquals(chain.stats.size(), flat.stats.size());
        assertTrue(flat.id < 0 && !flat.name.equals(chain.name), "a synthetic item with its own id and name");
        // a passive line goes away; the flat stats stay
        ItemDef thorn = new ItemDef();
        thorn.id = 8;
        thorn.name = "Colete Espinhoso";
        thorn.cost = 1000;
        thorn.stats.add(StatLine.flat(Stats.ARMOR, 30));
        thorn.stats.add(StatLine.percent(Stats.ARMOR, "Espinhos", 0.1, Stats.ARMOR, Model.RefScope.BONUS));
        assertEquals(1, EffectTestService.flatOnly(thorn).stats.size());
        // the planner gives the same stats with the item or with its flat-only copy: the same fighter, the same fight
        ReferenceData ref = new ReferenceData();
        ref.items.put(chain.id, chain);
        ref.items.put(flat.id, flat);
        double[] xp = {0, 280, 660, 1140, 1720, 2400, 3180, 4060, 5040, 6120, 7300, 8580, 9960, 11440, 13020};
        for (int i = 0; i < xp.length; i++) {
            ref.xpTable.put(i + 1, xp[i]);
        }
        Map<String, Double> with = snapshot(ref, chain.id);
        Map<String, Double> asFlat = snapshot(ref, flat.id);
        assertEquals(with, asFlat);
    }

    private static Map<String, Double> snapshot(ReferenceData ref, long item) {
        UnitProfile u = new UnitProfile();
        u.code = "X";
        u.name = "X";
        u.stats.put(Stats.MAX_HEALTH, new StatGrowth(2000, 0));
        u.stats.put(Stats.ARMOR, new StatGrowth(50, 0));
        EngineInput in = new EngineInput();
        in.unit = u;
        in.goldPerMin = 1e6;
        in.xpPerMin = 0;
        in.itemIds = List.of(item);
        return new TimelineEngine(ref).run(in).steps.get(0).stats.total;
    }

    /** Pulses: true damage ignores armor; a support's shield lands on the ally with the least health; attackers hold at 1. */
    @Test
    void pulsesShieldsAndAttackersThatNeverDie() {
        ArenaModel.Dummy t = new ArenaModel.Dummy();
        t.attackDamage = 0;
        ArenaModel.Pulse p = new ArenaModel.Pulse();
        p.type = "verdadeiro";
        p.base = 200;
        p.every = 2;
        p.range = 600;
        t.pulses.add(p);
        Measures lo = fight(defender(2000, 50, 50), attacker(t), 60);
        Measures hi = fight(defender(2000, 500, 500), attacker(t.copy("ataca")), 60);
        assertEquals(lo.timeToDeath, hi.timeToDeath, 1e-9, "true damage: resistances change nothing");
        assertEquals(0, lo.mitigatedPct, 1e-9);
        // the defender hits back: the attacker never dies, the fight lasts until the defender does
        Arena.Input hitter = ArenaScenarios.champion("Darius", 0, 1000, 0, 2000, 400, 50);
        hitter.autoAbilities = false;
        ArenaModel.Dummy weak = new ArenaModel.Dummy();
        weak.health = 500;
        weak.attackDamage = 60;
        Result r = new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).withoutFrames().run(List.of(hitter, attacker(weak)),
                List.of(), 30);
        assertEquals(null, r.fighters.get(1).deathTime);
        assertEquals(1, r.fighters.get(1).health, 1e-9);
        assertTrue(r.fighters.get(0).dealt > 500);
        // a shield pulse of an ally lands on the hurt defender
        ArenaModel.Dummy sup = new ArenaModel.Dummy();
        sup.behavior = "ataca";
        sup.attackDamage = 0;
        sup.attackRange = 500.0;   // a support stays back (melee, it would walk into the attacker and be the nearest)
        ArenaModel.Pulse sh = new ArenaModel.Pulse();
        sh.kind = "escudo";
        sh.base = 300;
        sh.every = 3;
        sh.range = 800;
        sup.pulses.add(sh);
        sup.spells = new ArrayList<>();
        Arena.Input ally = ComboBench.dummyInput(sup);
        ally.f.team = 0;
        ally.x = 800;
        ally.policy = (o, m) -> Policy.Decision.of(ActionSpace.IDLE);
        ArenaModel.Dummy hit = new ArenaModel.Dummy();
        hit.attackDamage = 150;
        Result withSupport = new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).withoutFrames().stopWhenDead(0)
                .run(List.of(defender(2000, 50, 50), attacker(hit), ally), List.of(), 120);
        Measures alone = fight(defender(2000, 50, 50), attacker(hit.copy("ataca")), 120);
        Measures helped = EffectTestService.measure(withSupport, 1, 1, 120);
        assertTrue(helped.shieldAbsorbed > 0, "the support's shields absorb hits");
        assertTrue(helped.timeToDeath > alone.timeToDeath);
        assertEquals(100, helped.absorbedShare, 1e-9, "the attacker hits the nearest, the build; the fight ends with it");
        assertEquals(helped.timeToDeath, helped.alliesAlive, 1e-9, "the ally outlived the build");
    }

    /** The same fights give the same measures, one after another and on many threads at once. */
    @Test
    void deterministicAlsoInParallel() throws Exception {
        List<Callable<String>> runs = new ArrayList<>();
        for (int k = 0; k < 12; k++) {
            int n = k;
            runs.add(() -> JSON.writeValueAsString(fight(defender(3000, 100 + 20 * (n % 3), 60),
                    attacker(n % 2 == 0 ? crit() : mage()), 60)));
        }
        List<String> serial = new ArrayList<>();
        for (Callable<String> c : runs) {
            serial.add(c.call());
        }
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<String>> par = pool.invokeAll(runs);
            for (int k = 0; k < runs.size(); k++) {
                assertEquals(serial.get(k), par.get(k).get());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
