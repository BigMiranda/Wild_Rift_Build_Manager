package com.ornnplanner.arena;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ornnplanner.engine.Model.ItemContribution;
import com.ornnplanner.engine.Model.ItemDef;
import com.ornnplanner.engine.Model.MinutePoint;
import com.ornnplanner.engine.Model.TimelineResult;
import com.ornnplanner.engine.Model.TimelineStep;
import com.ornnplanner.engine.Model.Violation;
import com.ornnplanner.engine.Stats;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.service.PlannerService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The planner's quick item comparison: "in this slot, which of these gives me more?". Each candidate takes the slot by
 * {@link PlannerService#withItem} (the same swap the effectiveness reports use) and goes through the timeline and the
 * shop's rules; the measures are the quick ones (no fight): the item's stats and gold value at a minute, effective
 * health from the zero-second run of {@link SeenBy} (cached) and the passives' values. The suggestion is quicker still:
 * effective health from the planner's sheet, so a whole shop tab answers in seconds.
 */
@Service
public class QuickCompareService {

    public static final int MAX_CANDIDATES = 5;
    public static final int SUGGESTIONS = 5;
    /** Minutes after the item is ready that the second reading looks at, unless a minute is chosen. */
    static final double LATER = 5;
    private static final int CACHE = 400;

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** A question: a build, the purchase compared (or a new one), the candidates and how to read them. */
    public static class Request {
        public Long buildId;
        public BuildRepository.Build build;
        /** Index in the build's steps of the purchase compared; null = a new purchase. */
        public Integer slot;
        /** New purchase: right after this game minute; null = after the last purchase. */
        public Double atMinute;
        public List<Long> candidates = new ArrayList<>();
        /** The minute the candidates are compared at; null = 5 minutes after the last of them is ready. */
        public Double minute;
        public List<SeenBy.Applied> effects = new ArrayList<>();
        public Double physicalShare;
        /** mista | fisica | magica | ouro (suggestions). */
        public String criterion = "mista";
        /** Shop tab of a suggestion for a new purchase (a slot uses its item's tabs). */
        public String tab;
    }

    /** One candidate in the slot. */
    public static class Card {
        public long itemId;
        public String name;
        public int cost;
        /** The item already in the slot (the reference of the deltas). */
        public boolean reference;
        /** The shop does not allow it there: the rules it breaks (exclusive, duplicate, boots, slots...). */
        public List<Violation> violations = new ArrayList<>();
        public boolean ignored;
        /** Minute it is ready, and the minute a shop rule held it to (null = it did not wait). */
        public Double readyAt;
        public Double waitedUntil;
        /** Change in the purchase minute of the next item and of the last one, against the build as it is. */
        public Double nextDelay;
        public Double lastDelay;
        public String nextItem;
        /** What the item adds (its stats and passives) when ready and at the compared minute. */
        public Map<String, Double> statsAtReady = new LinkedHashMap<>();
        public Map<String, Double> statsAtMinute = new LinkedHashMap<>();
        /** Whether it is held at the compared minute. */
        public boolean held;
        public double effectivePhysical;
        public double effectiveMagic;
        public double effectiveMixed;
        /** Gold value of what it adds at the minute, and over its cost (%), as in the relevance report. */
        public double goldValue;
        public double efficiency;
        public List<Map<String, Object>> passives = new ArrayList<>();
        /** Suggestions: gain on the build as it is by the criterion, %, both read from the planner's sheet. */
        public Double gainPct;
    }

    public static class Result {
        /** The minute the candidates are compared at. */
        public double minute;
        /** The slot the candidates took (a new purchase: where it went). */
        public int slot;
        public List<Card> cards = new ArrayList<>();
        public double ms;
    }

    private final PlannerService planner;
    private final ArenaService arena;
    private final BuildRepository builds;
    private final Map<String, SeenBy.Base> bases = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> values = new ConcurrentHashMap<>();

    public QuickCompareService(PlannerService planner, ArenaService arena, BuildRepository builds) {
        this.planner = planner;
        this.arena = arena;
        this.builds = builds;
    }

    private BuildRepository.Build buildOf(Request req) {
        if (req.build != null) {
            return req.build;
        }
        if (req.buildId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha uma build.");
        }
        return builds.findBuild(req.buildId).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Build " + req.buildId + " não existe."));
    }

    /** The candidates side by side: the one already in the slot first. */
    public Result compare(Request req) {
        long t0 = System.nanoTime();
        BuildRepository.Build build = buildOf(req);
        Map<Long, ItemDef> items = planner.referenceData().items;
        Set<Long> ids = new LinkedHashSet<>();
        Long current = currentItem(build, req.slot);
        if (current != null) {
            ids.add(current);
        }
        ids.addAll(req.candidates == null ? List.of() : req.candidates);
        TimelineResult before = planner.calculate(build);
        List<Swap> swaps = new ArrayList<>();
        for (Long id : ids) {
            if (swaps.size() < MAX_CANDIDATES && items.containsKey(id)) {
                swaps.add(swap(build, req.slot, req.atMinute, id, before));
            }
        }
        Result out = new Result();
        out.minute = req.minute != null ? req.minute : commonMinute(swaps);
        out.slot = swaps.isEmpty() ? (req.slot == null ? 0 : req.slot) : swaps.get(0).slot;
        double share = req.physicalShare == null ? 0.5 : req.physicalShare;
        List<java.util.concurrent.Callable<Card>> runs = new ArrayList<>();
        for (Swap s : swaps) {
            runs.add(() -> {   // the zero-second runs of a new minute: each candidate on its own thread
                Card c = card(s, items.get(s.itemId), out.minute);
                c.reference = current != null && current == s.itemId;
                if (!c.ignored) {
                    SeenBy e = SeenBy.apply(base(s.build, out.minute), req.effects, share);
                    c.effectivePhysical = e.effectiveHpPhysical;
                    c.effectiveMagic = e.effectiveHpMagic;
                    c.effectiveMixed = e.effectiveHpMixed;
                    c.passives = passives(s.build, out.minute, c.name);
                }
                return c;
            });
        }
        out.cards.addAll(ArenaService.parallel(runs));
        out.ms = (System.nanoTime() - t0) / 1e6;
        return out;
    }

    /** Whether an item fits a purchase and when it would be ready there. */
    public static class Fit {
        public boolean fits;
        public Double readyAt;
    }

    /** Every upgraded item of the shop in the slot, by the shop's rules: one timeline each (the candidates' picker). */
    public Map<Long, Fit> fits(Request req) {
        BuildRepository.Build build = buildOf(req);
        Long current = currentItem(build, req.slot);
        TimelineResult before = planner.calculate(build);
        List<ItemDef> list = new ArrayList<>();
        for (ItemDef it : planner.referenceData().items.values()) {
            if ("aprimorado".equals(it.section)) {
                list.add(it);
            }
        }
        List<java.util.concurrent.Callable<Fit>> runs = new ArrayList<>();
        for (ItemDef it : list) {
            runs.add(() -> {
                Swap s = swap(build, req.slot, req.atMinute, it.id, before);
                Fit f = new Fit();
                f.fits = s.step != null && s.step.violations.isEmpty();
                f.readyAt = s.step == null ? null : s.step.minute;
                return f;
            });
        }
        List<Fit> done = ArenaService.parallel(runs);
        Map<Long, Fit> out = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            out.put(list.get(i).id, done.get(i));
        }
        if (current != null) {
            out.computeIfPresent(current, (k, f) -> {
                f.fits = true;
                return f;
            });
        }
        return out;
    }

    /**
     * The best items of the slot's shop tab that fit by the shop's rules, by the criterion. Quick measures only: the
     * timeline and the planner's sheet at the minute (no fight, no zero-second run), so the ones picked are read again
     * by {@link #compare}.
     */
    public Result suggest(Request req) {
        long t0 = System.nanoTime();
        BuildRepository.Build build = buildOf(req);
        Map<Long, ItemDef> items = planner.referenceData().items;
        Long current = currentItem(build, req.slot);
        ItemDef held = current == null ? null : items.get(current);
        TimelineResult before = planner.calculate(build);
        double share = req.physicalShare == null ? 0.5 : req.physicalShare;
        Double minute = req.minute;
        if (minute == null && current != null) {
            minute = commonMinute(List.of(swap(build, req.slot, req.atMinute, current, before)));
        }
        List<Swap> fits = new ArrayList<>();
        for (ItemDef it : items.values()) {
            if (!"aprimorado".equals(it.section) || it.cost <= 0 || !sameTab(it, held, req.tab) || it.id == (current == null ? -1 : current)) {
                continue;
            }
            Swap s = swap(build, req.slot, req.atMinute, it.id, before);
            if (s.step != null && s.step.violations.isEmpty()) {
                fits.add(s);
            }
        }
        Result out = new Result();
        out.minute = minute != null ? minute : commonMinute(fits);
        out.slot = req.slot == null ? (fits.isEmpty() ? 0 : fits.get(0).slot) : req.slot;
        SeenBy now = SeenBy.apply(sheetBase(point(before, out.minute)), req.effects, share);
        Card reference = new Card();
        reference.effectivePhysical = now.effectiveHpPhysical;
        reference.effectiveMagic = now.effectiveHpMagic;
        reference.effectiveMixed = now.effectiveHpMixed;
        List<Card> cards = new ArrayList<>();
        for (Swap s : fits) {
            Card c = card(s, items.get(s.itemId), out.minute);
            SeenBy e = SeenBy.apply(sheetBase(point(s.result, out.minute)), req.effects, share);
            c.effectivePhysical = e.effectiveHpPhysical;
            c.effectiveMagic = e.effectiveHpMagic;
            c.effectiveMixed = e.effectiveHpMixed;
            double was = score(reference, req.criterion);
            c.gainPct = "ouro".equals(req.criterion) || was <= 0 ? null : 100 * (score(c, req.criterion) / was - 1);
            cards.add(c);
        }
        cards.sort(Comparator.comparingDouble((Card c) -> -score(c, req.criterion)).thenComparing(c -> c.name));
        out.cards = new ArrayList<>(cards.subList(0, Math.min(SUGGESTIONS, cards.size())));
        out.ms = (System.nanoTime() - t0) / 1e6;
        return out;
    }

    private static double score(Card c, String criterion) {
        switch (criterion == null ? "mista" : criterion) {
            case "fisica":
                return c.effectivePhysical;
            case "magica":
                return c.effectiveMagic;
            case "ouro":
                return c.efficiency;
            default:
                return c.effectiveMixed;
        }
    }

    private static boolean sameTab(ItemDef it, ItemDef held, String tab) {
        if (held == null) {
            return tab == null || it.tabs.contains(tab);
        }
        if (it.boots() != held.boots()) {
            return false;
        }
        return it.tabs.stream().anyMatch(held.tabs::contains);
    }

    private static Long currentItem(BuildRepository.Build build, Integer slot) {
        if (slot == null) {
            return null;
        }
        if (build.steps == null || slot < 0 || slot >= build.steps.size() || build.steps.get(slot).moment()
                || build.steps.get(slot).sell()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha uma compra da build.");
        }
        return build.steps.get(slot).itemId;
    }

    /** A candidate in the slot: the build with it, its timeline and its purchase. */
    private static final class Swap {
        long itemId;
        int slot;
        BuildRepository.Build build;
        TimelineResult result;
        TimelineResult before;
        boolean inserted;
        /** Its purchase in the timeline (null = left out). */
        TimelineStep step;
    }

    private Swap swap(BuildRepository.Build build, Integer slot, Double atMinute, long itemId, TimelineResult before) {
        Swap s = new Swap();
        s.itemId = itemId;
        s.before = before;
        s.inserted = slot == null;
        s.slot = slot != null ? slot : planner.insertPosition(build, atMinute);
        s.build = planner.withItem(build, slot, itemId, atMinute);
        s.result = planner.calculate(s.build);
        for (TimelineStep st : s.result.steps) {
            if (!st.implied && st.buildIndex == s.slot) {
                s.step = st;
            }
        }
        return s;
    }

    /** Five minutes after the last candidate is ready: everyone is compared at the same minute and level. */
    private static double commonMinute(List<Swap> swaps) {
        double last = 0;
        for (Swap s : swaps) {
            if (s.step != null) {
                last = Math.max(last, s.step.minute);
            }
        }
        return last + LATER;
    }

    private Card card(Swap s, ItemDef it, double minute) {
        Card c = new Card();
        c.itemId = s.itemId;
        c.name = it.name;
        c.cost = it.cost;
        if (s.step == null) {
            c.ignored = true;
            for (TimelineStep st : s.result.ignored) {
                if (st.buildIndex == s.slot) {
                    c.violations.addAll(st.violations);
                }
            }
            return c;
        }
        c.violations.addAll(s.step.violations);
        c.readyAt = s.step.minute;
        c.waitedUntil = s.step.waitedUntil;
        delays(s, c);
        ItemContribution ready = contribution(point(s.result, s.step.minute), s);
        if (ready != null) {
            c.statsAtReady = stats(ready);
        }
        ItemContribution at = minute + 1e-9 >= s.step.minute ? contribution(point(s.result, minute), s) : null;
        if (at != null) {
            c.held = true;
            c.statsAtMinute = stats(at);
            c.goldValue = at.flatGold + at.passiveGold;
            c.efficiency = it.cost > 0 ? 100 * c.goldValue / it.cost : 0;
        }
        return c;
    }

    /** Against the build as it is: the purchases after the slot, matched by their place in the sequence. */
    private static void delays(Swap s, Card c) {
        Map<Integer, TimelineStep> then = new LinkedHashMap<>();
        for (TimelineStep st : s.before.steps) {
            if (!st.implied) {
                then.put(st.buildIndex, st);
            }
        }
        for (TimelineStep st : s.result.steps) {
            if (st.implied || st.buildIndex <= s.slot) {
                continue;
            }
            TimelineStep old = then.get(s.inserted ? st.buildIndex - 1 : st.buildIndex);
            if (old == null) {
                continue;
            }
            double d = st.minute - old.minute;
            if (c.nextDelay == null) {
                c.nextDelay = d;
                c.nextItem = st.itemName;
            }
            c.lastDelay = d;
        }
    }

    private static ItemContribution contribution(MinutePoint p, Swap s) {
        for (ItemContribution ic : p.contributions) {
            if (!ic.rune && ic.itemId == s.itemId && ic.buildIndex == s.slot) {
                return ic;
            }
        }
        return null;
    }

    private static Map<String, Double> stats(ItemContribution ic) {
        Map<String, Double> m = new LinkedHashMap<>(ic.flat);
        ic.passives.forEach((k, v) -> m.merge(k, v, Double::sum));
        return m;
    }

    private static MinutePoint point(TimelineResult r, double minute) {
        MinutePoint best = r.series.get(0);
        for (MinutePoint p : r.series) {
            if (p.minute <= minute + 1e-9) {
                best = p;
            }
        }
        return best;
    }

    /** The planner's sheet as a {@link SeenBy.Base}: the suggestion's measure, without the zero-second run. */
    private static SeenBy.Base sheetBase(MinutePoint p) {
        SeenBy.Base b = new SeenBy.Base();
        b.health = p.total.getOrDefault(Stats.MAX_HEALTH, 0.0);
        b.armor = p.total.getOrDefault(Stats.ARMOR, 0.0);
        b.magicResist = p.total.getOrDefault(Stats.MAGIC_RESIST, 0.0);
        b.shieldAny = p.total.getOrDefault("Shield", 0.0);
        b.shieldPhysical = p.total.getOrDefault("Physical Shield", 0.0);
        b.shieldMagic = p.total.getOrDefault("Magic Shield", 0.0);
        return b;
    }

    private SeenBy.Base base(BuildRepository.Build b, double minute) {
        String key = write(b) + "@" + minute;
        if (bases.size() > CACHE) {
            bases.clear();
        }
        return bases.computeIfAbsent(key, k -> arena.startBase(b, minute));
    }

    private List<Map<String, Object>> passives(BuildRepository.Build b, double minute, String item) {
        String key = write(b) + "@" + minute;
        if (values.size() > CACHE) {
            values.clear();
        }
        List<Map<String, Object>> all = values.computeIfAbsent(key, k -> arena.effectValues(b, minute));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> v : all) {
            if (item.equals(v.get("source"))) {
                out.add(v);
            }
        }
        return out;
    }

    private static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
