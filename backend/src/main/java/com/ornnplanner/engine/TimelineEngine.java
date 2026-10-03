package com.ornnplanner.engine;

import com.ornnplanner.engine.Model.ComponentRef;
import com.ornnplanner.engine.Model.Efficiency;
import com.ornnplanner.engine.Model.EngineInput;
import com.ornnplanner.engine.Model.ForgeDetail;
import com.ornnplanner.engine.Model.ForgeTier;
import com.ornnplanner.engine.Model.ItemDef;
import com.ornnplanner.engine.Model.MinutePoint;
import com.ornnplanner.engine.Model.PassiveDetail;
import com.ornnplanner.engine.Model.RefScope;
import com.ornnplanner.engine.Model.ReferenceData;
import com.ornnplanner.engine.Model.StatGrowth;
import com.ornnplanner.engine.Model.StatLine;
import com.ornnplanner.engine.Model.StatSnapshot;
import com.ornnplanner.engine.Model.TimelineResult;
import com.ornnplanner.engine.Model.TimelineStep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.ornnplanner.engine.GoldPricing.fmt;

/**
 * Build timeline calculator.
 *
 * <h2>Time and level</h2>
 * Gold at minute t = {@value #STARTING_GOLD} + goldPerMin * t. A purchase happens at the first minute where
 * accumulated gold covers the cumulative gold paid up to and including it. XP at minute t = xpPerMin * t and the
 * level is the highest level whose cumulative XP requirement is met (editable XP table).
 *
 * <h2>Stat composition (per purchase, over the whole inventory at that point)</h2>
 * <ol>
 *   <li>Flat stats of every owned item are summed ("itemFlat"). Ranges shown by the shop as "X–Y (by level)" are
 *       interpolated linearly between level 1 and 15. Conditional effects (stacks, in combat, low health) only count
 *       when the build enables them.</li>
 *   <li>Unit base stats at the current level: {@code base + growth * (level - 1)}.</li>
 *   <li>Percentage passives are evaluated <b>in purchase order</b>. For the item at inventory position k, a line
 *       "ratio x ref_type" is worth {@code ratio * (base[ref_type] + itemFlat[ref_type] + passives of items at
 *       positions < k contributing to ref_type)} for scope TOTAL; BONUS drops the base term and BASE keeps only it. An item's passive never sees its own
 *       passive lines nor passives of items bought after it. Ratio lines without a ref_type cannot be evaluated
 *       against the build and fall back to the reference data's static value (flagged in the output).</li>
 *   <li>Living Forge (Ornn only) is applied last: {@code pct(level) * bonus} for Max Health, Armor and Magic
 *       Resistance, where bonus = itemFlat + itemPassives. Base stats are never multiplied.</li>
 *   <li>Gold value of any stat amount = amount * gold price of the stat (see {@link GoldPricing}).</li>
 * </ol>
 *
 * <h2>Efficiencies of the purchased item</h2>
 * <ul>
 *   <li>static: reference-site formula, context free.</li>
 *   <li>dynamic: (gold value of its flat stats + gold value of its passive lines as evaluated in step 3 at this
 *       point of the build) / item cost.</li>
 *   <li>marginal (extra): gold value of all bonus stats (incl. Living Forge and other items' passives) after the
 *       purchase minus before it, both at the purchase level, / gold actually paid.</li>
 * </ul>
 *
 * <h2>Components</h2>
 * If the purchased item has a recipe, owned components (searched recursively through sub-recipes) are consumed
 * and their cost is discounted, like the in-game shop.
 */
public class TimelineEngine {

    public static final double STARTING_GOLD = 500;
    public static final int MAX_LEVEL = 15;
    public static final int INVENTORY_SLOTS = 6;

    private final ReferenceData ref;
    /** Set at the start of each run: whether conditional effects count. */
    private boolean includeConditional = true;

    public TimelineEngine(ReferenceData ref) {
        this.ref = ref;
    }

    /** One item in the inventory, remembering the purchase that brought it. */
    private static final class Owned {
        final ItemDef item;
        final int purchaseIndex;

        Owned(ItemDef item, int purchaseIndex) {
            this.item = item;
            this.purchaseIndex = purchaseIndex;
        }
    }

