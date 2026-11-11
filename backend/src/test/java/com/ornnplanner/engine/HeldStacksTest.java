package com.ornnplanner.engine;

import com.ornnplanner.engine.Model.EngineInput;
import com.ornnplanner.engine.Model.ItemDef;
import com.ornnplanner.engine.Model.MinutePoint;
import com.ornnplanner.engine.Model.ReferenceData;
import com.ornnplanner.engine.Model.StatGrowth;
import com.ornnplanner.engine.Model.StatLine;
import com.ornnplanner.engine.Model.UnitProfile;
import com.ornnplanner.seed.ItemEffectCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.ornnplanner.engine.Stats.ATTACK_DAMAGE;
import static com.ornnplanner.engine.Stats.MAX_HEALTH;
import static com.ornnplanner.engine.Stats.MAX_MANA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stacks gathered while held (Rodada 13, {@code acumulo_por_posse}): Bastão das Eras' Veterano counts a stack every 35s
 * from the purchase, not the ten stacks at once; Mejai's movement speed only from 10 stacks.
 */
class HeldStacksTest {

    private static final double EPS = 1e-6;
    private static ItemEffectCatalog effects;

    @BeforeAll
    static void load() {
        effects = new ItemEffectCatalog(new ClassPathResource("seed/efeitos_itens_7_3f.yml"),
                new ClassPathResource("seed/precos_status.yml"));
    }

    @Test
    void fractionCountsWholeStacks() {
        StatLine l = StatLine.flat(MAX_HEALTH, 150);
        l.heldEvery = 35.0;
        l.heldMax = 10.0;
        assertEquals(0, l.heldFraction(0), EPS);
        assertEquals(0, l.heldFraction(34.0 / 60), EPS);
        assertEquals(0.1, l.heldFraction(35.0 / 60), EPS);
        assertEquals(1, l.heldFraction(6), EPS);   // 350s = 10 stacks
        assertEquals(1, l.heldFraction(30), EPS);
        l.heldFrom = 10.0;
        assertEquals(0, l.heldFraction(5), EPS);
        assertEquals(1, l.heldFraction(10), EPS);
        assertEquals(1, StatLine.flat(MAX_HEALTH, 1).heldFraction(0), EPS);
    }

    @Test
    void catalogMarksTheShopLines() {
        assertNotNull(effects.holds().get("Bastão das Eras|Veterano"));
        ItemDef rod = rod();
        ItemDef marked = effects.apply(rod, Map.of(), Map.of(), Map.of());
        StatLine veteran = marked.stats.stream().filter(l -> "Veterano".equals(l.passive)).findFirst().orElseThrow();
        assertEquals(35.0, veteran.heldEvery, EPS);
        assertEquals(10.0, veteran.heldMax, EPS);
        // the catalog's own item is not changed
        assertTrue(rod.stats.stream().allMatch(l -> l.heldEvery == null));
        // the effect lines that grow by themselves: Yun Tal's critical strike, Grevas Vorazes' takedowns (the 5% stay whole)
        ItemDef yunTal = effects.apply(item(9, "Flechatroz de Yun Tal", null), Map.of(), Map.of(), Map.of());
        assertTrue(yunTal.stats.stream().anyMatch(l -> l.heldEvery != null && l.heldMax == 25));
        ItemDef greaves = effects.apply(item(10, "Grevas Vorazes", null), Map.of(), Map.of(), Map.of());
        assertEquals(1, greaves.stats.stream().filter(l -> l.heldEvery != null).count());
        assertEquals(2, greaves.stats.stream().filter(l -> "Conversão".equals(l.passive)).count());
    }

    @Test
    void stacksGrowFromThePurchaseNotFromTheStartOfTheMatch() {
        // bought at once: 1 stack after 60s, 3 after 2 min, all 10 after 350s (base 1000 + 350 + 15 a stack)
        assertEquals(1365, health(rod(), 0.0, 1.0), EPS);
        assertEquals(1395, health(rod(), 0.0, 2.0), EPS);
        assertEquals(1500, health(rod(), 0.0, 6.0), EPS);
        // bought at 18 min (a hypothetical inventory held as given): none at 18, 3 stacks at 20, not 10
        assertEquals(1350, health(rod(), 18.0, 18.0), EPS);
        assertEquals(1395, health(rod(), 18.0, 20.0), EPS);
    }

