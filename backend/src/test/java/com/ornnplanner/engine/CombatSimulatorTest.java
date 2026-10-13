package com.ornnplanner.engine;

import com.ornnplanner.combat.AbilityParser;
import com.ornnplanner.combat.CombatCatalog;
import com.ornnplanner.combat.CombatSimulator;
import com.ornnplanner.combat.CombatSimulator.Fighter;
import com.ornnplanner.combat.CombatSimulator.ItemEffect;
import com.ornnplanner.combat.CombatSimulator.Ledger;
import com.ornnplanner.combat.CombatSimulator.Result;
import com.ornnplanner.seed.ChampionCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Matchup simulator: ability damage read from the texts, mitigation and the ledger of counter items. */
class CombatSimulatorTest {

    private static final double EPS = 1e-6;
    private static CombatCatalog catalog;
    private static ChampionCatalog champions;

    @BeforeAll
    static void load() {
        catalog = new CombatCatalog(new ClassPathResource("seed/combate_7_3.yml"));
        champions = new ChampionCatalog(new ClassPathResource("seed/campeoes_7_3.yml"),
                new ClassPathResource("seed/precos_status.yml"));
    }

    @Test
    void abilityDamageFromTheTexts() {
        List<AbilityParser.Ability> garen = AbilityParser.abilities(champions.find("GAREN").orElseThrow());
        Map<String, Double> total = Map.of(Stats.ATTACK_DAMAGE, 100.0);
        Map<String, Double> base = Map.of(Stats.ATTACK_DAMAGE, 64.0);
        // Q: 40/80/120/160 + 40% AD, physical
        assertEquals("fisico", garen.get(0).damage.type);
        assertEquals(80 + 40, garen.get(0).damage.at(2, total, base), EPS);
        // W: a shield, no damage
        assertNull(garen.get(1).damage);
        // E: (13/17/21/25 + 25/30/35/40% AD) x 8 hits
        assertEquals(8, garen.get(2).damage.hits);
        assertEquals((21 + 0.35 * 100) * 8, garen.get(2).damage.at(3, total, base), EPS);
        assertEquals(8.5, garen.get(0).cooldown[1], EPS);
    }

    @Test
    void mostDamageTextsAreRead() {
        int withDamage = 0;
        int read = 0;
        for (Map<String, Object> c : champions.summaries()) {
            Map<String, Object> champ = champions.find(String.valueOf(c.get("code"))).orElseThrow();
            List<AbilityParser.Ability> abilities = AbilityParser.abilities(champ);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> raw = (List<Map<String, Object>>) champ.getOrDefault("habilidades", List.of());
            for (int i = 0; i < abilities.size(); i++) {
                if (String.valueOf(raw.get(i).get("texto")).matches("(?s).*Dano (Físico|Mágico|Verdadeiro).*")) {
                    withDamage++;
                    if (abilities.get(i).damage != null) {
                        read++;
                    }
                }
            }
        }
        System.out.println("Habilidades com dano no texto: " + withDamage + ", lidas: " + read);
        assertTrue(read >= withDamage * 0.75, read + " de " + withDamage);
    }

    private static Fighter fighter(String name, double hp, double armor, double mr, double ad, double as) {
        Fighter f = new Fighter();
        f.name = name;
        f.champion = name;
        f.level = 15;
        f.total.put(Stats.MAX_HEALTH, hp);
        f.total.put(Stats.ARMOR, armor);
        f.total.put(Stats.MAGIC_RESIST, mr);
        f.total.put(Stats.ATTACK_DAMAGE, ad);
        f.base.put(Stats.MAX_HEALTH, hp);
        f.base.put(Stats.ARMOR, armor);
        f.base.put(Stats.MAGIC_RESIST, mr);
        f.base.put(Stats.ATTACK_DAMAGE, ad);
        f.attackSpeed = as;
        f.order = List.of();
        return f;
    }

    private static Ledger ledger(Result r, int fighter, String source) {
        return r.ledger.stream().filter(l -> l.fighter == fighter && l.source.equals(source)).findFirst().orElse(null);
    }

    @Test
    void attacksAreMitigatedByArmor() {
        Fighter a = fighter("A", 3000, 0, 0, 100, 1);
        Fighter b = fighter("B", 100000, 100, 0, 0, 0);
        Result r = new CombatSimulator(catalog).run(List.of(a), List.of(b), 10);
        // 11 attacks (0s, 1s ... 10s) of 100 at 50% reduction
        assertEquals(11 * 50, r.fighters.get(1).taken, 1e-6);
        assertEquals(-1, r.winner);
    }

    @Test
    void ornnBreathReadsTheTargetsHealthNotTheMinionsDamage() {
        List<AbilityParser.Ability> ornn = AbilityParser.abilities(champions.find("ORNN").orElseThrow());
        AbilityParser.Damage w = ornn.get(1).damage;
        assertEquals("magico", w.type);
        assertEquals(0.12 * 3000, w.at(3, s -> 0.0, s -> 0.0, 3000), EPS);   // 10/11/12/13% of the target's health
        assertTrue(ornn.get(1).brittle);
        assertTrue(ornn.get(2).immobilizes);   // Investida Calcinante: knock-up
        assertTrue(ornn.get(3).immobilizes && ornn.get(3).brittle);
    }

