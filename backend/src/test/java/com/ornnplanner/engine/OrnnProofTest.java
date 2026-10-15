package com.ornnplanner.engine;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel.Combo;
import com.ornnplanner.arena.ArenaModel.ComboStep;
import com.ornnplanner.arena.ArenaModel.Condition;
import com.ornnplanner.arena.ArenaModel.Dummy;
import com.ornnplanner.arena.ArenaModel.Result;
import com.ornnplanner.arena.ComboBench;

import com.ornnplanner.arena.ComboSearch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The proof with Ornn (round 3, part F): the user's "full combo", typed by hand as a reference, against the ragdoll;
 * the search must reach it (or better) without being given it. The champion is the test scenarios' Ornn (level 13,
 * ranks 4/3/2/2, no items), so the numbers repeat.
 */
class OrnnProofTest {

    static final ComboBench BENCH = new ComboBench(ArenaScenarios.CATALOG, ArenaScenarios.MAP);

    static Arena.Input ornn() {
        return ArenaScenarios.champion("Ornn", 0, 1000, 0, 3000, 160, 90);
    }

    static Arena.Input dummy() {
        return ComboBench.dummyInput(new Dummy());
    }

    private static ComboStep step(String action, String until, double delta) {
        ComboStep s = new ComboStep(action, until, delta);
        s.max = 3;
        if ("alvo_pousa".equals(until) || "alvo_no_chao".equals(until)) {
            s.deltaMin = 0.0;
            s.deltaMax = 0.6;
        }
        return s;
    }

    /**
     * The user's full combo: Q (its 55% slow for 2s makes the E possible) → R → E into the pillar, still in the slow
     * (the target is knocked up and cannot react) → an attack without Brittle → when the way out of the elemental has
     * passed the target (Brittle on), recast R: the way back knocks up and consumes the Brittle (1st, +30% time in the
     * air), chained before the landing, and puts another → an attack at the edge before the landing (2nd) → W with the
     * target in the air (its last hit puts Brittle) → an attack right after the landing (3rd).
     */
    static Combo manual() {
        Combo c = new Combo();
        c.champion = "Ornn";
        c.name = "Full combo (usuário)";
        c.origin = "manual";
        c.maxTime = 8;
        c.steps = new ArrayList<>(List.of(
                step("habilidade:1", null, 0),
                step("habilidade:R", null, 0),
                step("habilidade:3", "acertaria", 0),            // the pillar of the Q stands behind the target
                step("atacar", null, 0),
                step("reconjurar:R", "marca_aplicada", 0),       // the way out put the Brittle; the recast waits for reach
                step("atacar", "alvo_pousa", 0.25),
                step("habilidade:2", null, 0),
                step("atacar", "alvo_no_chao", 0)));
        c.start = new ArrayList<>(List.of(new Condition("alvo.avanco_pronto", "==", 0), new Condition("pronto:1", "==", 1),
                new Condition("pronto:R", "==", 1), new Condition("pronto:3", "==", 1), new Condition("pronto:2", "==", 1)));
        return c;
    }

    /** One run of the manual combo against the standing ragdoll, with frames, and its numbers in words. */
    static String describe(Result r) {
        StringBuilder sb = new StringBuilder();
        r.combos.forEach(c -> {
            sb.append("combo ").append(c.combo).append(": ").append(c.outcome).append(c.reason == null ? "" : " (" + c.reason + ")").append('\n');
            c.steps.forEach(e -> sb.append("  ").append(e.t).append(" ").append(e.text).append('\n'));
        });
        sb.append("events ").append(r.fighters.get(0).events).append('\n');
        sb.append("control spans ");
        r.fighters.get(1).controlSpans.forEach(s -> sb.append(String.format("[%.2f-%.2f] ", s[0], s[1])));
        sb.append('\n');
        r.events.forEach(e -> sb.append("  ev ").append(e.t).append(" ").append(e.text).append('\n'));
        return sb.toString();
    }

    static ComboBench.Config allBehaviors() {
        return new ComboBench.Config();
    }

