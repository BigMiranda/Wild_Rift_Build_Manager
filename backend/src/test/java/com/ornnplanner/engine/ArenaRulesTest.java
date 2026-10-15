package com.ornnplanner.engine;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel.Action;
import com.ornnplanner.arena.ArenaModel.Combo;
import com.ornnplanner.arena.ArenaModel.ComboStep;
import com.ornnplanner.arena.ArenaModel.FighterStats;
import com.ornnplanner.arena.ArenaModel.Result;
import com.ornnplanner.arena.ComboBench;
import com.ornnplanner.arena.fight.DamageRules;
import com.ornnplanner.arena.fight.Delay;
import com.ornnplanner.arena.fight.ItemEffect;
import com.ornnplanner.arena.fight.Ledger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.ornnplanner.engine.ArenaScenarios.CATALOG;
import static com.ornnplanner.engine.ArenaScenarios.champion;
import static com.ornnplanner.engine.ArenaScenarios.order;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Damage rules and item effects in the arena, ported from the quick matchup's tests when it was removed (round 4,
 * part 2): mitigation, delayed statuses, magic-only shields, Brittle broken by an immobilize, the elemental's recast
 * breaking the first pass' Brittle, Darius' bleed and Noxian Might, Frozen Heart, Grievous Wounds and the shield
 * reaver with armor reduction. The ability-text reading is in {@link AbilityParserTest}.
 */
class ArenaRulesTest {

    private static final double EPS = 1e-6;

    private static Result run(List<Arena.Input> in, List<Action> orders, double seconds) {
        return new Arena(CATALOG, ArenaScenarios.MAP).run(in, orders, seconds);
    }

    /** A target that stands still and never hurts (no damage, no abilities). */
    private static Arena.Input post(double hp, double armor, double mr) {
        Arena.Input b = champion("Darius", 1, 1200, 0, hp, 0, armor);
        b.f.total.put(Stats.MAGIC_RESIST, mr);
        b.f.base.put(Stats.MAGIC_RESIST, mr);
        b.f.total.put(Stats.MOVE_SPEED, 0.0);
        b.autoAbilities = false;
        return b;
    }

    private static Arena.Input attacker(double ad) {
        Arena.Input a = champion("Darius", 0, 1000, 0, 5000, ad, 0);
        a.autoAbilities = false;
        a.forms.clear();
        return a;
    }

    private static Ledger ledger(Result r, int fighter, String source) {
        return r.ledger.stream().filter(l -> l.fighter == fighter && l.source.equals(source)).findFirst().orElse(null);
    }

    private static double event(Result r, String text) {
        return r.events.stream().filter(e -> e.text.contains(text)).mapToDouble(e -> e.t).min().orElse(-1);
    }

    @Test
    void attacksAreMitigatedByArmor() {
        Result r = run(List.of(attacker(100), post(100000, 100, 0)), List.of(), 10);
        FighterStats b = r.fighters.get(1);
        // 100 armor halves each attack of 100 (and the bleed, physical too): attacks land 50 each, as much is mitigated
        double attacks = r.fighters.get(0).damageBySource.getOrDefault("Ataque", 0.0);
        assertTrue(attacks > 0);
        assertEquals(0, attacks % 50, EPS, "whole attacks of 50: " + attacks);
        assertEquals(b.taken, b.mitigated, EPS);
        assertEquals(-1, r.winner);
    }

    @Test
    void delayedStatusCountsOnlyAfterItsTime() {
        Arena.Input b = post(100000, 200, 0);   // 100 of the armor from Duplaguarda's Tolerância, only after 5s in combat
        Delay d = new Delay();
        d.source = "Duplaguarda de Amaranto";
        d.amounts.put(Stats.ARMOR, 100.0);
        d.seconds = 5.0;
        b.f.delays.add(d);
        Result r = run(List.of(attacker(100), b), List.of(), 12);
        double on = event(r, "Duplaguarda de Amaranto ativo");
        assertTrue(on >= 5 && on < 7, "on at " + on);
        double always100 = run(List.of(attacker(100), post(100000, 100, 0)), List.of(), 12).fighters.get(1).taken;
        double always200 = run(List.of(attacker(100), post(100000, 200, 0)), List.of(), 12).fighters.get(1).taken;
        double taken = r.fighters.get(1).taken;
        assertTrue(taken < always100 - EPS && taken > always200 + EPS, always200 + " < " + taken + " < " + always100);
    }

