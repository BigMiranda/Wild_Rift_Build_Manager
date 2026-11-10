package com.ornnplanner.engine;

import com.ornnplanner.engine.Model.EngineInput;
import com.ornnplanner.engine.Model.ItemDef;
import com.ornnplanner.engine.Model.MinutePoint;
import com.ornnplanner.engine.Model.ReferenceData;
import com.ornnplanner.engine.Model.StatGrowth;
import com.ornnplanner.engine.Model.StatLine;
import com.ornnplanner.engine.Model.UnitProfile;
import com.ornnplanner.seed.ItemEffectCatalog;
import com.ornnplanner.seed.RuneCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.ornnplanner.engine.Stats.ARMOR;
import static com.ornnplanner.engine.Stats.MAGIC_RESIST;
import static com.ornnplanner.engine.Stats.MAX_HEALTH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Modeled item effects (efeitos_itens_7_3d.yml): effective stats with their own switch, options and stack rates. */
class ItemEffectsTest {

    private static final double EPS = 1e-6;
    private static ItemEffectCatalog effects;

    @BeforeAll
    static void load() {
        effects = new ItemEffectCatalog(new ClassPathResource("seed/efeitos_itens_7_3d.yml"),
                new ClassPathResource("seed/precos_status.yml"));
    }

    private static ItemDef item(long id, String name, StatLine... lines) {
        ItemDef i = new ItemDef();
        i.id = id;
        i.name = name;
        i.cost = 1000;
        i.section = "aprimorado";
        i.stats.addAll(List.of(lines));
        return i;
    }

    private static UnitProfile unit() {
        UnitProfile u = new UnitProfile();
        u.code = "TEST";
        u.name = "Teste";
        u.stats.put(MAX_HEALTH, new StatGrowth(1000, 0));
        u.stats.put(ARMOR, new StatGrowth(50, 0));
        u.stats.put(MAGIC_RESIST, new StatGrowth(40, 0));
        return u;
    }

    /** Totals at a minute (level 1), the items bought at minute 0 with the build's item effect choices. */
    private static Map<String, Double> totals(List<ItemDef> items, Map<String, Integer> options,
                                              Map<String, Boolean> conditional, Map<String, List<double[]>> rates,
                                              double minute) {
        ReferenceData ref = new ReferenceData();
        ref.xpTable.put(1, 0.0);
        ref.statPrices.put(MAX_HEALTH, 2.67);
        List<Long> ids = new ArrayList<>();
        for (ItemDef i : items) {
            ref.items.put(i.id, effects.apply(i, options, conditional, rates));
            ids.add(i.id);
        }
        EngineInput in = new EngineInput();
        in.unit = unit();
        in.goldPerMin = 1e6;
        in.itemIds = ids;
        in.includeConditional = false;   // the purchase's own conditional lines stay off
        in.matchEnd = minute + 1;
        Model.TimelineResult r = new TimelineEngine(ref).run(in);
        MinutePoint p = r.series.stream().filter(m -> Math.abs(m.minute - minute) < 1e-9).findFirst().orElseThrow();
        return p.total;
    }

    @Test
    void ultimateHasteIsItsOwnStat() {
        Map<String, Double> t = totals(List.of(item(1, "Convergência de Zeke", StatLine.flat(Stats.ABILITY_HASTE, 10))),
                Map.of(), Map.of(), Map.of(), 1);
        assertEquals(10, t.get("Ultimate Haste"), EPS);
        assertEquals(10, t.get(Stats.ABILITY_HASTE), EPS);   // the haste shown is not changed
    }

    @Test
    void conditionalEffectHasItsOwnSwitch() {
        ItemDef mantle = item(2, "Manto da Meia-noite", StatLine.flat(MAX_HEALTH, 600));
        // On by default even with the purchase's conditional lines off: 200 + 15% of 1600 health (level 1).
        Map<String, Double> on = totals(List.of(mantle), Map.of(), Map.of(), Map.of(), 1);
        assertEquals(200 + 0.15 * 1600, on.get("Heal"), EPS);
        assertEquals(20, on.get("% Tenacity"), EPS);
        Map<String, Double> off = totals(List.of(mantle), Map.of(),
                Map.of(ItemEffectCatalog.key("Manto da Meia-noite", "Salva-Vidas (restauração)"), false), Map.of(), 1);
        assertNull(off.get("Heal"));
        assertEquals(20, off.get("% Tenacity"), EPS);   // the other effect of the same passive keeps its own switch
    }

