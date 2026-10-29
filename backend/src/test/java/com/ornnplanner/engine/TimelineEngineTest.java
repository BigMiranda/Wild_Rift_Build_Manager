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
        assertEquals(2, twoBoots.steps.size());                   // other boots: a swap, never two pairs
        assertEquals("Botas Galvanizadas", twoBoots.steps.get(1).swappedOut);
        assertEquals(List.of(boots2.id), twoBoots.steps.get(1).inventoryIds);
        assertTrue(twoBoots.ignored.isEmpty());

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

    // ------------------------------------------------------------------ game time: runes that scale, extra gold

    private com.ornnplanner.seed.RuneCatalog runeCatalog() {
        return new com.ornnplanner.seed.RuneCatalog(
                new org.springframework.core.io.ClassPathResource("seed/runas_7_3d.yml"),
                new org.springframework.core.io.ClassPathResource("seed/feiticos_7_3d.yml"),
                new org.springframework.core.io.ClassPathResource("seed/precos_status.yml"));
    }

    @Test
    void runesThatScaleWithGameTime() {
        com.ornnplanner.seed.RuneCatalog runes = runeCatalog();
        ItemDef cheap = item(120, "Barato", 100, StatLine.flat(ARMOR, 1));
        EngineInput in = new EngineInput();
        in.unit = ornn();
        in.unit.stats.put(MAX_HEALTH, new StatGrowth(1000, 0));
        in.unit.stats.put(Stats.ATTACK_DAMAGE, new StatGrowth(0, 0));
        in.unit.livingForge = false;                              // (the Forge would amplify the rune's health too)
        in.goldPerMin = 100;
        in.xpPerMin = 0;
        in.itemIds = List.of(cheap.id);
        in.matchEnd = 20.0;
        // 2.05 activations per minute x 8 health (10 before 7.3b) -> 16.4 health per minute (328 over a 20:00 match).
        in.runes.add(runes.toItem("Aperto dos Mortos-Vivos", null, -1, List.<double[]>of(new double[] {0, 2.05})).orElseThrow());
        in.runes.add(runes.toItem("Tempestade Crescente", null, -2).orElseThrow());
        TimelineResult r = new TimelineEngine(ref).run(in);

        Model.MinutePoint last = r.series.get(r.series.size() - 1);
        assertEquals(20.0, last.minute, EPS);                     // the timeline goes up to the end of the match
        assertEquals(1000 + 328, last.total.get(MAX_HEALTH), EPS);
        java.util.function.DoubleFunction<Model.MinutePoint> at = m -> r.series.stream()
                .filter(p -> Math.abs(p.minute - m) < 1e-9).findFirst().orElseThrow();
        assertEquals(1000 + 164, at.apply(10).total.get(MAX_HEALTH), EPS);
        double[][] storm = {{5, 0}, {6, 2}, {8, 2}, {9, 5}, {12, 9}, {15, 14}, {18, 20}};   // 2, 5, 9, 14, 20...
        for (double[] m : storm) {
            assertEquals(m[1], at.apply(m[0]).total.get(Stats.ATTACK_DAMAGE), EPS, "Tempestade at " + m[0]);
        }

        // Crescimento Excessivo: 3 stacks per minute x 3 health; +3% max health once 30 stacks are reached (10:00).
        EngineInput g = new EngineInput();
        g.unit = in.unit;
        g.goldPerMin = 100;
        g.xpPerMin = 0;
        g.itemIds = List.of(cheap.id);
        g.matchEnd = 20.0;
        g.runes.add(runes.toItem("Crescimento Excessivo", null, -1, List.<double[]>of(new double[] {0, 3})).orElseThrow());
        TimelineResult gr = new TimelineEngine(ref).run(g);
        java.util.function.DoubleFunction<Double> hp = m -> gr.series.stream()
                .filter(p -> Math.abs(p.minute - m) < 1e-9).findFirst().orElseThrow().total.get(MAX_HEALTH);
        assertEquals(1000 + 81, hp.apply(9), EPS);                                  // 27 stacks, no bonus yet
        assertEquals((1000 + 90) * 1.03, hp.apply(10), 0.5);                        // 30 stacks: +3% of the total

        // Periods: 4 stacks/min until 5:00 (farming), then 1/min (team fights) -> 20 stacks at 5:00, 30 at 15:00.
        EngineInput p = new EngineInput();
        p.unit = in.unit;
        p.goldPerMin = 100;
        p.xpPerMin = 0;
        p.itemIds = List.of(cheap.id);
        p.matchEnd = 20.0;
        p.runes.add(runes.toItem("Crescimento Excessivo", null, -1,
                List.<double[]>of(new double[] {5, 1}, new double[] {0, 4})).orElseThrow());   // any order
        TimelineResult pr = new TimelineEngine(ref).run(p);
        java.util.function.DoubleFunction<Double> hp2 = m -> pr.series.stream()
                .filter(x -> Math.abs(x.minute - m) < 1e-9).findFirst().orElseThrow().total.get(MAX_HEALTH);
        assertEquals(1000 + 60, hp2.apply(5), EPS);                                 // 20 stacks x 3
        assertEquals(1000 + 3 * 29, hp2.apply(14), EPS);                            // 29 stacks, no bonus yet
        assertEquals((1000 + 90) * 1.03, hp2.apply(15), 0.5);                       // 30 stacks at 15:00
    }

    @Test
    void extraGoldMakesPurchasesEarlier() {
        ItemDef thousand = item(121, "Mil", 1000, StatLine.flat(ARMOR, 1));
        java.util.function.Function<List<double[]>, TimelineResult> run = extra -> {
            EngineInput in = new EngineInput();
            in.unit = ornn();
            in.goldPerMin = 100;
            in.xpPerMin = 0;
            in.itemIds = List.of(thousand.id);
            in.extraGold = new java.util.ArrayList<>(extra);
            return new TimelineEngine(ref).run(in);
        };
        assertEquals(5.0, run.apply(List.of()).steps.get(0).minute, EPS);
        assertEquals(2.0, run.apply(List.<double[]>of(new double[] {2, 300})).steps.get(0).minute, EPS);   // 500+200+300
        assertEquals(4.0, run.apply(List.<double[]>of(new double[] {4, 100})).steps.get(0).minute, EPS);   // 500+400+100
        assertEquals(4.5, run.apply(List.<double[]>of(new double[] {7, 900}, new double[] {1, 50})).steps.get(0).minute, EPS);
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
                new org.springframework.core.io.ClassPathResource("seed/runas_7_3d.yml"),
                new org.springframework.core.io.ClassPathResource("seed/feiticos_7_3d.yml"),
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

    private ItemDef boots(long id, String name, int cost, ItemDef component, StatLine... stats) {
        ItemDef b = item(id, name, cost, stats);
        b.tabs = List.of("Botas");
        b.section = cost >= 2000 ? "aprimorado" : cost >= 1000 ? "tier_medio" : "basico";
        if (component != null) {
            b.components.add(new ComponentRef(component.id, 1));
        }
        return b;
    }

    private EngineInput input(List<Long> items, List<Boolean> sells) {
        EngineInput in = new EngineInput();
        in.unit = ragdoll(0);
        in.goldPerMin = 100;
        in.xpPerMin = 0;
        in.itemIds = items;
        in.sells = sells;
        return in;
    }

    @Test
    void bootsOfTheSameTierAreSwappedForTheDifference() {
        ItemDef speed = boots(20, "Botas da Velocidade", 400, null, StatLine.flat("Move Speed", 25));
        boots(21, "Botas Ionianas da Lucidez", 1000, speed, StatLine.flat(Stats.ABILITY_HASTE, 15));
        boots(22, "Botas Galvanizadas de Aço", 1200, speed, StatLine.flat(ARMOR, 25));
        TimelineResult r = new TimelineEngine(ref).run(input(List.of(21L, 22L), List.of(false, false)));
        TimelineStep swap = r.steps.get(1);
        assertEquals("Botas Ionianas da Lucidez", swap.swappedOut);
        assertEquals(700, swap.tradeIn);                        // the old boots are sold (70%)...
        assertEquals(500, swap.paidCost);                       // ...and the new ones bought: 1200 - 700
        assertEquals(List.of("Botas Galvanizadas de Aço"), swap.inventory);
        assertTrue(r.ignored.isEmpty());
        assertTrue(swap.warnings.stream().anyMatch(w -> w.startsWith("Troca de bota")));
        // As in the game's shop with Passos de Mercúrio (1200): the 1000 boots cost 160, the 1200 ones 360.
        TimelineResult back = new TimelineEngine(ref).run(input(List.of(22L, 21L), List.of(false, false)));
        assertEquals(160, back.steps.get(1).paidCost);
        // Boots of another tier cannot be bought over them: left out, the old boots stay.
        boots(23, "Mobilização Blindada", 2200, ref.items.get(22L), StatLine.flat(ARMOR, 30));
        boots(24, "Lucidez Escarlate", 2000, ref.items.get(21L), StatLine.flat(Stats.ABILITY_HASTE, 25));
        TimelineResult up = new TimelineEngine(ref).run(input(List.of(22L, 24L), List.of(false, false)));
        assertEquals(1, up.steps.size());
        assertEquals("bootsTier", up.ignored.get(0).violations.get(0).code);
        TimelineResult down = new TimelineEngine(ref).run(input(List.of(23L, 21L), List.of(false, false)));
        assertEquals("bootsTier", down.ignored.get(0).violations.get(0).code);
        assertEquals(List.of("Mobilização Blindada"), down.ignored.get(0).inventory);
        // The upgrade of the same line is still an upgrade, not a swap.
        TimelineResult upgrade = new TimelineEngine(ref).run(input(List.of(22L, 23L), List.of(false, false)));
        assertEquals(null, upgrade.steps.get(1).swappedOut);
        assertEquals(1000, upgrade.steps.get(1).paidCost);
    }

    @Test
    void sellingAnItemGivesGoldBackAndFreesTheSlot() {
        TimelineResult r = new TimelineEngine(ref).run(input(List.of(2L, 2L, 3L), List.of(false, true, false)));
        TimelineStep sale = r.steps.get(1);
        assertTrue(sale.sold);
        assertEquals(-350, sale.paidCost);                      // 70% of 500
        assertTrue(sale.inventory.isEmpty());
        assertEquals(500 - 350 + 900, r.steps.get(2).cumulativeGold);
        // Selling what is not held: left out.
        TimelineResult none = new TimelineEngine(ref).run(input(List.of(2L), List.of(true)));
        assertEquals("notOwned", none.ignored.get(0).violations.get(0).code);
    }

    // ------------------------------------------------------------------ sale timed to the next item, ranges

    /** Suporte (400) to sell; Final (1000) = Meio (600 = 2x Pequeno 200 + 200) + Pequeno (200) + 200. */
    private ItemDef[] saleRecipe() {
        ItemDef support = shopItem(110, "Suporte", "aprimorado", "Suporte");
        support.cost = 400;
        ItemDef small = shopItem(111, "Pequeno", "basico");
        small.cost = 200;
        ItemDef half = shopItem(112, "Meio", "tier_medio");
        half.cost = 600;
        half.components.add(new ComponentRef(small.id, 2));
        ItemDef fin = shopItem(113, "Final", "aprimorado");
        fin.cost = 1000;
        fin.components.add(new ComponentRef(half.id, 1));
        fin.components.add(new ComponentRef(small.id, 1));
        return new ItemDef[] {support, small, half, fin};
    }

    private EngineInput saleInput(List<Long> items, List<Boolean> sells, List<String> when) {
        EngineInput in = input(items, sells);
        in.assumeHalfItems = true;
        in.assumeSmallItems = true;
        in.sellWhen = when;
        return in;
    }

    @Test
    void saleWhenTheNextItemCanBeBoughtWholeKeepsTheGold() {
        ItemDef[] r = saleRecipe();
        TimelineResult res = new TimelineEngine(ref).run(saleInput(List.of(r[0].id, r[0].id, r[3].id),
                List.of(false, true, false), java.util.Arrays.asList(null, "afford", null)));
        // Suporte at 0:00 (400 of the 500). Sale gives 280; Final whole needs 1000 - 280 = 720 in hand:
        // 100 in hand + 100/min -> 6.2 minutes. No component is assumed in between, and Final lands at that minute.
        assertEquals(3, res.steps.size());
        TimelineStep sale = res.steps.get(1);
        assertTrue(sale.sold);
        assertEquals(6.2, sale.minute, EPS);
        assertEquals(720, sale.saleTiming.goldNeeded);
        assertEquals(0, sale.saleTiming.nextDiscount);
        assertEquals(100, sale.saleTiming.goldAfterPrevious, EPS);
        assertEquals("Final", sale.saleTiming.nextItemName);
        TimelineStep fin = res.steps.get(2);
        assertEquals("Final", fin.itemName);
        assertEquals(6.2, fin.minute, EPS);
        assertEquals(1000, fin.paidCost);
        assertEquals("sale", fin.assumption);
        assertTrue(res.steps.stream().noneMatch(st -> st.implied));
        // The gold in hand right before the sale minute is short of 720.
        Model.MinutePoint six = res.series.stream().filter(p -> Math.abs(p.minute - 6) < EPS).findFirst().get();
        assertEquals(100 + 600, six.goldInHand, EPS);
    }

    @Test
    void saleTimedToTheNextItemCountsTheComponentsHeld() {
        ItemDef[] r = saleRecipe();
        TimelineResult res = new TimelineEngine(ref).run(saleInput(List.of(r[0].id, r[1].id, r[0].id, r[3].id),
                List.of(false, false, true, false), java.util.Arrays.asList(null, null, "afford", null)));
        TimelineStep sale = res.steps.get(2);
        assertEquals(200, sale.saleTiming.nextDiscount);      // Pequeno held: the purchase discounts it
        assertEquals(1000 - 200 - 280, sale.saleTiming.goldNeeded);
        // 600 paid by 1:00 (Pequeno), 520 more needed in hand: earned 1120 -> 6.2 minutes.
        assertEquals(6.2, sale.minute, EPS);
        assertEquals(800, res.steps.get(3).paidCost);
        assertEquals(6.2, res.steps.get(3).minute, EPS);
    }

    @Test
    void saleRightAfterThePreviousPurchaseIsUnchanged() {
        ItemDef[] r = saleRecipe();
        TimelineResult res = new TimelineEngine(ref).run(saleInput(List.of(r[0].id, r[0].id, r[3].id),
                List.of(false, true, false), java.util.Arrays.asList(null, "after", null)));
        TimelineStep sale = res.steps.get(1);
        assertEquals(0.0, sale.minute, EPS);
        assertEquals(null, sale.saleTiming);
        assertTrue(res.steps.stream().anyMatch(st -> st.implied));   // the components are assumed as before
        // No choice saved (builds before v21) = the same result.
        TimelineResult old = new TimelineEngine(ref).run(saleInput(List.of(r[0].id, r[0].id, r[3].id),
                List.of(false, true, false), new java.util.ArrayList<>()));
        assertEquals(json(res), json(old));
    }

    @Test
    void wholeItemsRangeInABuildWithHalfItems() {
        ItemDef[] r = saleRecipe();
        ItemDef other = shopItem(114, "Outro", "aprimorado");
        other.cost = 1000;
        other.components.add(new ComponentRef(r[2].id, 1));
        ItemDef third = shopItem(115, "Terceiro", "aprimorado");
        third.cost = 1000;
        third.components.add(new ComponentRef(r[1].id, 1));
        EngineInput in = input(List.of(r[3].id, other.id, third.id), List.of(false, false, false));
        in.assumeHalfItems = true;
        in.assume = java.util.Arrays.asList(null, "whole", null);
        TimelineResult res = new TimelineEngine(ref).run(in);
        assertEquals(1, res.steps.stream().filter(st -> st.buildIndex == 0 && st.implied).count());   // Meio
        assertEquals(0, res.steps.stream().filter(st -> st.buildIndex == 1 && st.implied).count());   // whole
        assertEquals("whole", res.steps.stream().filter(st -> st.buildIndex == 1).findFirst().get().assumption);
        // "small" in the range: the third item's Pequeno is assumed, which the build's half items alone would not.
        in.assume = java.util.Arrays.asList(null, null, "small");
        TimelineResult small = new TimelineEngine(ref).run(in);
        assertEquals(1, small.steps.stream().filter(st -> st.buildIndex == 2 && st.implied).count());
        // A manual range over the item after a timed sale prevails over the automatic "whole".
        EngineInput sale = saleInput(List.of(r[0].id, r[0].id, r[3].id), List.of(false, true, false),
                java.util.Arrays.asList(null, "afford", null));
        sale.assume = java.util.Arrays.asList(null, null, "half");
        TimelineResult manual = new TimelineEngine(ref).run(sale);
        assertEquals("half", manual.steps.get(1).saleTiming.manualAssume);
        assertTrue(manual.steps.stream().anyMatch(st -> st.implied && st.itemName.equals("Meio")));
    }

    @Test
    void saleWhoseGoldNeverArrivesDoesNotHappen() {
        ItemDef[] r = saleRecipe();
        EngineInput in = saleInput(List.of(r[0].id, r[0].id, r[3].id), List.of(false, true, false),
                java.util.Arrays.asList(null, "afford", null));
        in.matchEnd = 5.0;
        TimelineResult res = new TimelineEngine(ref).run(in);
        assertEquals(1, res.steps.size());                       // only Suporte
        assertEquals(2, res.ignored.size());
        assertEquals("saleNeverAffords", res.ignored.get(0).violations.get(0).code);
        assertEquals("saleNeverAffords", res.ignored.get(1).violations.get(0).code);
        assertEquals(List.of("Suporte"), res.ignored.get(1).inventory);   // still held
    }


    // ------------------------------------------------------------------ relevance with passed-on passives

    private static Model.MinutePoint lastPoint(TimelineResult r) {
        return r.series.get(r.series.size() - 1);
    }

    private static double inherited(Model.Relevance rel, String item, String source) {
        return rel.contributions.stream().filter(c -> c.itemName.equals(item))
                .mapToDouble(c -> c.inherited.containsKey(source) ? c.inherited.get(source).gold : 0).sum();
    }

    private static double plainGrand(Model.MinutePoint p, Model.Relevance rel) {
        return p.contributions.stream().mapToDouble(c -> c.flatGold + c.passiveGold).sum() + rel.forgeGold;
    }

    @Test
    void livingForgeOfAnItemIsTheForgeOverItsOwnStats() {
        TimelineResult r = run(ornn(), 400, 0, clothArmor.id, ruby.id);
        Model.MinutePoint p = lastPoint(r);
        Model.Relevance rel = TimelineEngine.relevance(p, List.of(TimelineEngine.FORGE_SOURCE), ref.statPrices);
        ItemContributionView cloth = view(rel, "Cloth Armor");
        assertEquals(20 * 0.07, cloth.stat(ARMOR), EPS);
        assertEquals(20 * 0.07 * 25.0, cloth.gold, EPS);
        assertEquals(150 * 0.07, view(rel, "Ruby Crystal").stat(MAX_HEALTH), EPS);
        // The Forge passes on all it has and the grand total does not change.
        double passed = rel.contributions.stream().flatMap(c -> c.inherited.values().stream()).mapToDouble(sh -> sh.gold).sum();
        assertEquals(rel.forgeGold, passed, EPS);
        assertEquals(rel.forgeGold, rel.forgeRepassed.gold, EPS);
        assertEquals(plainGrand(p, rel), rel.grand, EPS);
    }

    @Test
    void passiveWithRefIsSplitByWhoGivesTheStat() {
        ItemDef vest = item(120, "Colete", 1000, StatLine.flat(ARMOR, 30),
                StatLine.percent(ARMOR, "Espinhos", 0.06, ARMOR, RefScope.BONUS));
        TimelineResult r = run(ragdoll(50), 1000, 0, vest.id, clothArmor.id, ruby.id);
        Model.MinutePoint p = lastPoint(r);
        String key = view(TimelineEngine.relevance(p, List.of(), ref.statPrices), "Colete").index + "|Espinhos";
        Model.Relevance rel = TimelineEngine.relevance(p, List.of(key), ref.statPrices);
        // 6% of the 50 bonus Armor = 3: 1.8 from Colete's 30, 1.2 from Cloth Armor's 20, nothing to Ruby (no Armor).
        assertEquals(1.8 * 25, inherited(rel, "Colete", key), EPS);
        assertEquals(1.2 * 25, inherited(rel, "Cloth Armor", key), EPS);
        assertEquals(0.0, inherited(rel, "Ruby Crystal", key), EPS);
        assertEquals(3 * 25, view(rel, "Colete").repassed(key), EPS);
        assertEquals(plainGrand(p, rel), rel.grand, EPS);
        // Of the total (base included), the champion's base and level go to the champion row.
        TimelineResult total = run(ragdoll(50), 1000, 0, amaranth.id, clothArmor.id);
        Model.MinutePoint tp = lastPoint(total);
        String endurance = view(TimelineEngine.relevance(tp, List.of(), ref.statPrices), amaranth.name).index + "|Endurance";
        Model.Relevance trel = TimelineEngine.relevance(tp, List.of(endurance), ref.statPrices);
        assertEquals(0.3 * 50 * 25, trel.champion.get(endurance).gold, EPS);
        assertEquals(0.3 * 20 * 25, inherited(trel, "Cloth Armor", endurance), EPS);
        assertEquals(plainGrand(tp, trel), trel.grand, EPS);
    }

    @Test
    void chainedSourcesKeepTheGrandTotalAndNothingPassedIsTheSameReport() {
        ItemDef twin = item(121, "Duplaguarda", 3000, StatLine.flat(ARMOR, 40),
                StatLine.percent(ARMOR, "Resistência", 0.2, ARMOR, RefScope.BONUS));
        TimelineResult r = run(ornn(), 1000, 1000, twin.id, clothArmor.id, amaranth.id, ruby.id);
        Model.MinutePoint p = lastPoint(r);
        Model.Relevance none = TimelineEngine.relevance(p, List.of(), ref.statPrices);
        assertEquals(json(p.contributions), json(none.contributions));
        assertEquals(plainGrand(p, none), none.grand, EPS);
        List<String> all = new java.util.ArrayList<>();
        none.sources.forEach(src -> all.add(src.key));
        assertEquals(3, all.size());   // the Forge, Resistência and Endurance (its Armor and MR lines: one source)
        Model.Relevance every = TimelineEngine.relevance(p, all, ref.statPrices);
        assertEquals(none.grand, every.grand, EPS);
        // Everything passed on: nothing is left on the passives' owners beyond their flat stats and what they receive.
        double kept = every.contributions.stream().mapToDouble(c -> c.passiveGold
                - c.repassed.values().stream().mapToDouble(sh -> sh.gold).sum()).sum();
        assertEquals(0.0, kept, 1e-6);
        // The Forge amplifies Resistência's gain and Resistência reads the Forge: followed until nothing is left.
        assertEquals(0.0, every.forgeInherited.values().stream().mapToDouble(sh -> sh.gold).sum(), 1e-3);
    }

    /** One contribution of a relevance report, with what it receives in total. */
    private static final class ItemContributionView {
        int index;
        double gold;
        Model.ItemContribution c;

        double stat(String s) {
            return c.inherited.values().stream().mapToDouble(sh -> sh.stats.getOrDefault(s, 0.0)).sum();
        }

        double repassed(String key) {
            return c.repassed.containsKey(key) ? c.repassed.get(key).gold : 0;
        }
    }

    private static ItemContributionView view(Model.Relevance rel, String item) {
        ItemContributionView v = new ItemContributionView();
        v.c = rel.contributions.stream().filter(c -> c.itemName.equals(item)).findFirst().get();
        v.index = v.c.purchaseIndex;
        v.gold = v.c.inherited.values().stream().mapToDouble(sh -> sh.gold).sum();
        return v;
    }

    /** Boots line of the shop rule tests: basic (400) -> mid tier (1200) -> upgraded (2200, the mid tier inside). */
    private void bootsLine() {
        ItemDef speed = boots(20, "Botas da Velocidade", 400, null, StatLine.flat("Move Speed", 25));
        ItemDef steel = boots(22, "Botas Galvanizadas de Aço", 1200, speed, StatLine.flat(ARMOR, 25));
        boots(23, "Mobilização Blindada", 2200, steel, StatLine.flat(ARMOR, 30));
    }

    private TimelineResult withRule(double gpm, boolean half, ShopRuleCheck rule, Long... ids) {
        EngineInput in = new EngineInput();
        in.unit = ragdoll(0);
        in.goldPerMin = gpm;
        in.xpPerMin = 0;
        in.itemIds = List.of(ids);
        in.assumeHalfItems = half;
        in.assumeSmallItems = half;
        in.shopRules = rule;
        return new TimelineEngine(ref).run(in);
    }

    @Test
    void upgradedBootsBeforeTenWaitForTheShopRule() {
        bootsLine();
        ShopRuleCheck rule = new ShopRuleCheck(10);
        // 300 gold/min: the steel boots at (1200 - 500) / 300 = 2:20; the upgrade (1000 more) has the gold at 5:40 and
        // waits for 10:00; the cloth armor after it has its gold at 7:20, so it comes right at 10:00 too.
        TimelineResult r = withRule(300, false, rule, 22L, 23L, clothArmor.id);
        TimelineStep up = r.steps.get(1);
        assertEquals("Mobilização Blindada", up.itemName);
        assertEquals(10.0, up.minute, EPS);
        assertEquals(10.0, up.waitedUntil, EPS);
        assertTrue(up.warnings.contains("Esperou os 10:00 (regra da loja: botas aprimoradas só a partir dos 10:00)."), up.warnings.toString());
        assertEquals(1000, up.paidCost);
        assertEquals(3500 - 2200, up.goldLeft, EPS);           // the gold was kept in the bag: 500 + 300 x 10 - 2200
        assertEquals(10.0, r.steps.get(2).minute, EPS);
        assertEquals(null, r.steps.get(2).waitedUntil);
        // Assumed half items of the next purchases do not jump ahead of it: chain vest's cloth armor at 10:00 or later.
        TimelineResult half = withRule(300, true, rule, 23L, chainVest.id);
        List<TimelineStep> s = half.steps;
        assertEquals("Botas da Velocidade", s.get(0).itemName);  // its own components are bought as the gold comes
        assertEquals(0.0, s.get(0).minute, EPS);
        assertEquals("Botas Galvanizadas de Aço", s.get(1).itemName);
        assertEquals(7.0 / 3, s.get(1).minute, EPS);
        assertEquals("Mobilização Blindada", s.get(2).itemName);
        assertEquals(10.0, s.get(2).minute, EPS);
        for (TimelineStep later : s.subList(3, s.size())) {
            assertTrue(later.minute >= 10.0 - EPS, later.itemName + " at " + later.minute);
        }
        assertTrue(rule.allowedAt(ref.items.get(23L), 10.0));
        assertTrue(!rule.allowedAt(ref.items.get(23L), 9.99));
        assertTrue(rule.allowedAt(ref.items.get(22L), 0));
    }

    @Test
    void upgradedBootsAfterTenAreBoughtAsUsual() {
        bootsLine();
        // 100 gold/min: the upgrade has its gold at (2200 - 500) / 100 = 17:00, well past the rule.
        TimelineResult r = withRule(100, false, new ShopRuleCheck(10), 22L, 23L);
        assertEquals(17.0, r.steps.get(1).minute, EPS);
        assertEquals(null, r.steps.get(1).waitedUntil);
        assertTrue(r.steps.get(1).warnings.stream().noneMatch(w -> w.startsWith("Esperou")));
    }

    @Test
    void buildsWithoutUpgradedBootsBeforeTenAreTheSameAsWithoutTheRule() {
        bootsLine();
        for (boolean half : new boolean[] {false, true}) {
            Long[][] builds = {{22L, chainVest.id, amaranth.id}, {clothArmor.id, ruby.id, amaranth.id, 23L}};
            for (Long[] b : builds) {
                // the second build reaches the upgraded boots after 10:00 at 300 gold/min: nothing waits
                assertEquals(json(withRule(300, half, ShopRuleCheck.NONE, b)), json(withRule(300, half, new ShopRuleCheck(10), b)));
            }
        }
    }

    @Test
    void saleTimedToUpgradedBootsWaitsForTheRuleToo() {
        bootsLine();
        EngineInput in = new EngineInput();
        in.unit = ragdoll(0);
        in.goldPerMin = 300;
        in.itemIds = List.of(22L, clothArmor.id, clothArmor.id, 23L);
        in.sells = List.of(false, false, true, false);
        in.sellWhen = java.util.Arrays.asList(null, null, EngineInput.SELL_AFFORD, null);
        in.shopRules = new ShopRuleCheck(10);
        TimelineResult r = new TimelineEngine(ref).run(in);
        TimelineStep sale = r.steps.get(2);
        assertTrue(sale.sold);
        assertEquals(10.0, sale.minute, EPS);                   // the gold is there at 5:00; the boots only at 10:00
        assertEquals(10.0, r.steps.get(3).minute, EPS);
    }

    private static String json(Object r) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(r);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
