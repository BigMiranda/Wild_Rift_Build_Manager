package com.ornnplanner.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Plain data holders used by the calculation engine and exposed as JSON by the API.
 * Public fields on purpose: this is a small personal tool and these are pure DTOs.
 */
public final class Model {
    private Model() {
    }

    /** How the "reference total" of a percentage passive is measured. */
    public enum RefScope {
        /** Unit base stat at the current level + all bonus (items and earlier passives). */
        TOTAL,
        /** Only bonus (items and earlier passives), never the unit base stat. */
        BONUS,
        /** Only the unit base stat at the current level (e.g. "50% of base Attack Damage"). */
        BASE
    }

    /** One stat line of an item: a flat stat, or a percentage passive ("ratio x refType"). */
    public static class StatLine {
        public String type;
        /** Flat value at level 1. For ratio lines: optional fixed value used only by the static calculation. */
        public Double value;
        /** Flat value at level 15 when the shop shows a range ("200–300 by level"); linear in between. */
        public Double valueMax;
        /** Only active in some situations (stacks, in combat, low health...); the build decides whether to count it. */
        public boolean conditional;
        public String passive;
        /** Percentage passive ratio (0.3 = 30%). Null means the line is a flat stat. */
        public Double ratio;
        /** Champion-specific reference value used only by the static (reference site) calculation. */
        public Double ref;
        /** Stat the ratio is applied to. Null together with a ratio = cannot be evaluated dynamically. */
        public String refType;
        public RefScope refScope = RefScope.TOTAL;
        /** Flat line that only counts from this level on (e.g. rune Transcendência: +5 AH at level 5). */
        public Integer minLevel;
        /**
         * Flat line growing with game time, in periods: {start minute, value per minute}, each until the next one starts
         * (e.g. a rune's health from activations: more in the farming phase, fewer in the team fights).
         */
        public List<double[]> perMinute;
        /** Line that only counts from this game minute on (e.g. +3% health once a rune reaches 30 stacks). */
        public Double minMinute;
        /**
         * Flat line in steps of game time: values[k] from stepStart + k x stepEvery on; past the list, the increments keep
         * growing as in the list's last two (e.g. rune Tempestade Crescente: 2, 5, 9, 14... every 3 min from 6 min).
         */
        public Double stepStart;
        public Double stepEvery;
        public List<Double> stepValues;
        /**
         * Value at each champion level 1..15 (champion abilities: the rank follows the build's skill order). For a
         * per-minute line, the value of one stack at each level (the periods then count stacks).
         */
        public List<Double> levelValues;
        /** Ratio at each champion level 1..15 (champion abilities whose percentage grows with the rank). */
        public List<Double> levelRatios;
        /** Conversion that reads only the flat bonus of its source stat (items, runes), before any multiplier. */
        public boolean refPre;

        public StatLine() {
        }

        /** Ratio at a level: per-level ratios when given, else the fixed ratio. */
        public double ratioAt(int level) {
            if (levelRatios != null && !levelRatios.isEmpty()) {
                return levelRatios.get(Math.max(1, Math.min(levelRatios.size(), level)) - 1);
            }
            return ratio == null ? 0 : ratio;
        }

        private double perLevel(int level) {
            return levelValues.get(Math.max(1, Math.min(levelValues.size(), level)) - 1);
        }

        public static StatLine flat(String type, double value) {
            StatLine s = new StatLine();
            s.type = type;
            s.value = value;
            return s;
        }

        public static StatLine percent(String type, String passive, double ratio, String refType, RefScope scope) {
            StatLine s = new StatLine();
            s.type = type;
            s.passive = passive;
            s.ratio = ratio;
            s.refType = refType;
            s.refScope = scope;
            return s;
        }

        public boolean marker() {
            return Stats.UNSTABLE_MARKER.equals(type);
        }

        public boolean hasRatio() {
            return ratio != null || levelRatios != null;
        }

        /** Ratio line that can be evaluated against the build's real stats. */
        public boolean dynamicPercent() {
            return hasRatio() && refType != null;
        }

        /** Line that contributes a fixed amount (flat stat, or a ratio line we must fall back to its static value). */
        public boolean countsAsFlat() {
            return !marker() && !hasRatio()
                    && (value != null || perMinute != null || stepValues != null || levelValues != null);
        }

        /** Sum of per-minute rates over [0, minute]; periods {start, rate}, sorted, each until the next start. */
        public static double accumulated(List<double[]> periods, double minute) {
            double total = 0;
            for (int i = 0; i < periods.size(); i++) {
                double from = periods.get(i)[0];
                double to = i + 1 < periods.size() ? periods.get(i + 1)[0] : Double.MAX_VALUE;
                total += periods.get(i)[1] * Math.max(0, Math.min(minute, to) - from);
            }
            return total;
        }