    @Test
    void delayedStatusCountsOnlyAfterItsTime() {
        Fighter a = fighter("A", 3000, 0, 0, 100, 1);
        Fighter b = fighter("B", 100000, 100, 0, 0, 0);
        b.total.put(Stats.ARMOR, 200.0);   // 100 of them from Duplaguarda's Tolerância, only from 5s on
        CombatSimulator.Delay d = new CombatSimulator.Delay();
        d.source = "Duplaguarda de Amaranto";
        d.amounts.put(Stats.ARMOR, 100.0);
        d.seconds = 5.0;
        b.delays.add(d);
        Result r = new CombatSimulator(catalog).run(List.of(a), List.of(b), 9.99);
        // attacks at 0..4 s at 100 armor, 5..9 s at 200 armor
        assertEquals(5 * 50 + 5 * 100 / 3.0, r.fighters.get(1).taken, 1e-6);
        assertTrue(r.events.stream().anyMatch(e -> e.startsWith("5,0s") && e.contains("Duplaguarda de Amaranto ativo")));
    }

    @Test
    void magicShieldOnlyAbsorbsMagicDamage() {
        Fighter a = fighter("A", 3000, 0, 0, 100, 1);
        Fighter b = fighter("B", 100000, 0, 0, 0, 0);
        b.magicShieldSources.put("Rookern Lamúrico", 500.0);
        Result r = new CombatSimulator(catalog).run(List.of(a), List.of(b), 5);
        assertEquals(0, r.fighters.get(1).shieldAbsorbed, EPS);   // physical attacks go through
    }

    @Test
    void brittleBreaksOnTheNextImmobilize() {
        Fighter ornn = fighter("Ornn", 3000, 0, 0, 0, 0);
        ornn.champion = "Ornn";
        ornn.abilities = AbilityParser.abilities(champions.find("ORNN").orElseThrow());
        ornn.ranks = new int[] {0, 1, 1, 0};
        ornn.order = List.of("2", "3");
        ornn.mechanics.putAll(catalog.champion("Ornn"));
        Fighter b = fighter("B", 10000, 0, 0, 0, 0);
        Result r = new CombatSimulator(catalog).run(List.of(ornn), List.of(b), 1);
        Ledger brittle = ledger(r, 0, "Frágil");
        assertNotNull(brittle, "the charge breaks the Brittle of the breath");
        assertEquals(0.22 * 10000, brittle.damage, 1e-6);   // level 15
    }

    @Test
    void ornnOpeningWithTheUltimateDelaysTheContact() {
        Fighter ornn = fighter("Ornn", 5000, 0, 0, 0, 0);
        ornn.champion = "Ornn";
        ornn.abilities = AbilityParser.abilities(champions.find("ORNN").orElseThrow());
        ornn.ranks = new int[] {0, 0, 1, 1};
        ornn.order = List.of("R", "3");
        ornn.mechanics.putAll(catalog.champion("Ornn"));
        Fighter darius = fighter("Darius", 10000, 0, 0, 100, 1);
        darius.champion = "Darius";
        darius.mechanics.putAll(catalog.champion("Darius"));
        CombatSimulator.Delay might = new CombatSimulator.Delay();
        might.source = "Hemorragia";
        might.amounts.put(Stats.ATTACK_DAMAGE, 32.0);
        might.hits = 5;
        might.seconds = 3.0;
        darius.delays.add(might);
        darius.total.put(Stats.ATTACK_DAMAGE, 132.0);
        Result r = new CombatSimulator(catalog).run(List.of(ornn), List.of(darius), 3.4);
        assertEquals(3.0, r.contact, EPS);
        // Darius only attacks from 3s on: at 3.0s (and no bleed tick yet)
        assertEquals(100, r.fighters.get(0).taken, 1e-6);
        // the elemental's first pass (damage + Brittle) landed, the recast (3.5s) did not yet
        assertNull(ledger(r, 0, "Frágil"));
        Result full = new CombatSimulator(catalog).run(List.of(ornn), List.of(darius), 4);
        // the recast's knock-up breaks the first pass' Brittle, then the charge breaks the recast's one
        assertEquals(2 * 0.22 * 10000, ledger(full, 0, "Frágil").damage, 1e-6);
        // Noxian Might needs 3s in contact: not before 6s
        assertTrue(full.events.stream().noneMatch(e -> e.contains("Hemorragia ativo")));
    }

