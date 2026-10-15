package com.ornnplanner.arena;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ornnplanner.arena.ArenaModel.Combo;
import com.ornnplanner.arena.ArenaModel.Dummy;
import com.ornnplanner.arena.fight.CombatCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.PreDestroy;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The lab: a build at a minute against the ragdoll. Runs a combo on the bench, searches combos (in the background,
 * one at a time) and keeps their library, calibrates a combo's waits, and stores the user's ragdolls (arena_dummy).
 */
@Service
public class LabService {

    private static final Logger log = LoggerFactory.getLogger(LabService.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** A build at a minute, a ragdoll and how to measure. */
    public static class Request {
        public Long buildId;
        public Double minute;
        public Dummy dummy = new Dummy();
        public ComboBench.Config bench = new ComboBench.Config();
        /** The combo to run (inline), or the library's {@link #comboId}. */
        public Combo combo;
        public Long comboId;
        /** The behavior whose fight comes back with frames (null = the first of the bench). */
        public String show;
        public ComboSearch.Config search = new ComboSearch.Config();
        /** Search: save the combos found as the champion's library for this build and minute. */
        public boolean save = true;
        /** Compare runes: the keystones tried in place of the build's (each with a new search). */
        public List<String> runes = new ArrayList<>(List.of("Aperto dos Mortos-Vivos", "Suserano do Gelo", "Impacto Repentino"));
    }

    /** One rune of the comparison: the combo with it, and the best combo searched again with it. */
    public static class RuneRow {
        public String rune;
        public ComboBench.Report combo;
        public double runeDamage;
        public double procs;
        public Combo best;
        public ComboBench.Report bestReport;
        public double bestRuneDamage;
        public double bestProcs;
    }

    /** A combo's numbers on each behavior, its score, and one fight to watch. */
    public static class RunResult {
        public Combo combo;
        public ComboBench.Report report;
        public ArenaModel.Result fight;
        public String champion;
    }

    public static class SearchJob {
        public String id;
        /** queued | running | done | failed | cancelled */
        public volatile String status = "queued";
        public String champion;
        public Long buildId;
        public Double minute;
        public volatile int evaluated;
        public volatile int depth;
        public volatile double best;
        public int budget;
        public volatile String message;
        public volatile List<ComboSearch.Found> found = new ArrayList<>();
        public volatile List<String> log = new ArrayList<>();
        public long startedAt;
        public volatile Long finishedAt;
        @JsonIgnore
        volatile boolean cancel;
    }

    private final ArenaService arena;
    private final ComboRepository combos;
    private final JdbcTemplate jdbc;
    private final ComboBench bench;
    private final CombatCatalog catalog;
    private final Map<String, SearchJob> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "arena-lab");
        t.setDaemon(true);
        return t;
    });

    @SuppressWarnings("unchecked")
    public LabService(ArenaService arena, ComboRepository combos, CombatCatalog catalog, JdbcTemplate jdbc) {
        this.arena = arena;
        this.combos = combos;
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.bench = new ComboBench(catalog, (Map<String, Object>) arena.kits().get("mapa"));
    }

    @PreDestroy
    void stop() {
        jobs.values().forEach(j -> j.cancel = true);
        runner.shutdownNow();
    }

    public ComboBench bench() {
        return bench;
    }

    // ------------------------------------------------------------------ inputs

    Arena.Input champion(Request req) {
        if (req.buildId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha a build do campeão.");
        }
        return arena.labInput(req.buildId, req.minute);
    }

    Arena.Input dummy(Request req) {
        return arena.dummyInput(req.dummy == null ? new Dummy() : req.dummy);
    }

    private Combo combo(Request req) {
        if (req.combo != null && req.combo.steps != null && !req.combo.steps.isEmpty()) {
            return req.combo;
        }
        if (req.comboId != null) {
            return combos.find(req.comboId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Combo " + req.comboId + " não existe."));
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha ou escreva um combo.");
    }

    private static ComboBench.Config benchConfig(Request req) {
        ComboBench.Config c = req.bench == null ? new ComboBench.Config() : req.bench;
        if (c.behaviors == null || c.behaviors.isEmpty()) {
            c.behaviors = new ArrayList<>(ComboBench.BEHAVIORS);
        }
        c.behaviors.removeIf(b -> !ComboBench.BEHAVIORS.contains(b));
        c.seconds = Math.max(2, Math.min(60, c.seconds));
        c.distance = Math.max(0, Math.min(1500, c.distance));
        return c;
    }

    // ------------------------------------------------------------------ run and calibrate

    public RunResult run(Request req) {
        Arena.Input me = champion(req);
        Arena.Input d = dummy(req);
        Combo c = combo(req);
        ComboBench.Config cfg = benchConfig(req);
        RunResult out = new RunResult();
        out.combo = c;
        out.champion = me.f.champion;
        out.report = bench.evaluate(me, d, c, cfg);
        String show = req.show != null && cfg.behaviors.contains(req.show) ? req.show : cfg.behaviors.get(0);
        out.fight = bench.run(me, d, c, show, cfg, true);
        return out;
    }

    /**
     * The value of each item of the champion: the combo again against the ragdoll (the behavior shown) without the
     * item, no frames, the runs in parallel.
     */
    public List<ArenaModel.ItemValue> itemValues(Request req) {
        Arena.Input me = champion(req);
        Arena.Input d = dummy(req);
        Combo c = combo(req);
        ComboBench.Config cfg = benchConfig(req);
        String show = req.show != null && cfg.behaviors.contains(req.show) ? req.show : cfg.behaviors.get(0);
        List<ArenaModel.ItemValue> rows = new ArrayList<>();
        List<java.util.concurrent.Callable<ArenaModel.Result>> runs = new ArrayList<>();
        for (Map.Entry<String, Long> item : me.f.items.entrySet()) {
            Arena.Input without = arena.labInput(req.buildId, req.minute, java.util.Set.of(item.getValue()));
            ArenaModel.ItemValue v = new ArenaModel.ItemValue();
            v.item = item.getKey();
            v.itemId = item.getValue();
            rows.add(v);
            runs.add(() -> bench.run(without, d, c, show, cfg, false));
        }
        List<ArenaModel.Result> results = ArenaService.parallel(runs);
        for (int i = 0; i < rows.size(); i++) {
            ArenaService.fill(rows.get(i), results.get(i));
        }
        return rows;
    }

    /** The combo with each calibratable wait moved to its best value (coordinate by coordinate). */
    public RunResult calibrate(Request req) {
        Arena.Input me = champion(req);
        Arena.Input d = dummy(req);
        Combo c = combo(req);
        for (ArenaModel.ComboStep st : c.steps) {
            if (st.until != null && !st.calibratable() && !"marca_aplicada".equals(st.until)
                    && !"reconjuracao_acertaria".equals(st.until) && !"ataque_pronto".equals(st.until)) {
                st.deltaMin = Math.max(-0.2, st.delta - 0.3);   // a wait typed without bounds: around its value
                st.deltaMax = st.delta + 0.4;
            }
        }
        ComboBench.Config cfg = benchConfig(req);
        Combo best = new ComboSearch(bench).calibrate(me, d, c, cfg, req.search == null ? 4 : req.search.threads);
        RunResult out = new RunResult();
        out.combo = best;
        out.champion = me.f.champion;
        out.report = bench.evaluate(me, d, best, cfg);
        out.fight = bench.run(me, d, best, cfg.behaviors.get(0), cfg, true);
        return out;
    }

    /**
     * The combo with each keystone in place of the build's (the rune's fight effects of the combat file; the others of
     * the list are taken off), and the search run again with it, since the best order may change.
     */
    public List<RuneRow> compareRunes(Request req) {
        Arena.Input base = champion(req);
        Arena.Input d = dummy(req);
        Combo c = combo(req);
        ComboBench.Config cfg = benchConfig(req);
        ComboSearch.Config sc = req.search == null ? new ComboSearch.Config() : req.search;
        sc.budget = Math.max(50, Math.min(20000, sc.budget));
        List<RuneRow> out = new ArrayList<>();
        List<String> runes = new ArrayList<>(req.runes == null ? List.of() : req.runes);
        runes.add(0, null);
        for (String rune : runes) {
            Arena.Input me = base.copy();
            me.f = copyFighter(base.f);
            me.f.effects.removeIf(e -> req.runes.contains(e.item));
            if (rune != null) {
                for (Map<String, Object> e : catalog.runeEffects(rune)) {
                    me.f.effects.add(new com.ornnplanner.arena.fight.ItemEffect(rune, e));
                }
            }
            RuneRow row = new RuneRow();
            row.rune = rune;
            row.combo = bench.evaluate(me, d, c, cfg);
            ComboBench.Metrics m = row.combo.byBehavior.values().iterator().next();
            row.runeDamage = rune == null ? 0 : m.bySource.getOrDefault(rune, 0.0);
            row.procs = rune == null ? 0 : m.procs.getOrDefault(rune, 0.0);
            ComboSearch.Result r = new ComboSearch(bench).search(me, d, cfg, sc, new ComboSearch.Listener() { });
            if (!r.best.isEmpty()) {
                row.best = r.best.get(0).combo;
                row.bestReport = r.best.get(0).report;
                ComboBench.Metrics b = row.bestReport.byBehavior.values().iterator().next();
                row.bestRuneDamage = rune == null ? 0 : b.bySource.getOrDefault(rune, 0.0);
                row.bestProcs = rune == null ? 0 : b.procs.getOrDefault(rune, 0.0);
            }
            out.add(row);
        }
        return out;
    }

    /** A fighter whose effects can be changed for one comparison (the rest is shared, read only). */
    private static com.ornnplanner.arena.fight.Fighter copyFighter(com.ornnplanner.arena.fight.Fighter f) {
        com.ornnplanner.arena.fight.Fighter c = new com.ornnplanner.arena.fight.Fighter();
        try {
            for (java.lang.reflect.Field fd : f.getClass().getFields()) {
                if (!java.lang.reflect.Modifier.isStatic(fd.getModifiers()) && !java.lang.reflect.Modifier.isFinal(fd.getModifiers())) {
                    fd.set(c, fd.get(f));   // shallow: the run only reads them
                }
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        c.effects = new ArrayList<>(f.effects);
        return c;
    }

    // ------------------------------------------------------------------ search

    public SearchJob startSearch(Request req) {
        Arena.Input me = champion(req);   // checks the request now, not in the background
        Arena.Input d = dummy(req);
        ComboBench.Config cfg = benchConfig(req);
        ComboSearch.Config sc = req.search == null ? new ComboSearch.Config() : req.search;
        sc.beam = Math.max(4, Math.min(256, sc.beam));
        sc.maxSteps = Math.max(1, Math.min(12, sc.maxSteps));
        sc.budget = Math.max(50, Math.min(200000, sc.budget));
        SearchJob job = new SearchJob();
        job.id = "b" + ids.incrementAndGet();
        job.champion = me.f.champion;
        job.buildId = req.buildId;
        job.minute = req.minute;
        job.budget = sc.budget;
        job.startedAt = System.currentTimeMillis();
        job.message = "na fila";
        jobs.put(job.id, job);
        String buildName = me.build;
        runner.submit(() -> {
            try {
                job.status = "running";
                job.message = "buscando";
                ComboSearch.Result r = new ComboSearch(bench).search(me, d, cfg, sc, new ComboSearch.Listener() {
                    @Override
                    public void progress(int evaluated, int depth, double best) {
                        job.evaluated = evaluated;
                        job.depth = depth;
                        job.best = best;
                    }

                    @Override
                    public boolean cancelled() {
                        return job.cancel;
                    }
                });
                job.evaluated = r.evaluated;
                job.log = r.log;
                if (job.cancel) {
                    job.status = "cancelled";
                    job.message = "cancelado";
                } else {
                    if (req.save) {
                        combos.deleteFound(job.champion, req.buildId, req.minute);
                        for (ComboSearch.Found f : r.best) {
                            f.combo.build = buildName;
                            f.combo.buildId = req.buildId;
                            f.combo.minute = req.minute;
                            combos.save(f.combo);
                        }
                    }
                    job.found = r.best;
                    job.status = "done";
                    job.message = r.best.size() + " combos encontrados em " + r.evaluated + " medidos ("
                            + ("budget".equals(r.stopped) ? "orçamento esgotado" : "busca completa") + ")";
                }
            } catch (Throwable e) {
                log.warn("Combo search {} failed", job.id, e);
                job.status = "failed";
                job.message = "falhou: " + e.getMessage();
            }
            job.finishedAt = System.currentTimeMillis();
        });
        return job;
    }

    public SearchJob job(String id) {
        SearchJob j = jobs.get(id);
        if (j == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Busca " + id + " não encontrada.");
        }
        return j;
    }

    public SearchJob cancel(String id) {
        SearchJob j = job(id);
        j.cancel = true;
        return j;
    }

    // ------------------------------------------------------------------ the user's ragdolls

    public List<Dummy> dummies() {
        List<Dummy> out = new ArrayList<>();
        jdbc.query("SELECT id, name, data FROM arena_dummy ORDER BY name COLLATE NOCASE", rs -> {
            try {
                Dummy d = JSON.readValue(rs.getString("data"), Dummy.class);
                d.id = rs.getLong("id");
                d.name = rs.getString("name");
                out.add(d);
            } catch (Exception e) {
                log.warn("unreadable ragdoll {}", rs.getLong("id"));
            }
        });
        return out;
    }

    public Dummy saveDummy(Dummy d) {
        String name = d.name == null || d.name.isBlank() ? "Boneco" : d.name.trim();
        d.name = name;
        String data;
        try {
            data = JSON.writeValueAsString(d);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        String now = Instant.now().toString();
        if (d.id == null) {
            KeyHolder kh = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("INSERT INTO arena_dummy (name, data, updated_at) VALUES (?,?,?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, name);
                ps.setString(2, data);
                ps.setString(3, now);
                return ps;
            }, kh);
            d.id = kh.getKey() == null ? null : kh.getKey().longValue();
        } else if (jdbc.update("UPDATE arena_dummy SET name = ?, data = ?, updated_at = ? WHERE id = ?", name, data, now, d.id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Boneco " + d.id + " não existe.");
        }
        return d;
    }

    public void deleteDummy(long id) {
        jdbc.update("DELETE FROM arena_dummy WHERE id = ?", id);
    }
}
