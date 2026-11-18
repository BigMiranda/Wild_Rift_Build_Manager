package com.ornnplanner.arena.atlas;

import com.ornnplanner.service.PlannerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rodada 19, part 4: "aproveita o kit?" with the seed's rules, and the out-of-archetype choice. */
@SpringBootTest(properties = {"server.port=0"})
class AtlasKitTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        String db = Files.createTempDirectory("atlas-kit").resolve("t.db").toString();
        r.add("planner.db-path", () -> db);
    }

    @Autowired
    AtlasService atlas;
    @Autowired
    PlannerService planner;

    private long item(String name) {
        return planner.itemViews().stream().filter(i -> name.equals(i.name)).findFirst().orElseThrow().id;
    }

    @Test
    void aTankDoesNotTurnCriticalStrikesIntoEffectAMarksmanDoes() {
        KitUse.Rules r = AtlasService.kitRules(new org.yaml.snakeyaml.Yaml().load(getClass().getResourceAsStream("/seed/atlas_7_3d.yml")));
        KitUse.Kit ornn = atlas.kitOf("Ornn");
        KitUse.Kit jinx = atlas.kitOf("Jinx");
        KitUse.Kit rumble = atlas.kitOf("Rumble");
        double ornnCrit = KitUse.stat(ornn, r, "% Critical Rate");
        double ornnSpeed = KitUse.stat(ornn, r, "% Attack Speed");
        double jinxCrit = KitUse.stat(jinx, r, "% Critical Rate");
        assertTrue(ornnCrit < r.threshold, "Ornn crit " + ornnCrit);
        assertTrue(ornnSpeed < r.threshold, "Ornn attack speed " + ornnSpeed);
        assertTrue(jinxCrit >= r.threshold, "Jinx crit " + jinxCrit);
        assertTrue(KitUse.stat(jinx, r, "% Attack Speed") >= r.threshold);
        // health and resistances: every kit
        assertEquals(1.0, KitUse.stat(ornn, r, "Max Health"), 1e-9);
        // no mana: mana is wasted; the abilities' ratios: ability power whole for a mage
        assertEquals(0.0, KitUse.stat(rumble, r, "Max Mana"), 1e-9);
        assertEquals(1.0, KitUse.stat(rumble, r, "Ability Power"), 1e-9);
        // an item by its stats' gold: a critical strike and attack speed item is out of the tank's archetype, in the
        // marksman's; one of attack damage stays in the tank's (its abilities scale with it)
        for (String name : new String[] {"Dançarina Fantasma", "Furacão de Runaan", "Adagas Rápidas Navori"}) {
            assertFalse(atlas.inKit("Ornn", item(name)), name);
            assertTrue(atlas.inKit("Jinx", item(name)), name);
        }
        assertTrue(atlas.inKit("Ornn", item("Gume do Infinito")));
        assertTrue(atlas.inKit("Ornn", item("Coração Congelado")));
    }

    @Test
    void anOutOfArchetypeChoiceOnlyWinsByTheMargin() {
        double[] values = {5, 4};
        boolean[] in = {false, true};
        assertEquals(1, KitUse.choose(values, new double[] {62, 60}, in, 3), "wins by 2: the kit's");
        assertEquals(0, KitUse.choose(values, new double[] {64, 60}, in, 3), "wins by 4: the discovery");
        assertEquals(0, KitUse.choose(values, new double[] {62, 60}, new boolean[] {true, true}, 3));
        assertEquals(0, KitUse.choose(new double[] {5}, new double[] {62}, new boolean[] {false}, 3), "nothing in the kit");
    }

    @Test
    void itemsWeighTheirStatsByGold() {
        KitUse.Rules r = new KitUse.Rules();
        r.stats.put("% Critical Rate", "critico");
        r.classAttack.put("Tanque", 0.3);
        KitUse.Kit k = new KitUse.Kit();
        k.classes = java.util.List.of("Tanque");
        k.attackShare = 0.3;
        k.ratios = Set.of();
        Map<String, Double> gold = new LinkedHashMap<>();
        gold.put("% Critical Rate", 1000.0);
        gold.put("Max Health", 1000.0);
        double crit = KitUse.stat(k, r, "% Critical Rate");
        assertEquals((crit + 1) / 2, KitUse.weighted(k, r, gold), 1e-9);
    }
}