        /** First minute the per-minute periods add up to {@code amount} (null if they never do). */
        public static Double minuteReaching(List<double[]> periods, double amount) {
            double total = 0;
            for (int i = 0; i < periods.size(); i++) {
                double from = periods.get(i)[0];
                double to = i + 1 < periods.size() ? periods.get(i + 1)[0] : Double.MAX_VALUE;
                double rate = periods.get(i)[1];
                if (rate > 0 && total + rate * (to - from) >= amount - 1e-9) {
                    return from + (amount - total) / rate;
                }
                total += rate * (to - from);
            }
            return null;
        }

        /** Flat value at a level and game minute. */
        public double valueAt(int level, double minute) {
            if (perMinute != null) {
                return accumulated(perMinute, minute) * (levelValues != null ? perLevel(level) : 1);
            }
            if (stepValues != null && !stepValues.isEmpty()) {
                if (minute < stepStart - 1e-9) {
                    return 0;
                }
                int k = (int) Math.floor((minute - stepStart) / stepEvery + 1e-9);
                int n = stepValues.size();
                if (k < n) {
                    return stepValues.get(k);
                }
                double v = stepValues.get(n - 1);
                double inc = n >= 2 ? stepValues.get(n - 1) - stepValues.get(n - 2) : 0;
                double grow = n >= 3 ? inc - (stepValues.get(n - 2) - stepValues.get(n - 3)) : 0;
                for (int j = n; j <= k; j++) {
                    inc += grow;
                    v += inc;
                }
                return v;
            }
            return valueAt(level);
        }

        /** Flat value at a level: `value` at level 1, `valueMax` at level 15, linear in between. */
        public double valueAt(int level) {
            if (levelValues != null && perMinute == null) {
                return perLevel(level);
            }
            if (value == null) {
                return 0;
            }
            if (valueMax == null) {
                return value;
            }
            int l = Math.max(1, Math.min(15, level));
            return value + (valueMax - value) * (l - 1) / 14.0;
        }
    }

    public static class ComponentRef {
        public long itemId;
        public int quantity = 1;

        public ComponentRef() {
        }

        public ComponentRef(long itemId, int quantity) {
            this.itemId = itemId;
            this.quantity = quantity;
        }
    }

    /** Passive / active text exactly as the shop shows it. */
    public static class PassiveText {
        public String name;
        public String text;

        public PassiveText() {
        }

        public PassiveText(String name, String text) {
            this.name = name;
            this.text = text;
        }
    }

    public static class ItemDef {
        public long id;
        public String name;
        public int cost;
        /** Shop tabs joined by ", " (kept for compatibility with older code paths and the admin screen). */
        public String category;
        public String image;
        public String sourcePatch;
        public boolean edited;
        /** Shop section: aprimorado | tier_medio | basico | preparacao | evolucao */
        public String section;
        /** Shop tabs the item appears in (Lutador, Defesa...). */
        public List<String> tabs = new ArrayList<>();
        public boolean active;
        /** Patch change badge: novo | reformulado | alterado */
        public String marker;
        public String summary;
        /** Item this one belongs to in the shop: itself, or the base item for an evolution (Fimbulwinter -> Aproximação Invernal). */
        public String group;
        /** Screenshot the data was transcribed from. */
        public String capture;
        public List<PassiveText> passives = new ArrayList<>();
        public List<StatLine> stats = new ArrayList<>();
        public List<ComponentRef> components = new ArrayList<>();
        /** Groups of which only one item can be held (Lâmina Arcana, Lágrima da Deusa, % penetration...). */
        public List<String> exclusiveGroups = new ArrayList<>();

        /** Boots take the single boots slot. */
        public boolean boots() {
            return tabs != null && tabs.contains("Botas");
        }

        /** Completed item (upgraded tier or an evolution): cannot be held twice. */
        public boolean finished() {
            return "aprimorado".equals(section) || "evolucao".equals(section);
        }
    }

    public static class StatGrowth {
        public double base;
        public double growth;

        public StatGrowth() {
        }

        public StatGrowth(double base, double growth) {
            this.base = base;
            this.growth = growth;
        }

        /** value(L) = base + growth * (L - 1) */
        public double at(int level) {
            return base + growth * (level - 1);
        }
    }

    public static class UnitProfile {
        public String code;
        public String name;
        public boolean livingForge;
        public Map<String, StatGrowth> stats = new LinkedHashMap<>();
    }

