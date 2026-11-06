package com.ornnplanner.arena;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ornnplanner.engine.Model.ItemContribution;
import com.ornnplanner.engine.Model.MinutePoint;
import com.ornnplanner.engine.Model.TimelineResult;
import com.ornnplanner.engine.Stats;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.service.PlannerService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * "Swap and recalculate the whole build": the one function behind every comparison (the planner's quick item
 * comparison, the rune comparison, the effectiveness candidates and the calculator's alternatives). A change (an item
 * in a purchase, a new purchase, another rune page or another purchase order) is applied to the build, both timelines
 * run, and the answer is the change of the whole build at a minute: effective gold by source (the changed source's own
 * and what it did to the other items, the runes and the Living Forge), every stat of the sheet, effective health and
 * the gold of the passives. Each screen picks what it shows; with a scenario, the fight's measures come from the
 * effectiveness runs (variant {@code candidato}) on top.
 */
@Service
public class BuildDeltaService {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** What changes. One of: an item ({@code itemId}, in {@code slot} or as a new purchase), a rune page, an order. */
    public static class Change {
        /** Index in the build's steps of the purchase replaced; null with {@code itemId} = a new purchase. */
        public Integer slot;
        public Long itemId;
        /** New purchase: right after this game minute; null = after the last purchase. */
        public Double atMinute;
        public BuildRepository.RunePage runePage;
        /** The build's purchases replaced by these items, in this order. */
        public List<Long> order;

        public static Change item(Integer slot, Long itemId, Double atMinute) {
            Change c = new Change();
            c.slot = slot;
            c.itemId = itemId;
            c.atMinute = atMinute;
            return c;
        }

        public static Change runes(BuildRepository.RunePage page) {
            Change c = new Change();
            c.runePage = page;
            return c;
        }

        public static Change order(List<Long> order) {
            Change c = new Change();
            c.order = order;
            return c;
        }
    }

    /** One source of effective gold: an item purchase, a rune, the champion's passive or the Living Forge. */
    public static class Source {
        /** item | rune | champion | forge */
        public String kind;
        public String name;
        public Long itemId;
        /** Part of the change itself (the item swapped in or out, the runes of the page): "own" in the cards. */
        public boolean own;
        public double before;
        public double after;
        public double delta;
        /** What the source adds in stats after the change (flat and passives): what a rune page adds, by rune. */
        public Map<String, Double> statsAfter = new LinkedHashMap<>();
    }

    /** The whole build before and after the change, at one minute. */
    public static class Delta {
        public double minute;
        public int level;
        /** Effective gold of the whole build (stats x prices + modeled passives + Living Forge + runes). */
        public double goldBefore;
        public double goldAfter;
        public double goldDelta;
        /** Of {@link #goldDelta}: what the changed sources gave, and what the change did to everything else. */
        public double ownDelta;
        public double othersDelta;
        /** Every source whose gold changed (own first, then by size of the change). */
        public List<Source> sources = new ArrayList<>();
        /** Total stats of the sheet. */
        public Map<String, Double> statsBefore = new LinkedHashMap<>();
        public Map<String, Double> statsAfter = new LinkedHashMap<>();
        public Map<String, Double> statsDelta = new LinkedHashMap<>();
        /** Effective health from the planner's sheet (no fight), half physical and half magic for the mixed one. */
        public double effectivePhysicalBefore;
        public double effectivePhysicalAfter;
        public double effectiveMagicBefore;
        public double effectiveMagicAfter;
        public double effectiveMixedBefore;
        public double effectiveMixedAfter;
        /** Gold of the modeled passives of the items (what their passives give in stats). */
        public double passiveGoldBefore;
        public double passiveGoldAfter;
        /** The change could not be bought there (the purchase was left out of the timeline). */
        public boolean ignored;
    }

    private final PlannerService planner;

    public BuildDeltaService(PlannerService planner) {
        this.planner = planner;
    }