    @Test
    void magicShieldOnlyAbsorbsMagicDamage() {
        Arena.Input b = post(100000, 0, 0);
        b.f.magicShieldSources.put("Rookern Lamúrico", 500.0);
        Result r = run(List.of(attacker(100), b), List.of(), 5);
        assertEquals(0, r.fighters.get(1).shieldAbsorbed, EPS);   // physical attacks go through
        assertEquals(run(List.of(attacker(100), post(100000, 0, 0)), List.of(), 5).fighters.get(1).taken,
                r.fighters.get(1).taken, EPS);
    }

    /** W leaves the target Brittle; the knock-up of the R's recast (not an attack) breaks it. */
    @Test
    void brittleBreaksOnTheNextImmobilize() {
        Combo c = new Combo();
        c.champion = "Ornn";
        c.name = "W, R, reconjurar";
        c.maxTime = 8;
        c.steps = new ArrayList<>(List.of(new ComboStep("habilidade:2", null, 0), new ComboStep("habilidade:R", null, 0),
                new ComboStep("reconjurar:R", "marca_aplicada", 0)));
        Result r = new ComboBench(CATALOG, ArenaScenarios.MAP).run(OrnnProofTest.ornn(), OrnnProofTest.dummy(), c, "parado",
                new ComboBench.Config(), false);
        Map<String, Double> ev = r.fighters.get(0).events;
        assertTrue(ev.getOrDefault("marca_consumida:Frágil:R", 0.0) >= 1, ev.toString());
        assertEquals(0, ev.getOrDefault("marca_consumida:Frágil:ataque", 0.0), EPS, ev.toString());
        assertTrue(r.fighters.get(0).damageBySource.getOrDefault("Frágil", 0.0) > 0);
    }

    /** The elemental's first pass leaves Brittle, the recast's knock-up on the way back breaks it. */
    @Test
    void theUltimatesRecastBreaksTheFirstPassBrittle() {
        Arena.Input ornn = champion("Ornn", 0, 1000, 0, 5000, 0, 100);
        ornn.autoAbilities = false;
        Arena.Input b = post(20000, 0, 0);
        b.x = 1500;
        List<Action> orders = new ArrayList<>(List.of(order(0, 0, "ability", "R", 1), order(0, 1.5, "recast", "R", null)));
        Result r = run(List.of(ornn, b), orders, 6);
        Map<String, Double> ev = r.fighters.get(0).events;
        assertTrue(ev.getOrDefault("marca_aplicada:Frágil", 0.0) >= 1, ev.toString());
        assertTrue(ev.getOrDefault("marca_consumida:Frágil:R", 0.0) >= 1, ev.toString() + " " + r.events);
    }

    @Test
    void dariusBleedStacksAndMightComesAfterFiveHits() {
        Arena.Input darius = champion("Darius", 0, 1000, 0, 5000, 100, 0);
        darius.autoAbilities = false;
        darius.baseAttackSpeed = 1.0;
        darius.forms.put("Hemorragia", Map.of(Stats.ATTACK_DAMAGE, 50.0));
        Result r = run(List.of(darius, post(100000, 0, 0)), List.of(), 8);
        assertTrue(r.fighters.get(0).damageBySource.getOrDefault("Hemorragia", 0.0) > 0, r.fighters.get(0).damageBySource.toString());
        double might = event(r, "Hemorragia ativo");
        assertTrue(might > 0, r.events.toString());
        // Noxian Might comes with the 5th stack on the target: just before it, 4 stacks and no Might
        darius = champion("Darius", 0, 1000, 0, 5000, 100, 0);
        darius.autoAbilities = false;
        darius.baseAttackSpeed = 1.0;
        darius.forms.put("Hemorragia", Map.of(Stats.ATTACK_DAMAGE, 50.0));
        Result before = run(List.of(darius, post(100000, 0, 0)), List.of(), might - 0.02);
        assertEquals(-1, event(before, "Hemorragia ativo"), EPS);
        assertEquals(4, before.fighters.get(0).events.getOrDefault("marca_aplicada:Hemorragia", 0.0), EPS);
    }