    public TimelineResult run(EngineInput in) {
        TimelineResult result = new TimelineResult();
        result.unitCode = in.unit.code;
        result.unitName = in.unit.name;
        result.startingGold = STARTING_GOLD;
        result.goldPerMin = in.goldPerMin;
        result.xpPerMin = in.xpPerMin;
        result.statPrices = ref.statPrices;
        includeConditional = in.includeConditional;

        if (in.goldPerMin <= 0) {
            result.warnings.add("Ouro por minuto deve ser maior que zero.");
            return result;
        }
        if (in.xpPerMin < 0) {
            result.warnings.add("XP por minuto não pode ser negativo.");
            return result;
        }

        List<Owned> inventory = new ArrayList<>();
        List<List<Owned>> inventoryAfterStep = new ArrayList<>();
        int cumulative = 0;

        for (int i = 0; i < in.itemIds.size(); i++) {
            ItemDef item = ref.items.get(in.itemIds.get(i));
            if (item == null) {
                result.warnings.add("Compra #" + (i + 1) + ": item id " + in.itemIds.get(i) + " não existe no catálogo.");
                inventoryAfterStep.add(new ArrayList<>(inventory));
                continue;
            }
            TimelineStep step = new TimelineStep();
            step.index = i;
            step.itemId = item.id;
            step.itemName = item.name;
            step.category = item.category;
            step.itemCost = item.cost;

            List<Owned> before = new ArrayList<>(inventory);
            List<Owned> consumed = new ArrayList<>();
            int discount = 0;
            for (ComponentRef c : item.components) {
                for (int q = 0; q < c.quantity; q++) {
                    discount += consume(c.itemId, inventory, consumed);
                }
            }
            for (Owned o : consumed) {
                step.consumedComponents.add(o.item.name);
            }
            step.paidCost = Math.max(0, item.cost - discount);
            cumulative += step.paidCost;
            step.cumulativeGold = cumulative;
            step.minute = Math.max(0, (cumulative - STARTING_GOLD) / in.goldPerMin);
            step.xp = in.xpPerMin * step.minute;
            step.level = levelForXp(step.xp);

            inventory.add(new Owned(item, i));
            inventoryAfterStep.add(new ArrayList<>(inventory));
            for (Owned o : inventory) {
                step.inventory.add(o.item.name);
            }
            if (inventory.size() > INVENTORY_SLOTS) {
                step.warnings.add("Inventário com " + inventory.size() + " itens (limite do jogo: " + INVENTORY_SLOTS + ").");
            }

            step.stats = snapshot(in.unit, inventory, step.level);
            StatSnapshot beforeSnap = snapshot(in.unit, before, step.level);
            step.efficiency = efficiency(item, i, step, beforeSnap);
            for (PassiveDetail p : step.stats.passives) {
                if (p.purchaseIndex == i && p.staticFallback) {
                    step.warnings.add("Passiva '" + p.passive + "' não tem ref_type: usado valor estático " + fmt(p.value) + ".");
                }
            }
            result.steps.add(step);
        }

        buildSeries(in, result, inventoryAfterStep);
        return result;
    }

    /**
     * Consumes one owned copy of the component, or recursively its sub-components when it is not owned.
     * Returns the gold discounted.
     */
    private int consume(long componentId, List<Owned> inventory, List<Owned> consumed) {
        for (int k = 0; k < inventory.size(); k++) {
            if (inventory.get(k).item.id == componentId) {
                Owned o = inventory.remove(k);
                consumed.add(o);
                return o.item.cost;
            }
        }
        ItemDef comp = ref.items.get(componentId);
        if (comp == null) {
            return 0;
        }
        int discount = 0;
        for (ComponentRef sub : comp.components) {
            for (int q = 0; q < sub.quantity; q++) {
                discount += consume(sub.itemId, inventory, consumed);
            }
        }
        return discount;
    }

    public int levelForXp(double xp) {
        int level = 1;
        for (Map.Entry<Integer, Double> e : ref.xpTable.entrySet()) {
            if (e.getKey() <= MAX_LEVEL && xp + 1e-9 >= e.getValue()) {
                level = Math.max(level, e.getKey());
            }
        }
        return level;
    }

    public double forgePct(int level) {
        double pct = 0;
        int best = Integer.MIN_VALUE;
        for (ForgeTier t : ref.forgeTiers) {
            if (level >= t.minLevel && t.minLevel > best) {
                best = t.minLevel;
                pct = t.pct;
            }
        }
        return pct;
    }