    public static class ForgeTier {
        public int minLevel;
        public double pct;

        public ForgeTier() {
        }

        public ForgeTier(int minLevel, double pct) {
            this.minLevel = minLevel;
            this.pct = pct;
        }
    }

    /** Everything the engine needs that comes from the reference tables. */
    public static class ReferenceData {
        public Map<Long, ItemDef> items = new LinkedHashMap<>();
        public List<ForgeTier> forgeTiers = new ArrayList<>();
        /** level -> cumulative XP needed to reach it (level 1 = 0). */
        public NavigableMap<Integer, Double> xpTable = new TreeMap<>();
        /** stat -> gold per point. */
        public Map<String, Double> statPrices = new LinkedHashMap<>();
        /** Names of items that are gold-price base items (static efficiency 100% by definition). */
        public java.util.Set<String> baseItemNames = new java.util.HashSet<>();
    }

    public static class EngineInput {
        public UnitProfile unit;
        /** Default for purchases without their own choice: count conditional effects (stacks, in combat...). */
        public boolean includeConditional = true;
        /** Per purchase (same order as itemIds): count that item's conditional effects. Null entries use the default. */
        public List<Boolean> conditional = new ArrayList<>();
        public double goldPerMin;
        public double xpPerMin;
        public List<Long> itemIds = new ArrayList<>();
        /** Assume the missing half items (tier_medio and other non-basic components) are bought before each item. */
        public boolean assumeHalfItems;
        /** Assume the missing smaller (basic) components are bought before each item. */
        public boolean assumeSmallItems;
        /**
         * Position of each item (same order as itemIds) in the build's sequence, which also holds purchase moments.
         * Empty = 0, 1, 2...
         */
        public List<Integer> positions = new ArrayList<>();
        /** Purchase moments of the sequence. */
        public List<Moment> moments = new ArrayList<>();
        /** Game minute the match ends (null = a few minutes after the last purchase). */
        public Double matchEnd;
        /** Extra gold earned at given minutes (e.g. runes Demolir, Primeiro Ataque): {minute, gold}. */
        public List<double[]> extraGold = new ArrayList<>();
        /** Runes of the build: held from the start, no cost, no inventory slot. */
        public List<ItemDef> runes = new ArrayList<>();
        /** Per rune (same order): count its conditional effects. */
        public List<Boolean> runeConditional = new ArrayList<>();
    }

    /**
     * Purchase moment ("back to base"): the items after it in the sequence are bought from its minute on (or later, when
     * the gold is not there yet). Assumed components are bought instantly, as soon as the gold allows.
     */
    public static class Moment {
        /** Position in the build's sequence. */
        public int position;
        /** Exact game minute... */
        public Double atMinute;
        /** ...or minutes after the previous purchase of the build. */
        public Double afterMinutes;
        /** Resolved game minute. */
        public double minute;
        /** Gold in the bag at that minute, before buying. */
        public double gold;

        public Moment() {
        }

        public Moment(int position, Double atMinute, Double afterMinutes) {
            this.position = position;
            this.atMinute = atMinute;
            this.afterMinutes = afterMinutes;
        }
    }

    // ----------------------------------------------------------------- results

    public static class PassiveDetail {
        /** Timeline position of the purchase (see TimelineStep.index). */
        public int purchaseIndex;
        /** Build purchase it belongs to, and whether it is a component the engine assumed bought. */
        public int buildIndex;
        /** Number of the build's item purchase (1, 2...), as shown to the user. */
        public int number;
        public boolean implied;
        /** From a rune of the build (not an item). */
        public boolean rune;
        /** From the champion's own passive or abilities (also flagged as rune: held from the start, no cost). */
        public boolean champion;
        public String itemName;
        public String passive;
        public String stat;
        public Double ratio;
        public String refType;
        public RefScope refScope;
        public double refBase;
        public double refItems;
        public double refEarlierPassives;
        public double refTotal;
        public double value;
        /** True when the line has no ref_type and the reference data's static value was used. */
        public boolean staticFallback;
        public boolean conditional;
        public String formula;
    }

    public static class ForgeDetail {
        public String stat;
        public double bonus;
        public double pct;
        public double value;
    }

    public static class Efficiency {
        /** Reference-site (static) gold efficiency, % */
        public Double staticPct;
        public String staticFormula;
        /** Dynamic gold efficiency of the purchased item in this build context, % */
        public Double dynamicPct;
        public String dynamicFormula;
        /** Gold value gained by the whole build (incl. Living Forge and other passives) / gold paid, % */
        public Double marginalPct;
        public Double marginalGold;
        public String marginalFormula;
    }

