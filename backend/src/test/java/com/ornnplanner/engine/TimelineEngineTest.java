package com.ornnplanner.engine;

import com.ornnplanner.engine.Model.ComponentRef;
import com.ornnplanner.engine.Model.EngineInput;
import com.ornnplanner.engine.Model.ForgeTier;
import com.ornnplanner.engine.Model.ItemDef;
import com.ornnplanner.engine.Model.RefScope;
import com.ornnplanner.engine.Model.ReferenceData;
import com.ornnplanner.engine.Model.StatGrowth;
import com.ornnplanner.engine.Model.StatLine;
import com.ornnplanner.engine.Model.TimelineResult;
import com.ornnplanner.engine.Model.TimelineStep;
import com.ornnplanner.engine.Model.UnitProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import com.ornnplanner.engine.Stats;

import static com.ornnplanner.engine.Stats.ABILITY_POWER;
import static com.ornnplanner.engine.Stats.ARMOR;
import static com.ornnplanner.engine.Stats.MAGIC_RESIST;
import static com.ornnplanner.engine.Stats.MAX_HEALTH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimelineEngineTest {

    private static final double EPS = 1e-6;

    private ReferenceData ref;
    private ItemDef clothArmor;
    private ItemDef chainVest;
    private ItemDef amaranth;
    private ItemDef ruby;

    @BeforeEach
    void setUp() {
        ref = new ReferenceData();
        ref.statPrices.put(MAX_HEALTH, 3.33);
        ref.statPrices.put(ARMOR, 25.0);
        ref.statPrices.put(MAGIC_RESIST, 25.0);
        ref.statPrices.put(ABILITY_POWER, 25.0);
        ref.baseItemNames.add("Cloth Armor");
        ref.baseItemNames.add("Ruby Crystal");
        ref.forgeTiers = List.of(new ForgeTier(1, 0.07), new ForgeTier(7, 0.12), new ForgeTier(10, 0.17),
                new ForgeTier(13, 0.22));
        double[] xp = {0, 280, 660, 1140, 1720, 2400, 3180, 4060, 5040, 6120, 7300, 8580, 9960, 11440, 13020};
        for (int i = 0; i < xp.length; i++) {
            ref.xpTable.put(i + 1, xp[i]);
        }

        clothArmor = item(1, "Cloth Armor", 500, StatLine.flat(ARMOR, 20));
        ruby = item(2, "Ruby Crystal", 500, StatLine.flat(MAX_HEALTH, 150));
        chainVest = item(3, "Chain Vest", 900, StatLine.flat(ARMOR, 40));
        chainVest.components.add(new ComponentRef(clothArmor.id, 1));

        StatLine armorPassive = StatLine.percent(ARMOR, "Endurance", 0.3, ARMOR, RefScope.TOTAL);
        armorPassive.value = 15.0; // reference data's static value
        StatLine mrPassive = StatLine.percent(MAGIC_RESIST, "Endurance", 0.3, MAGIC_RESIST, RefScope.TOTAL);
        mrPassive.value = 15.0;
        amaranth = item(4, "Amaranth's Twinguard (Endurance)", 3200,
                StatLine.flat(MAX_HEALTH, 300), StatLine.flat(ARMOR, 50), StatLine.flat(MAGIC_RESIST, 50),
                armorPassive, mrPassive);
    }

    private ItemDef item(long id, String name, int cost, StatLine... stats) {
        ItemDef i = new ItemDef();
        i.id = id;
        i.name = name;
        i.cost = cost;
        i.category = "TEST";
        i.stats.addAll(List.of(stats));
        ref.items.put(id, i);
        return i;
    }

    private static UnitProfile ornn() {
        UnitProfile u = new UnitProfile();
        u.code = "ORNN";
        u.name = "Ornn";
        u.livingForge = true;
        u.stats.put(MAX_HEALTH, new StatGrowth(720, 120));
        u.stats.put(ARMOR, new StatGrowth(46, 5));
        u.stats.put(MAGIC_RESIST, new StatGrowth(40, 2));
        return u;
    }

    private static UnitProfile ragdoll(double armor) {
        UnitProfile u = new UnitProfile();
        u.code = "RAGDOLL";
        u.name = "Boneco";
        u.livingForge = false;
        u.stats.put(ARMOR, new StatGrowth(armor, 0));
        return u;
    }

    private TimelineResult run(UnitProfile unit, double gpm, double xpm, Long... ids) {
        EngineInput in = new EngineInput();
        in.unit = unit;
        in.goldPerMin = gpm;
        in.xpPerMin = xpm;
        in.itemIds = List.of(ids);
        return new TimelineEngine(ref).run(in);
    }

    @Test
    void itemWithoutPassive() {
        TimelineStep s = run(ornn(), 400, 0, clothArmor.id).steps.get(0);

        assertEquals(0.0, s.minute, EPS, "500 starting gold covers a 500 gold item at minute 0");
        assertEquals(1, s.level);
        // 46 base + 20 item + 7% Living Forge on the 20 bonus
        assertEquals(46 + 20 + 20 * 0.07, s.stats.total.get(ARMOR), EPS);
        assertEquals(100.0, s.efficiency.dynamicPct, EPS);
        assertEquals(100.0, s.efficiency.staticPct, EPS);
        assertTrue(s.stats.passives.isEmpty());
    }

    @Test
    void purchaseMinuteAndLevelFromGoldAndXp() {
        // (3200 - 500) / 100 = 27 min; 27 * 300 = 8100 XP -> level 11 (7300 <= 8100 < 8580)
        TimelineStep s = run(ornn(), 100, 300, amaranth.id).steps.get(0);
        assertEquals(27.0, s.minute, EPS);
        assertEquals(11, s.level);
        assertEquals(3200, s.cumulativeGold);
    }

    @Test
    void amaranthPassiveUsesTheWholeBuildNotOnlyItsOwnStats() {
        TimelineResult r = run(ornn(), 1000, 0, clothArmor.id, amaranth.id);
        TimelineStep s = r.steps.get(1);
        assertEquals(1, s.level);

        double flatArmor = 20 + 50;
        double armorPassive = 0.3 * (46 + flatArmor);          // base + all item armor
        double mrPassive = 0.3 * (40 + 50);
        assertEquals(armorPassive, s.stats.itemPassives.get(ARMOR), EPS);
        assertEquals(mrPassive, s.stats.itemPassives.get(MAGIC_RESIST), EPS);

        double forgeArmor = (flatArmor + armorPassive) * 0.07; // Living Forge last, bonus only
        assertEquals(forgeArmor, s.stats.forge.get(ARMOR), EPS);
        assertEquals(46 + flatArmor + armorPassive + forgeArmor, s.stats.total.get(ARMOR), EPS);

        // static: passive only on the item's own 50 armor / 50 MR -> reference site value
        assertEquals(132.78, s.efficiency.staticPct, 0.005);
        double dynamicWorth = 300 * 3.33 + 50 * 25 + 50 * 25 + armorPassive * 25 + mrPassive * 25;
        assertEquals(dynamicWorth / 3200 * 100, s.efficiency.dynamicPct, EPS);
    }

    @Test
    void bonusScopeIgnoresUnitBaseStats() {
        amaranth.stats.get(3).refScope = RefScope.BONUS;
        TimelineStep s = run(ornn(), 1000, 0, amaranth.id).steps.get(0);
        assertEquals(0.3 * 50, s.stats.itemPassives.get(ARMOR), EPS);
    }

    @Test
    void percentagePassivesComposeInPurchaseOrder() {
        StatLine a = StatLine.percent(ABILITY_POWER, "A", 0.3, ABILITY_POWER, RefScope.TOTAL);
        StatLine b = StatLine.percent(ABILITY_POWER, "B", 0.1, ABILITY_POWER, RefScope.TOTAL);
        ItemDef first = item(10, "First", 1000, StatLine.flat(ABILITY_POWER, 100), a);
        ItemDef second = item(11, "Second", 1000, StatLine.flat(ABILITY_POWER, 100), b);

        TimelineStep s = run(ragdoll(0), 1000, 0, first.id, second.id).steps.get(1);
        // First: 30% of the 200 flat AP (does not see Second's passive, bought later)
        assertEquals(60.0, s.stats.passives.get(0).value, EPS);
        // Second: 10% of 200 flat + 60 from First's passive (bought earlier)
        assertEquals(26.0, s.stats.passives.get(1).value, EPS);
        assertEquals(286.0, s.stats.total.get(ABILITY_POWER), EPS);

        TimelineStep swapped = run(ragdoll(0), 1000, 0, second.id, first.id).steps.get(1);
        assertEquals(20.0, swapped.stats.passives.get(0).value, EPS);   // Second first: 10% of 200
        assertEquals(66.0, swapped.stats.passives.get(1).value, EPS);   // First: 30% of 200 + 20
        assertEquals(286.0, swapped.stats.total.get(ABILITY_POWER), EPS);
    }

    @Test
    void livingForgeTiers() {
        TimelineEngine engine = new TimelineEngine(ref);
        assertEquals(0.07, engine.forgePct(1), EPS);
        assertEquals(0.07, engine.forgePct(6), EPS);
        assertEquals(0.12, engine.forgePct(7), EPS);
        assertEquals(0.12, engine.forgePct(9), EPS);
        assertEquals(0.17, engine.forgePct(10), EPS);
        assertEquals(0.17, engine.forgePct(12), EPS);
        assertEquals(0.22, engine.forgePct(13), EPS);
        assertEquals(0.22, engine.forgePct(15), EPS);
    }

    @Test
    void livingForgeAtDifferentLevels() {
        // Ruby at minute 0, then a zero-stat item whose cost moves the clock to the wanted level.
        int[][] cases = {{1, 0}, {7, 3180}, {10, 6120}, {13, 9960}};
        double[] pct = {0.07, 0.12, 0.17, 0.22};
        for (int c = 0; c < cases.length; c++) {
            int level = cases[c][0];
            int xpNeeded = cases[c][1];
            // 1000 gpm, 1000 xpm: minute m reaches xp 1000m. Filler cost = 1000 * xpNeeded/1000 = xpNeeded.
            ItemDef filler = item(30 + c, "Filler" + c, xpNeeded, StatLine.flat(MAGIC_RESIST, 0));
            TimelineStep s = run(ornn(), 1000, 1000, ruby.id, filler.id).steps.get(1);
            assertEquals(level, s.level, "level for case " + c);
            double base = 720 + 120 * (level - 1);
            assertEquals(150 * pct[c], s.stats.forge.get(MAX_HEALTH), EPS);
            assertEquals(base + 150 + 150 * pct[c], s.stats.total.get(MAX_HEALTH), EPS);
        }
    }

    @Test
    void ragdollHasNoLivingForge() {
        TimelineStep s = run(ragdoll(30), 1000, 0, clothArmor.id).steps.get(0);
        assertEquals(50.0, s.stats.total.get(ARMOR), EPS);
        assertTrue(s.stats.forgeDetails.isEmpty());
    }

    @Test
    void componentsAreConsumedAndDiscounted() {
        TimelineResult r = run(ornn(), 100, 0, clothArmor.id, chainVest.id);
        TimelineStep vest = r.steps.get(1);
        assertEquals(400, vest.paidCost);
        assertEquals(900, vest.cumulativeGold);
        assertEquals(List.of("Cloth Armor"), vest.consumedComponents);
        assertEquals(List.of("Chain Vest"), vest.inventory);
        assertEquals(4.0, vest.minute, EPS);
        assertEquals(46 + 40 + 40 * 0.07, vest.stats.total.get(ARMOR), EPS);
    }

    @Test
    void seriesCoversEveryMinuteUntilAfterLastPurchase() {
        TimelineResult r = run(ornn(), 100, 0, clothArmor.id, chainVest.id);
        assertEquals(0.0, r.series.get(0).minute, EPS);
        assertTrue(r.series.get(r.series.size() - 1).minute >= 7);
        assertTrue(r.series.stream().anyMatch(p -> p.stepIndex == 1));
    }

    @Test
    void bonusScopeConditionalPassiveLikeTheShopDuplaguarda() {
        StatLine armor = StatLine.percent(ARMOR, "Tolerância", 0.3, ARMOR, RefScope.BONUS);
        armor.conditional = true;
        ItemDef twinguard = item(40, "Duplaguarda de Amaranto", 3200, StatLine.flat(ARMOR, 50), armor);

        TimelineStep on = run(ornn(), 1000, 0, clothArmor.id, twinguard.id).steps.get(1);
        assertEquals(0.3 * (20 + 50), on.stats.itemPassives.get(ARMOR), EPS); // only bonus armor, never the base 46
        assertTrue(on.stats.passives.get(0).conditional);

        EngineInput in = new EngineInput();
        in.unit = ornn();
        in.goldPerMin = 1000;
        in.itemIds = List.of(clothArmor.id, twinguard.id);
        in.includeConditional = false;
        TimelineStep off = new TimelineEngine(ref).run(in).steps.get(1);
        assertTrue(off.stats.passives.isEmpty());
        assertEquals(46 + 70 + 70 * 0.07, off.stats.total.get(ARMOR), EPS);
    }

    @Test
    void baseScopeUsesOnlyTheUnitBaseStat() {
        UnitProfile u = ornn();
        u.stats.put(Stats.ATTACK_DAMAGE, new StatGrowth(62, 4));
        ItemDef sword = item(41, "Espada", 500, StatLine.flat(Stats.ATTACK_DAMAGE, 12));
        ItemDef sterak = item(42, "Sinal de Sterak", 3200,
                StatLine.percent(Stats.ATTACK_DAMAGE, "Severidade", 0.5, Stats.ATTACK_DAMAGE, RefScope.BASE));
        TimelineStep s = run(u, 1000, 0, sword.id, sterak.id).steps.get(1);
        assertEquals(31.0, s.stats.itemPassives.get(Stats.ATTACK_DAMAGE), EPS); // 50% of 62, ignores the +12 bonus
    }

    @Test
    void levelRangeIsInterpolatedAndConditionalFlatCanBeTurnedOff() {
        StatLine lifeline = StatLine.flat(MAX_HEALTH, 200);
        lifeline.valueMax = 300.0;
        lifeline.conditional = true;
        lifeline.passive = "Salva-Vidas";
        assertEquals(200.0, lifeline.valueAt(1), EPS);
        assertEquals(250.0, lifeline.valueAt(8), EPS);
        assertEquals(300.0, lifeline.valueAt(15), EPS);

        ItemDef mantle = item(43, "Manto da Meia-noite", 2550, StatLine.flat(MAX_HEALTH, 600), lifeline);
        // 2550 gold at 1000/min with 3180 XP/min -> minute 2.05 -> 6519 XP -> level 10
        TimelineStep s = run(ornn(), 1000, 3180, mantle.id).steps.get(0);
        assertEquals(10, s.level);
        double expected = 200 + 100 * 9 / 14.0;
        assertEquals(600 + expected, s.stats.itemFlat.get(MAX_HEALTH), EPS);
    }
}
