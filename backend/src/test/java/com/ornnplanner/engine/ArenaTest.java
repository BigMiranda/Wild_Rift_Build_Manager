package com.ornnplanner.engine;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.ArenaModel.Action;
import com.ornnplanner.arena.ArenaModel.Result;
import com.ornnplanner.arena.fight.CombatCatalog;
import com.ornnplanner.arena.fight.FighterBuilder;
import com.ornnplanner.arena.fight.ItemEffect;
import com.ornnplanner.arena.fight.Fighter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Orchestrated matchup on the 2D arena: movement, abilities from the sheets, terrain, timeline and cooldowns. */
class ArenaTest {

    private static CombatCatalog catalog;
    private static Map<String, Object> kits;
    private static Map<String, Object> map;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() throws Exception {
        catalog = new CombatCatalog(new ClassPathResource("seed/combate_7_3.yml"));
        try (InputStream in = new ClassPathResource("seed/kits_7_3.yml").getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            kits = (Map<String, Object>) root.get("campeoes");
            map = (Map<String, Object>) root.get("mapa");
        }
    }

    @SuppressWarnings("unchecked")
    private static Arena.Input champion(String name, int team, double x, double y, double hp, double ad, double armor) {
        Fighter f = new Fighter();
        f.name = name;
        f.champion = name;
        f.team = team;
        f.level = 15;
        for (Map<String, Double> m : List.of(f.total, f.base)) {
            m.put(Stats.MAX_HEALTH, hp);
            m.put(Stats.ATTACK_DAMAGE, ad);
            m.put(Stats.ARMOR, armor);
            m.put(Stats.MAGIC_RESIST, armor);
            m.put(Stats.MOVE_SPEED, 350.0);
        }
        f.ranks = new int[] {4, 4, 4, 3};
        Arena.Input in = new Arena.Input();
        in.f = f;
        in.build = name;
        in.kit = (Map<String, Object>) kits.get(name);
        in.x = x;
        in.y = y;
        in.baseAttackSpeed = 1.0;
        in.autoAbilities = false;
        return in;
    }

    private static Action order(int fighter, double time, String type, String slot) {
        Action a = new Action();
        a.fighter = fighter;
        a.time = time;
        a.type = type;
        a.slot = slot;
        return a;
    }

    private static Result run(List<Arena.Input> inputs, List<Action> actions, double duration) {
        return new Arena(catalog, map).run(inputs, actions, duration);
    }

    @Test
    void championsWalkIntoRangeThenAttack() {
        Arena.Input a = champion("Darius", 0, 1000, 0, 5000, 100, 0);
        Arena.Input b = champion("Darius", 1, 2000, 0, 5000, 0, 0);
        Result r = run(List.of(a, b), List.of(), 3);
        // 1000 apart, both walking at 350 towards each other: in reach (175 + 2 x 80) after ~0.95s.
        Integer firstHit = null;
        for (ArenaModel.Frame f : r.frames) {
            if (f.units.get(1).hp < 5000) {
                firstHit = (int) Math.round(f.t * 10);
                break;
            }
        }
        assertNotNull(firstHit);
        assertTrue(firstHit >= 9 && firstHit <= 13, "first hit at " + firstHit / 10.0 + "s");
        assertTrue(r.fighters.get(1).taken > 0);
    }

    @Test
    void ornnRuptureTravelsHitsAndLeavesAPillar() {
        Arena.Input ornn = champion("Ornn", 0, 1000, 0, 5000, 100, 0);
        Arena.Input b = champion("Darius", 1, 1600, 0, 5000, 0, 0);
        b.f.total.put(Stats.MOVE_SPEED, 0.0);   // stays put
        Action q = order(0, 0, "ability", "1");
        q.target = 1;
        Result r = run(List.of(ornn, b), List.of(q), 2);
        double expected = 130 + 1.1 * 100;     // rank 4 + 110% AD, no armor
        assertEquals(expected, r.fighters.get(0).damageBySource.get("1 · Ruptura Vulcânica"), 1e-6);
        // The pillar appears 1.125s after the fissure ends (200 behind the target), and blocks.
        boolean pillar = r.frames.stream().anyMatch(f -> f.shapes.stream().anyMatch(s -> s.label.startsWith("Pilar")));
        assertTrue(pillar);
        assertTrue(r.frames.stream().anyMatch(f -> f.units.get(1).marks.stream().anyMatch(m -> m.startsWith("Lentidão 55"))));
    }

    @Test
    void condemnIntoTheWallStuns() {
        Arena.Input vayne = champion("Vayne", 0, 1000, 0, 5000, 100, 0);
        // Target near the lane's wall (width 1100: wall at y = 550), Vayne below it: the push goes into the wall.
        Arena.Input b = champion("Darius", 1, 1000, 300, 5000, 0, 0);
        b.f.total.put(Stats.MOVE_SPEED, 0.0);
        Action e = order(0, 0, "ability", "3");
        e.target = 1;
        Result r = run(List.of(vayne, b), List.of(e), 2);
        assertTrue(r.events.stream().anyMatch(ev -> ev.text.contains("prendeu")), r.events.toString());
        assertTrue(r.fighters.get(0).damageBySource.containsKey("3 · Condenar (parede)"));
        // Away from walls: pushed, no stun.
        Arena.Input free = champion("Darius", 1, 1500, 0, 5000, 0, 0);
        free.f.total.put(Stats.MOVE_SPEED, 0.0);
        Result open = run(List.of(champion("Vayne", 0, 1000, 0, 5000, 100, 0), free), List.of(e), 2);
        assertTrue(open.events.stream().noneMatch(ev -> ev.text.contains("prendeu")));
    }