    @Test
    void shieldScalesWithBonusHealthAndWarmogDoesNotAmplifyIt() {
        ItemDef gargoyle = item(3, "Placa Gargolítica", StatLine.flat(MAX_HEALTH, 200));
        ItemDef warmog = item(4, "Armadura de Warmog", StatLine.flat(MAX_HEALTH, 700));
        assertEquals(100 + 0.9 * 200, totals(List.of(gargoyle), Map.of(), Map.of(), Map.of(), 1).get("Shield"), EPS);
        Map<String, Double> both = totals(List.of(gargoyle, warmog), Map.of(), Map.of(), Map.of(), 1);
        // Abençoado does not amplify shields from items (checked in game: Placa, Sterak, Eclipse, Medalhão)
        assertEquals(100 + 0.9 * 900, both.get("Shield"), EPS);
        assertEquals(1900, both.get(MAX_HEALTH), EPS);   // shields never raise the max health
    }

    @Test
    void warmogAmplifiesShieldsFromRunesAndTheChampionOnly() {
        ReferenceData ref = new ReferenceData();
        ref.xpTable.put(1, 0.0);
        ItemDef gargoyle = item(3, "Placa Gargolítica", StatLine.flat(MAX_HEALTH, 200));
        ItemDef warmog = item(4, "Armadura de Warmog", StatLine.flat(MAX_HEALTH, 700));
        for (ItemDef i : List.of(gargoyle, warmog)) {
            ref.items.put(i.id, effects.apply(i, Map.of(), Map.of(), Map.of()));
        }
        EngineInput in = new EngineInput();
        in.unit = unit();
        in.goldPerMin = 1e6;
        in.itemIds = List.of(gargoyle.id, warmog.id);
        in.includeConditional = false;
        in.matchEnd = 2.0;
        in.runes.add(item(-1, "Guardião", StatLine.flat("Shield", 100)));   // a rune's shield (held like the runes)
        MinutePoint p = new TimelineEngine(ref).run(in).series.stream().filter(m -> Math.abs(m.minute - 1) < 1e-9)
                .findFirst().orElseThrow();
        assertEquals(100 * 1.3 + (100 + 0.9 * 900), p.total.get("Shield"), EPS);
    }

    @Test
    void warmogDoesNotAmplifyShieldsOfOneDamageType() {
        // Abençoado: healing and general shields +30%, magic / physical-only shields unchanged (checked in game)
        ItemDef rookern = item(3, "Rookern Lamúrico", StatLine.flat(MAX_HEALTH, 400));
        ItemDef warmog = item(4, "Armadura de Warmog", StatLine.flat(MAX_HEALTH, 700));
        Map<String, Double> both = totals(List.of(rookern, warmog), Map.of(), Map.of(), Map.of(), 1);
        assertEquals(50 + 0.14 * 2100, both.get("Magic Shield"), EPS);
    }

    @Test
    void optionsMultiplyPerFightEffects() {
        ItemDef solari = item(5, "Medalhão dos Solari de Ferro");
        Map<String, Double> t = totals(List.of(solari),
                Map.of(ItemEffectCatalog.key("Medalhão dos Solari de Ferro", "Medalhão"), 2), Map.of(), Map.of(), 1);
        assertEquals(250, t.get("Shield"), EPS);          // level 1
        assertEquals(500, t.get("Ally Shield"), EPS);     // 2 allies
    }

    @Test
    void heartsteelGrowsWithItsStackRate() {
        ItemDef heartsteel = item(8, "Coração de Aço", StatLine.flat(MAX_HEALTH, 700));
        assertEquals(1700, totals(List.of(heartsteel), Map.of(), Map.of(), Map.of(), 1).get(MAX_HEALTH), EPS);
        // 2 strikes per minute from 0:00: 10 stacks at 5:00 -> +210 flat, then +5.25% of the total.
        Map<String, Double> t = totals(List.of(heartsteel), Map.of(), Map.of(),
                Map.of(ItemEffectCatalog.key("Coração de Aço", "Consumo Colossal"), List.of(new double[] {0, 2})), 5);
        assertEquals((1700 + 210) * 1.0525, t.get(MAX_HEALTH), EPS);
    }

    @Test
    void stasisAndReviveOfGuardianAngel() {
        Map<String, Double> t = totals(List.of(item(6, "Anjo Guardião")), Map.of(), Map.of(), Map.of(), 1);
        assertEquals(4, t.get("Stasis"), EPS);
        assertEquals(500, t.get("Heal"), EPS);   // 50% of base health
    }

