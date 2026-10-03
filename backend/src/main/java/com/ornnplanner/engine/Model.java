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

        public StatLine() {
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
            return ratio != null;
        }

        /** Ratio line that can be evaluated against the build's real stats. */
        public boolean dynamicPercent() {
            return ratio != null && refType != null;
        }

        /** Line that contributes a fixed amount (flat stat, or a ratio line we must fall back to its static value). */
        public boolean countsAsFlat() {
            return !marker() && ratio == null && value != null;
        }

        /** Flat value at a level: `value` at level 1, `valueMax` at level 15, linear in between. */
        public double valueAt(int level) {
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
        /** Count conditional effects (stacks, in combat, low health...) as active. */
        public boolean includeConditional = true;
        public double goldPerMin;
        public double xpPerMin;
        public List<Long> itemIds = new ArrayList<>();
    }

    // ----------------------------------------------------------------- results

    public static class PassiveDetail {
        public int purchaseIndex;
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
    }

    public static class TimelineStep {
        public int index;
        public long itemId;
        public String itemName;
        public String category;
        public int itemCost;
        public int paidCost;
        public List<String> consumedComponents = new ArrayList<>();
        public int cumulativeGold;
        public double minute;
        public double xp;
        public int level;
        public List<String> inventory = new ArrayList<>();
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
    }

    public static class TimelineResult {
        public String unitCode;
        public String unitName;
        public double startingGold;
        public double goldPerMin;
        public double xpPerMin;
        public List<TimelineStep> steps = new ArrayList<>();
        public List<MinutePoint> series = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
        public Map<String, Double> statPrices = new LinkedHashMap<>();
    }
}