    @Test
    void framesCarryCooldownsAndOrdersWaitForThem() {
        Arena.Input ornn = champion("Ornn", 0, 1000, 0, 5000, 100, 0);
        Arena.Input b = champion("Darius", 1, 1400, 0, 50000, 0, 0);
        b.f.total.put(Stats.MOVE_SPEED, 0.0);
        Action q1 = order(0, 0, "ability", "1");
        Action q2 = order(0, 1, "ability", "1");   // ordered at 1s: waits for the cooldown (7.5s at rank 4)
        Result r = run(List.of(ornn, b), new ArrayList<>(List.of(q1, q2)), 9);
        ArenaModel.Frame at2 = r.frames.stream().filter(f -> Math.abs(f.t - 2.0) < 0.06).findFirst().orElseThrow();
        assertEquals(7.5 - 2 + 0.25 - 0.25, at2.units.get(0).cooldowns.get("1"), 0.06);
        assertTrue(r.events.stream().anyMatch(ev -> ev.text.contains("depois de esperar")), r.events.toString());
    }

    @Test
    void ornnUltimateFirstPassThenHeadbuttBreaksBrittle() {
        Arena.Input ornn = champion("Ornn", 0, 1000, 0, 5000, 100, 0);
        Arena.Input b = champion("Darius", 1, 1500, 0, 20000, 0, 0);
        b.f.total.put(Stats.MOVE_SPEED, 0.0);
        Action r1 = order(0, 0, "ability", "R");
        r1.target = 1;
        Action r2 = order(0, 1.5, "recast", "R");   // waits for the elemental to come within reach
        Result r = run(List.of(ornn, b), new ArrayList<>(List.of(r1, r2)), 8);
        assertTrue(r.events.stream().anyMatch(ev -> ev.text.contains("devolveu o elemental")), r.events.toString());
        assertTrue(r.events.stream().anyMatch(ev -> ev.text.contains("quebrou o Frágil")), r.events.toString());
        // the headbutt breaks the first pass' Brittle; Ornn's attack then breaks the one the headbutt applied
        assertEquals(2 * 0.22 * 20000, r.fighters.get(0).damageBySource.get("Frágil"), 1e-6);
        // first pass + the elemental sent back: two hits of 225 (rank 3, no AP)
        assertEquals(2 * 225, r.fighters.get(0).damageBySource.get("R · Chamado do Deus da Forja"), 1e-6);
        assertTrue(r.frames.stream().anyMatch(f -> "air".equals(f.units.get(1).state)));
    }

    @Test
    void dariusMightTurnsOnWithFiveStacks() {
        Arena.Input darius = champion("Darius", 0, 1000, 0, 5000, 100, 0);
        darius.forms.put("Hemorragia", Map.of(Stats.ATTACK_DAMAGE, 236.0));
        darius.baseAttackSpeed = 2.0;
        Arena.Input b = champion("Darius", 1, 1200, 0, 50000, 0, 0);
        b.f.total.put(Stats.MOVE_SPEED, 0.0);
        Result r = run(List.of(darius, b), List.of(), 4);
        assertTrue(r.events.stream().anyMatch(ev -> ev.text.contains("Hemorragia ativo")), r.events.toString());
        assertTrue(r.fighters.get(0).damageBySource.get("Hemorragia") > 0);
    }

    private static FighterBuilder.Triggered trigger(String key, String mode, Map<String, Object> spec, Map<String, Double> delta) {
        FighterBuilder.Triggered t = new FighterBuilder.Triggered();
        t.key = key;
        t.source = key.contains("|") ? key.substring(0, key.indexOf('|')) : key;
        t.effect = key.contains("|") ? key.substring(key.indexOf('|') + 1) : key;
        t.kind = key.contains("|") ? "item" : "runa";
        t.mode = mode;
        Map<String, Object> sp = new LinkedHashMap<>(spec);
        sp.put("quando", mode);
        t.spec = sp;
        t.delta.putAll(delta);
        return t;
    }

    private static void rune(Arena.Input in, String name) {
        for (Map<String, Object> e : catalog.runeEffects(name)) {
            in.f.effects.add(new ItemEffect(name, e));
        }
    }