    @Test
    void effectsLeaveOtherItemsUntouched() {
        ItemDef plain = item(7, "Cinto do Gigante", StatLine.flat(MAX_HEALTH, 300));
        assertTrue(effects.apply(plain, Map.of(), Map.of(), Map.of()) == plain);
        assertFalse(effects.effects("Convergência de Zeke").isEmpty());
    }

    @Test
    void stackingRunesGrowByMinuteUpToTheirLimit() {
        RuneCatalog runes = new RuneCatalog(new ClassPathResource("seed/runas_7_3d.yml"),
                new ClassPathResource("seed/feiticos_7_3d.yml"), new ClassPathResource("seed/precos_status.yml"));
        // Coleção de Olhos: 1 kill per minute -> 1.5 AD each, up to 8 stacks (12 AD).
        ItemDef eyes = runes.toItem("Coleção de Olhos", null, -1, List.of(new double[] {0, 1})).orElseThrow();
        StatLine ad = eyes.stats.stream().filter(l -> l.perMinute != null).findFirst().orElseThrow();
        assertEquals(7.5, ad.valueAt(1, 5), EPS);
        assertEquals(12, ad.valueAt(1, 20), EPS);
        // Lenda: Aceleração: 15 AH over 10 stacks; no rate, no bonus.
        assertTrue(runes.toItem("Lenda: Aceleração", null, -1).orElseThrow().stats.isEmpty());
        ItemDef haste = runes.toItem("Lenda: Aceleração", null, -1, List.of(new double[] {0, 0.5})).orElseThrow();
        assertEquals(15, haste.stats.get(0).valueAt(1, 30), EPS);
        assertEquals(7.5, haste.stats.get(0).valueAt(1, 10), EPS);
        // Húbris: 5 + 1 per kill, both conditional (30s after a kill).
        ItemDef hubris = runes.toItem("Húbris", null, -1, List.of(new double[] {0, 0.2})).orElseThrow();
        assertTrue(hubris.stats.stream().allMatch(l -> l.conditional));
        assertEquals(2, hubris.stats.stream().filter(l -> l.perMinute != null).findFirst().orElseThrow().valueAt(1, 10), EPS);
        // Colheita Sombria: the souls only count in the fights.
        assertTrue(runes.toItem("Colheita Sombria", null, -1, List.of(new double[] {0, 0.5})).orElseThrow().stats.isEmpty());
        assertEquals(5, runes.stacksAt("Colheita Sombria", List.of(new double[] {0, 0.5}), 10), EPS);
        assertEquals(8, runes.stacksAt("Coleção de Olhos", List.of(new double[] {0, 1}), 30), EPS);
        // Inabalável: 1 enemy nearby in the lane, 2 in the ganks, 3 in the team fights (3% + 2% each).
        ItemDef unshaken = runes.toItem("Inabalável", null, -1, null, null).orElseThrow();
        StatLine armor = unshaken.stats.get(0);
        assertEquals(0.05, armor.ratioAt(1, 2), EPS);
        assertEquals(0.07, armor.ratioAt(1, 10), EPS);
        assertEquals(0.09, armor.ratioAt(1, 20), EPS);
        ItemDef own = runes.toItem("Inabalável", null, -1, null, List.of(new double[] {0, 0}, new double[] {20, 3})).orElseThrow();
        assertEquals(0.03, own.stats.get(0).ratioAt(1, 10), EPS);
        assertEquals(0.09, own.stats.get(0).ratioAt(1, 25), EPS);
        assertEquals(0.07, runes.toItem("Inabalável", 2, -1).orElseThrow().stats.get(0).ratioAt(1, 25), EPS);
    }

    @Test
    void omnivampRuneAndPricesByFactor() {
        RuneCatalog runes = new RuneCatalog(new ClassPathResource("seed/runas_7_3d.yml"),
                new ClassPathResource("seed/feiticos_7_3d.yml"), new ClassPathResource("seed/precos_status.yml"));
        ItemDef linhagem = runes.toItem("Lenda: Linhagem", null, -1).orElseThrow();
        assertTrue(linhagem.stats.stream().anyMatch(l -> "% Omnivamp".equals(l.type) && l.value == 1));

        GoldPricing.StatDef ah = new GoldPricing.StatDef();
        ah.name = Stats.ABILITY_HASTE;
        ah.baseType = "exclude";
        ah.fixedPrice = 60.0;
        GoldPricing.StatDef uh = new GoldPricing.StatDef();
        uh.name = "Ultimate Haste";
        uh.alias = Stats.ABILITY_HASTE;
        uh.factor = 0.25;
        assertEquals(15, GoldPricing.computePrices(List.of(ah, uh), List.of()).asMap().get("Ultimate Haste"), EPS);
    }
}
