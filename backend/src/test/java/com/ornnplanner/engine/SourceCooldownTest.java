package com.ornnplanner.engine;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel.Combo;
import com.ornnplanner.arena.ArenaModel.ComboStep;
import com.ornnplanner.arena.ArenaModel.Condition;
import com.ornnplanner.arena.ArenaModel.Result;
import com.ornnplanner.arena.ArenaModel.TriggerReport;
import com.ornnplanner.arena.ComboBench;
import com.ornnplanner.combat.CombatCatalog;
import com.ornnplanner.combat.CombatService;
import com.ornnplanner.combat.CombatSimulator.ItemEffect;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One cooldown per source (round 4, part 1): a rune or item with several parts (modeled effect, trigger, stat lines)
 * goes off once per activation, with every part, and counts once. Glacial Augment used to count twice per
 * immobilize: its ice field (modeled) and its defenses (the ao_imobilizar trigger), each with its own cooldown.
 */
class SourceCooldownTest {

    static final CombatCatalog CATALOG = ArenaScenarios.CATALOG;
    static final ComboBench BENCH = new ComboBench(CATALOG, ArenaScenarios.MAP);

    /** The proof's Ornn with a keystone: its modeled effects and its trigger as the build would give them. */
    static Arena.Input ornnWith(String rune, Map<String, Double> delta) {
        Arena.Input in = OrnnProofTest.ornn();
        for (Map<String, Object> e : CATALOG.runeEffects(rune)) {
            in.f.effects.add(new ItemEffect(rune, e));
        }
        CombatService.Triggered t = new CombatService.Triggered();
        t.key = rune;
        t.source = rune;
        t.effect = rune;
        t.kind = "runa";
        t.spec = CATALOG.trigger(rune);
        t.mode = String.valueOf(t.spec.get("quando"));
        t.delta.putAll(delta);
        in.triggers.add(t);
        return in;
    }

    static Arena.Input glacial() {
        return ornnWith("Suserano do Gelo", Map.of("Armor", 60.0, "Magic Resist", 60.0));
    }

    static Arena.Input colossus() {
        return ornnWith("Coragem do Colosso", Map.of("Shield", 300.0));
    }

    static double activations(Result r, String rune) {
        return r.fighters.get(0).events.getOrDefault("runa_ativou:" + rune, 0.0);
    }

    static double immobilizes(Result r) {
        return r.fighters.get(0).events.entrySet().stream().filter(e -> e.getKey().startsWith("cc_aplicado:"))
                .mapToDouble(Map.Entry::getValue).sum();
    }

    private static void oneActivationInTheCombo(Arena.Input ornn, String rune) {
        Result r = BENCH.run(ornn, OrnnProofTest.dummy(), OrnnProofTest.manual(), "parado", new ComboBench.Config(), false);
        String what = rune + " " + r.fighters.get(0).events;
        assertTrue(r.duration <= 9.6, what);
        assertTrue(immobilizes(r) >= 3, "the combo immobilizes 3 times: " + what);
        assertEquals(1, activations(r, rune), 1e-9, what);
        TriggerReport rep = r.triggers.stream().filter(t -> t.fighter == 0 && rune.equals(t.source)).findFirst().orElseThrow();
        assertEquals(1, rep.activations, what);
    }

    /** W (Brittle) and an attack that breaks it (a knock-back): an immobilize every time the W is up (~12s). */
    static Combo shortCombo() {
        Combo c = new Combo();
        c.champion = "Ornn";
        c.name = "W + ataque";
        c.origin = "manual";
        c.maxTime = 4;
        c.steps = new ArrayList<>(List.of(new ComboStep("habilidade:2", null, 0), new ComboStep("atacar", null, 0)));
        c.start = new ArrayList<>(List.of(new Condition("pronto:2", "==", 1)));
        return c;
    }

    private static void spacedInRepeat(Arena.Input ornn, String rune, double cooldown) {
        ComboBench.Config cfg = new ComboBench.Config();
        cfg.mode = "repetir";
        cfg.seconds = 45;
        Result r = BENCH.run(ornn, OrnnProofTest.dummy(), shortCombo(), "parado", cfg, false);
        double n = activations(r, rune);
        TriggerReport rep = r.triggers.stream().filter(t -> t.fighter == 0 && rune.equals(t.source)).findFirst().orElseThrow();
        String what = rune + " " + n + " " + r.fighters.get(0).events;
        assertTrue(n >= 2 && n <= 3, what);
        assertEquals(n, rep.activations, 1e-9, "the trigger and the rune count the same activations: " + what);
        assertTrue(immobilizes(r) > n, "more immobilizes than activations: " + what);
        // the activations, as the counter sees them, at least the cooldown apart
        List<Double> at = r.fighters.get(0).procTimes.getOrDefault(rune, List.of());
        assertEquals(n, at.size(), 1e-9, what + " " + at);
        for (int i = 1; i < at.size(); i++) {
            assertTrue(at.get(i) - at.get(i - 1) >= cooldown - 1e-6, rune + " at " + at);
        }
    }

    /** 🎯 Ornn's 8s combo immobilizes 3 times and Glacial Augment goes off once (ice field and defenses together). */
    @Test
    void glacialAugmentOnceInAnEightSecondCombo() {
        oneActivationInTheCombo(glacial(), "Suserano do Gelo");
    }

    /** 🎯 repetir 45s: at most 3 Glacial Augments, 20s apart. */
    @Test
    void glacialAugmentEveryTwentySecondsInRepeat() {
        spacedInRepeat(glacial(), "Suserano do Gelo", 20);
    }

    @Test
    void courageOfTheColossusOnceInAnEightSecondCombo() {
        oneActivationInTheCombo(colossus(), "Coragem do Colosso");
    }

    @Test
    void courageOfTheColossusEveryEighteenSecondsInRepeat() {
        spacedInRepeat(colossus(), "Coragem do Colosso", 18);
    }

    /** The ice field's damage lands once per activation too (not once per part). */
    @Test
    void glacialFieldDamageOncePerActivation() {
        Result once = BENCH.run(glacial(), OrnnProofTest.dummy(), OrnnProofTest.manual(), "parado", new ComboBench.Config(), false);
        double one = once.fighters.get(0).damageBySource.getOrDefault("Suserano do Gelo", 0.0);
        assertTrue(one > 0, once.fighters.get(0).damageBySource.toString());
        ComboBench.Config cfg = new ComboBench.Config();
        cfg.mode = "repetir";
        cfg.seconds = 45;
        Result r = BENCH.run(glacial(), OrnnProofTest.dummy(), shortCombo(), "parado", cfg, false);
        double n = activations(r, "Suserano do Gelo");
        assertEquals(n * one, r.fighters.get(0).damageBySource.getOrDefault("Suserano do Gelo", 0.0), 1e-6);
    }
}