    @Test
    void lifelineShieldComesOnlyBelowItsHealth() {
        Arena.Input a = champion("Darius", 0, 1000, 0, 5000, 300, 0);
        Arena.Input b = champion("Darius", 1, 1200, 0, 3000, 0, 0);
        b.f.total.put(Stats.MOVE_SPEED, 0.0);
        b.f.items.put("Sinal de Sterak", 1L);
        b.triggers.add(trigger("Sinal de Sterak|Salva-Vidas", "vida_abaixo",
                Map.of("vida", 0.35, "duracao", 5, "decai", true, "recarga", 70), Map.of("Shield", 1000.0)));
        Result r = run(List.of(a, b), List.of(), 12);
        ArenaModel.TriggerReport rep = r.triggers.get(0);
        assertEquals(1, rep.activations);
        assertEquals(1000, rep.shield, 1e-6);
        // no shield before the hit that took it under 35% (3000 -> 1050): the 7th attack (300 each, from ~0.6s)
        assertTrue(rep.first > 5, "activated at " + rep.first);
        ArenaModel.Frame early = r.frames.get(5);
        assertEquals(0, early.units.get(1).shield, 1e-6);
    }

    @Test
    void amaranthsTwinguardTurnsOnAfterFiveSecondsInCombat() {
        Arena.Input a = champion("Darius", 0, 1000, 0, 5000, 100, 0);
        Arena.Input b = champion("Darius", 1, 1200, 0, 50000, 0, 0);
        b.f.total.put(Stats.MOVE_SPEED, 0.0);
        b.triggers.add(trigger("Duplaguarda de Amaranto|Tolerância", "combate", Map.of("segundos", 5),
                Map.of(Stats.ARMOR, 100.0)));
        Result r = run(List.of(a, b), List.of(), 10);
        ArenaModel.TriggerReport rep = r.triggers.get(0);
        double contact = r.events.stream().filter(e -> e.text.contains("Duplaguarda")).findFirst().orElseThrow().t;
        assertEquals(1, rep.activations);
        assertTrue(contact > 5 && contact < 6.2, "on at " + contact);
        assertTrue(rep.activeSeconds > 3.5);
    }

    @Test
    void ornnAttackOnBrittleTargetBreaksItAndKnocksBack() {
        Arena.Input ornn = champion("Ornn", 0, 1000, 0, 5000, 100, 0);
        ornn.baseAttackSpeed = 2;
        Arena.Input b = champion("Darius", 1, 1250, 0, 20000, 0, 0);
        b.f.total.put(Stats.MOVE_SPEED, 0.0);
        Action w = order(0, 0, "ability", "2");
        w.target = 1;
        Result r = run(List.of(ornn, b), new ArrayList<>(List.of(w)), 3);
        assertTrue(r.events.stream().anyMatch(ev -> ev.text.contains("quebrou o Frágil")), r.events.toString());
        assertEquals(0.22 * 20000, r.fighters.get(0).damageBySource.get("Frágil"), 1e-6);
    }

    @Test
    void electrocuteAfterThreeHits() {
        Arena.Input a = champion("Darius", 0, 1000, 0, 5000, 100, 0);
        rune(a, "Eletrocutar");
        Arena.Input b = champion("Darius", 1, 1200, 0, 50000, 0, 0);
        b.f.total.put(Stats.MOVE_SPEED, 0.0);
        Result r = run(List.of(a, b), List.of(), 4);
        assertEquals(210, r.fighters.get(0).damageBySource.get("Eletrocutar"), 1e-6);   // level 15, no bonus AD / AP
    }

    @Test
    void graspEveryThreeSecondsInCombat() {
        Arena.Input a = champion("Darius", 0, 1000, 0, 5000, 100, 0);
        rune(a, "Aperto dos Mortos-Vivos");
        Arena.Input b = champion("Darius", 1, 1200, 0, 50000, 0, 0);
        b.f.total.put(Stats.MOVE_SPEED, 0.0);
        Result r = run(List.of(a, b), List.of(), 7.5);
        // contact ~0.6s: procs at ~3.6s and ~6.6s, 3.3% of 5000 each
        assertEquals(2 * 0.033 * 5000, r.fighters.get(0).damageBySource.get("Aperto dos Mortos-Vivos"), 1e-6);
    }

    @Test
    void guardianAngelBringsTheChampionBack() {
        Arena.Input a = champion("Darius", 0, 1000, 0, 5000, 400, 0);
        Arena.Input b = champion("Darius", 1, 1200, 0, 1000, 0, 0);
        b.f.total.put(Stats.MOVE_SPEED, 0.0);
        b.f.items.put("Anjo Guardião", 1L);
        b.triggers.add(trigger("Anjo Guardião|Ressurreição", "ao_morrer", Map.of("estase", 4, "recarga", 180),
                Map.of("Heal", 500.0, "Stasis", 4.0)));
        Result r = run(List.of(a, b), List.of(), 9);
        List<String> texts = r.events.stream().map(ev -> ev.t + " " + ev.text).collect(java.util.stream.Collectors.toList());
        assertTrue(texts.stream().anyMatch(x -> x.contains("voltou com 500")), texts.toString());
        // 1000 health dies at the 3rd attack (~2.6s), comes back 4s later: not dead before ~6.6s
        assertFalse(r.fighters.get(1).deathTime != null && r.fighters.get(1).deathTime < 6.5, texts.toString());
    }
}