    @Test
    void perMinuteStacksOfAnItemCountFromThePurchase() {
        // Coração de Aço with 1 charged strike a minute, bought at 13:00: at 15:00, 2 strikes, not 15
        Map<String, List<double[]>> rate = Map.of("Coração de Aço|Consumo Colossal", List.of(new double[] {0, 1}));
        ItemDef heart = item(5, "Coração de Aço", null);
        double with = stat(List.of(heart), rate, Map.of(5L, 13.0), 15, MAX_HEALTH);
        double without = stat(List.of(heart), Map.of(), Map.of(5L, 13.0), 15, MAX_HEALTH);
        double strikes = with - without;
        assertTrue(strikes > 2 * 21 - EPS && strikes < 2 * 21 + 30, "2 strikes: 42 + 0,525% of max health each, got " + strikes);
        StatLine l = StatLine.flat(MAX_HEALTH, 0);
        l.perMinute = List.of(new double[] {0, 1}, new double[] {20, 3});
        assertEquals(2, l.from(13).stacks(15), EPS);
        assertEquals(7 + 3 * 2, l.from(13).stacks(22), EPS);
        assertEquals(15, l.from(0).stacks(15), EPS);
    }

    @Test
    void anEvolutionByTimeIsItsBaseItemUntilThen() {
        // Muramana bought at 10:00 is a Manamune until 16:00 (evolucao_por_tempo: 6)
        ItemDef manamune = item(6, "Manamune", ATTACK_DAMAGE);
        ItemDef muramana = item(7, "Muramana", ATTACK_DAMAGE);
        muramana.group = "Manamune";
        muramana.section = "evolucao";
        muramana.stats.get(0).value = 100.0;
        assertEquals(35, stat(List.of(manamune, muramana), Map.of(), Map.of(7L, 10.0), 12, ATTACK_DAMAGE, 7L), EPS);
        assertEquals(100, stat(List.of(manamune, muramana), Map.of(), Map.of(7L, 10.0), 16, ATTACK_DAMAGE, 7L), EPS);
    }

    @Test
    void manamuneKeepsTheTearsCharges() {
        // Lágrima at 0:00, Manamune at 24:00 (100 gold a minute): at 25:00 the 700 Mana are full (50 charges since 0:00),
        // not the 8 charges of one minute
        ItemDef tear = item(8, "Lágrima da Deusa", null);
        tear.cost = 400;
        tear.section = "basico";
        ItemDef manamune = item(6, "Manamune", ATTACK_DAMAGE);
        manamune.cost = 2900;
        StatLine charge = StatLine.flat(MAX_MANA, 700);
        charge.passive = "Carga de Mana";
        charge.conditional = true;
        manamune.stats.add(charge);
        manamune.components.add(new Model.ComponentRef(8, 1));
        ReferenceData ref = ref(List.of(tear, manamune), Map.of());
        EngineInput in = input(List.of(8L, 6L), 100, 26);
        Model.TimelineResult r = new TimelineEngine(ref).run(in);
        assertEquals(24, r.steps.get(r.steps.size() - 1).minute, 1e-6);
        assertEquals(700, at(r, 25).total.get(MAX_MANA), EPS);
    }

    @Test
    void yordleTrapsGoldBringsTheNextPurchaseForward() {
        // 100 gold a minute: the trap at 19:00 (2400), the next item (2000) at 39:00 without its gold; with 20 gold every
        // 2 min from 19:00 it comes almost 2 min earlier
        ItemDef trap = item(11, "Armadilha de Yordle", null);
        trap.cost = 2400;
        ItemDef next = item(12, "Outro", null);
        next.cost = 2000;
        Model.TimelineResult r = new TimelineEngine(ref(List.of(trap, next), Map.of())).run(input(List.of(11L, 12L), 100, 45));
        assertEquals(19, r.steps.get(0).minute, 1e-6);
        double second = r.steps.get(1).minute;
        assertTrue(second < 39 - 1.5 && second > 39 - 2.5, "the trap's gold: " + second);
    }