    private StatSnapshot snapshot(Model.UnitProfile unit, List<Owned> inventory, int level) {
        StatSnapshot s = new StatSnapshot();
        s.level = level;

        // Step 2: unit base stats at this level.
        for (Map.Entry<String, StatGrowth> e : unit.stats.entrySet()) {
            s.base.put(e.getKey(), e.getValue().at(level));
        }

        // Step 1: flat stats of every owned item (level ranges interpolated, conditional ones only when enabled).
        for (Owned o : inventory) {
            for (StatLine l : o.item.stats) {
                if (l.countsAsFlat() && counts(l)) {
                    s.itemFlat.merge(l.type, l.valueAt(level), Double::sum);
                }
            }
        }

        // Step 3: percentage passives, composed in purchase order.
        for (Owned o : inventory) {
            Map<String, Double> contributions = new LinkedHashMap<>();
            for (StatLine l : o.item.stats) {
                if (!l.hasRatio() || l.marker() || !counts(l)) {
                    continue;
                }
                PassiveDetail d = new PassiveDetail();
                d.purchaseIndex = o.purchaseIndex;
                d.itemName = o.item.name;
                d.passive = l.passive;
                d.stat = l.type;
                d.ratio = l.ratio;
                d.refType = l.refType;
                d.refScope = l.refScope;
                d.conditional = l.conditional;
                if (l.dynamicPercent()) {
                    boolean withBase = l.refScope != RefScope.BONUS;
                    boolean withBonus = l.refScope != RefScope.BASE;
                    d.refBase = withBase ? s.base.getOrDefault(l.refType, 0.0) : 0;
                    d.refItems = withBonus ? s.itemFlat.getOrDefault(l.refType, 0.0) : 0;
                    // itemPassives only holds items bought before this one at this point of the loop
                    d.refEarlierPassives = withBonus ? s.itemPassives.getOrDefault(l.refType, 0.0) : 0;
                    d.refTotal = d.refBase + d.refItems + d.refEarlierPassives;
                    d.value = l.ratio * d.refTotal;
                    String parts = l.refScope == RefScope.BASE ? fmt(d.refBase) + " base"
                            : (withBase ? fmt(d.refBase) + " base + " : "") + fmt(d.refItems) + " itens + "
                            + fmt(d.refEarlierPassives) + " passivas anteriores";
                    d.formula = fmt(l.ratio) + " × (" + parts + ") = " + fmt(d.value) + " " + l.type
                            + (l.conditional ? " (condicional)" : "");
                } else {
                    d.staticFallback = true;
                    d.value = l.value != null ? l.value : (l.ref != null ? l.ratio * l.ref : 0);
                    d.formula = "valor estático da referência = " + fmt(d.value) + " " + l.type;
                }
                contributions.merge(l.type, d.value, Double::sum);
                s.passives.add(d);
            }
            // Added only after the whole item was evaluated: an item never compounds on itself.
            contributions.forEach((k, v) -> s.itemPassives.merge(k, v, Double::sum));
        }

        // Step 4: Living Forge on bonus health / armor / MR.
        s.forgePct = unit.livingForge ? forgePct(level) : 0;
        if (unit.livingForge) {
            for (String stat : Stats.LIVING_FORGE_STATS) {
                ForgeDetail f = new ForgeDetail();
                f.stat = stat;
                f.bonus = s.itemFlat.getOrDefault(stat, 0.0) + s.itemPassives.getOrDefault(stat, 0.0);
                f.pct = s.forgePct;
                f.value = f.bonus * f.pct;
                s.forge.put(stat, f.value);
                s.forgeDetails.add(f);
            }
        }

        // Totals.
        Set<String> keys = new LinkedHashSet<>(Stats.DISPLAY_STATS);
        keys.addAll(s.base.keySet());
        keys.addAll(s.itemFlat.keySet());
        keys.addAll(s.itemPassives.keySet());
        for (String k : keys) {
            double v = s.base.getOrDefault(k, 0.0) + bonusOf(s, k);
            s.total.put(k, v);
        }
        // Regen: items give % of base regen.
        s.total.put(Stats.HEALTH_REGEN, s.base.getOrDefault(Stats.HEALTH_REGEN, 0.0)
                * (1 + bonusOf(s, Stats.PCT_HEALTH_REGEN) / 100.0));
        s.total.put(Stats.MANA_REGEN, s.base.getOrDefault(Stats.MANA_REGEN, 0.0)
                * (1 + bonusOf(s, Stats.PCT_MANA_REGEN) / 100.0));
        return s;
    }

    private boolean counts(StatLine l) {
        return includeConditional || !l.conditional;
    }