    @Test
    void frozenHeartSlowsTheEnemysAttacks() {
        Arena.Input b = post(100000, 0, 0);
        for (Map<String, Object> e : CATALOG.effects("Coração Congelado")) {
            b.f.effects.add(new ItemEffect("Coração Congelado", e));
        }
        double slowed = run(List.of(attacker(100), b), List.of(), 10).fighters.get(1).taken;
        double plain = run(List.of(attacker(100), post(100000, 0, 0)), List.of(), 10).fighters.get(1).taken;
        assertTrue(slowed < plain * 0.9, slowed + " vs " + plain);
    }

    @Test
    void grievousWoundsCutLifesteal() {
        Arena.Input a = attacker(100);
        a.f.total.put("% Lifesteal", 20.0);
        a.f.vampSources.put("Sedenta por Sangue", 20.0);
        Arena.Input b = post(100000, 0, 0);
        b.f.total.put(Stats.ATTACK_DAMAGE, 50.0);
        b.autoAbilities = false;
        b.f.items.put("Armadura de Espinhos", 1L);
        for (Map<String, Object> e : CATALOG.effects("Armadura de Espinhos")) {
            b.f.effects.add(new ItemEffect("Armadura de Espinhos", e));
        }
        Result r = run(List.of(a, b), List.of(), 10);
        Ledger thornmail = ledger(r, 1, "Armadura de Espinhos");
        assertNotNull(thornmail, r.ledger.toString());
        assertTrue(thornmail.healCut > 0, "Espinhos cuts the lifesteal");
        assertTrue(thornmail.damage > 0, "Espinhos reflects damage");
        Ledger lifesteal = ledger(r, 0, "Sedenta por Sangue");
        assertTrue(lifesteal.healLost > 0);
        assertTrue(lifesteal.healLost <= lifesteal.healed + EPS, lifesteal.healed + " healed, " + lifesteal.healLost + " cut");
    }

    @Test
    void grievousWoundsOnlyCountsTheHealThatWouldHaveLanded() {
        Arena.Input a = champion("Darius", 0, 1000, 0, 100000, 100, 0);   // barely hurt: most lifesteal goes over full health
        a.autoAbilities = false;
        a.forms.clear();
        a.f.total.put("% Lifesteal", 20.0);
        a.f.vampSources.put("Sedenta por Sangue", 20.0);
        Arena.Input b = post(100000, 0, 0);
        b.f.total.put(Stats.ATTACK_DAMAGE, 5.0);
        b.f.items.put("Colete Espinhoso", 1L);
        for (Map<String, Object> e : CATALOG.effects("Colete Espinhoso")) {
            b.f.effects.add(new ItemEffect("Colete Espinhoso", e));
        }
        Result r = run(List.of(a, b), List.of(), 10);
        FighterStats fa = r.fighters.get(0);
        assertTrue(fa.healLost <= 0.4 / 0.6 * fa.healed + 1e-6, fa.healed + " healed, " + fa.healLost + " cut");
        assertTrue(fa.healLost <= fa.taken + EPS, "a cut heal can't be more than the health missing");
    }

    @Test
    void shieldReaverCutsShieldsAndShredAddsDamage() {
        Arena.Input a = attacker(100);
        a.f.base.put(Stats.ATTACK_DAMAGE, 60.0);   // 40 bonus AD
        a.f.items.put("Presa da Serpente", 1L);
        a.f.items.put("Cutelo Negro", 2L);
        for (String item : List.of("Presa da Serpente", "Cutelo Negro")) {
            for (Map<String, Object> e : CATALOG.effects(item)) {
                a.f.effects.add(new ItemEffect(item, e));
            }
        }
        Arena.Input b = post(100000, 100, 0);
        b.f.shieldSources.put("Placa Gargolítica", 1000.0);
        b.f.total.put("Shield", 1000.0);
        Result r = run(List.of(a, b), List.of(), 10);
        Ledger fang = ledger(r, 0, "Presa da Serpente");
        assertNotNull(fang, r.ledger.toString());
        assertTrue(fang.shieldCut > 0, "Presa cuts the shield");
        assertTrue(ledger(r, 0, "Cutelo Negro").extraShred > 0);
        // 30% armor reduction: 70 armor -> 100/170 of the damage
        assertEquals(100 * 100 / 170.0, DamageRules.mitigation(100, 0.3, 0, 0) * 100, 1e-9);
    }
}
