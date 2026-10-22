package com.ornnplanner.arena;

import com.ornnplanner.arena.ArenaModel.Combo;
import com.ornnplanner.arena.ArenaModel.Dummy;
import com.ornnplanner.arena.ArenaModel.Scenario;
import com.ornnplanner.arena.ArenaModel.Slot;
import com.ornnplanner.arena.fight.CombatCatalog;
import com.ornnplanner.arena.fight.ItemEffect;
import com.ornnplanner.repo.BuildRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The two panels of the build planner, for the open build at a minute: "test this build in a scenario or combo" (a
 * saved arena scenario with this build in a slot of its champion, or a library combo on the ragdoll; the result and the
 * items that weighed most) and "seen under effects" (this build's stats under chosen item and champion effects,
 * each at its value). Also the scored bench a future automatic build search will call ({@link #score}, cached;
 * {@link #compareBuilds}).
 */
@Service
public class PlannerLabService {

    /** What the screen asks: the build, the minute, the ragdoll and, optionally, a combo or the effects to apply. */
    public static class Request {
        public Long buildId;
        public Double minute;
        public Dummy dummy = new Dummy();
        public Long comboId;
        public Combo combo;
        public ComboBench.Config bench;
        /** "Visão sob efeitos": the effects applied to the build, each at its value. */
        public List<SeenBy.Applied> effects = new ArrayList<>();
        /** Compare builds: the builds, each with its own best combo. */
        public List<Long> buildIds = new ArrayList<>();
    }

    /** "Testar esta build": a saved scenario (this build in a slot) or a library combo on the ragdoll. */
    public static class TestRequest {
        public Long buildId;
        public Double minute;
        public Long scenarioId;
        /** The slot of the scenario that takes this build (null = the first of its champion). */
        public Integer slot;
        public Long comboId;
        /** The ragdoll of the combo (null = the default one: 10000 health, 100 / 100). */
        public Dummy dummy;
    }

    /** One of the items that weighed most: the same fight without it. */
    public static class ItemWeight {
        public String item;
        public Long itemId;
        /** Winner without it (scenario), damage dealt, seconds alive and health of the side: with minus without. */
        public Integer winnerWithout;
        public double dealt;
        public double alive;
        public double side;
    }

    public static class TestResult {
        /** scenario | combo */
        public String kind;
        public String champion;
        /** Scenario: the winner (0, 1, -1) and the build's side; the slot used. */
        public Integer winner;
        public Integer team;
        public Integer slot;
        public double duration;
        public double dealt;
        public double taken;
        public Double alive;
        /** The three items that weighed most (by the change of winner, then of side health and damage). */
        public List<ItemWeight> items = new ArrayList<>();
        /** What "Abrir na Arena" opens: the scenario with this build in the slot, or the combo. */
        public Scenario scenario;
        public Combo combo;
        public Dummy dummy;
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

    private final ArenaService arena;
    private final LabService lab;
    private final ComboRepository combos;
    private final BuildRepository builds;
    private final CombatCatalog catalog;
    private final EffectSources sources;
    private final Map<String, Object> map;
    private final Map<String, ComboBench.Report> cache = new ConcurrentHashMap<>();
    private final Map<String, SeenBy.Base> bases = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public PlannerLabService(ArenaService arena, LabService lab, ComboRepository combos, BuildRepository builds, CombatCatalog catalog,
                             EffectSources sources) {
        this.arena = arena;
        this.lab = lab;
        this.combos = combos;
        this.builds = builds;
        this.catalog = catalog;
        this.sources = sources;
        this.map = (Map<String, Object>) arena.kits().get("mapa");
    }

    private ComboBench bench() {
        return lab.bench();
    }

    private Arena.Input input(Long buildId, Double minute) {
        if (buildId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Salve a build para usar este painel.");
        }
        return arena.labInput(buildId, minute);
    }

    private static ComboBench.Config config(Request req) {
        return req.bench == null ? new ComboBench.Config() : req.bench;
    }

    // ------------------------------------------------------------------ 1. test this build in a scenario or combo

    /** The saved scenarios with this build's champion (and its slots there), and the champion's library combos. */
    public Map<String, Object> testOptions(long buildId) {
        BuildRepository.Build mine = builds.findBuild(buildId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Build " + buildId + " não existe."));
        Map<Long, String> units = new LinkedHashMap<>();
        List<Map<String, Object>> scenarios = new ArrayList<>();
        for (Map<String, Object> row : arena.list()) {
            long id = ((Number) row.get("id")).longValue();
            Scenario sc;
            try {
                sc = arena.find(id);
            } catch (RuntimeException e) {
                continue;   // a scenario that no longer reads is left out
            }
            List<Map<String, Object>> slots = new ArrayList<>();
            for (int k = 0; k < (sc.fighters == null ? 0 : sc.fighters.size()); k++) {
                Slot slot = sc.fighters.get(k);
                if (slot.buildId == null || slot.dummy != null) {
                    continue;
                }
                String unit = units.computeIfAbsent(slot.buildId, b -> builds.findBuild(b).map(x -> x.unitCode).orElse(""));
                if (unit.equals(mine.unitCode)) {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("index", k);
                    s.put("team", slot.team);
                    s.put("buildId", slot.buildId);
                    slots.add(s);
                }
            }
            if (!slots.isEmpty()) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("id", id);
                s.put("name", sc.name);
                s.put("slots", slots);
                scenarios.add(s);
            }
        }
        String champion = arena.labInput(buildId, null).f.champion;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("champion", champion);
        out.put("scenarios", scenarios);
        out.put("combos", combos.list(champion));
        return out;
    }

    /**
     * The scenario's fight with this build in the champion's slot (at the minute), or the combo on the ragdoll; and the
     * three items that weighed most: the same fight without each item.
     */
    public TestResult test(TestRequest req) {
        if (req.buildId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Salve a build para usar este painel.");
        }
        return req.scenarioId != null ? testScenario(req) : testCombo(req);
    }

    private TestResult testScenario(TestRequest req) {
        Scenario sc = arena.find(req.scenarioId);
        BuildRepository.Build mine = builds.findBuild(req.buildId).orElseThrow();
        Integer slot = req.slot;
        if (slot == null) {
            for (int k = 0; k < sc.fighters.size() && slot == null; k++) {
                Long b = sc.fighters.get(k).buildId;
                if (b != null && builds.findBuild(b).map(x -> mine.unitCode.equals(x.unitCode)).orElse(false)) {
                    slot = k;
                }
            }
        }
        if (slot == null || slot < 0 || slot >= sc.fighters.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O cenário não tem esse campeão.");
        }
        Slot s = sc.fighters.get(slot);
        s.buildId = req.buildId;
        s.minute = req.minute;
        s.excludeItems = null;
        ArenaModel.Result r = arena.runQuiet(sc);
        ArenaModel.FighterStats me = r.fighters.get(slot);
        TestResult out = new TestResult();
        out.kind = "scenario";
        out.champion = me.champion;
        out.winner = r.winner;
        out.team = me.team;
        out.slot = slot;
        out.duration = r.duration;
        out.dealt = me.dealt;
        out.taken = me.taken;
        out.alive = me.deathTime != null ? me.deathTime : r.duration;
        double side = 0;
        for (ArenaModel.FighterStats f : r.fighters) {
            side += f.team == me.team ? Math.max(0, f.health) : 0;
        }
        for (ArenaModel.ItemValue v : arena.itemValues(sc, slot)) {
            ItemWeight w = new ItemWeight();
            w.item = v.item;
            w.itemId = v.itemId;
            w.winnerWithout = v.winner;
            w.dealt = me.dealt - v.dealt;
            w.alive = out.alive - v.alive;
            w.side = side - v.teamHealth;
            out.items.add(w);
        }
        int winner = r.winner;
        out.items.sort(Comparator.comparingDouble((ItemWeight w) -> -((w.winnerWithout != null && w.winnerWithout != winner ? 1e9 : 0)
                + Math.abs(w.side) + Math.abs(w.dealt))));
        out.items = new ArrayList<>(out.items.subList(0, Math.min(3, out.items.size())));
        out.scenario = sc;
        return out;
    }

    private TestResult testCombo(TestRequest req) {
        if (req.comboId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha um cenário ou um combo.");
        }
        Combo c = combos.find(req.comboId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Combo " + req.comboId + " não existe."));
        LabService.Request lr = new LabService.Request();
        lr.buildId = req.buildId;
        lr.minute = req.minute;
        lr.dummy = req.dummy == null ? new Dummy() : req.dummy;
        lr.comboId = req.comboId;
        lr.show = "parado";
        Arena.Input me = input(req.buildId, req.minute);
        ComboBench.Config cfg = new ComboBench.Config();
        ArenaModel.Result r = bench().run(me, arena.dummyInput(lr.dummy), c, "parado", cfg, false);
        ArenaModel.FighterStats f = r.fighters.get(0);
        TestResult out = new TestResult();
        out.kind = "combo";
        out.champion = f.champion;
        out.duration = r.duration;
        out.dealt = f.dealt;
        out.taken = f.taken;
        for (ArenaModel.ItemValue v : lab.itemValues(lr)) {
            ItemWeight w = new ItemWeight();
            w.item = v.item;
            w.itemId = v.itemId;
            w.dealt = f.dealt - v.dealt;
            out.items.add(w);
        }
        out.items.sort(Comparator.comparingDouble((ItemWeight w) -> -Math.abs(w.dealt)));
        out.items = new ArrayList<>(out.items.subList(0, Math.min(3, out.items.size())));
        out.combo = c;
        out.dummy = lr.dummy;
        return out;
    }

    /** What each item and rune effect of the build is worth at the minute (the planner's passives tab). */
    public List<Map<String, Object>> effectValues(BuildRepository.Build build, Double minute) {
        return arena.effectValues(build, minute);
    }

    // ------------------------------------------------------------------ 2. seen under chosen effects

    /** The items and champion passives / abilities the panel can apply, with their default values and formulas. */
    public List<EffectSources.Source> effectSources() {
        return sources.list();
    }

    /** The build under the effects at the minute, and along the minutes of its timeline. */
    public Map<String, Object> seenBy(Request req) {
        if (req.buildId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Salve a build para usar este painel.");
        }
        BuildRepository.Build b = builds.findBuild(req.buildId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Build " + req.buildId + " não existe."));
        List<SeenBy.Applied> fx = req.effects == null ? List.of() : req.effects;
        Map<String, Object> out = new LinkedHashMap<>();
        SeenBy now = SeenBy.apply(base(b, req.minute, null), fx);
        now.minute = req.minute;
        out.put("now", now);
        List<SeenBy> series = new ArrayList<>();
        for (Map.Entry<Double, SeenBy.Base> e : series(b).entrySet()) {
            SeenBy s = SeenBy.apply(e.getValue(), fx);
            s.minute = e.getKey();
            series.add(s);
        }
        out.put("series", series);
        return out;
    }

    /**
     * The build's starting stats at a minute (without some items: null = all of them), cached by the build's last save:
     * the effects only change the arithmetic on top of them.
     */
    SeenBy.Base base(BuildRepository.Build b, Double minute, Set<Long> without) {
        String key = b.id + "|" + b.updatedAt + "|" + minute + "|" + (without == null ? "" : new java.util.TreeSet<>(without));
        SeenBy.Base hit = bases.get(key);
        if (hit != null) {
            return hit;
        }
        SeenBy.Base base = SeenBy.base(catalog, map, arena.labInput(b.id, minute, without), arena.dummyInput(new Dummy()));
        if (bases.size() > 20000) {
            bases.clear();
        }
        bases.put(key, base);
        return base;
    }

    /** Minute by minute (1, 2, ... until the end of the build's match, at most 30): the starting stats. */
    private Map<Double, SeenBy.Base> series(BuildRepository.Build b) {
        Map<Double, SeenBy.Base> out = new LinkedHashMap<>();
        double end = b.matchEnd != null ? b.matchEnd : 20;
        for (int m = 1; m <= Math.min(30, (int) Math.ceil(end)); m++) {
            try {
                out.put((double) m, base(b, (double) m, null));
            } catch (RuntimeException e) {
                // a minute the build cannot be computed at (no gold yet): left out
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the best combo (for the build search)

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

    private static ComboBench.Metrics first(ComboBench.Report r) {
        return r.byBehavior.values().iterator().next();
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