    /** The build with the change applied (a copy; the build given is not touched). */
    public BuildRepository.Build apply(BuildRepository.Build build, Change change) {
        if (change == null) {
            return copy(build);
        }
        if (change.itemId != null) {
            return planner.withItem(build, change.slot, change.itemId, change.atMinute);
        }
        BuildRepository.Build b = copy(build);
        if (change.runePage != null) {
            b.runePage = JSON.convertValue(change.runePage, BuildRepository.RunePage.class);
        }
        if (change.order != null) {
            b.steps = new ArrayList<>();
            for (Long id : change.order) {
                b.steps.add(new BuildRepository.Step(id, true));
            }
        }
        return b;
    }

    /** The change of the whole build at {@code minute} (null = the last purchase of either timeline). */
    public Delta delta(BuildRepository.Build build, Change change, Double minute) {
        if (build == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha uma build.");
        }
        BuildRepository.Build after = apply(build, change);
        TimelineResult before = planner.calculate(build);
        TimelineResult now = planner.calculate(after);
        double at = minute != null ? minute : Math.max(lastPurchase(before), lastPurchase(now));
        Delta d = compare(before, now, at, change, build);
        if (change != null && change.itemId != null) {
            int slot = change.slot != null ? change.slot : planner.insertPosition(build, change.atMinute);
            d.ignored = now.ignored.stream().anyMatch(s -> s.buildIndex == slot)
                    || now.steps.stream().noneMatch(s -> !s.implied && s.buildIndex == slot);
        }
        return d;
    }

    /** Two timelines already run: the change from {@code before} to {@code after} at a minute. */
    public Delta compare(TimelineResult before, TimelineResult after, double minute, Change change, BuildRepository.Build build) {
        MinutePoint p0 = point(before, minute);
        MinutePoint p1 = point(after, minute);
        Delta d = new Delta();
        d.minute = minute;
        d.level = p1.level;
        Map<String, Source> sources = new LinkedHashMap<>();
        Integer ownSlot = null;
        Integer ownSlotBefore = null;
        if (change != null && change.itemId != null) {
            ownSlot = change.slot != null ? change.slot : planner.insertPosition(build, change.atMinute);
            ownSlotBefore = change.slot;   // a new purchase had nothing there before
        }
        collect(sources, p0, before.statPrices, true, ownSlotBefore, change);
        collect(sources, p1, after.statPrices, false, ownSlot, change);
        if (change != null && change.order != null) {
            markOrderOwn(sources);
        }
        for (Source s : sources.values()) {
            s.delta = s.after - s.before;
            d.goldBefore += s.before;
            d.goldAfter += s.after;
            if (s.own) {
                d.ownDelta += s.delta;
            } else {
                d.othersDelta += s.delta;
            }
        }
        d.goldDelta = d.goldAfter - d.goldBefore;
        List<Source> list = new ArrayList<>();
        for (Source s : sources.values()) {
            if (s.own || Math.abs(s.delta) > 1e-6) {
                list.add(s);
            }
        }
        list.sort((a, b) -> a.own != b.own ? (a.own ? -1 : 1) : Double.compare(Math.abs(b.delta), Math.abs(a.delta)));
        d.sources = list;
        TreeSet<String> stats = new TreeSet<>(p0.total.keySet());
        stats.addAll(p1.total.keySet());
        for (String k : stats) {
            double v0 = p0.total.getOrDefault(k, 0.0);
            double v1 = p1.total.getOrDefault(k, 0.0);
            d.statsBefore.put(k, v0);
            d.statsAfter.put(k, v1);
            if (Math.abs(v1 - v0) > 1e-9) {
                d.statsDelta.put(k, v1 - v0);
            }
        }
        SeenBy e0 = SeenBy.apply(sheetBase(p0), List.of(), 0.5);
        SeenBy e1 = SeenBy.apply(sheetBase(p1), List.of(), 0.5);
        d.effectivePhysicalBefore = e0.effectiveHpPhysical;
        d.effectivePhysicalAfter = e1.effectiveHpPhysical;
        d.effectiveMagicBefore = e0.effectiveHpMagic;
        d.effectiveMagicAfter = e1.effectiveHpMagic;
        d.effectiveMixedBefore = e0.effectiveHpMixed;
        d.effectiveMixedAfter = e1.effectiveHpMixed;
        for (ItemContribution c : p0.contributions) {
            d.passiveGoldBefore += c.passiveGold;
        }
        for (ItemContribution c : p1.contributions) {
            d.passiveGoldAfter += c.passiveGold;
        }
        return d;
    }