    @Test
    void dariusBleedStacksAndMightComesAfterFiveHits() {
        Fighter darius = fighter("Darius", 3000, 0, 0, 100, 2);
        darius.champion = "Darius";
        darius.mechanics.putAll(catalog.champion("Darius"));
        darius.total.put(Stats.ATTACK_DAMAGE, 132.0);   // +32 from Hemorragia (Noxian Might)
        CombatSimulator.Delay d = new CombatSimulator.Delay();
        d.source = "Hemorragia";
        d.amounts.put(Stats.ATTACK_DAMAGE, 32.0);
        d.hits = 5;
        d.seconds = 3.0;
        darius.delays.add(d);
        Fighter b = fighter("B", 100000, 0, 0, 0, 0);
        Result r = new CombatSimulator(catalog).run(List.of(darius), List.of(b), 6);
        assertTrue(ledger(r, 0, "Hemorragia").damage > 0);
        // 5th hit at 2s, but the Might waits for 3s
        assertTrue(r.events.stream().anyMatch(e -> e.startsWith("3,0s") && e.contains("Hemorragia ativo")), r.events.toString());
    }

    @Test
    void frozenHeartSlowsTheEnemysAttacks() {
        Fighter a = fighter("A", 3000, 0, 0, 100, 1);
        Fighter b = fighter("B", 100000, 0, 0, 0, 0);
        for (Map<String, Object> e : catalog.effects("Coração Congelado")) {
            b.effects.add(new ItemEffect("Coração Congelado", e));
        }
        Result r = new CombatSimulator(catalog).run(List.of(a), List.of(b), 10);
        assertEquals(8 * 100, r.fighters.get(1).taken, 1e-6);   // one attack every 1/0.75 s: 0, 1.33 ... 9.33
    }

    @Test
    void grievousWoundsCutLifesteal() {
        Fighter a = fighter("A", 3000, 0, 0, 100, 1);
        a.total.put("% Lifesteal", 20.0);
        a.vampSources.put("Sedenta por Sangue", 20.0);
        Fighter b = fighter("B", 100000, 0, 0, 50, 1);
        b.items.put("Armadura de Espinhos", 1L);
        for (Map<String, Object> e : catalog.effects("Armadura de Espinhos")) {
            b.effects.add(new ItemEffect("Armadura de Espinhos", e));
        }
        Result r = new CombatSimulator(catalog).run(List.of(a), List.of(b), 10);
        Ledger thornmail = ledger(r, 1, "Armadura de Espinhos");
        assertNotNull(thornmail);
        assertTrue(thornmail.healCut > 0, "Espinhos cuts A's lifesteal");
        assertTrue(thornmail.damage > 0, "Espinhos reflects damage");
        Ledger lifesteal = ledger(r, 0, "Sedenta por Sangue");
        assertTrue(lifesteal.healLost > 0);
        // Armadura de Espinhos cuts 50%: what landed equals what was cut, not more
        assertEquals(lifesteal.healed, lifesteal.healLost, 1e-6);
    }

    @Test
    void grievousWoundsOnlyCountsTheHealThatWouldHaveLanded() {
        Fighter a = fighter("A", 100000, 0, 0, 100, 1);   // barely hurt: most of the lifesteal goes over full health
        a.total.put("% Lifesteal", 20.0);
        a.vampSources.put("Sedenta por Sangue", 20.0);
        Fighter b = fighter("B", 100000, 0, 0, 5, 1);
        b.items.put("Colete Espinhoso", 1L);
        for (Map<String, Object> e : catalog.effects("Colete Espinhoso")) {
            b.effects.add(new ItemEffect("Colete Espinhoso", e));
        }
        Result r = new CombatSimulator(catalog).run(List.of(a), List.of(b), 10);
        CombatSimulator.FighterResult fa = r.fighters.get(0);
        // A loses 5 a second and heals 20 a hit cut to 12: the cut never passes 40% of what would have healed
        assertTrue(fa.healLost <= 0.4 / 0.6 * fa.healed + 1e-6, fa.healed + " healed, " + fa.healLost + " cut");
        assertTrue(fa.healLost < fa.taken, "a cut heal can't be more than the health missing");
    }

    @Test
    void shieldReaverCutsShieldsAndShredAddsDamage() {
        Fighter a = fighter("A", 3000, 0, 0, 100, 1);
        a.base.put(Stats.ATTACK_DAMAGE, 60.0);   // 40 bonus AD: 40% + 4% = 44% cut
        a.items.put("Presa da Serpente", 1L);
        a.items.put("Cutelo Negro", 2L);
        for (String item : List.of("Presa da Serpente", "Cutelo Negro")) {
            for (Map<String, Object> e : catalog.effects(item)) {
                a.effects.add(new ItemEffect(item, e));
            }
        }
        Fighter b = fighter("B", 100000, 100, 0, 0, 0);
        b.shieldSources.put("Placa Gargolítica", 1000.0);
        Result r = new CombatSimulator(catalog).run(List.of(a), List.of(b), 10);
        assertEquals(440, ledger(r, 0, "Presa da Serpente").shieldCut, 1e-6);
        assertEquals(440, ledger(r, 1, "Placa Gargolítica").shieldLost, 1e-6);
        assertTrue(ledger(r, 0, "Cutelo Negro").extraShred > 0);
        // 5 hits reach 30%: armor 70 -> 100/170 of the damage
        assertEquals(100 * 100 / 170.0, CombatSimulator.mitigation(100, 0.3, 0, 0) * 100, 1e-9);
    }
}
