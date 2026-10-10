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

/** Modeled item effects (efeitos_itens_7_3.yml): effective stats with their own switch, options and stack rates. */
class ItemEffectsTest {

    private static final double EPS = 1e-6;
    private static ItemEffectCatalog effects;

    @BeforeAll
    static void load() {
        effects = new ItemEffectCatalog(new ClassPathResource("seed/efeitos_itens_7_3.yml"),
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
    void shieldScalesWithBonusHealthAndWarmogAmplifiesIt() {
        ItemDef gargoyle = item(3, "Placa Gargolítica", StatLine.flat(MAX_HEALTH, 200));
        ItemDef warmog = item(4, "Armadura de Warmog", StatLine.flat(MAX_HEALTH, 700));
        assertEquals(100 + 0.9 * 200, totals(List.of(gargoyle), Map.of(), Map.of(), Map.of(), 1).get("Shield"), EPS);
        Map<String, Double> both = totals(List.of(gargoyle, warmog), Map.of(), Map.of(), Map.of(), 1);
        assertEquals((100 + 0.9 * 900) * 1.3, both.get("Shield"), EPS);
        assertEquals(1900, both.get(MAX_HEALTH), EPS);   // shields never raise the max health
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
    void omnivampRuneAndPricesByFactor() {
        RuneCatalog runes = new RuneCatalog(new ClassPathResource("seed/runas_7_3.yml"),
                new ClassPathResource("seed/feiticos_7_3.yml"), new ClassPathResource("seed/precos_status.yml"));
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
