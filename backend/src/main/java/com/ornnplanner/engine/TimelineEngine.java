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
 *   <li><b>Conversions</b> (a line "ratio x ref_type" whose target stat differs from ref_type, e.g. Mana -> Health,
 *       bonus Health -> AD) read the <i>final</i> value of their source stat, so stats are resolved in dependency
 *       order (Mana before Health before AD/AP...). Scope TOTAL = base + bonus, BONUS = bonus only, BASE = base only.</li>
 *   <li><b>Living Forge</b> (Ornn) multiplies flat + conversions + the gains of continuous % of bonus multipliers.
 *       <b>% of bonus</b> multipliers (Duplaguarda) are continuous: they read all bonus (Forge and buffs included) and
 *       the Forge amplifies their gain. <b>% of total</b> multipliers (Manto da Aurora, Rabadon) read the total after
 *       a first pass of the % of bonus ones and are not amplified by the Forge. In game the result does not depend on
 *       when each effect was activated (the game recomputes in a fixed order). Fitted to 8 in-game
 *       tooltips (Ornn level 15, six tank items), all matched within ~1 point.</li>
 *   <li>The result never depends on purchase order, only on what is held. Reference: League of Legends wiki,
 *       Rabadon's Deathcap notes ("multiplier stacks additively with Infernal Might" / "stacks recursively with other
 *       sources of ability power"). Ratio lines without a ref_type fall back to the reference data's static value.</li>
 *   <li>Gold value of any stat amount = amount * gold price of the stat (see {@link GoldPricing}).</li>
 * </ol>
 *
 * <h2>Efficiencies of the purchased item</h2>
 * <ul>
 *   <li>static: reference-site formula, context free.</li>
 *   <li>dynamic: (gold value of its flat stats + gold value of its passive lines as evaluated with the whole
 *       inventory at this point of the build) / item cost.</li>
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
    /** Item slots besides the single boots slot. */
    public static final int ITEM_SLOTS = 5;
    /** Section of the engine items made from the champion's passive / abilities (passed with the runes). */
    public static final String CHAMPION_SECTION = "campeao";

    private final ReferenceData ref;

    public TimelineEngine(ReferenceData ref) {
        this.ref = ref;
    }

    /** One item in the inventory, remembering the purchase that brought it and its conditional-effects choice. */
    private static final class Owned {
        final ItemDef item;
        final int purchaseIndex;
        /** Purchase of the build it belongs to, and whether the engine assumed it (component bought towards it). */
        final int buildIndex;
        /** Number of the build's item purchase (1, 2...; purchase moments not counted). */
        final int number;
        final boolean implied;
        final boolean conditional;

        Owned(ItemDef item, int purchaseIndex, int buildIndex, int number, boolean implied, boolean conditional) {
            this.item = item;
            this.purchaseIndex = purchaseIndex;
            this.buildIndex = buildIndex;
            this.number = number;
            this.implied = implied;
            this.conditional = conditional;
        }

        /** Whether a line of this item counts: unconditional lines always, conditional ones only when enabled. */
        boolean counts(StatLine l) {
            return conditional || !l.conditional;
        }
    }

    /** Runes of the run in progress (purchase index -1, -2...). */
    private final List<Owned> runes = new ArrayList<>();
    /** Extra gold events of the run in progress, by minute. */
    private final List<double[]> extraGold = new ArrayList<>();

    public TimelineResult run(EngineInput in) {
        TimelineResult result = new TimelineResult();
        result.unitCode = in.unit.code;
        result.unitName = in.unit.name;
        result.startingGold = STARTING_GOLD;
        result.goldPerMin = in.goldPerMin;
        result.xpPerMin = in.xpPerMin;
        result.statPrices = ref.statPrices;

        if (in.goldPerMin <= 0) {
            result.warnings.add("Ouro por minuto deve ser maior que zero.");
            return result;
        }
        if (in.xpPerMin < 0) {
            result.warnings.add("XP por minuto não pode ser negativo.");
            return result;
        }

        result.matchEnd = in.matchEnd;
        extraGold.clear();
        for (double[] e : in.extraGold) {
            if (e != null && e.length >= 2 && e[0] >= 0) {
                extraGold.add(new double[] {e[0], e[1]});
            }
        }
        extraGold.sort(java.util.Comparator.comparingDouble(e -> e[0]));
        runes.clear();
        for (int k = 0; k < in.runes.size(); k++) {
            boolean cond = k >= in.runeConditional.size() || !Boolean.FALSE.equals(in.runeConditional.get(k));
            runes.add(new Owned(in.runes.get(k), -1 - k, -1, 0, false, cond));
        }
        List<Owned> inventory = new ArrayList<>();
        List<List<Owned>> inventoryAfterStep = new ArrayList<>();
        int cumulative = 0;
        double lastMinute = 0;       // purchases happen in sequence order: never before the previous one
        double lastBuildMinute = 0;  // minute of the previous purchase of the build (for "x minutes after")
        double gate = 0;             // earliest minute of the build's items: the last purchase moment passed
        int nextMoment = 0;
        List<Model.Moment> moments = new ArrayList<>(in.moments);
        moments.sort(java.util.Comparator.comparingInt(m -> m.position));

        for (int i = 0; i < in.itemIds.size(); i++) {
            int position = i < in.positions.size() ? in.positions.get(i) : i;
            while (nextMoment < moments.size() && moments.get(nextMoment).position < position) {
                Model.Moment m = moments.get(nextMoment++);
                m.minute = Math.max(lastMinute, m.atMinute != null ? m.atMinute
                        : lastBuildMinute + (m.afterMinutes == null ? 0 : m.afterMinutes));
                gate = m.minute;
                result.moments.add(m);
            }
            ItemDef item = ref.items.get(in.itemIds.get(i));
            if (item == null) {
                result.warnings.add("Compra #" + (position + 1) + ": item id " + in.itemIds.get(i) + " não existe no catálogo.");
                inventoryAfterStep.add(new ArrayList<>(inventory));
                continue;
            }
            boolean cond = i < in.conditional.size() && in.conditional.get(i) != null
                    ? in.conditional.get(i) : in.includeConditional;
            // Build options: the missing components are bought first, one by one as gold allows, towards this item.
            List<ItemDef> queue = impliedComponents(item, inventory, in.assumeHalfItems, in.assumeSmallItems);
            queue.add(item);
            for (int q = 0; q < queue.size(); q++) {
                ItemDef buy = queue.get(q);
                boolean implied = q < queue.size() - 1;
                int pos = inventoryAfterStep.size();
                TimelineStep step = new TimelineStep();
                step.index = pos;
                step.buildIndex = position;
                step.number = i + 1;
                step.implied = implied;
                step.itemId = buy.id;
                step.itemName = buy.name;
                step.category = buy.category;
                step.itemCost = buy.cost;

                List<Owned> before = new ArrayList<>(inventory);
                List<Owned> consumed = new ArrayList<>();
                int discount = 0;
                for (ComponentRef c : buy.components) {
                    for (int k = 0; k < c.quantity; k++) {
                        discount += consume(c.itemId, inventory, consumed);
                    }
                }
                for (Owned o : consumed) {
                    step.consumedComponents.add(o.item.name);
                }
                step.paidCost = Math.max(0, buy.cost - discount);
                cumulative += step.paidCost;
                step.cumulativeGold = cumulative;
                // As soon as the gold covers it, never before the previous purchase; the build's own items also wait
                // for the last purchase moment (assumed components are bought instantly).
                step.minute = Math.max(minuteFor(cumulative, in.goldPerMin), lastMinute);
                if (!implied) {
                    step.minute = Math.max(step.minute, gate);
                }
                step.goldLeft = goldAt(step.minute, in.goldPerMin) - cumulative;
                step.xp = in.xpPerMin * step.minute;
                step.level = levelForXp(step.xp);

                Owned bought = new Owned(buy, pos, position, i + 1, implied, cond);
                inventory.add(bought);
                inventoryAfterStep.add(new ArrayList<>(inventory));
                for (Owned o : inventory) {
                    step.inventory.add(o.item.name);
                    step.inventoryIds.add(o.item.id);
                }
                step.violations = violations(buy, inventory);
                if (implied && !step.violations.isEmpty()) {
                    // An assumed component that would break a shop rule (e.g. no free slot) is simply not bought.
                    inventory.clear();
                    inventory.addAll(before);
                    cumulative -= step.paidCost;
                    inventoryAfterStep.remove(inventoryAfterStep.size() - 1);
                    continue;
                }
                if (exceedsLimit(step.violations)) {
                    // No free slot: the purchase is impossible at this point of the order, so it is left out entirely
                    // (no gold, no stats, components not consumed) and reported apart.
                    inventory.clear();
                    inventory.addAll(before);
                    cumulative -= step.paidCost;
                    inventoryAfterStep.set(inventoryAfterStep.size() - 1, new ArrayList<>(before));
                    step.ignored = true;
                    step.inventory.clear();
                    step.inventoryIds.clear();
                    for (Owned o : before) {
                        step.inventory.add(o.item.name);
                        step.inventoryIds.add(o.item.id);
                    }
                    result.ignored.add(step);
                    continue;
                }

                step.stats = snapshot(in.unit, inventory, step.level, step.minute);
                StatSnapshot beforeSnap = snapshot(in.unit, before, step.level, step.minute);
                step.efficiency = efficiency(bought, step, beforeSnap);
                for (PassiveDetail p : step.stats.passives) {
                    if (p.purchaseIndex == pos && p.staticFallback) {
                        step.warnings.add("Passiva '" + p.passive + "' não tem ref_type: usado valor estático " + fmt(p.value) + ".");
                    }
                }
                result.steps.add(step);
                lastMinute = step.minute;
                if (!implied) {
                    lastBuildMinute = step.minute;
                }
            }
        }
        while (nextMoment < moments.size()) {  // moments after the last item: shown, nothing bought
            Model.Moment m = moments.get(nextMoment++);
            m.minute = Math.max(lastMinute, m.atMinute != null ? m.atMinute
                    : lastBuildMinute + (m.afterMinutes == null ? 0 : m.afterMinutes));
            result.moments.add(m);
        }
        // Gold in the bag at each moment, before the first item it releases: earned minus everything bought until then
        // in sequence order (the assumed components of that item, bought instantly, included).
        for (Model.Moment m : result.moments) {
            double spent = 0;
            for (TimelineStep s : result.steps) {
                if (!s.implied && s.buildIndex > m.position) {
                    break;
                }
                spent += s.paidCost;
            }
            m.gold = goldAt(m.minute, in.goldPerMin) - spent;
        }

        buildSeries(in, result, inventoryAfterStep);
        return result;
    }

    /**
     * Components of {@code item} that are missing from the inventory and assumed bought before it, in recipe order
     * (sub-components before the half item they build). Smaller items = basic ones (section "basico", or no
     * recipe); half items = the other components (mid tier, or a boots upgrade's boots). With only smaller items assumed, a missing half item is replaced by its basic parts.
     * Owned components are reserved as the recipe would consume them, so they are never bought again.
     */
    List<ItemDef> impliedComponents(ItemDef item, List<Owned> inventory, boolean half, boolean small) {
        List<ItemDef> out = new ArrayList<>();
        if (!half && !small) {
            return out;
        }
        List<Long> owned = new ArrayList<>();
        for (Owned o : inventory) {
            owned.add(o.item.id);
        }
        expand(item, owned, half, small, out);
        return out;
    }

    private void expand(ItemDef item, List<Long> owned, boolean half, boolean small, List<ItemDef> out) {
        for (ComponentRef c : item.components) {
            for (int q = 0; q < c.quantity; q++) {
                if (owned.remove(Long.valueOf(c.itemId))) {
                    continue;
                }
                ItemDef comp = ref.items.get(c.itemId);
                if (comp == null) {
                    continue;
                }
                boolean basic = "basico".equals(comp.section) || comp.components.isEmpty() && !"tier_medio".equals(comp.section);
                if (basic) {
                    if (small) {
                        out.add(comp);
                    }
                } else {
                    expand(comp, owned, half, small, out);
                    if (half) {
                        out.add(comp);
                    }
                }
            }
        }
    }

    /** Gold earned by a minute: starting gold + gold/min, plus the extra gold events up to it. */
    private double goldAt(double minute, double goldPerMin) {
        double extra = 0;
        for (double[] e : extraGold) {
            if (e[0] <= minute + 1e-9) {
                extra += e[1];
            }
        }
        return STARTING_GOLD + goldPerMin * minute + extra;
    }

    /** First minute the gold earned covers {@code gold} (extra gold events make it a step function). */
    private double minuteFor(double gold, double goldPerMin) {
        double start = 0;
        double extra = 0;
        for (double[] e : extraGold) {
            double t = (gold - STARTING_GOLD - extra) / goldPerMin;
            if (t <= e[0] + 1e-9) {
                return Math.max(start, Math.max(0, t));
            }
            extra += e[1];
            start = e[0];
        }
        return Math.max(start, Math.max(0, (gold - STARTING_GOLD - extra) / goldPerMin));
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

    /**
     * Shop rules, checked on the inventory right after a purchase (owned components already consumed, as in game):
     * no completed item twice, one item per exclusive group, one active item, one boots and five other items.
     * Only violations involving the item just bought are reported, so a broken rule shows up once.
     */
    static List<Model.Violation> violations(ItemDef bought, List<Owned> inventory) {
        List<Model.Violation> out = new ArrayList<>();
        String key = bought.group == null ? bought.name : bought.group;
        if (bought.finished()) {
            List<String> same = names(inventory, o -> key.equals(o.group == null ? o.name : o.group));
            if (same.size() > 1) {
                out.add(new Model.Violation("duplicate", null, same));
            }
        }
        for (String group : bought.exclusiveGroups) {
            List<String> members = names(inventory, o -> o.exclusiveGroups.contains(group));
            if (members.size() > 1) {
                out.add(new Model.Violation("exclusive", group, members));
            }
        }
        if (bought.active) {
            List<String> actives = names(inventory, o -> o.active);
            if (actives.size() > 1) {
                out.add(new Model.Violation("active", null, actives));
            }
        }
        if (bought.boots()) {
            List<String> boots = names(inventory, ItemDef::boots);
            if (boots.size() > 1) {
                out.add(new Model.Violation("boots", null, boots));
            }
        } else {
            List<String> others = names(inventory, o -> !o.boots());
            if (others.size() > ITEM_SLOTS) {
                out.add(new Model.Violation("slots", null, others));
            }
        }
        return out;
    }

    /** Slot limits (five items + one boots): a purchase breaking them is ignored instead of counted. */
    static boolean exceedsLimit(List<Model.Violation> violations) {
        for (Model.Violation v : violations) {
            if ("slots".equals(v.code) || "boots".equals(v.code)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> names(List<Owned> inventory, java.util.function.Predicate<ItemDef> filter) {
        List<String> out = new ArrayList<>();
        for (Owned o : inventory) {
            if (filter.test(o.item)) {
                out.add(o.item.name);
            }
        }
        return out;
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

    private StatSnapshot snapshot(Model.UnitProfile unit, List<Owned> items, int level, double minute) {
        // The build's runes count like items held from the start (they never use a slot or cost gold).
        List<Owned> inventory = new ArrayList<>(runes);
        inventory.addAll(items);
        StatSnapshot s = new StatSnapshot();
        s.level = level;

        // Step 2: unit base stats at this level.
        for (Map.Entry<String, StatGrowth> e : unit.stats.entrySet()) {
            s.base.put(e.getKey(), e.getValue().at(level));
        }

        // Step 1: flat stats of every owned item (level ranges interpolated, conditional ones only when enabled).
        for (Owned o : inventory) {
            for (StatLine l : o.item.stats) {
                if (l.countsAsFlat() && o.counts(l) && (l.minLevel == null || level >= l.minLevel)
                        && (l.minMinute == null || minute >= l.minMinute - 1e-9)) {
                    s.itemFlat.merge(l.type, l.valueAt(level, minute), Double::sum);
                }
            }
        }

        // Steps 3-4: conversions, multipliers and Living Forge, independent of purchase order.
        new Resolver(s, inventory, level, minute, unit.livingForge ? forgePct(level) : 0, unit.livingForge).run();

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

    /** A ratio line of an owned item. */
    private static final class RatioLine {
        final Owned owner;
        final StatLine line;
        PassiveDetail detail;

        RatioLine(Owned owner, StatLine line) {
            this.owner = owner;
            this.line = line;
        }

        boolean conversion() {
            return !line.type.equals(line.refType);
        }
    }

    /**
     * Resolves every stat of a snapshot: total(stat) = base + bonusPre + multipliers + forge, where
     * bonusPre = flat + conversions into the stat (each reading the final value of its source stat) and each
     * multiplier / the Living Forge is a percentage of the pre-multiplier value (added, not compounded).
     */
    private final class Resolver {
        private final StatSnapshot s;
        private final List<Owned> inventory;
        private final int level;
        private final double minute;
        private final double forgePct;
        private final boolean forge;
        private final List<RatioLine> lines = new ArrayList<>();
        private final Map<String, Double> finalTotal = new LinkedHashMap<>();
        private final Set<String> resolving = new LinkedHashSet<>();

        Resolver(StatSnapshot s, List<Owned> inventory, int level, double minute, double forgePct, boolean forge) {
            this.s = s;
            this.inventory = inventory;
            this.level = level;
            this.minute = minute;
            this.forgePct = forgePct;
            this.forge = forge;
        }

        void run() {
            s.forgePct = forgePct;
            for (Owned o : inventory) {
                for (StatLine l : o.item.stats) {
                    if (l.hasRatio() && !l.marker() && o.counts(l) && (l.minMinute == null || minute >= l.minMinute - 1e-9)) {
                        lines.add(new RatioLine(o, l));
                    }
                }
            }
            // Lines without ref_type: fixed value from the reference data.
            for (RatioLine r : lines) {
                if (!r.line.dynamicPercent()) {
                    PassiveDetail d = detail(r);
                    d.staticFallback = true;
                    d.value = r.line.value != null ? r.line.value : (r.line.ref != null ? r.line.ratioAt(level) * r.line.ref : 0);
                    d.formula = "valor estático da referência = " + fmt(d.value) + " " + r.line.type;
                    s.itemPassives.merge(r.line.type, d.value, Double::sum);
                }
            }
            Set<String> stats = new LinkedHashSet<>(s.base.keySet());
            stats.addAll(s.itemFlat.keySet());
            for (RatioLine r : lines) {
                if (r.line.dynamicPercent()) {
                    stats.add(r.line.type);
                    stats.add(r.line.refType);
                }
            }
            if (forge) {
                stats.addAll(Stats.LIVING_FORGE_STATS);
            }
            for (String stat : stats) {
                resolve(stat);
            }
            // Keep the purchase order in the breakdown, and one contribution per owned item.
            lines.sort(java.util.Comparator.comparingInt(r -> r.owner.purchaseIndex));
            for (RatioLine r : lines) {
                s.passives.add(r.detail);
            }
            for (Owned o : inventory) {
                List<Model.PassivePart> parts = new ArrayList<>();
                for (RatioLine r : lines) {
                    if (r.owner == o) {
                        parts.add(part(r.detail.passive, r.detail.stat, r.detail.value, r.line.conditional));
                    }
                }
                s.contributions.add(contribution(o, level, minute, parts));
            }
        }

        /** Final total of a stat (base + every bonus). */
        double resolve(String stat) {
            Double done = finalTotal.get(stat);
            if (done != null) {
                return done;
            }
            double base = s.base.getOrDefault(stat, 0.0);
            if (!resolving.add(stat)) {
                // Conversion cycle (none in the catalog): fall back to the stat without conversions.
                return base + s.itemFlat.getOrDefault(stat, 0.0);
            }
            double pre = s.itemFlat.getOrDefault(stat, 0.0);
            for (RatioLine r : lines) {
                if (r.line.dynamicPercent() && r.conversion() && r.line.type.equals(stat)) {
                    // refPre (e.g. Vladimir, Pyke): only the flat bonus of the source, so two conversions between the
                    // same stats do not feed each other and a multiplier removing the source does not cancel it.
                    double sourceBase = s.base.getOrDefault(r.line.refType, 0.0);
                    double source = r.line.refPre ? sourceBase + s.itemFlat.getOrDefault(r.line.refType, 0.0)
                            : resolve(r.line.refType);
                    double ref = r.line.refPre || r.line.refScope == RefScope.BONUS ? source - sourceBase
                            : r.line.refScope == RefScope.BASE ? sourceBase : source;
                    PassiveDetail d = detail(r);
                    d.refBase = r.line.refScope == RefScope.BONUS ? 0 : sourceBase;
                    d.refItems = r.line.refScope == RefScope.BASE ? 0 : source - sourceBase;
                    d.refTotal = ref;
                    d.value = r.line.ratioAt(level) * ref;
                    d.formula = fmt(r.line.ratioAt(level)) + " × " + fmt(ref) + " " + r.line.refType + " ("
                            + scopeLabel(r.line.refScope) + ", valor final) = " + fmt(d.value) + " " + stat
                            + (r.line.conditional ? " (condicional)" : "");
                    pre += d.value;
                    s.itemPassives.merge(stat, d.value, Double::sum);
                }
            }
            // Multipliers (target = ref_type), fitted to in-game values (Ornn level 15, Duplaguarda de Amaranto and
            // Manto da Aurora, 8 tooltips matched within ~1 point):
            //  - % of BONUS (e.g. Duplaguarda): reads all bonus of the stat (Living Forge and % of total gains
            //    included, its own gain excluded) and its gain is amplified by the Forge.
            //  - % of TOTAL (e.g. Manto da Aurora, Rabadon): reads the total after a first pass of the % of bonus
            //    ones; added as bonus but NOT amplified by the Forge. Activation timing does not change the result in
            //    game (Aurora activated before and after Duplaguarda stacks gave the same tooltip).
            //  - % of BASE (e.g. Sterak): ratio x base.
            // Sequence: bonus multipliers (1st pass) -> total multipliers -> bonus multipliers again (now seeing them).
            double f = forge && Stats.LIVING_FORGE_STATS.contains(stat) ? forgePct : 0;
            double forged = pre * (1 + f);
            double bonusFirst = 0;
            for (RatioLine r : multiplierLines(stat, RefScope.BONUS)) {
                bonusFirst += r.line.ratioAt(level) * forged;
            }
            double totalBeforeBuffs = base + forged + bonusFirst * (1 + f);
            double totalGains = 0;
            for (RatioLine r : multiplierLines(stat, RefScope.TOTAL)) {
                PassiveDetail d = detail(r);
                d.refBase = base;
                d.refItems = totalBeforeBuffs - base;
                d.refTotal = totalBeforeBuffs;
                d.value = r.line.ratioAt(level) * totalBeforeBuffs;
                d.formula = fmt(r.line.ratioAt(level)) + " × " + fmt(totalBeforeBuffs) + " " + stat
                        + " (total" + (f > 0 ? ", com Forja; não é amplificado por ela" : "") + ") = "
                        + fmt(d.value) + (r.line.conditional ? " (condicional)" : "");
                totalGains += d.value;
                s.itemPassives.merge(stat, d.value, Double::sum);
            }
            double bonusGains = 0;
            for (RatioLine r : multiplierLines(stat, RefScope.BONUS)) {
                double ref = forged + totalGains;
                PassiveDetail d = detail(r);
                d.refBase = 0;
                d.refItems = ref;
                d.refTotal = ref;
                d.value = r.line.ratioAt(level) * ref;
                d.formula = fmt(r.line.ratioAt(level)) + " × (" + fmt(forged) + " adicional" + (f > 0 ? " com Forja" : "")
                        + (totalGains > 0 ? " + " + fmt(totalGains) + " de bônus de % do total" : "") + ") = "
                        + fmt(d.value) + " " + stat + (f > 0 ? " (+" + fmt(d.value * f) + " da Forja Viva)" : "")
                        + (r.line.conditional ? " (condicional)" : "");
                bonusGains += d.value;
                s.itemPassives.merge(stat, d.value, Double::sum);
            }
            double baseGains = 0;
            for (RatioLine r : multiplierLines(stat, RefScope.BASE)) {
                PassiveDetail d = detail(r);
                d.refBase = base;
                d.refTotal = base;
                d.value = r.line.ratioAt(level) * base;
                d.formula = fmt(r.line.ratioAt(level)) + " × " + fmt(base) + " base = " + fmt(d.value) + " " + stat
                        + (r.line.conditional ? " (condicional)" : "");
                baseGains += d.value;
                s.itemPassives.merge(stat, d.value, Double::sum);
            }
            double gains = bonusGains;   // the only gains the Forge amplifies
            double multipliers = totalGains + bonusGains + baseGains;
            double forgeValue = 0;
            if (f > 0) {
                ForgeDetail fd = new ForgeDetail();
                fd.stat = stat;
                fd.bonus = pre + gains;
                fd.pct = f;
                fd.value = (pre + gains) * f;
                forgeValue = fd.value;
                s.forge.put(stat, fd.value);
                s.forgeDetails.add(fd);
            }
            double total = base + pre + multipliers + forgeValue;
            resolving.remove(stat);
            finalTotal.put(stat, total);
            return total;
        }

        private List<RatioLine> multiplierLines(String stat, RefScope scope) {
            List<RatioLine> out = new ArrayList<>();
            for (RatioLine r : lines) {
                if (r.line.dynamicPercent() && !r.conversion() && r.line.type.equals(stat) && r.line.refScope == scope) {
                    out.add(r);
                }
            }
            return out;
        }

        private PassiveDetail detail(RatioLine r) {
            PassiveDetail d = new PassiveDetail();
            d.purchaseIndex = r.owner.purchaseIndex;
            d.buildIndex = r.owner.buildIndex;
            d.number = r.owner.number;
            d.rune = r.owner.purchaseIndex < 0;
            d.champion = CHAMPION_SECTION.equals(r.owner.item.section);
            d.implied = r.owner.implied;
            d.itemName = r.owner.item.name;
            d.passive = r.line.passive;
            d.stat = r.line.type;
            d.ratio = r.line.ratioAt(level);
            d.refType = r.line.refType;
            d.refScope = r.line.refScope;
            d.conditional = r.line.conditional;
            r.detail = d;
            return d;
        }
    }

    private static String scopeLabel(RefScope scope) {
        return scope == RefScope.BONUS ? "adicional" : scope == RefScope.BASE ? "base" : "total";
    }

    private Model.PassivePart part(String passive, String stat, double amount, boolean conditional) {
        Model.PassivePart p = new Model.PassivePart();
        p.passive = passive;
        p.stat = stat;
        p.amount = amount;
        p.gold = goldValue(stat, amount);
        p.conditional = conditional;
        return p;
    }

    /**
     * Gold value an owned item adds: plain stats, and its passives one by one (percentage passives as evaluated in
     * step 3, plus fixed bonuses that belong to a named passive, e.g. "Salva-Vidas: +200–300 Vida").
     */
    private Model.ItemContribution contribution(Owned o, int level, double minute, List<Model.PassivePart> ratioParts) {
        Model.ItemContribution c = new Model.ItemContribution();
        c.purchaseIndex = o.purchaseIndex;
        c.buildIndex = o.buildIndex;
        c.number = o.number;
        c.rune = o.purchaseIndex < 0;
        c.champion = CHAMPION_SECTION.equals(o.item.section);
        c.implied = o.implied;
        c.itemId = o.item.id;
        c.itemName = o.item.name;
        c.conditionalIncluded = o.conditional;
        for (StatLine l : o.item.stats) {
            if (!l.countsAsFlat() || !o.counts(l)) {
                continue;
            }
            if (l.passive != null) {
                c.passiveParts.add(part(l.passive, l.type, l.valueAt(level, minute), l.conditional));
            } else {
                c.flat.merge(l.type, l.valueAt(level, minute), Double::sum);
            }
        }
        c.passiveParts.addAll(ratioParts);
        for (Model.PassivePart p : c.passiveParts) {
            c.passives.merge(p.stat, p.amount, Double::sum);
            c.passiveGold += p.gold;
        }
        c.flat.forEach((k, v) -> c.flatGold += goldValue(k, v));
        return c;
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

    private Efficiency efficiency(Owned bought, TimelineStep step, StatSnapshot before) {
        ItemDef item = bought.item;
        int purchaseIndex = bought.purchaseIndex;
        Efficiency e = new Efficiency();
        GoldPricing.StaticResult st = GoldPricing.staticEfficiency(item, ref.statPrices, ref.baseItemNames);
        e.staticPct = st.pct;
        e.staticFormula = st.formula;

        double worth = 0;
        StringBuilder f = new StringBuilder();
        for (StatLine l : item.stats) {
            if (l.countsAsFlat() && bought.counts(l)) {
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
        // Up to the end of the match when given (stats that grow with time keep growing), else a bit after the build.
        double end = in.matchEnd != null && in.matchEnd > 0 ? in.matchEnd : Math.ceil(lastPurchase) + 3;
        List<Double> times = new ArrayList<>();
        for (int m = 0; m <= Math.floor(end + 1e-9); m++) {
            times.add((double) m);
        }
        times.add(end);
        for (TimelineStep s : result.steps) {
            if (s.minute <= end + 1e-9) {
                times.add(s.minute);
            }
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
            StatSnapshot snap = snapshot(in.unit, inv, level, t);
            MinutePoint p = new MinutePoint();
            p.minute = t;
            p.level = level;
            p.stepIndex = stepIdx;
            for (String k : Stats.DISPLAY_STATS) {
                p.total.put(k, snap.total.getOrDefault(k, 0.0));
            }
            snap.total.forEach(p.total::putIfAbsent);
            p.base.putAll(snap.base);
            p.forge.putAll(snap.forge);
            p.forgePct = snap.forgePct;
            p.contributions = snap.contributions;
            result.series.add(p);
        }
    }
}
