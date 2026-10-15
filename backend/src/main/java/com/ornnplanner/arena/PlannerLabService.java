package com.ornnplanner.arena;

import com.ornnplanner.arena.ArenaModel.Combo;
import com.ornnplanner.arena.ArenaModel.Dummy;
import com.ornnplanner.arena.fight.CombatCatalog;
import com.ornnplanner.arena.fight.ItemEffect;
import com.ornnplanner.repo.BuildRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The lab of the build planner: the open build at a minute against the ragdoll (its best combo, what each item is worth
 * in it, item × ragdoll scenario), how another build sees it ("visto por"), and the scored bench a future automatic
 * build search will call ({@link #score}, cached; {@link #compareBuilds}).
 */
@Service
public class PlannerLabService {

    /** What the screen asks: the build, the minute, the ragdoll and, optionally, a combo and another build. */
    public static class Request {
        public Long buildId;
        public Double minute;
        public Dummy dummy = new Dummy();
        public Long comboId;
        public Combo combo;
        public ComboBench.Config bench;
        /** Value of the items: also calibrate the combo again without each item. */
        public boolean recalibrate;
        /** Scenarios: the ragdolls of the table (empty = the presets). */
        public List<Dummy> dummies = new ArrayList<>();
        /** "Visto por" and damage received: the other build, at its minute (null = the same minute). */
        public Long otherBuildId;
        public Double otherMinute;
        /** An ally ragdoll of the attacker: the item effects of the build it wears, at their most (null = none). */
        public Long allyWearBuildId;
        public Double allyWearMinute;
        /** "Visto por" the other way: how this build sees the other. */
        public boolean reverse;
        /** Compare builds: the builds, each with its own best combo. */
        public List<Long> buildIds = new ArrayList<>();
    }

    public static class ComboResult {
        public String champion;
        public Combo combo;
        public ComboBench.Report report;
        /** The library's combos found for this build and minute (the one shown first), then the champion's others. */
        public List<Combo> combos = new ArrayList<>();
        /** True when none existed and a small search made them now. */
        public boolean generated;
    }

    /** One item: the combo with and without it. */
    public static class ItemRow {
        public long itemId;
        public String item;
        public double damage;
        public double control;
        public double duration;
        public double score;
        /** The same, with the combo calibrated again without the item (when asked). */
        public Double damageRecalibrated;
        public Double scoreRecalibrated;
        /** Damage received from the other build's combo without the item (tanks). */
        public Double received;
    }

    public static class ItemsResult {
        public ComboBench.Metrics with;
        public double score;
        public Double received;
        public List<ItemRow> items = new ArrayList<>();
    }

    private final ArenaService arena;
    private final LabService lab;
    private final ComboRepository combos;
    private final BuildRepository builds;
    private final CombatCatalog catalog;
    private final Map<String, Object> map;
    private final Map<String, ComboBench.Report> cache = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public PlannerLabService(ArenaService arena, LabService lab, ComboRepository combos, BuildRepository builds, CombatCatalog catalog) {
        this.arena = arena;
        this.lab = lab;
        this.combos = combos;
        this.builds = builds;
        this.catalog = catalog;
        this.map = (Map<String, Object>) arena.kits().get("mapa");
    }

    private ComboBench bench() {
        return lab.bench();
    }

    private Arena.Input input(Long buildId, Double minute) {
        if (buildId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Salve a build para usar o laboratório.");
        }
        return arena.labInput(buildId, minute);
    }

    private static ComboBench.Config config(Request req) {
        return req.bench == null ? new ComboBench.Config() : req.bench;
    }

    // ------------------------------------------------------------------ 1. the combo on the ragdoll

    /**
     * The best combo of the library for the build and minute (found by the search, or the champion's best active
     * one); when none was found for them, a small search makes them now and saves them (the cache of the library).
     */
    public ComboResult combo(Request req) {
        Arena.Input me = input(req.buildId, req.minute);
        Arena.Input d = arena.dummyInput(req.dummy == null ? new Dummy() : req.dummy);
        ComboBench.Config cfg = config(req);
        ComboResult out = new ComboResult();
        out.champion = me.f.champion;
        List<Combo> here = forBuild(me.f.champion, req.buildId, req.minute);
        if (here.isEmpty() && req.combo == null) {
            ComboSearch.Config sc = new ComboSearch.Config();
            sc.beam = 24;
            sc.maxSteps = 8;
            sc.budget = 800;
            sc.threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
            String buildName = me.build;
            for (ComboSearch.Found f : new ComboSearch(bench()).search(me, d, cfg, sc, new ComboSearch.Listener() { }).best) {
                f.combo.build = buildName;
                f.combo.buildId = req.buildId;
                f.combo.minute = req.minute;
                combos.save(f.combo);
            }
            here = forBuild(me.f.champion, req.buildId, req.minute);
            out.generated = true;
        }
        out.combos.addAll(here);
        for (Combo c : combos.list(me.f.champion)) {
            if (here.stream().noneMatch(x -> Objects.equals(x.id, c.id)) && c.active) {
                out.combos.add(c);
            }
        }
        Combo chosen = req.combo != null ? req.combo
                : req.comboId != null ? out.combos.stream().filter(c -> c.id.equals(req.comboId)).findFirst().orElse(null) : null;
        out.combo = chosen != null ? chosen : out.combos.isEmpty() ? null : out.combos.get(0);
        if (out.combo != null) {
            out.report = score(me, d, out.combo, cfg);
        }
        return out;
    }

    private List<Combo> forBuild(String champion, Long buildId, Double minute) {
        List<Combo> out = new ArrayList<>();
        for (Combo c : combos.list(champion)) {
            if (c.active && Objects.equals(c.buildId, buildId) && Objects.equals(c.minute, minute)) {
                out.add(c);
            }
        }
        return out;
    }

    private Combo chosen(Request req, Arena.Input me) {
        if (req.combo != null) {
            return req.combo;
        }
        ComboResult r = combo(req);
        if (r.combo == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nenhum combo para " + me.f.champion + ".");
        }
        return r.combo;
    }

    // ------------------------------------------------------------------ 2. what each item is worth in the combo

    public ItemsResult items(Request req) {
        Arena.Input me = input(req.buildId, req.minute);
        Arena.Input d = arena.dummyInput(req.dummy == null ? new Dummy() : req.dummy);
        ComboBench.Config cfg = config(req);
        Combo c = chosen(req, me);
        ItemsResult out = new ItemsResult();
        ComboBench.Report base = score(me, d, c, cfg);
        out.score = base.score;
        out.with = first(base);
        // damage received from the other build's combo (tanks): that champion attacks this one, standing
        Arena.Input other = req.otherBuildId == null ? null : input(req.otherBuildId, req.otherMinute != null ? req.otherMinute : req.minute);
        Combo otherCombo = null;
        if (other != null) {
            Request oreq = new Request();
            oreq.buildId = req.otherBuildId;
            oreq.minute = req.otherMinute != null ? req.otherMinute : req.minute;
            oreq.dummy = req.dummy;
            otherCombo = combo(oreq).combo;
            if (otherCombo != null) {
                out.received = received(other, me, otherCombo, cfg);
            }
        }
        for (Map.Entry<String, Long> item : new LinkedHashMap<>(me.f.items).entrySet()) {
            Arena.Input without = arena.labInput(req.buildId, req.minute, java.util.Set.of(item.getValue()));
            ComboBench.Report r = score(without, d, c, cfg);
            ComboBench.Metrics m = first(r);
            ItemRow row = new ItemRow();
            row.itemId = item.getValue();
            row.item = item.getKey();
            row.damage = out.with.damage - m.damage;
            row.control = out.with.control - m.control;
            row.duration = out.with.duration - m.duration;
            row.score = base.score - r.score;
            if (req.recalibrate) {
                Combo again = new ComboSearch(bench()).calibrate(without, d, c, cfg, 4);
                ComboBench.Report rr = score(without, d, again, cfg);
                row.damageRecalibrated = out.with.damage - first(rr).damage;
                row.scoreRecalibrated = base.score - rr.score;
            }
            if (other != null && otherCombo != null) {
                row.received = received(other, without, otherCombo, cfg) - out.received;
            }
            out.items.add(row);
        }
        return out;
    }

    /** Damage the attacker's combo deals to the defender standing still (the defender as a ragdoll that does nothing). */
    private double received(Arena.Input attacker, Arena.Input defender, Combo combo, ComboBench.Config cfg) {
        Arena.Input target = defender.copy();
        target.f = defender.f;
        Dummy still = new Dummy();
        still.spells = new ArrayList<>();
        target.dummy = still;
        ComboBench.Config one = new ComboBench.Config();
        one.behaviors = List.of("parado");
        one.distance = cfg.distance;
        one.mode = cfg.mode;
        one.seconds = cfg.seconds;
        int team = target.f.team;
        try {
            target.f.team = 1;
            return bench().run(attacker, target, combo, "parado", one, false).fighters.get(0).dealt;
        } finally {
            target.f.team = team;
        }
    }

    private static ComboBench.Metrics first(ComboBench.Report r) {
        return r.byBehavior.values().iterator().next();
    }

    // ------------------------------------------------------------------ 3. item × ragdoll scenario

    /** For each ragdoll of the list (the presets when empty): the combo's score with and without each item. */
    public Map<String, Object> scenarios(Request req) {
        Arena.Input me = input(req.buildId, req.minute);
        Combo c = chosen(req, me);
        ComboBench.Config cfg = config(req);
        List<Dummy> dummies = req.dummies == null || req.dummies.isEmpty() ? Dummy.presets() : req.dummies;
        List<Map<String, Object>> cols = new ArrayList<>();
        Map<String, Map<String, Double>> rows = new LinkedHashMap<>();
        for (Dummy dm : dummies) {
            Arena.Input d = arena.dummyInput(dm);
            ComboBench.Report base = score(me, d, c, cfg);
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("name", dm.name);
            col.put("score", base.score);
            col.put("damage", first(base).damage);
            cols.add(col);
            for (Map.Entry<String, Long> item : me.f.items.entrySet()) {
                Arena.Input without = arena.labInput(req.buildId, req.minute, java.util.Set.of(item.getValue()));
                ComboBench.Report r = score(without, d, c, cfg);
                rows.computeIfAbsent(item.getKey(), k -> new LinkedHashMap<>()).put(dm.name, first(base).damage - first(r).damage);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("combo", c);
        out.put("scenarios", cols);
        out.put("items", rows);
        return out;
    }

    // ------------------------------------------------------------------ 4. "visto por"

    /** How the other build (at its minute) sees this one, or the other way; and along the minutes of the timeline. */
    public Map<String, Object> seenBy(Request req) {
        if (req.otherBuildId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha a build que olha.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        List<ItemEffect> ally = allyEffects(req);
        Long def = req.reverse ? req.otherBuildId : req.buildId;
        Long att = req.reverse ? req.buildId : req.otherBuildId;
        Double defMin = req.reverse ? (req.otherMinute != null ? req.otherMinute : req.minute) : req.minute;
        Double attMin = req.reverse ? req.minute : (req.otherMinute != null ? req.otherMinute : req.minute);
        SeenBy now = SeenBy.of(catalog, map, input(def, defMin), input(att, attMin), ally);
        now.minute = defMin;
        out.put("now", now);
        // minute by minute, both builds at the same minute, until the end of the defender's match
        BuildRepository.Build b = builds.findBuild(def).orElseThrow();
        double end = b.matchEnd != null ? b.matchEnd : 20;
        List<SeenBy> series = new ArrayList<>();
        for (int m = 1; m <= Math.min(30, (int) Math.ceil(end)); m++) {
            try {
                SeenBy s = SeenBy.of(catalog, map, input(def, (double) m), input(att, (double) m), ally);
                s.minute = (double) m;
                series.add(s);
            } catch (RuntimeException e) {
                // a minute a build cannot be computed at (no gold yet): left out
            }
        }
        out.put("series", series);
        return out;
    }

    /** The on-hit effects of the build an ally ragdoll wears (Black Cleaver, Grievous Wounds...), or none. */
    private List<ItemEffect> allyEffects(Request req) {
        if (req.allyWearBuildId == null) {
            return List.of();
        }
        Dummy d = new Dummy();
        d.wearBuildId = req.allyWearBuildId;
        d.wearMinute = req.allyWearMinute;
        return arena.dummyInput(d).f.effects;
    }

    // ------------------------------------------------------------------ 5. for the automatic build search

    /**
     * A build at a minute scored on the bench with a combo and a ragdoll, cached by what decides the fight: the items,
     * runes and level of the champion, the combo's steps, the ragdoll and the mode.
     */
    public ComboBench.Report score(Arena.Input champion, Arena.Input dummy, Combo combo, ComboBench.Config cfg) {
        StringBuilder key = new StringBuilder(champion.f.champion).append('|').append(champion.f.level).append('|');
        new java.util.TreeSet<>(champion.f.items.values()).forEach(id -> key.append(id).append(','));
        key.append('|');
        LinkedHashSet<String> runes = new LinkedHashSet<>();
        for (ItemEffect e : champion.f.effects) {
            if (!champion.f.items.containsKey(e.item)) {
                runes.add(e.item);
            }
        }
        key.append(runes).append('|');
        combo.steps.forEach(s -> key.append(s).append(';'));
        key.append('|').append(dummy.f.total).append(dummy.dummy == null ? "" : dummy.dummy.behavior + dummy.dummy.appliesEffects)
                .append('|').append(cfg.mode).append(cfg.seconds).append(cfg.distance).append(cfg.behaviors).append(cfg.aggregate);
        ComboBench.Report hit = cache.get(key.toString());
        if (hit != null) {
            return hit;
        }
        ComboBench.Report r = bench().evaluate(champion, dummy, combo, cfg);
        if (cache.size() > 5000) {
            cache.clear();
        }
        cache.put(key.toString(), r);
        return r;
    }

    /** Builds side by side on the bench (each with its own best combo for the minute): the search of builds to come. */
    public List<Map<String, Object>> compareBuilds(Request req) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Long id : req.buildIds) {
            Request r = new Request();
            r.buildId = id;
            r.minute = req.minute;
            r.dummy = req.dummy;
            r.bench = req.bench;
            ComboResult c = combo(r);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("buildId", id);
            row.put("champion", c.champion);
            row.put("combo", c.combo == null ? null : c.combo.name);
            row.put("score", c.report == null ? null : c.report.score);
            row.put("damage", c.report == null ? null : first(c.report).damage);
            row.put("control", c.report == null ? null : first(c.report).control);
            out.add(row);
        }
        return out;
    }
}