    /**
     * The sources of a point, matched across the two timelines: an item by its id and which copy of it (so a purchase
     * inserted before it does not break the match), a rune or champion effect by name, the Living Forge as one.
     */
    private static void collect(Map<String, Source> out, MinutePoint p, Map<String, Double> prices, boolean before,
                                Integer ownSlot, Change change) {
        Map<Long, Integer> copies = new LinkedHashMap<>();
        for (ItemContribution c : p.contributions) {
            String key;
            String kind;
            boolean own;
            if (c.champion) {
                key = "champion:" + c.itemName;
                kind = "champion";
                own = false;
            } else if (c.rune) {
                key = "rune:" + c.itemName;
                kind = "rune";
                own = change != null && change.runePage != null;
            } else {
                int n = copies.merge(c.itemId, 1, Integer::sum);
                own = ownSlot != null && c.buildIndex == ownSlot;
                // the item swapped is one source whatever it is: before and after share the key
                key = own ? "own-item" : "item:" + c.itemId + "#" + n + (c.implied ? "i" : "");
                kind = "item";
            }
            Source s = out.computeIfAbsent(key, k -> new Source());
            s.kind = kind;
            if (s.name == null || !before) {
                s.name = c.itemName;
                s.itemId = c.rune ? null : c.itemId;
            }
            s.own |= own;
            double gold = c.flatGold + c.passiveGold;
            if (before) {
                s.before += gold;
            } else {
                s.after += gold;
                c.flat.forEach((k, v) -> s.statsAfter.merge(k, v, Double::sum));
                c.passives.forEach((k, v) -> s.statsAfter.merge(k, v, Double::sum));
            }
        }
        double forge = 0;
        for (Map.Entry<String, Double> e : p.forge.entrySet()) {
            if (e.getValue() > 0) {
                forge += e.getValue() * prices.getOrDefault(e.getKey(), 0.0);
            }
        }
        if (forge > 0 || out.containsKey("forge")) {
            Source s = out.computeIfAbsent("forge", k -> new Source());
            s.kind = "forge";
            s.name = "Forja Viva";
            if (before) {
                s.before += forge;
            } else {
                s.after += forge;
            }
        }
    }

    /** Another order: the items only one side holds are the change itself. */
    private static void markOrderOwn(Map<String, Source> sources) {
        for (Source s : sources.values()) {
            if ("item".equals(s.kind) && (s.before == 0 || s.after == 0)) {
                s.own = true;
            }
        }
    }

    private static double lastPurchase(TimelineResult r) {
        double last = 0;
        for (var s : r.steps) {
            last = Math.max(last, s.minute);
        }
        return last;
    }

    static MinutePoint point(TimelineResult r, double minute) {
        MinutePoint best = r.series.get(0);
        for (MinutePoint p : r.series) {
            if (p.minute <= minute + 1e-9) {
                best = p;
            }
        }
        return best;
    }

    /** The planner's sheet as a {@link SeenBy.Base}: effective health without the zero-second run. */
    static SeenBy.Base sheetBase(MinutePoint p) {
        SeenBy.Base b = new SeenBy.Base();
        b.health = p.total.getOrDefault(Stats.MAX_HEALTH, 0.0);
        b.armor = p.total.getOrDefault(Stats.ARMOR, 0.0);
        b.magicResist = p.total.getOrDefault(Stats.MAGIC_RESIST, 0.0);
        b.shieldAny = p.total.getOrDefault("Shield", 0.0);
        b.shieldPhysical = p.total.getOrDefault("Physical Shield", 0.0);
        b.shieldMagic = p.total.getOrDefault("Magic Shield", 0.0);
        return b;
    }

    private static BuildRepository.Build copy(BuildRepository.Build build) {
        BuildRepository.Build b = JSON.convertValue(build, BuildRepository.Build.class);
        b.name = build.name;
        return b;
    }
}