    /** One passive's share of an item's contribution. */
    public static class PassivePart {
        public String passive;
        public String stat;
        public double amount;
        public double gold;
        public boolean conditional;
    }

    /** What one owned item adds at a point of the timeline (before Living Forge). */
    public static class ItemContribution {
        /** Timeline position of the purchase (see TimelineStep.index). */
        public int purchaseIndex;
        /** Build purchase it belongs to, and whether it is a component the engine assumed bought. */
        public int buildIndex;
        /** Number of the build's item purchase (1, 2...), as shown to the user. */
        public int number;
        public boolean implied;
        /** From a rune of the build (not an item). */
        public boolean rune;
        /** From the champion's own passive or abilities (also flagged as rune: held from the start, no cost). */
        public boolean champion;
        public long itemId;
        public String itemName;
        public boolean conditionalIncluded;
        public Map<String, Double> flat = new LinkedHashMap<>();
        public Map<String, Double> passives = new LinkedHashMap<>();
        public List<PassivePart> passiveParts = new ArrayList<>();
        public double flatGold;
        public double passiveGold;
    }

    public static class StatSnapshot {
        public int level;
        public double forgePct;
        public Map<String, Double> base = new LinkedHashMap<>();
        public Map<String, Double> itemFlat = new LinkedHashMap<>();
        public Map<String, Double> itemPassives = new LinkedHashMap<>();
        public Map<String, Double> forge = new LinkedHashMap<>();
        public Map<String, Double> total = new LinkedHashMap<>();
        public List<PassiveDetail> passives = new ArrayList<>();
        public List<ForgeDetail> forgeDetails = new ArrayList<>();
        public List<ItemContribution> contributions = new ArrayList<>();
    }

    /** A shop rule broken by a purchase. `code` is translated by the UI; `items` are the items involved. */
    public static class Violation {
        /** duplicate | exclusive | active | boots | slots */
        public String code;
        /** Exclusive group name (code "exclusive"). */
        public String group;
        public List<String> items = new ArrayList<>();

        public Violation() {
        }

        public Violation(String code, String group, List<String> items) {
            this.code = code;
            this.group = group;
            this.items = items;
        }
    }

    public static class TimelineStep {
        /** Position in the timeline (purchases assumed by the engine included). */
        public int index;
        /** Purchase of the build this step belongs to (for an assumed component: the item it is bought towards). */
        public int buildIndex;
        /** Number of the build's item purchase (1, 2...; purchase moments not counted). */
        public int number;
        /** Component bought automatically towards the next item (build options "assume half / smaller items"). */
        public boolean implied;
        public long itemId;
        public String itemName;
        public String category;
        public int itemCost;
        public int paidCost;
        public List<String> consumedComponents = new ArrayList<>();
        public int cumulativeGold;
        public double minute;
        /** Gold left in the bag right after the purchase (earned until its minute minus everything paid). */
        public double goldLeft;
        public double xp;
        public int level;
        public List<String> inventory = new ArrayList<>();
        /** Inventory after the purchase, by item id (same order as `inventory`). */
        public List<Long> inventoryIds = new ArrayList<>();
        /** Shop rules this purchase breaks (the build would not be possible in game). */
        public List<Violation> violations = new ArrayList<>();
        /** Purchase left out because the inventory had no free slot for it (it is listed in TimelineResult.ignored). */
        public boolean ignored;
        public StatSnapshot stats;
        public Efficiency efficiency;
        public List<String> warnings = new ArrayList<>();
    }

    public static class MinutePoint {
        public double minute;
        public int level;
        /** Index of the last purchase completed at this minute (-1 = none yet). */
        public int stepIndex;
        public Map<String, Double> total = new LinkedHashMap<>();
        /** Unit base stats at this minute's level (bonus = total - base). */
        public Map<String, Double> base = new LinkedHashMap<>();
        public Map<String, Double> forge = new LinkedHashMap<>();
        public double forgePct;
        public List<ItemContribution> contributions = new ArrayList<>();
    }

    public static class TimelineResult {
        /** Game minute the match ends, when given. */
        public Double matchEnd;
        public String unitCode;
        public String unitName;
        public double startingGold;
        public double goldPerMin;
        public double xpPerMin;
        public List<TimelineStep> steps = new ArrayList<>();
        /** Purchases over the item limit at their place in the order: not counted (no gold, time or stats). */
        public List<TimelineStep> ignored = new ArrayList<>();
        /** Purchase moments of the build, with their resolved minute. */
        public List<Moment> moments = new ArrayList<>();
        public List<MinutePoint> series = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
        public Map<String, Double> statPrices = new LinkedHashMap<>();
    }
}
