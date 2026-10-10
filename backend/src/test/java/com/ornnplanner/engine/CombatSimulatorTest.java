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
