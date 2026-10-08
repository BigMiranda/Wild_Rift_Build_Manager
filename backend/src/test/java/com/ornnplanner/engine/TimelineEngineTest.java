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
        double armorPassive = 0.3 * (46 + flatArmor * 1.07);   // base + all bonus armor, Living Forge included
        double mrPassive = 0.3 * (40 + 50 * 1.07);
        assertEquals(armorPassive, s.stats.itemPassives.get(ARMOR), EPS);
        assertEquals(mrPassive, s.stats.itemPassives.get(MAGIC_RESIST), EPS);

        double forgeArmor = flatArmor * 0.07; // a % of total buff is not amplified by the Forge
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
        assertEquals(0.3 * 50 * 1.07, s.stats.itemPassives.get(ARMOR), EPS); // bonus armor with Living Forge
    }

    @Test
    void multipliersOfTheSameStatAddUpAndPurchaseOrderDoesNotMatter() {
        StatLine a = StatLine.percent(ABILITY_POWER, "A", 0.3, ABILITY_POWER, RefScope.TOTAL);
        StatLine b = StatLine.percent(ABILITY_POWER, "B", 0.1, ABILITY_POWER, RefScope.TOTAL);
        ItemDef first = item(10, "First", 1000, StatLine.flat(ABILITY_POWER, 100), a);
        ItemDef second = item(11, "Second", 1000, StatLine.flat(ABILITY_POWER, 100), b);

        // Each multiplier is a % of the 200 AP before multipliers; they add (200 x 1.4), never compound.
        TimelineStep s = run(ragdoll(0), 1000, 0, first.id, second.id).steps.get(1);
        assertEquals(60.0, s.stats.passives.get(0).value, EPS);
        assertEquals(20.0, s.stats.passives.get(1).value, EPS);
        assertEquals(280.0, s.stats.total.get(ABILITY_POWER), EPS);

        TimelineStep swapped = run(ragdoll(0), 1000, 0, second.id, first.id).steps.get(1);
        assertEquals(280.0, swapped.stats.total.get(ABILITY_POWER), EPS);
    }

    @Test
    void tankItemsGiveTheSameResistancesInAnyOrder() {
        StatLine twinArmor = StatLine.percent(ARMOR, "Tolerância", 0.3, ARMOR, RefScope.BONUS);
        StatLine twinMr = StatLine.percent(MAGIC_RESIST, "Tolerância", 0.3, MAGIC_RESIST, RefScope.BONUS);
        StatLine dawnArmor = StatLine.percent(ARMOR, "Emissário da Aurora", 0.2, ARMOR, RefScope.TOTAL);
        StatLine dawnMr = StatLine.percent(MAGIC_RESIST, "Emissário da Aurora", 0.2, MAGIC_RESIST, RefScope.TOTAL);
        ItemDef twin = item(12, "Duplaguarda", 3200, StatLine.flat(ARMOR, 50), StatLine.flat(MAGIC_RESIST, 50), twinArmor, twinMr);
        ItemDef dawn = item(13, "Manto da Aurora", 2700, StatLine.flat(ARMOR, 50), StatLine.flat(MAGIC_RESIST, 30), dawnArmor, dawnMr);

        TimelineStep ab = run(ornn(), 1000, 0, twin.id, dawn.id).steps.get(1);
        TimelineStep ba = run(ornn(), 1000, 0, dawn.id, twin.id).steps.get(1);
        double forged = 100 * 1.07;                          // (50 + 50) with Living Forge
        double totalAtActivation = 46 + forged + 0.3 * forged * 1.07;
        double dawnGain = 0.2 * totalAtActivation;           // snapshot buff, not amplified by the Forge
        double twinGain = 0.3 * (forged + dawnGain);         // continuous, sees the Aurora buff
        double expected = 46 + forged + dawnGain + twinGain * 1.07;
        assertEquals(expected, ab.stats.total.get(ARMOR), EPS);
        assertEquals(expected, ba.stats.total.get(ARMOR), EPS);
        assertEquals(ab.stats.total.get(MAGIC_RESIST), ba.stats.total.get(MAGIC_RESIST), EPS);
    }

    @Test
    void conversionsReadTheFinalValueOfTheirSourceStat() {
        UnitProfile u = ornn();
        u.stats.put(Stats.MAX_MANA, new StatGrowth(380, 0));
        u.stats.put(Stats.ATTACK_DAMAGE, new StatGrowth(62, 0));
        // Mana -> Health (15% of total mana), then bonus Health -> AD (2.5%), with Living Forge on bonus health.
        ItemDef winters = item(14, "Aproximação Invernal", 2600, StatLine.flat(MAX_HEALTH, 500),
                StatLine.flat(Stats.MAX_MANA, 500),
                StatLine.percent(MAX_HEALTH, "Fascínio", 0.15, Stats.MAX_MANA, RefScope.TOTAL));
        ItemDef bloodmail = item(15, "Armadura Sangrenta", 3200, StatLine.flat(MAX_HEALTH, 450),
                StatLine.percent(Stats.ATTACK_DAMAGE, "Tirania", 0.025, MAX_HEALTH, RefScope.BONUS));

        double mana = 380 + 500;
        double healthPre = 500 + 450 + 0.15 * mana;     // flat + Mana conversion
        double bonusHealth = healthPre * 1.07;           // + Living Forge (level 1)
        double ad = 62 + 0.025 * bonusHealth;
        for (Long[] order : new Long[][] {{winters.id, bloodmail.id}, {bloodmail.id, winters.id}}) {
            TimelineStep s = run(u, 1000, 0, order).steps.get(1);
            assertEquals(720 + bonusHealth, s.stats.total.get(MAX_HEALTH), EPS);
            assertEquals(ad, s.stats.total.get(Stats.ATTACK_DAMAGE), EPS);
        }
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
        assertEquals(0.3 * (20 + 50) * 1.07, on.stats.itemPassives.get(ARMOR), EPS); // bonus armor only, never the base
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

    @Test
    void conditionalEffectsAreChosenPerPurchase() {
        StatLine armor = StatLine.percent(ARMOR, "Tolerância", 0.3, ARMOR, RefScope.BONUS);
        armor.conditional = true;
        ItemDef twinguard = item(50, "Duplaguarda de Amaranto", 3200, StatLine.flat(ARMOR, 50), armor);

        EngineInput in = new EngineInput();
        in.unit = ornn();
        in.goldPerMin = 1000;
        in.itemIds = List.of(twinguard.id, twinguard.id);
        in.conditional = java.util.Arrays.asList(true, false);
        TimelineStep s = new TimelineEngine(ref).run(in).steps.get(1);

        assertEquals(1, s.stats.passives.size());              // only the first copy's passive counts
        assertEquals(0, s.stats.passives.get(0).purchaseIndex);
        assertEquals(0.3 * 100 * 1.07, s.stats.passives.get(0).value, EPS);
        assertEquals(2, s.stats.contributions.size());
        assertTrue(s.stats.contributions.get(0).conditionalIncluded);
        assertEquals(0.3 * 100 * 1.07 * 25.0, s.stats.contributions.get(0).passiveGold, EPS);
        assertEquals(0.0, s.stats.contributions.get(1).passiveGold, EPS);
    }

    // ------------------------------------------------------------------ shop rules

    private ItemDef shopItem(long id, String name, String section, String... groups) {
        ItemDef i = item(id, name, 1000, StatLine.flat(ARMOR, 10));
        i.section = section;
        i.tabs = new java.util.ArrayList<>(List.of("Defesa"));
        i.exclusiveGroups = new java.util.ArrayList<>(List.of(groups));
        return i;
    }

    private List<Model.Violation> lastViolations(Long... ids) {
        TimelineResult r = run(ornn(), 1000, 0, ids);
        return r.steps.get(r.steps.size() - 1).violations;
    }

    @Test
    void completedItemsCannotBeRepeatedButComponentsCan() {
        ItemDef finished = shopItem(60, "Coração de Aço", "aprimorado");
        ItemDef component = shopItem(61, "Cinto do Gigante", "tier_medio");
        assertEquals("duplicate", lastViolations(finished.id, finished.id).get(0).code);
        assertTrue(lastViolations(component.id, component.id).isEmpty());
    }

    @Test
    void oneItemPerExclusiveGroupUnlessTheComponentIsConsumed() {
        ItemDef tear = shopItem(62, "Lágrima da Deusa", "basico", "Lágrima da Deusa");
        ItemDef manamune = shopItem(63, "Manamune", "aprimorado", "Lágrima da Deusa");
        ItemDef winters = shopItem(64, "Aproximação Invernal", "aprimorado", "Lágrima da Deusa");
        manamune.components.add(new ComponentRef(tear.id, 1));

        assertTrue(lastViolations(tear.id, manamune.id).isEmpty()); // the Tear is consumed by the recipe
        Model.Violation v = lastViolations(manamune.id, winters.id).get(0);
        assertEquals("exclusive", v.code);
        assertEquals("Lágrima da Deusa", v.group);
        assertEquals(List.of("Manamune", "Aproximação Invernal"), v.items);
    }

    @Test
    void onlyOneActiveItem() {
        ItemDef locket = shopItem(65, "Medalhão", "aprimorado");
        ItemDef zhonya = shopItem(66, "Ampulheta", "aprimorado");
        locket.active = true;
        zhonya.active = true;
        assertEquals("active", lastViolations(locket.id, zhonya.id).get(0).code);
    }

    @Test
    void oneBootsAndFiveOtherItems() {
        ItemDef boots1 = shopItem(67, "Botas Galvanizadas", "tier_medio");
        ItemDef boots2 = shopItem(68, "Passos de Mercúrio", "tier_medio");
        boots1.tabs = new java.util.ArrayList<>(List.of("Botas"));
        boots2.tabs = new java.util.ArrayList<>(List.of("Botas"));
        TimelineResult twoBoots = run(ornn(), 1000, 0, boots1.id, boots2.id);
        assertEquals(1, twoBoots.steps.size());                   // the second boots is left out...
        assertEquals("boots", twoBoots.ignored.get(0).violations.get(0).code);
        assertEquals(1, twoBoots.ignored.get(0).index);

        Long[] six = new Long[7];
        six[0] = boots1.id;
        for (int k = 0; k < 6; k++) {
            six[k + 1] = shopItem(70 + k, "Item " + k, "aprimorado").id;
        }
        TimelineResult r = run(ornn(), 1000, 0, six);
        assertTrue(r.steps.get(5).violations.isEmpty());          // boots + 5 items: a full, legal inventory
        assertEquals(6, r.steps.size());                          // the sixth item has no slot: not counted
        TimelineStep extra = r.ignored.get(0);
        assertTrue(extra.ignored);
        assertEquals(6, extra.index);
        assertEquals("slots", extra.violations.get(0).code);
        assertEquals(6, extra.inventoryIds.size());               // inventory unchanged
        assertEquals(r.steps.get(5).stats.total.get(ARMOR), r.series.get(r.series.size() - 1).total.get(ARMOR), EPS);
        assertEquals(6000, r.steps.get(5).cumulativeGold);
    }

    // ------------------------------------------------------------------ assumed components

    private TimelineResult runAssuming(boolean half, boolean small, Long... ids) {
        EngineInput in = new EngineInput();
        in.unit = ornn();
        in.goldPerMin = 100;
        in.xpPerMin = 0;
        in.itemIds = List.of(ids);
        in.assumeHalfItems = half;
        in.assumeSmallItems = small;
        return new TimelineEngine(ref).run(in);
    }

    @Test
    void assumedComponentsAreBoughtBeforeTheItemAsGoldAllows() {
        // Final (1000) = Half (600 = 2x Small 200 + 200) + Small (200) + 200.
        ItemDef small = shopItem(90, "Pequeno", "basico");
        small.cost = 200;
        ItemDef half = shopItem(91, "Meio", "tier_medio");
        half.cost = 600;
        half.components.add(new ComponentRef(small.id, 2));
        ItemDef fin = shopItem(92, "Final", "aprimorado");
        fin.cost = 1000;
        fin.components.add(new ComponentRef(half.id, 1));
        fin.components.add(new ComponentRef(small.id, 1));

        TimelineResult none = runAssuming(false, false, fin.id);
        assertEquals(1, none.steps.size());

        TimelineResult both = runAssuming(true, true, fin.id);
        List<String> order = new java.util.ArrayList<>();
        for (TimelineStep s : both.steps) {
            order.add(s.itemName + (s.implied ? "*" : ""));
            assertEquals(0, s.buildIndex);
        }
        assertEquals(List.of("Pequeno*", "Pequeno*", "Meio*", "Pequeno*", "Final"), order);
        assertEquals(1000, both.steps.get(4).cumulativeGold);     // same total gold, spread over time
        assertEquals(200, both.steps.get(4).paidCost);            // only the recipe cost is left
        assertEquals(0.0, both.steps.get(1).minute, EPS);         // 400 gold: covered by the 500 starting gold
        assertEquals(1.0, both.steps.get(2).minute, EPS);         // Meio completes at 600 gold
        assertEquals(5.0, both.steps.get(4).minute, EPS);         // the item itself still lands at 1000 gold
        assertEquals(List.of("Final"), both.steps.get(4).inventory);

        TimelineResult halfOnly = runAssuming(true, false, fin.id);
        assertEquals(List.of("Meio", "Final"), List.of(halfOnly.steps.get(0).itemName, halfOnly.steps.get(1).itemName));
        assertEquals(600, halfOnly.steps.get(0).paidCost);

        TimelineResult smallOnly = runAssuming(false, true, fin.id);
        assertEquals(4, smallOnly.steps.size());                  // three Pequeno, then Final consumes them all
        assertEquals(400, smallOnly.steps.get(3).paidCost);

        // Components already owned are not bought again.
        TimelineResult owned = runAssuming(true, true, half.id, fin.id);
        assertEquals(1, owned.steps.stream().filter(s -> s.buildIndex == 1 && s.implied).count());
    }

    @Test
    void assumedComponentsWithoutAFreeSlotAreSkipped() {
        ItemDef small = shopItem(93, "Pequeno", "basico");
        ItemDef fin = shopItem(94, "Final", "aprimorado");
        fin.components.add(new ComponentRef(small.id, 2));
        Long[] ids = new Long[5];
        for (int k = 0; k < 4; k++) {
            ids[k] = shopItem(95 + k, "Item " + k, "aprimorado").id;
        }
        ids[4] = fin.id;
        TimelineResult r = runAssuming(false, true, ids);
        // 4 items + 1 Pequeno fills the five slots; the second Pequeno has no room and is bought with the item.
        assertEquals(6, r.steps.size());
        assertTrue(r.ignored.isEmpty());
        assertEquals(5, r.steps.get(5).inventory.size());
    }

    // ------------------------------------------------------------------ purchase moments

    @Test
    void itemsAfterAPurchaseMomentWaitForItAndTheGoldStaysInTheBag() {
        // Sequence: A (1000) | moment at 10:00 | B (1000) | moment 3 min after the previous purchase | C (1000)
        ItemDef a = shopItem(110, "A", "aprimorado");
        ItemDef b = shopItem(111, "B", "aprimorado");
        ItemDef c = shopItem(112, "C", "aprimorado");
        EngineInput in = new EngineInput();
        in.unit = ornn();
        in.goldPerMin = 400;
        in.xpPerMin = 0;
        in.itemIds = List.of(a.id, b.id, c.id);
        in.positions = List.of(0, 2, 4);
        in.moments = List.of(new Model.Moment(1, 10.0, null), new Model.Moment(3, null, 3.0));
        TimelineResult r = new TimelineEngine(ref).run(in);

        assertEquals(1.25, r.steps.get(0).minute, EPS);           // A: as soon as the gold covers it
        assertEquals(0.0, r.steps.get(0).goldLeft, EPS);
        assertEquals(10.0, r.steps.get(1).minute, EPS);           // B: gold at 3:45, but it waits for the 10:00 moment
        assertEquals(500 + 400 * 10.0 - 2000, r.steps.get(1).goldLeft, EPS);
        assertEquals(2, r.steps.get(1).buildIndex);
        assertEquals(10.0, r.moments.get(0).minute, EPS);
        assertEquals(13.0, r.moments.get(1).minute, EPS);         // 3 minutes after B
        assertEquals(500 + 400 * 13.0 - 2000, r.moments.get(1).gold, EPS);
        assertEquals(500 + 400 * 10.0 - 1000, r.moments.get(0).gold, EPS);
        assertEquals(13.0, r.steps.get(2).minute, EPS);
        assertEquals(500 + 400 * 13.0 - 3000, r.steps.get(2).goldLeft, EPS);

        in.goldPerMin = 100;                                       // not enough gold at the moment: bought when it is
        TimelineResult poor = new TimelineEngine(ref).run(in);
        assertEquals(15.0, poor.steps.get(1).minute, EPS);
        assertEquals(0.0, poor.steps.get(1).goldLeft, EPS);
    }

    @Test
    void matchesTheInGameTankBuildAtLevel15() {
        // "Soldado v1" (05/10/2026): Desespero Eterno, Armadura de Espinhos, Máscara Abissal, Manto da Aurora, Duplaguarda de
        // Amaranto, Esmagadores Acorrentados -> 215 flat armor / 195 flat MR. In-game tooltips at level 15 (base 116
        // armor / 68 MR, Living Forge 22%): 379 / 306 without passives, 475 / 393 with Duplaguarda stacked,
        // 603 / 499 with Duplaguarda + Manto da Aurora.
        UnitProfile u = ornn();
        u.stats.put(ARMOR, new StatGrowth(116, 0));
        u.stats.put(MAGIC_RESIST, new StatGrowth(68, 0));
        double[] xp = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14};
        for (int k = 0; k < xp.length; k++) {
            ref.xpTable.put(k + 1, xp[k]);
        }
        StatLine twinA = StatLine.percent(ARMOR, "Tolerância", 0.3, ARMOR, RefScope.BONUS);
        StatLine twinM = StatLine.percent(MAGIC_RESIST, "Tolerância", 0.3, MAGIC_RESIST, RefScope.BONUS);
        StatLine dawnA = StatLine.percent(ARMOR, "Emissário da Aurora", 0.2, ARMOR, RefScope.TOTAL);
        StatLine dawnM = StatLine.percent(MAGIC_RESIST, "Emissário da Aurora", 0.2, MAGIC_RESIST, RefScope.TOTAL);
        for (StatLine l : List.of(twinA, twinM, dawnA, dawnM)) {
            l.conditional = true;                          // stacks / immobilize: switched per purchase below
        }
        ItemDef others = item(80, "Outros quatro itens", 600, StatLine.flat(ARMOR, 115), StatLine.flat(MAGIC_RESIST, 115));
        ItemDef twin = item(81, "Duplaguarda", 0, StatLine.flat(ARMOR, 50), StatLine.flat(MAGIC_RESIST, 50), twinA, twinM);
        ItemDef dawn = item(82, "Manto da Aurora", 0, StatLine.flat(ARMOR, 50), StatLine.flat(MAGIC_RESIST, 30), dawnA, dawnM);

        // cases: no passives, Duplaguarda, Duplaguarda + Aurora (Aurora triggered after the stacks), Aurora only
        double[][] expected = {{379, 306}, {475, 393}, {603, 499}, {454, 368}};
        boolean[][] active = {{false, false}, {true, false}, {true, true}, {false, true}};
        for (int c = 0; c < expected.length; c++) {
            EngineInput in = new EngineInput();
            in.unit = u;
            in.goldPerMin = 1000;
            in.xpPerMin = 1000;                            // level 15 from the first minute
            in.itemIds = List.of(others.id, twin.id, dawn.id);
            in.conditional = java.util.Arrays.asList(true, active[c][0], active[c][1]);
            TimelineStep s = new TimelineEngine(ref).run(in).steps.get(2);
            assertEquals(15, s.level);
            assertEquals(expected[c][0], s.stats.total.get(ARMOR), 1.5, "armor, case " + c);
            assertEquals(expected[c][1], s.stats.total.get(MAGIC_RESIST), 1.5, "MR, case " + c);
        }

        // Same build with the armor boots (Mobilização Blindada: +30 armor instead of +30 MR), measured 08/10/2026:
        // 415 / 270 nothing, 525 / 343 Duplaguarda, 498 / 324 Aurora, 666 / 436 both, and 711 / 465 both + rune
        // Inabalável with 2 enemy champions nearby (3% + 2 x 2% of bonus armor / MR).
        ItemDef armorOthers = item(83, "Outros quatro itens (botas de armadura)", 600, StatLine.flat(ARMOR, 145),
                StatLine.flat(MAGIC_RESIST, 85));
        double[][] armorBoots = {{415, 270}, {525, 343}, {666, 436}, {498, 324}};
        com.ornnplanner.seed.RuneCatalog runes = new com.ornnplanner.seed.RuneCatalog(
                new org.springframework.core.io.ClassPathResource("seed/runas_7_3.yml"),
                new org.springframework.core.io.ClassPathResource("seed/feiticos_7_3.yml"),
                new org.springframework.core.io.ClassPathResource("seed/precos_status.yml"));
        for (int c = 0; c <= armorBoots.length; c++) {
            boolean withRune = c == armorBoots.length;
            int a = withRune ? 2 : c;
            EngineInput in = new EngineInput();
            in.unit = u;
            in.goldPerMin = 1000;
            in.xpPerMin = 1000;
            in.itemIds = List.of(armorOthers.id, twin.id, dawn.id);
            in.conditional = java.util.Arrays.asList(true, active[a][0], active[a][1]);
            if (withRune) {
                in.runes.add(runes.toItem("Inabalável", 2, -1).orElseThrow());
            }
            TimelineStep s = new TimelineEngine(ref).run(in).steps.get(2);
            double[] want = withRune ? new double[] {711, 465} : armorBoots[c];
            assertEquals(want[0], s.stats.total.get(ARMOR), 2, "armor boots, case " + c);
            assertEquals(want[1], s.stats.total.get(MAGIC_RESIST), 2, "armor boots MR, case " + c);
        }
    }
}