    /** 🎯 The manual combo on the standing ragdoll: 3 Brittles consumed, control unbroken from the E to the landing. */
    @Test
    void theManualComboConsumesThreeBrittlesWithUnbrokenControl() {
        ComboBench.Config cfg = allBehaviors();
        Result r = BENCH.run(ornn(), dummy(), manual(), "parado", cfg, false);
        String what = describe(r);
        assertEquals("done", r.combos.get(0).outcome, what);
        assertEquals(3, r.fighters.get(0).events.entrySet().stream().filter(e -> e.getKey().startsWith("marca_consumida:Frágil"))
                .mapToDouble(java.util.Map.Entry::getValue).sum(), 1e-9, what);
        // the knock-up of the E (the first control) lasts, chained, until the landing after the way back
        double[] first = r.fighters.get(1).controlSpans.get(0);
        double recast = r.combos.get(0).steps.get(4).t;
        assertTrue(first[0] < recast && first[1] > recast + 1.0, "one span from the E past the recast's knock-up: " + what);
        assertTrue(r.fighters.get(0).events.getOrDefault("cc_encadeado", 0.0) >= 1, what);
    }

    /**
     * 🎯 Discovery: the search, never given the manual combo, against the ragdoll in its 4 behaviors, finds a combo
     * scoring at least 95% of the calibrated manual one. Prints the 3 best found next to the manual one. Long: off by
     * default ({@code -Darena.proof=true}).
     */
    @EnabledIfSystemProperty(named = "arena.proof", matches = "true")
    @Test
    void theSearchFindsTheFullComboOrBetter() throws Exception {
        ComboBench.Config cfg = allBehaviors();
        Combo calibrated = new ComboSearch(BENCH).calibrate(ornn(), dummy(), manual(), cfg, 10);
        ComboBench.Report manual = BENCH.evaluate(ornn(), dummy(), calibrated, cfg);
        ComboSearch.Config sc = new ComboSearch.Config();
        sc.beam = Integer.getInteger("arena.beam", 64);
        sc.maxSteps = Integer.getInteger("arena.steps", 9);
        sc.budget = Integer.getInteger("arena.budget", 20000);
        sc.threads = 10;
        long t0 = System.nanoTime();
        ComboSearch.Result found = new ComboSearch(BENCH).search(ornn(), dummy(), cfg, sc, new ComboSearch.Listener() { });
        double seconds = (System.nanoTime() - t0) / 1e9;
        StringBuilder out = new StringBuilder();
        out.append(String.format("search: %d measured in %.1fs, depth %d, stopped %s%n", found.evaluated, seconds, found.depth, found.stopped));
        found.log.forEach(l -> out.append("  ").append(l).append(System.lineSeparator()));
        out.append(line("manual (calibrated)", calibrated, manual));
        for (int i = 0; i < Math.min(3, found.best.size()); i++) {
            out.append(line("found #" + (i + 1), found.best.get(i).combo, found.best.get(i).report));
        }
        double ratio = found.best.isEmpty() ? 0 : found.best.get(0).score / manual.score;
        out.append(String.format("best found / manual = %.3f%n", ratio));
        Files.write(Paths.get("target/ornn-proof.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println(out);
        assertTrue(ratio >= 0.95, out.toString());
    }

    static String line(String label, Combo c, ComboBench.Report r) {
        StringBuilder sb = new StringBuilder(String.format("%s: score %.0f%n  steps: %s%n", label, r.score, ComboSearch.name(c)));
        r.byBehavior.forEach((b, m) -> sb.append(String.format("  %-7s dmg %5.0f  Brittle consumed %.0f  control %.2fs  free %.2fs  %s%n",
                b, m.damage, m.events.entrySet().stream().filter(e -> e.getKey().startsWith("marca_consumida:Frágil"))
                        .mapToDouble(java.util.Map.Entry::getValue).sum(), m.control, m.longestFree, m.done ? "done" : m.reason)));
        return sb.toString();
    }

    /** The test Ornn with one rune of the combat file (its fight effects), or none. */
    static Arena.Input ornn(String rune) {
        Arena.Input in = ornn();
        if (rune != null) {
            for (java.util.Map<String, Object> e : ArenaScenarios.CATALOG.runeEffects(rune)) {
                in.f.effects.add(new com.ornnplanner.combat.CombatSimulator.ItemEffect(rune, e));
            }
        }
        return in;
    }

    /**
     * Runes on the same combo: Grasp of the Undying, Glacial Augment and Hail of Blades-like Sudden Impact (Aftershock is
     * not in the rune file). For each rune the search runs again (the best order may change); Sudden Impact also in the
     * repeat mode, 30s. Off by default ({@code -Darena.proof=true}).
     */
    @EnabledIfSystemProperty(named = "arena.proof", matches = "true")
    @Test
    void runesOnTheCombo() throws Exception {
        ComboBench.Config cfg = allBehaviors();
        StringBuilder out = new StringBuilder();
        for (String rune : java.util.Arrays.asList(null, "Aperto dos Mortos-Vivos", "Suserano do Gelo", "Impacto Repentino")) {
            String label = rune == null ? "sem runa" : rune;
            ComboBench.Report m = BENCH.evaluate(ornn(rune), dummy(), manual(), cfg);
            ComboSearch.Config sc = new ComboSearch.Config();
            sc.maxSteps = 9;
            sc.budget = 20000;
            sc.threads = 10;
            ComboSearch.Result found = new ComboSearch(BENCH).search(ornn(rune), dummy(), cfg, sc, new ComboSearch.Listener() { });
            ComboSearch.Found best = found.best.get(0);
            ComboBench.Metrics mp = m.byBehavior.get("parado");
            ComboBench.Metrics bp = best.report.byBehavior.get("parado");
            out.append(String.format("%s%n  manual: score %.0f, dmg (parado) %.0f, procs %s%n  best: score %.0f, dmg (parado) %.0f, procs %s%n    %s%n",
                    label, m.score, mp.damage, mp.procs, best.score, bp.damage, bp.procs, ComboSearch.name(best.combo)));
            if (rune != null) {
                out.append(String.format("  %s damage (parado): manual %.0f, best %.0f%n", rune, mp.bySource.getOrDefault(rune, 0.0),
                        bp.bySource.getOrDefault(rune, 0.0)));
            }
        }
        // Sudden Impact sustained: the manual combo repeated for 30s; which dashes armed it
        ComboBench.Config rep = allBehaviors();
        rep.mode = "repetir";
        rep.seconds = 30;
        rep.behaviors = List.of("parado");
        Result r = BENCH.run(ornn("Impacto Repentino"), dummy(), manual(), "parado", rep, true);
        out.append(String.format("Impacto Repentino, repetir 30s: %.0f ativações (%s), %d combos executados%n",
                r.fighters.get(0).events.getOrDefault("runa_ativou:Impacto Repentino", 0.0),
                r.fighters.get(0).damageBySource.getOrDefault("Impacto Repentino", 0.0), r.combos.size()));
        r.events.stream().filter(e -> e.text.contains("usou 3") || e.text.contains("cabeçada") || e.text.contains("usou 1")
                || e.text.contains("usou R")).forEach(e -> out.append("   ").append(e.t).append(' ').append(e.text).append(System.lineSeparator()));
        // a combo that only needs the E (its cooldown is shorter than the rune's 15s): does the rune arm twice?
        Combo eOnly = new Combo();
        eOnly.name = "E e ataques";
        eOnly.maxTime = 4;
        eOnly.steps = new ArrayList<>(List.of(step("habilidade:3", null, 0), step("atacar", null, 0), step("atacar", null, 0)));
        eOnly.start = new ArrayList<>(List.of(new Condition("pronto:3", "==", 1)));
        Result re = BENCH.run(ornn("Impacto Repentino"), dummy(), eOnly, "parado", rep, true);
        out.append(String.format("Impacto Repentino, repetir 30s com \"E e ataques\": %.0f ativações, %d combos%n",
                re.fighters.get(0).events.getOrDefault("runa_ativou:Impacto Repentino", 0.0), re.combos.size()));
        Files.write(Paths.get("target/ornn-runes.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
    }
}