    private static double bonusOf(StatSnapshot s, String stat) {
        return s.itemFlat.getOrDefault(stat, 0.0) + s.itemPassives.getOrDefault(stat, 0.0) + s.forge.getOrDefault(stat, 0.0);
    }

    private double goldValue(String stat, double amount) {
        return amount * ref.statPrices.getOrDefault(stat, 0.0);
    }

    /** Gold value of every bonus stat (items, passives, Living Forge) of a snapshot. */
    private double bonusGoldValue(StatSnapshot s) {
        Set<String> keys = new LinkedHashSet<>(s.itemFlat.keySet());
        keys.addAll(s.itemPassives.keySet());
        keys.addAll(s.forge.keySet());
        double total = 0;
        for (String k : keys) {
            total += goldValue(k, bonusOf(s, k));
        }
        return total;
    }

    private Efficiency efficiency(ItemDef item, int purchaseIndex, TimelineStep step, StatSnapshot before) {
        Efficiency e = new Efficiency();
        GoldPricing.StaticResult st = GoldPricing.staticEfficiency(item, ref.statPrices, ref.baseItemNames);
        e.staticPct = st.pct;
        e.staticFormula = st.formula;

        double worth = 0;
        StringBuilder f = new StringBuilder();
        for (StatLine l : item.stats) {
            if (l.countsAsFlat() && counts(l)) {
                double v = l.valueAt(step.level);
                worth += goldValue(l.type, v);
                appendTerm(f, v, l.type + (l.passive != null ? " [" + l.passive + "]" : ""));
            }
        }
        for (PassiveDetail p : step.stats.passives) {
            if (p.purchaseIndex == purchaseIndex) {
                worth += goldValue(p.stat, p.value);
                appendTerm(f, p.value, p.stat + " [" + p.passive + "]");
            }
        }
        if (item.cost > 0) {
            e.dynamicPct = worth / item.cost * 100;
            e.dynamicFormula = "(" + (f.length() == 0 ? "0" : f) + ") / " + item.cost + " = " + fmt(worth) + " / " + item.cost;
        }

        double gain = bonusGoldValue(step.stats) - bonusGoldValue(before);
        e.marginalGold = gain;
        if (step.paidCost > 0) {
            e.marginalPct = gain / step.paidCost * 100;
        }
        e.marginalFormula = "(valor bônus depois − antes, no nível " + step.level + ") / ouro pago = "
                + fmt(gain) + " / " + step.paidCost;
        return e;
    }

    private void appendTerm(StringBuilder f, double value, String stat) {
        if (f.length() > 0) {
            f.append(" + ");
        }
        f.append(fmt(value)).append('×').append(fmt(ref.statPrices.getOrDefault(stripPassive(stat), 0.0)))
                .append(" (").append(stat).append(')');
    }

    private static String stripPassive(String label) {
        int i = label.indexOf(" [");
        return i < 0 ? label : label.substring(0, i);
    }

    /** One point per minute (plus every purchase instant) so builds can be charted and overlaid. */
    private void buildSeries(EngineInput in, TimelineResult result, List<List<Owned>> inventoryAfterStep) {
        double lastPurchase = result.steps.isEmpty() ? 0 : result.steps.get(result.steps.size() - 1).minute;
        int horizon = (int) Math.ceil(lastPurchase) + 3;
        List<Double> times = new ArrayList<>();
        for (int m = 0; m <= horizon; m++) {
            times.add((double) m);
        }
        for (TimelineStep s : result.steps) {
            times.add(s.minute);
        }
        times.sort(Double::compare);

        double prev = -1;
        for (double t : times) {
            if (Math.abs(t - prev) < 1e-9) {
                continue;
            }
            prev = t;
            int stepIdx = -1;
            List<Owned> inv = new ArrayList<>();
            for (TimelineStep s : result.steps) {
                if (s.minute <= t + 1e-9) {
                    stepIdx = s.index;
                    inv = inventoryAfterStep.get(s.index);
                }
            }
            int level = levelForXp(in.xpPerMin * t);
            StatSnapshot snap = snapshot(in.unit, inv, level);
            MinutePoint p = new MinutePoint();
            p.minute = t;
            p.level = level;
            p.stepIndex = stepIdx;
            for (String k : Stats.DISPLAY_STATS) {
                p.total.put(k, snap.total.getOrDefault(k, 0.0));
            }
            result.series.add(p);
        }
    }
}