    private static ItemDef item(long id, String name, String stat) {
        ItemDef i = new ItemDef();
        i.id = id;
        i.name = name;
        i.group = name;
        i.cost = 1000;
        i.section = "aprimorado";
        if (stat != null) {
            i.stats.add(StatLine.flat(stat, 35));
        }
        return i;
    }

    private static ReferenceData ref(List<ItemDef> items, Map<String, List<double[]>> rates) {
        ReferenceData ref = new ReferenceData();
        ref.xpTable.put(1, 0.0);
        ref.statPrices.put(MAX_HEALTH, 2.67);
        for (ItemDef i : items) {
            ref.items.put(i.id, effects.apply(i, Map.of(), Map.of(), rates));
        }
        effects.timing(ref.items);
        return ref;
    }

    private static EngineInput input(List<Long> ids, double gold, double end) {
        EngineInput in = new EngineInput();
        UnitProfile u = new UnitProfile();
        u.code = "TEST";
        u.name = "Teste";
        u.stats.put(MAX_HEALTH, new StatGrowth(1000, 0));
        in.unit = u;
        in.goldPerMin = gold;
        in.itemIds = new ArrayList<>(ids);
        in.matchEnd = end;
        return in;
    }

    private static MinutePoint at(Model.TimelineResult r, double minute) {
        return r.series.stream().filter(p -> Math.abs(p.minute - minute) < 1e-9).findFirst().orElseThrow();
    }

    /** A stat at {@code minute} holding {@code buy} (or every item) bought at once, at the minutes of {@code acquired}. */
    private static double stat(List<ItemDef> items, Map<String, List<double[]>> rates, Map<Long, Double> acquired, double minute,
                               String stat, Long... buy) {
        ReferenceData ref = ref(items, rates);
        List<Long> ids = new ArrayList<>();
        if (buy.length > 0) {
            ids.addAll(List.of(buy));
        } else {
            items.forEach(i -> ids.add(i.id));
        }
        EngineInput in = input(ids, 1e9, minute + 1);
        in.acquiredAt = acquired;
        return at(new TimelineEngine(ref).run(in), minute).total.getOrDefault(stat, 0.0);
    }

    private static ItemDef rod() {
        ItemDef i = new ItemDef();
        i.id = 1;
        i.name = "Bastão das Eras";
        i.cost = 2700;
        i.section = "aprimorado";
        i.stats.add(StatLine.flat(MAX_HEALTH, 350));
        StatLine v = StatLine.flat(MAX_HEALTH, 150);
        v.passive = "Veterano";
        v.conditional = true;
        i.stats.add(v);
        return i;
    }

    /** Max health at {@code minute} holding the item, bought at {@code bought} (via acquiredAt) or at once. */
    private static double health(ItemDef item, double bought, double minute) {
        ReferenceData ref = new ReferenceData();
        ref.xpTable.put(1, 0.0);
        ref.statPrices.put(MAX_HEALTH, 2.67);
        ref.items.put(item.id, effects.apply(item, Map.of(), Map.of(), Map.of()));
        EngineInput in = new EngineInput();
        UnitProfile u = new UnitProfile();
        u.code = "TEST";
        u.name = "Teste";
        u.stats.put(MAX_HEALTH, new StatGrowth(1000, 0));
        in.unit = u;
        in.goldPerMin = 1e9;
        in.itemIds = new ArrayList<>(List.of(item.id));
        in.matchEnd = Math.max(minute, 0.01) + 1;
        if (bought > 0) {
            in.acquiredAt = Map.of(item.id, bought);
        }
        Model.TimelineResult r = new TimelineEngine(ref).run(in);
        MinutePoint best = r.series.get(0);
        for (MinutePoint p : r.series) {
            if (p.minute <= minute + 1e-9) {
                best = p;
            }
        }
        if (minute > 0 && Math.abs(best.minute - minute) > 1e-9) {
            throw new AssertionError("no series point at " + minute);
        }
        return best.total.get(MAX_HEALTH);
    }
}
