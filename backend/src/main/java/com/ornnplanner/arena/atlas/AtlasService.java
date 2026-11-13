package com.ornnplanner.arena.atlas;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.ArenaService;
import com.ornnplanner.arena.EffectModel;
import com.ornnplanner.arena.EffectTestService;
import com.ornnplanner.arena.discovery.CampaignService;
import com.ornnplanner.arena.fight.FighterBuilder;
import com.ornnplanner.engine.Model.ItemDef;
import com.ornnplanner.engine.Model.ItemEdit;
import com.ornnplanner.repo.BuildRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Rodada 16, the interaction atlas: a run fights two champions (its fixed context: champions, mode, minutes) with
 * conditions alone, in pairs and in triples, and keeps every set's race; the guide predicts any set from them and ranks
 * what each side gains next, from whatever is fixed ahead. Deterministic: the same run gives the same races.
 */
@Service
public class AtlasService {

    private static final Logger log = LoggerFactory.getLogger(AtlasService.class);
    private static final int BATCH = 400;
    /** Starts of a real fight of two whole builds: one build swings from 0 to 100 between starts. */
    private static final int CONFIRM_STARTS = 24;
    private static final double[] DISTANCES = {800, 650, 950, 720, 880, 760, 900};
    private static final double[] LATERAL = {0, 80, -80, 40, -40, 120, -120};

    private final AtlasRepository repo;
    private final AtlasCatalog catalog;
    private final ArenaService arena;
    private final CampaignService campaign;
    private final com.ornnplanner.publico.PublicMode publicMode;
    private final Map<String, Object> seed;
    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "atlas");
        t.setDaemon(true);
        return t;
    });
    private volatile Long running;
    private volatile boolean cancel;

    public AtlasService(AtlasRepository repo, AtlasCatalog catalog, ArenaService arena, @Lazy CampaignService campaign,
                        com.ornnplanner.publico.PublicMode publicMode,
                        @Value("${planner.seed.atlas:classpath:seed/atlas_7_3d.yml}") Resource file) {
        this.repo = repo;
        this.publicMode = publicMode;
        this.catalog = catalog;
        this.arena = arena;
        this.campaign = campaign;
        try (InputStream in = file.getInputStream()) {
            this.seed = new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + file, e);
        }
    }

    /** Runs left running by a stop of the app go back to the queue, which starts again. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void resume() {
        if (publicMode.on()) {
            return;   // the public version reads a read-only copy: the atlas is local only
        }
        repo.resetRunning();
        kick();
    }

    // ------------------------------------------------------------------ runs

    /** The presets of atlas_&lt;patch&gt;.yml (key -> config), with the seed's defaults applied. */
    @SuppressWarnings("unchecked")
    public Map<String, AtlasModel.Config> presets() {
        Map<String, AtlasModel.Config> out = new LinkedHashMap<>();
        Map<String, Object> runs = (Map<String, Object>) seed.getOrDefault("execucoes", Map.of());
        runs.forEach((k, v) -> out.put(k, config((Map<String, Object>) v)));
        return out;
    }

    @SuppressWarnings("unchecked")
    AtlasModel.Config config(Map<String, Object> m) {
        Map<String, Object> d = (Map<String, Object>) seed.getOrDefault("padrao", Map.of());
        Map<String, Object> all = new LinkedHashMap<>(d);
        all.putAll(m);
        AtlasModel.Config c = new AtlasModel.Config();
        c.name = str(all.get("nome"));
        c.mode = String.valueOf(all.getOrDefault("modo", "x1"));
        c.a = str(all.get("a"));
        c.b = str(all.get("b"));
        if (all.get("minutos") instanceof List) {
            c.minutes = new ArrayList<>();
            for (Object o : (List<Object>) all.get("minutos")) {
                c.minutes.add(((Number) o).doubleValue());
            }
        }
        if (all.get("dimensoes") instanceof List) {
            c.dims = new ArrayList<>();
            for (Object o : (List<Object>) all.get("dimensoes")) {
                c.dims.add(((Number) o).intValue());
            }
        }
        c.duration = num(all.get("duracao"), c.duration);
        c.starts = (int) num(all.get("largadas"), c.starts);
        c.top2d = (int) num(all.get("top_2d"), c.top2d);
        c.top3d = (int) num(all.get("top_3d"), c.top3d);
        c.validation = (int) num(all.get("validacao"), c.validation);
        c.builds = (int) num(all.get("builds_inteiras"), c.builds);
        if (all.get("fontes") instanceof List) {
            c.sources = new ArrayList<>();
            for (Object o : (List<Object>) all.get("fontes")) {
                c.sources.add(String.valueOf(o));
            }
        }
        c.seed = (long) num(all.get("semente"), c.seed);
        return c;
    }

    public List<AtlasModel.Run> list() {
        return repo.list();
    }

    public AtlasModel.Run find(long id) {
        return repo.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Execução do atlas " + id + " não existe."));
    }

    /** Queues a run (a preset by key, or a config) and starts it when nothing else of the atlas runs. */
    public AtlasModel.Run start(String preset, AtlasModel.Config given) {
        AtlasModel.Config c = given;
        if (preset != null) {
            c = presets().get(preset);
            if (c == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Execução pronta desconhecida: " + preset);
            }
        }
        if (c == null || c.a == null || c.b == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe os dois campeões.");
        }
        if (!"x1".equals(c.mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Só o modo x1 existe por enquanto.");
        }
        if (c.minutes == null || c.minutes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe ao menos um minuto.");
        }
        long id = repo.insert(c);
        kick();
        return find(id);
    }

    public void cancel(long id) {
        if (running != null && running == id) {
            cancel = true;
        } else {
            repo.finish(id, "cancelado", null, null);
        }
    }

    public void delete(long id) {
        if (running != null && running == id) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Essa execução está rodando: cancele antes.");
        }
        repo.delete(id);
    }

    /** Runs the next pending run, if none is running. */
    private synchronized void kick() {
        if (running != null) {
            return;
        }
        AtlasModel.Run next = repo.list().stream().filter(r -> "pendente".equals(r.status))
                .min(Comparator.comparingLong(r -> r.id)).orElse(null);
        if (next == null) {
            return;
        }
        running = next.id;
        cancel = false;
        runner.submit(() -> {
            try {
                AtlasModel.Result res = execute(next.id, next.config);
                repo.finish(next.id, cancel ? "cancelado" : "feito", null, res);
            } catch (RuntimeException e) {
                log.error("Atlas run {} failed", next.id, e);
                repo.finish(next.id, "erro", e.getMessage() == null ? e.toString() : e.getMessage(), null);
            } finally {
                synchronized (this) {
                    running = null;
                }
                kick();
            }
        });
    }

    // ------------------------------------------------------------------ the measures

    AtlasModel.Result execute(long id, AtlasModel.Config c) {
        BuildRepository.Build baseA = campaign.standardBuild(c.a);
        BuildRepository.Build baseB = campaign.standardBuild(c.b);
        AtlasModel.Result res = new AtlasModel.Result();
        for (double minute : c.minutes) {
            if (cancel) {
                break;
            }
            res.minutes.add(minute(id, c, baseA, baseB, minute));
        }
        return res;
    }

    private AtlasModel.MinuteResult minute(long id, AtlasModel.Config c, BuildRepository.Build baseA, BuildRepository.Build baseB, double minute) {
        long t0 = System.nanoTime();
        AtlasModel.MinuteResult mr = new AtlasModel.MinuteResult();
        mr.minute = minute;
        mr.a = side(baseA, c);
        mr.b = side(baseB, c);
        Fights f = new Fights(c, baseA, baseB, minute, mr);
        String tag = "min " + fmt(minute) + ": ";
        // 1 dimension (and the base)
        List<String> sets = new ArrayList<>();
        sets.add("");
        mr.a.conditions.forEach(x -> sets.add("A:" + x.key));
        mr.b.conditions.forEach(x -> sets.add("B:" + x.key));
        f.measure(sets, id, tag + "1 dimensão");
        mr.base = mr.races.get("");
        List<String> poolA = top(mr, "A", c.top2d, false);
        List<String> poolB = top(mr, "B", c.top2d, false);
        if (c.dims.contains(2) && !cancel) {
            List<String> pairs = new ArrayList<>();
            addCombos(pairs, poolA, poolA, null, f);
            addCombos(pairs, poolB, poolB, null, f);
            addCombos(pairs, poolA, poolB, null, f);
            f.measure(pairs, id, tag + "2 dimensões");
        }
        List<String> ta = top(mr, "A", c.top3d, true);
        List<String> tb = top(mr, "B", c.top3d, true);
        if (c.dims.contains(3) && !cancel) {
            // the pairs among them (with top2d some may be missing), then the triples
            List<String> pairs = new ArrayList<>();
            addCombos(pairs, ta, ta, null, f);
            addCombos(pairs, tb, tb, null, f);
            addCombos(pairs, ta, tb, null, f);
            f.measure(pairs, id, tag + "pares dos mais relevantes");
            List<String> triples = new ArrayList<>();
            addCombos(triples, ta, ta, ta, f);
            addCombos(triples, tb, tb, tb, f);
            addCombos(triples, ta, ta, tb, f);
            addCombos(triples, ta, tb, tb, f);
            f.measure(triples, id, tag + "3 dimensões");
        }
        if (c.validation > 0 && !cancel) {
            validate(mr, c, f, poolA, poolB, ta, tb, id, tag);
        }
        if (c.builds > 0 && !cancel) {
            f.fights += measureBuilds(id, c, mr, baseA, baseB, tag);
        }
        mr.fights = f.fights;
        mr.seconds = (System.nanoTime() - t0) / 1e9;
        return mr;
    }

    private AtlasModel.Side side(BuildRepository.Build base, AtlasModel.Config c) {
        AtlasModel.Side s = new AtlasModel.Side();
        s.unit = base.unitCode;
        FighterBuilder.Combatant cb = new FighterBuilder.Combatant();
        cb.build = base;
        s.champion = arena.championOf(cb);
        s.baseName = base.name;
        if (base.runePage != null) {
            s.keystone = base.runePage.keystone;
            if (base.runePage.primaryRunes != null) {
                s.runes.addAll(base.runePage.primaryRunes);
            }
            if (base.runePage.secondaryRune != null) {
                s.runes.add(base.runePage.secondaryRune);
            }
        }
        if (base.spells != null) {
            s.spells.addAll(base.spells);
        }
        s.conditions = catalog.conditions(base, c.sources);
        return s;
    }

    /**
     * The most relevant conditions of a side: by the size of their note, and with {@code withPairs} also of their pairs'
     * interactions; {@code n} &lt;= 0 = all.
     */
    static List<String> top(AtlasModel.MinuteResult mr, String side, int n, boolean withPairs) {
        AtlasModel.Side s = "A".equals(side) ? mr.a : mr.b;
        List<String> keys = new ArrayList<>();
        s.conditions.forEach(x -> keys.add(side + ":" + x.key));
        if (n <= 0 || keys.size() <= n) {
            return keys;
        }
        Map<String, Double> rel = new LinkedHashMap<>();
        List<String> all = new ArrayList<>();
        mr.a.conditions.forEach(x -> all.add("A:" + x.key));
        mr.b.conditions.forEach(x -> all.add("B:" + x.key));
        for (String k : keys) {
            Double n1 = AtlasMath.note1(mr.races, mr.base, k);
            double r = n1 == null ? 0 : Math.abs(n1);
            if (withPairs) {
                for (String o : all) {
                    if (!o.equals(k)) {
                        Double n2 = AtlasMath.note2(mr.races, mr.base, k, o);
                        r += n2 == null ? 0 : Math.abs(n2) / 4;
                    }
                }
            }
            rel.put(k, r);
        }
        keys.sort(Comparator.comparingDouble((String k) -> -rel.get(k)).thenComparing(k -> k));
        return new ArrayList<>(keys.subList(0, n));
    }

    /** Every compatible combination of one from each list (two or three lists), as set keys, without repeats. */
    private static void addCombos(List<String> out, List<String> x, List<String> y, List<String> z, Fights f) {
        Set<String> seen = new HashSet<>(out);
        for (String a : x) {
            for (String b : y) {
                if (a.equals(b)) {
                    continue;
                }
                if (z == null) {
                    if (f.compatible(List.of(a, b))) {
                        String k = AtlasMath.setKey(List.of(a, b));
                        if (seen.add(k)) {
                            out.add(k);
                        }
                    }
                    continue;
                }
                for (String c : z) {
                    if (c.equals(a) || c.equals(b)) {
                        continue;
                    }
                    if (f.compatible(List.of(a, b, c))) {
                        String k = AtlasMath.setKey(List.of(a, b, c));
                        if (seen.add(k)) {
                            out.add(k);
                        }
                    }
                }
            }
        }
    }

    /** Random sets never measured: their real race against the prediction of each order. */
    private void validate(AtlasModel.MinuteResult mr, AtlasModel.Config c, Fights f, List<String> poolA, List<String> poolB,
                          List<String> topA, List<String> topB, long id, String tag) {
        Random rng = new Random(c.seed * 31 + (long) (mr.minute * 100));
        List<String> sets = new ArrayList<>();
        Set<String> seen = new HashSet<>(mr.races.keySet());
        int guard = 0;
        while (sets.size() < c.validation && guard++ < c.validation * 50) {
            // half among the most relevant, where the triples were measured (so the third order is judged too)
            boolean relevant = sets.size() % 2 == 1 && !topA.isEmpty();
            List<String> pa = relevant ? topA : poolA;
            List<String> pb = relevant ? topB : poolB;
            List<String> s = new ArrayList<>();
            int na = 1 + rng.nextInt(4);
            int nb = rng.nextInt(4);
            for (int i = 0; i < na && !pa.isEmpty(); i++) {
                s.add(pa.get(rng.nextInt(pa.size())));
            }
            for (int i = 0; i < nb && !pb.isEmpty(); i++) {
                s.add(pb.get(rng.nextInt(pb.size())));
            }
            List<String> uniq = new ArrayList<>(new LinkedHashSet<>(s));
            if (uniq.size() < 3 || !f.compatible(uniq)) {
                continue;
            }
            String k = AtlasMath.setKey(uniq);
            if (seen.add(k)) {
                sets.add(k);
            }
        }
        mr.validationRaces = f.measureApart(sets, id, tag + "validação");
        score(mr);
    }

    /** The validation's numbers on both scales and each order, and the scale the guide uses (the smaller error). */
    static void score(AtlasModel.MinuteResult mr) {
        mr.validation = new ArrayList<>();
        Map<String, Double> lg = AtlasMath.logits(mr.races);
        double lb = AtlasMath.logit(mr.base);
        for (int scale = 0; scale <= 1; scale++) {
            for (int o = 1; o <= 3; o++) {
                List<Double> actual = new ArrayList<>();
                List<Double> pred = new ArrayList<>();
                for (Map.Entry<String, Double> e : mr.validationRaces.entrySet()) {
                    Map<String, Double> w = new LinkedHashMap<>();
                    for (String x : e.getKey().split("\\|")) {
                        w.put(x, 1.0);
                    }
                    actual.add(e.getValue());
                    pred.add(scale == 0 ? AtlasMath.predict(mr.races, mr.base, w, o) : AtlasMath.predictLogit(lg, lb, w, o));
                }
                double mae = 0;
                for (int i = 0; i < actual.size(); i++) {
                    mae += Math.abs(actual.get(i) - pred.get(i)) / Math.max(1, actual.size());
                }
                mr.validation.add(new double[] {o, AtlasMath.correlation(actual, pred), mae, actual.size(), scale});
            }
        }
        fit(mr, lg, lb);
    }

    private static final double[] F1 = {1, 0.75, 0.5};
    private static final double[] F2 = {0, 0.25, 0.5, 1};
    private static final double[] F3 = {0, 0.25, 0.5, 1};

    /**
     * The scale and the factors per order that predict the validation best, by two-fold cross validation: each half of
     * the sets picks the factors that predict it best, judged on the other half; the error is the two halves' mean,
     * and the guide uses the factors that predict all the sets best.
     */
    static void fit(AtlasModel.MinuteResult mr, Map<String, Double> lg, double lb) {
        List<Map.Entry<String, Double>> sets = new ArrayList<>(mr.validationRaces.entrySet());
        if (sets.size() < 10) {
            return;
        }
        List<double[]> grid = new ArrayList<>();
        for (int scale = 0; scale <= 1; scale++) {
            for (double a : F1) {
                for (double b : F2) {
                    for (double c : F3) {
                        grid.add(new double[] {scale, a, b, c});
                    }
                }
            }
        }
        // each set's prediction for each choice, once
        double[][] pred = new double[grid.size()][sets.size()];
        for (int g = 0; g < grid.size(); g++) {
            double[] f = {grid.get(g)[1], grid.get(g)[2], grid.get(g)[3]};
            for (int i = 0; i < sets.size(); i++) {
                Map<String, Double> w = new LinkedHashMap<>();
                for (String x : sets.get(i).getKey().split("\\|")) {
                    w.put(x, 1.0);
                }
                pred[g][i] = grid.get(g)[0] == 0 ? AtlasMath.predict(mr.races, mr.base, w, f) : AtlasMath.predictLogit(lg, lb, w, f);
            }
        }
        double cv = 0;
        List<Double> cvActual = new ArrayList<>();
        List<Double> cvPred = new ArrayList<>();
        for (int fold = 0; fold < 2; fold++) {
            int bestG = best(pred, sets, fold, 1 - fold);
            double err = 0;
            int n = 0;
            for (int i = fold; i < sets.size(); i += 2) {
                err += Math.abs(pred[bestG][i] - sets.get(i).getValue());
                cvActual.add(sets.get(i).getValue());
                cvPred.add(pred[bestG][i]);
                n++;
            }
            cv += err / Math.max(1, n) / 2;
        }
        int all = best(pred, sets, -1, -1);
        mr.scale = grid.get(all)[0] == 0 ? "pontos" : "logit";
        mr.factors = new double[] {grid.get(all)[1], grid.get(all)[2], grid.get(all)[3]};
        mr.fitted = new double[] {cv, AtlasMath.correlation(cvActual, cvPred)};
    }

    /** The choice with the smallest error on the sets of one half ({@code half} 0 / 1 = even / odd sets; -1 = all). */
    private static int best(double[][] pred, List<Map.Entry<String, Double>> sets, int skip, int half) {
        int best = 0;
        double bestErr = Double.MAX_VALUE;
        for (int g = 0; g < pred.length; g++) {
            double err = 0;
            for (int i = 0; i < sets.size(); i++) {
                if (half >= 0 && i % 2 != half) {
                    continue;
                }
                err += Math.abs(pred[g][i] - sets.get(i).getValue());
            }
            if (err < bestErr - 1e-12) {
                best = g;
                bestErr = err;
            }
        }
        return best;
    }

    /**
     * Redoes the validation of a finished run (new sets, half among the most relevant), keeping its races: for runs
     * measured before the validation kept its sets. Runs on the calling thread.
     */
    public AtlasModel.Run revalidate(long id) {
        AtlasModel.Run run = find(id);
        if (run.result == null || running != null && running == id) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Essa execução não terminou.");
        }
        AtlasModel.Config c = run.config;
        BuildRepository.Build baseA = campaign.standardBuild(c.a);
        BuildRepository.Build baseB = campaign.standardBuild(c.b);
        for (AtlasModel.MinuteResult mr : run.result.minutes) {
            Fights f = new Fights(c, baseA, baseB, mr.minute, mr);
            List<String> poolA = top(mr, "A", c.top2d, false);
            List<String> poolB = top(mr, "B", c.top2d, false);
            validate(mr, c, f, poolA, poolB, top(mr, "A", c.top3d, true), top(mr, "B", c.top3d, true), id, "validação");
        }
        repo.finish(id, run.status, run.error, run.result);
        return find(id);
    }

    /** The fights of one minute: each side's sets prepared once, the fights in parallel by batches. */
    private final class Fights {
        final AtlasModel.Config c;
        final BuildRepository.Build baseA;
        final BuildRepository.Build baseB;
        final double minute;
        final AtlasModel.MinuteResult mr;
        final Map<String, AtlasModel.Condition> byKey = new LinkedHashMap<>();
        int fights;
        private final Map<String, Arena.Input> prepared = new LinkedHashMap<>();
        /** The base and the single conditions of each side: every pair across the sides reuses them, never dropped. */
        private final Map<String, Arena.Input> kept = new LinkedHashMap<>();

        Fights(AtlasModel.Config c, BuildRepository.Build baseA, BuildRepository.Build baseB, double minute, AtlasModel.MinuteResult mr) {
            this.c = c;
            this.baseA = baseA;
            this.baseB = baseB;
            this.minute = minute;
            this.mr = mr;
            mr.a.conditions.forEach(x -> byKey.put("A:" + x.key, x));
            mr.b.conditions.forEach(x -> byKey.put("B:" + x.key, x));
        }

        boolean compatible(Collection<String> keys) {
            Set<String> tags = new HashSet<>();
            for (String k : keys) {
                AtlasModel.Condition x = byKey.get(k);
                if (x == null) {
                    return false;
                }
                for (String t : x.exclusive) {
                    if (!tags.add(k.substring(0, 1) + "|" + t)) {
                        return false;
                    }
                }
            }
            return true;
        }

        /** Measures the sets not measured yet, into the minute's races. */
        void measure(List<String> sets, long id, String stage) {
            List<String> todo = new ArrayList<>();
            for (String s : sets) {
                if (!mr.races.containsKey(s)) {
                    todo.add(s);
                }
            }
            mr.races.putAll(run(todo, id, stage));
        }

        /** Measures sets kept apart from the races (the validation: the prediction must not see them). */
        Map<String, Double> measureApart(List<String> sets, long id, String stage) {
            return run(sets, id, stage);
        }

        private Map<String, Double> run(List<String> todo, long id, String stage) {
            Map<String, Double> out = new LinkedHashMap<>();
            for (int from = 0; from < todo.size() && !cancel; from += BATCH) {
                List<String> batch = todo.subList(from, Math.min(todo.size(), from + BATCH));
                List<Callable<Double>> jobs = new ArrayList<>();
                long p0 = System.nanoTime();
                for (String set : batch) {
                    Arena.Input a = input(set, "A");
                    Arena.Input b = input(set, "B");
                    jobs.add(() -> race(a, b));
                }
                long p1 = System.nanoTime();
                List<Double> done = ArenaService.parallel(jobs);
                mr.prepareSeconds += (p1 - p0) / 1e9;
                mr.fightSeconds += (System.nanoTime() - p1) / 1e9;
                for (int i = 0; i < batch.size(); i++) {
                    out.put(batch.get(i), done.get(i));
                }
                fights += batch.size() * Math.max(1, c.starts);
                repo.progress(id, "rodando", stage, Math.min(todo.size(), from + BATCH), todo.size());
                if (prepared.size() > 4000) {
                    prepared.clear();
                }
            }
            return out;
        }

        /** The side's fighter for the set's conditions of that side, prepared once (on this thread). */
        private Arena.Input input(String set, String side) {
            List<AtlasModel.Condition> mine = new ArrayList<>();
            List<String> keys = new ArrayList<>();
            if (!set.isEmpty()) {
                for (String k : set.split("\\|")) {
                    if (k.startsWith(side + ":")) {
                        mine.add(byKey.get(k));
                        keys.add(k);
                    }
                }
            }
            String key = side + "#" + String.join("|", keys);
            Map<String, Arena.Input> cache = keys.size() <= 1 ? kept : prepared;
            Arena.Input in = cache.get(key);
            if (in == null) {
                ArenaModel.Slot me = slot("A".equals(side) ? baseA : baseB, minute, mine, "A".equals(side) ? 0 : 1);
                ArenaModel.Slot dummy = new ArenaModel.Slot();   // only to prepare this side: a ragdoll is cheap
                dummy.team = "A".equals(side) ? 1 : 0;
                dummy.dummy = new ArenaModel.Dummy();
                ArenaModel.Scenario sc = new ArenaModel.Scenario();
                sc.fighters = "A".equals(side) ? List.of(me, dummy) : List.of(dummy, me);
                List<Arena.Input> ins = arena.prepareSlots(sc);
                in = ins.get("A".equals(side) ? 0 : 1);
                cache.put(key, in);
            }
            return in;
        }

        private double race(Arena.Input a, Arena.Input b) {
            double sum = 0;
            for (double r : races(a, b, c, c.starts)) {
                sum += r;
            }
            return sum / Math.max(1, c.starts);
        }
    }

    /**
     * Side A's race in each of n starts. The first seven pair each distance with its lateral offset; after them every
     * round of seven shifts the offsets by one, so up to 49 starts are all different (the first seven never change).
     */
    private List<Double> races(Arena.Input a, Arena.Input b, AtlasModel.Config c, int starts) {
        List<Double> out = new ArrayList<>();
        int n = Math.max(1, starts);
        for (int k = 0; k < n; k++) {
            Arena.Input x = a.copy();
            Arena.Input y = b.copy();
            x.x = 4600;
            x.y = 0;
            y.x = 4600 + DISTANCES[k % DISTANCES.length];
            y.y = LATERAL[(k + k / DISTANCES.length) % LATERAL.length];
            x.healthTrail = true;
            y.healthTrail = true;
            EffectModel.Measures m = new EffectModel.Measures();
            EffectTestService.race(m, arena.runPlain(List.of(x, y), c.duration), 0, 1, 0);
            out.add(m.race);
        }
        return out;
    }

    /** A side's slot: its base build at the minute, holding exactly the conditions' items, runes and spell swapped in. */
    ArenaModel.Slot slot(BuildRepository.Build base, double minute, List<AtlasModel.Condition> conds, int team) {
        BuildRepository.Build b = AtlasRepository.read(AtlasRepository.write(base), BuildRepository.Build.class);
        b.id = null;
        ArenaModel.Slot s = new ArenaModel.Slot();
        s.team = team;
        s.minute = minute;
        s.brain = ArenaService.STAND;
        s.holdItems = new ArrayList<>();
        s.extraItems = new ArrayList<>();
        Map<Long, ItemEdit> edits = new LinkedHashMap<>();
        for (AtlasModel.Condition c : conds) {
            switch (c.source) {
                case "status":
                    s.extraItems.add(AtlasCatalog.statItem(c));
                    break;
                case "passiva":
                    s.holdItems.add(c.itemId);
                    edits.put(c.itemId, catalog.passiveOnly(c.itemId));
                    break;
                case "fundamental":
                    page(b).keystone = c.rune;
                    break;
                case "runa":
                    swapRune(page(b), c);
                    break;
                case "feitico":
                    swapSpell(b, c.spell);
                    break;
                default:
                    break;
            }
        }
        s.edits = edits.isEmpty() ? null : edits;
        s.build = b;
        return s;
    }

    private static BuildRepository.RunePage page(BuildRepository.Build b) {
        if (b.runePage == null) {
            b.runePage = new BuildRepository.RunePage();
        }
        return b.runePage;
    }

    /** A rune in its row of the primary tree, or in the secondary slot (its tree becomes the secondary). */
    static void swapRune(BuildRepository.RunePage p, AtlasModel.Condition c) {
        if (c.tree != null && c.tree.equals(p.primary) && c.row != null && c.row >= 1) {
            while (p.primaryRunes.size() < c.row) {
                p.primaryRunes.add(null);
            }
            p.primaryRunes.set(c.row - 1, c.rune);
        } else {
            p.secondary = c.tree;
            p.secondaryRune = c.rune;
        }
    }

    /** The spell takes the place of the one that is not Flash (or is added). */
    static void swapSpell(BuildRepository.Build b, String spell) {
        List<String> s = new ArrayList<>(b.spells == null ? List.of() : b.spells);
        int i = -1;
        for (int k = 0; k < s.size(); k++) {
            if (!"Flash".equals(s.get(k))) {
                i = k;
            }
        }
        if (i >= 0) {
            s.set(i, spell);
        } else {
            s.add(spell);
        }
        b.spells = s;
    }

    // ------------------------------------------------------------------ whole builds

    /**
     * Whole builds fought for real: half drawn from the whole shop, half among the empty guide's best items per gold
     * (where the guide works), with close sizes on both sides and sometimes a keystone, a rune or a spell swapped; then
     * the model of whole builds is fitted on them. Returns the fights.
     */
    private int measureBuilds(long id, AtlasModel.Config c, AtlasModel.MinuteResult mr, BuildRepository.Build baseA,
                              BuildRepository.Build baseB, String tag) {
        Random rng = new Random(c.seed * 131 + (long) (mr.minute * 100) + 7);
        prime(mr);
        AtlasModel.BuildModel keep = mr.model;
        mr.model = null;   // the guided pool comes from the notes, not from an earlier model
        List<ItemDef> shop = catalog.buyable();
        List<ItemDef> bestA = guided(mr, "A", shop);
        List<ItemDef> bestB = guided(mr, "B", shop);
        mr.model = keep;
        List<AtlasModel.BuildSample> todo = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int guard = 0;
        while (todo.size() < c.builds && guard++ < c.builds * 20) {
            boolean guidedKind = todo.size() % 2 == 1;
            int ka = 1 + rng.nextInt(5);
            int kb = Math.max(1, Math.min(5, ka + rng.nextInt(3) - 1));
            AtlasModel.BuildSample s = new AtlasModel.BuildSample();
            s.kind = guidedKind ? "guiada" : "sorteada";
            s.a = draw(rng, guidedKind ? bestA : shop, shop, ka, mr, "A", guidedKind);
            s.b = draw(rng, guidedKind ? bestB : shop, shop, kb, mr, "B", guidedKind);
            if (seen.add(AtlasRepository.write(s.a) + "/" + AtlasRepository.write(s.b))) {
                todo.add(s);
            }
        }
        int fights = 0;
        for (int from = 0; from < todo.size() && !cancel; from += BATCH) {
            List<AtlasModel.BuildSample> batch = todo.subList(from, Math.min(todo.size(), from + BATCH));
            List<Callable<Double>> jobs = new ArrayList<>();
            long p0 = System.nanoTime();
            for (AtlasModel.BuildSample s : batch) {
                ArenaModel.Scenario sc = new ArenaModel.Scenario();
                sc.fighters = List.of(fixedSlot(baseA, mr.minute, s.a, 0), fixedSlot(baseB, mr.minute, s.b, 1));
                List<Arena.Input> ins = arena.prepareSlots(sc);
                jobs.add(() -> {
                    List<Double> r = races(ins.get(0), ins.get(1), c, c.starts);
                    double sum = 0;
                    for (double v : r) {
                        sum += v;
                    }
                    return sum / r.size();
                });
            }
            long p1 = System.nanoTime();
            List<Double> done = ArenaService.parallel(jobs);
            mr.prepareSeconds += (p1 - p0) / 1e9;
            mr.fightSeconds += (System.nanoTime() - p1) / 1e9;
            for (int i = 0; i < batch.size(); i++) {
                batch.get(i).race = done.get(i);
            }
            fights += batch.size() * Math.max(1, c.starts);
            repo.progress(id, "rodando", tag + "builds inteiras", Math.min(todo.size(), from + BATCH), todo.size());
        }
        if (cancel) {
            return fights;
        }
        mr.builds = new ArrayList<>(todo);
        fitBuilds(mr);
        return fights;
    }

    /** The empty guide's 30 best items per gold and 3 best boots of a side (the notes with the validation's factors). */
    private List<ItemDef> guided(AtlasModel.MinuteResult mr, String side, List<ItemDef> shop) {
        AtlasModel.Fixed none = new AtlasModel.Fixed();
        List<AtlasModel.Option> opts = new ArrayList<>();
        for (ItemDef it : shop) {
            opts.add(itemOption(mr, side, none, Map.of(), Map.of(), 0, it));
        }
        opts.sort(Comparator.comparingDouble((AtlasModel.Option o) -> -(o.perThousand == null ? o.gain : o.perThousand))
                .thenComparing(o -> o.name));
        List<ItemDef> out = new ArrayList<>();
        int items = 0;
        int boots = 0;
        for (AtlasModel.Option o : opts) {
            ItemDef it = catalog.item(o.itemId);
            if (it.boots() ? boots++ < 3 : items++ < 30) {
                out.add(it);
            }
        }
        return out;
    }

    /**
     * A random build: k items of the pool that go together and boots most of the time. Drawn from the whole shop it
     * sometimes swaps one keystone, one rune or the spell; guided it takes a page like the guide's, among the side's best
     * by their notes: often a keystone and the spell, and up to four runes that go together.
     */
    private AtlasModel.Fixed draw(Random rng, List<ItemDef> pool, List<ItemDef> shop, int k, AtlasModel.MinuteResult mr,
                                  String sideKey, boolean page) {
        AtlasModel.Side side = "A".equals(sideKey) ? mr.a : mr.b;
        List<ItemDef> items = new ArrayList<>();
        List<ItemDef> boots = new ArrayList<>();
        for (ItemDef it : pool) {
            (it.boots() ? boots : items).add(it);
        }
        if (boots.isEmpty()) {
            shop.stream().filter(ItemDef::boots).forEach(boots::add);
        }
        AtlasModel.Fixed f = new AtlasModel.Fixed();
        int guard = 0;
        while (f.items.size() < k && guard++ < 200 && !items.isEmpty()) {
            ItemDef it = items.get(rng.nextInt(items.size()));
            if (!f.items.contains(it.id) && itemFits(it, f.items)) {
                f.items.add(it.id);
            }
        }
        if (rng.nextDouble() < 0.7 && !boots.isEmpty()) {
            ItemDef b = boots.get(rng.nextInt(boots.size()));
            if (itemFits(b, f.items)) {
                f.items.add(b.id);
            }
        }
        List<AtlasModel.Condition> keystones = new ArrayList<>();
        List<AtlasModel.Condition> runes = new ArrayList<>();
        List<AtlasModel.Condition> spells = new ArrayList<>();
        for (AtlasModel.Condition c : side.conditions) {
            if ("fundamental".equals(c.source)) {
                keystones.add(c);
            } else if ("runa".equals(c.source)) {
                runes.add(c);
            } else if ("feitico".equals(c.source)) {
                spells.add(c);
            }
        }
        if (page) {
            keystones = best(mr, sideKey, keystones, 4);
            runes = best(mr, sideKey, runes, 8);
            spells = best(mr, sideKey, spells, 3);
        }
        double chance = page ? 0.6 : 0.25;
        if (rng.nextDouble() < chance && !keystones.isEmpty()) {
            f.keystone = keystones.get(rng.nextInt(keystones.size())).rune;
        }
        int nRunes = page ? rng.nextInt(5) : rng.nextDouble() < 0.25 ? 1 : 0;
        Set<String> tags = new HashSet<>();
        for (int i = 0; i < 20 && f.runes.size() < nRunes && !runes.isEmpty(); i++) {
            AtlasModel.Condition r = runes.get(rng.nextInt(runes.size()));
            if (!f.runes.contains(r.rune) && r.exclusive.stream().noneMatch(tags::contains)) {
                f.runes.add(r.rune);
                tags.addAll(r.exclusive);
            }
        }
        if (rng.nextDouble() < chance && !spells.isEmpty()) {
            f.spell = spells.get(rng.nextInt(spells.size())).spell;
        }
        return f;
    }

    /** The n conditions with the best notes for their side (its own view). */
    private static List<AtlasModel.Condition> best(AtlasModel.MinuteResult mr, String side, List<AtlasModel.Condition> l, int n) {
        List<AtlasModel.Condition> out = new ArrayList<>(l);
        out.sort(Comparator.comparingDouble((AtlasModel.Condition c) -> {
            Double v = AtlasMath.note1(mr.races, mr.base, side + ":" + c.key);
            return v == null ? 1e9 : "A".equals(side) ? -v : v;
        }).thenComparing(c -> c.key));
        return new ArrayList<>(out.subList(0, Math.min(n, out.size())));
    }

    /** The model of whole builds on the measured ones, and each condition's mean gain inside them. */
    void fitBuilds(AtlasModel.MinuteResult mr) {
        List<Map<String, Double>> ws = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        for (AtlasModel.BuildSample s : mr.builds) {
            Map<String, Double> all = weights(mr, "A", s.a);
            all.putAll(weights(mr, "B", s.b));
            ws.add(all);
            ys.add(s.race);
        }
        mr.model = fitModel(mr, ws, ys);
        prime(mr);
        mr.inBuilds = inBuilds(mr, ws);
    }

    /** Penalties on the conditions' corrections tried by the cross validation (-1 = no corrections). */
    private static final double[] LAMBDAS = {-1, 300, 100, 30, 10, 3};
    /** The small penalty on the four global coefficients. */
    private static final double GLOBAL_PENALTY = 0.5;
    private static final int FOLDS = 5;

    /**
     * Ridge regression of the measured builds' log-odds on the four terms and the conditions' weights, its penalty
     * picked by five-fold cross validation on the error in points; the errors of the other predictions next to it.
     */
    static AtlasModel.BuildModel fitModel(AtlasModel.MinuteResult mr, List<Map<String, Double>> ws, List<Double> ys) {
        int n = ws.size();
        if (n < 20) {
            return null;
        }
        Map<String, Double> lg = AtlasMath.logits(mr.races);
        double lb = AtlasMath.logit(mr.base);
        java.util.TreeSet<String> keySet = new java.util.TreeSet<>();
        ws.forEach(w -> keySet.addAll(w.keySet()));
        List<String> keys = new ArrayList<>(keySet);
        Map<String, Integer> col = new java.util.HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            col.put(keys.get(i), 4 + i);
        }
        double[][] x = new double[n][4 + keys.size()];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            double[] t = AtlasMath.terms(lg, lb, ws.get(i));
            System.arraycopy(t, 0, x[i], 0, 4);
            for (Map.Entry<String, Double> e : ws.get(i).entrySet()) {
                x[i][col.get(e.getKey())] = e.getValue();
            }
            y[i] = AtlasMath.logit(Math.max(1, Math.min(99, ys.get(i)))) - lb;
        }
        double best = Double.MAX_VALUE;
        double bestLambda = -1;
        double[] bestPred = null;
        for (double lambda : LAMBDAS) {
            int p = lambda < 0 ? 4 : 4 + keys.size();
            double[] pred = new double[n];
            for (int fold = 0; fold < FOLDS; fold++) {
                List<double[]> tx = new ArrayList<>();
                List<Double> ty = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    if (i % FOLDS != fold) {
                        tx.add(java.util.Arrays.copyOf(x[i], p));
                        ty.add(y[i]);
                    }
                }
                double[] beta = AtlasMath.ridge(tx.toArray(new double[0][]), ty.stream().mapToDouble(Double::doubleValue).toArray(),
                        penalty(p, lambda));
                for (int i = fold; i < n; i += FOLDS) {
                    pred[i] = AtlasMath.expit(lb + dot(x[i], beta));
                }
            }
            double mae = 0;
            for (int i = 0; i < n; i++) {
                mae += Math.abs(pred[i] - ys.get(i)) / n;
            }
            if (mae < best - 1e-9) {
                best = mae;
                bestLambda = lambda;
                bestPred = pred;
            }
        }
        int p = bestLambda < 0 ? 4 : 4 + keys.size();
        double[][] xs = new double[n][];
        for (int i = 0; i < n; i++) {
            xs[i] = java.util.Arrays.copyOf(x[i], p);
        }
        double[] beta = AtlasMath.ridge(xs, y, penalty(p, bestLambda));
        AtlasModel.BuildModel m = new AtlasModel.BuildModel();
        m.coef = java.util.Arrays.copyOf(beta, 4);
        for (int i = 4; i < p; i++) {
            if (Math.abs(beta[i]) > 1e-9) {
                m.theta.put(keys.get(i - 4), beta[i]);
            }
        }
        m.lambda = bestLambda < 0 ? null : bestLambda;
        m.samples = n;
        m.errors.put("modelo", new double[] {best, AtlasMath.correlation(ys, toList(bestPred))});
        // the other predictions, on the same builds
        double[] base = new double[n];
        double[] one = new double[n];
        double[] whole = new double[n];
        double[] fitted = new double[n];
        for (int i = 0; i < n; i++) {
            base[i] = mr.base;
            one[i] = AtlasMath.predictLogit(lg, lb, ws.get(i), 1);
            whole[i] = AtlasMath.predictLogit(lg, lb, ws.get(i), 3);
            if (mr.factors != null) {
                fitted[i] = "pontos".equals(mr.scale) ? AtlasMath.predict(mr.races, mr.base, ws.get(i), mr.factors)
                        : AtlasMath.predictLogit(lg, lb, ws.get(i), mr.factors);
            }
        }
        m.errors.put("base", errors(base, ys));
        m.errors.put("notas", errors(one, ys));
        m.errors.put("inteiras", errors(whole, ys));
        if (mr.factors != null) {
            m.errors.put("fatores", errors(fitted, ys));
        }
        return m;
    }

    private static double[] penalty(int p, double lambda) {
        double[] pen = new double[p];
        for (int i = 0; i < p; i++) {
            pen[i] = i < 4 ? GLOBAL_PENALTY : lambda;
        }
        return pen;
    }

    private static double dot(double[] x, double[] beta) {
        double s = 0;
        for (int i = 0; i < beta.length; i++) {
            s += x[i] * beta[i];
        }
        return s;
    }

    private static List<Double> toList(double[] v) {
        List<Double> out = new ArrayList<>();
        for (double d : v) {
            out.add(d);
        }
        return out;
    }

    private static double[] errors(double[] pred, List<Double> ys) {
        double mae = 0;
        for (int i = 0; i < pred.length; i++) {
            mae += Math.abs(pred[i] - ys.get(i)) / pred.length;
        }
        return new double[] {mae, AtlasMath.correlation(ys, toList(pred))};
    }

    /** Each status and passive condition's mean gain over the measured builds, in its side's view. */
    static Map<String, Double> inBuilds(AtlasModel.MinuteResult mr, List<Map<String, Double>> ws) {
        Map<String, Double> sum = new LinkedHashMap<>();
        if (ws.isEmpty()) {
            return sum;
        }
        for (Map<String, Double> w : ws) {
            double raw = raw(mr, w);
            for (String side : List.of("A", "B")) {
                for (AtlasModel.Condition c : ("A".equals(side) ? mr.a : mr.b).conditions) {
                    if ("status".equals(c.source) || "passiva".equals(c.source)) {
                        String k = side + ":" + c.key;
                        sum.merge(k, gain(mr, w, raw, k, "status".equals(c.source)), Double::sum);
                    }
                }
            }
        }
        sum.replaceAll((k, v) -> v / ws.size());
        return sum;
    }

    /** The run's own scale for the readings (the model, when there is one, is on log-odds). */
    static void prime(AtlasModel.MinuteResult mr) {
        mr.useLogit = !"pontos".equals(mr.scale);
        mr.logits = AtlasMath.logits(mr.races);
    }

    /** A set's prediction before the scale's last step: log-odds (model or logit scale) or points. */
    static double raw(AtlasModel.MinuteResult mr, Map<String, Double> w) {
        double lb = AtlasMath.logit(mr.base);
        if (mr.model != null) {
            return AtlasMath.modelLogit(mr.logits, lb, w, mr.model);
        }
        double[] f = mr.factors != null ? mr.factors : AtlasMath.factors(3);
        return mr.useLogit ? AtlasMath.sum(mr.logits, lb, w, f) : AtlasMath.sum(mr.races, mr.base, w, f);
    }

    /** The raw prediction's derivative in a condition's weight (the set taken without it). */
    private static double grad(AtlasModel.MinuteResult mr, Map<String, Double> w, String x) {
        double lb = AtlasMath.logit(mr.base);
        if (mr.model != null) {
            return AtlasMath.modelGrad(mr.logits, lb, w, x, mr.model);
        }
        double[] f = mr.factors != null ? mr.factors : AtlasMath.factors(3);
        double[] d = mr.useLogit ? AtlasMath.partial(mr.logits, lb, w, x) : AtlasMath.partial(mr.races, mr.base, w, x);
        return f[0] * d[0] + f[1] * d[1] + f[2] * d[2];
    }

    private static double out(AtlasModel.MinuteResult mr, double raw) {
        return mr.model != null || mr.useLogit ? AtlasMath.expit(raw) : Math.max(0, Math.min(100, raw));
    }

    /**
     * What a condition changes in its side's race over a set (in that side's view): a stat one more level's worth, a
     * passive having it against not having it. Exact: the prediction is multilinear in each weight.
     */
    static double gain(AtlasModel.MinuteResult mr, Map<String, Double> w, double raw, String key, boolean stat) {
        double g = grad(mr, w, key);
        double without = stat ? raw : raw - w.getOrDefault(key, 0.0) * g;
        double with = without + g;
        double d = out(mr, with) - out(mr, without);
        return key.startsWith("A:") ? d : -d;
    }

    /**
     * Measures the whole builds of a finished run again (or for the first time, for runs from before them) and refits
     * the model, in the background on the atlas's thread; the run's other measures stay.
     */
    public AtlasModel.Run remeasureBuilds(long id) {
        AtlasModel.Run run = find(id);
        if (run.result == null || run.result.minutes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Essa execução ainda não tem resultado.");
        }
        synchronized (this) {
            if (running != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "O atlas está rodando outra execução: espere ou cancele.");
            }
            running = id;
            cancel = false;
        }
        AtlasModel.Config c = run.config;
        if (c.builds <= 0) {
            c.builds = new AtlasModel.Config().builds;
        }
        repo.progress(id, "rodando", "builds inteiras", 0, c.builds);
        runner.submit(() -> {
            String error = null;
            try {
                BuildRepository.Build baseA = campaign.standardBuild(c.a);
                BuildRepository.Build baseB = campaign.standardBuild(c.b);
                for (AtlasModel.MinuteResult mr : run.result.minutes) {
                    if (cancel) {
                        break;
                    }
                    mr.fights += measureBuilds(id, c, mr, baseA, baseB, "min " + fmt(mr.minute) + ": ");
                }
            } catch (RuntimeException e) {
                log.error("Atlas builds of run {} failed", id, e);
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                repo.finish(id, "feito", error, run.result);
                synchronized (this) {
                    running = null;
                }
                kick();
            }
        });
        return find(id);
    }

    // ------------------------------------------------------------------ the report of conditions

    /**
     * The status and passive conditions of each side and what they are worth: alone, inside the measured builds, and
     * over what is fixed now; with the conditions that raise or cancel them (measured pairs).
     */
    public AtlasModel.ConditionReport report(long id, AtlasModel.GuideRequest q) {
        AtlasModel.MinuteResult mr = minuteOf(find(id), q.minute);
        prime(mr);
        Map<String, Double> all = weights(mr, "A", q.a);
        all.putAll(weights(mr, "B", q.b));
        double raw = raw(mr, all);
        Map<String, String> names = new LinkedHashMap<>();
        mr.a.conditions.forEach(c -> names.put("A:" + c.key, c.name));
        mr.b.conditions.forEach(c -> names.put("B:" + c.key, c.name));
        AtlasModel.ConditionReport out = new AtlasModel.ConditionReport();
        out.minute = mr.minute;
        out.model = mr.model != null;
        out.a = sideReport(mr, "A", all, raw, names);
        out.b = sideReport(mr, "B", all, raw, names);
        return out;
    }

    private AtlasModel.SideReport sideReport(AtlasModel.MinuteResult mr, String side, Map<String, Double> all, double raw,
                                             Map<String, String> names) {
        AtlasModel.Side s = "A".equals(side) ? mr.a : mr.b;
        AtlasModel.SideReport r = new AtlasModel.SideReport();
        r.champion = s.champion;
        r.race = view(side, out(mr, raw));
        for (AtlasModel.Condition c : s.conditions) {
            boolean stat = "status".equals(c.source);
            if (!stat && !"passiva".equals(c.source)) {
                continue;
            }
            String k = side + ":" + c.key;
            AtlasModel.ConditionRow row = new AtlasModel.ConditionRow();
            row.key = c.key;
            row.name = c.name;
            row.source = c.source;
            row.itemId = stat ? null : c.itemId;
            row.stat = c.stat;
            row.amount = c.amount;
            row.level = c.level;
            Double n = AtlasMath.note1(mr.races, mr.base, k);
            row.alone = n == null ? null : "A".equals(side) ? n : -n;
            row.inBuilds = mr.inBuilds == null ? null : mr.inBuilds.get(k);
            row.here = gain(mr, all, raw, k, stat);
            row.gold = stat ? catalog.statGold(c.stat, c.amount) : null;
            partners(mr, side, k, row, names);
            r.rows.add(row);
        }
        r.rows.sort(Comparator.comparingDouble((AtlasModel.ConditionRow x) -> -(x.inBuilds != null ? x.inBuilds : x.here))
                .thenComparing(x -> x.name));
        return r;
    }

    /** The measured pairs that change a condition's worth by a point or more, the three largest of each kind. */
    private static void partners(AtlasModel.MinuteResult mr, String side, String k, AtlasModel.ConditionRow row,
                                 Map<String, String> names) {
        for (Map.Entry<String, String> e : names.entrySet()) {
            String o = e.getKey();
            if (o.equals(k)) {
                continue;
            }
            Double v = AtlasMath.note2(mr.races, mr.base, k, o);
            if (v == null) {
                continue;
            }
            double mine = "A".equals(side) ? v : -v;
            if (Math.abs(mine) < 1) {
                continue;
            }
            AtlasModel.Partner p = new AtlasModel.Partner();
            p.key = o.substring(2);
            p.name = e.getValue();
            p.value = mine;
            boolean own = o.startsWith(side + ":");
            (own ? mine > 0 ? row.synergies : row.overlaps : mine > 0 ? row.punishes : row.counters).add(p);
        }
        for (List<AtlasModel.Partner> l : List.of(row.synergies, row.overlaps, row.counters, row.punishes)) {
            l.sort(Comparator.comparingDouble((AtlasModel.Partner p) -> -Math.abs(p.value)).thenComparing(p -> p.name));
            while (l.size() > 3) {
                l.remove(l.size() - 1);
            }
        }
    }

    /** The run's minute nearest to the one asked (the first when none is asked). */
    private static AtlasModel.MinuteResult minuteOf(AtlasModel.Run run, Double minute) {
        if (run.result == null || run.result.minutes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Essa execução ainda não tem resultado.");
        }
        AtlasModel.MinuteResult mr = run.result.minutes.get(0);
        if (minute != null) {
            for (AtlasModel.MinuteResult m : run.result.minutes) {
                if (Math.abs(m.minute - minute) < Math.abs(mr.minute - minute)) {
                    mr = m;
                }
            }
        }
        return mr;
    }

    // ------------------------------------------------------------------ the guide

    /** What each side gains next from what is fixed, predicted from the run's races at the nearest minute. */
    public AtlasModel.Guide guide(long id, AtlasModel.GuideRequest q) {
        AtlasModel.Run run = find(id);
        AtlasModel.MinuteResult mr = minuteOf(run, q.minute);
        boolean fitted = mr.factors != null || mr.model != null;
        int order = q.order <= 0 && fitted ? 0 : Math.max(1, Math.min(3, q.order <= 0 ? 3 : q.order));
        AtlasModel.Guide g = new AtlasModel.Guide();
        g.minute = mr.minute;
        g.order = order;
        g.scale = q.scale != null ? q.scale : mr.scale == null ? "logit" : mr.scale;
        g.factors = order == 0 ? mr.factors : AtlasMath.factors(order);
        mr.useLogit = "logit".equals(g.scale);
        if (mr.useLogit || mr.model != null) {
            mr.logits = AtlasMath.logits(mr.races);
        }
        Map<String, Double> wa = weights(mr, "A", q.a);
        Map<String, Double> wb = weights(mr, "B", q.b);
        Map<String, Double> all = new LinkedHashMap<>(wa);
        all.putAll(wb);
        for (int o = fitted ? 0 : 1; o <= 3; o++) {
            g.byOrder.put(o, predict(mr, all, Map.of(), o));
        }
        g.a = sideGuide(mr, "A", q.a, wa, wb, order);
        g.b = sideGuide(mr, "B", q.b, wb, wa, order);
        return g;
    }

    /**
     * Fights for real what each side fixed (the real items, with their stats, the keystone, runes and spell) at the run's
     * minute, from the run's starts: how far the prediction is from a whole build.
     */
    public AtlasModel.Confirmation confirm(long id, AtlasModel.GuideRequest q) {
        AtlasModel.Run run = find(id);
        AtlasModel.MinuteResult mr = minuteOf(run, q.minute);
        AtlasModel.Config c = run.config;
        ArenaModel.Slot sa = fixedSlot(campaign.standardBuild(c.a), mr.minute, q.a, 0);
        ArenaModel.Slot sb = fixedSlot(campaign.standardBuild(c.b), mr.minute, q.b, 1);
        ArenaModel.Scenario sc = new ArenaModel.Scenario();
        sc.fighters = List.of(sa, sb);
        List<Arena.Input> ins = arena.prepareSlots(sc);
        AtlasModel.Confirmation out = new AtlasModel.Confirmation();
        out.minute = mr.minute;
        out.starts = races(ins.get(0), ins.get(1), c, Math.max(CONFIRM_STARTS, c.starts));
        for (double r : out.starts) {
            out.real += r / out.starts.size();
        }
        double var = 0;
        for (double r : out.starts) {
            var += (r - out.real) * (r - out.real) / Math.max(1, out.starts.size() - 1);
        }
        out.margin = 1.96 * Math.sqrt(var / out.starts.size());
        AtlasModel.GuideRequest g = new AtlasModel.GuideRequest();
        g.minute = mr.minute;
        g.a = q.a;
        g.b = q.b;
        g.order = q.order;
        g.scale = q.scale;
        AtlasModel.Guide pred = guide(id, g);
        out.predicted = pred.byOrder.getOrDefault(pred.order, pred.a.race);
        return out;
    }

    /** A side holding its fixed items (real ones, with their stats), keystone, runes and spell. */
    private ArenaModel.Slot fixedSlot(BuildRepository.Build base, double minute, AtlasModel.Fixed f, int team) {
        List<AtlasModel.Condition> swaps = new ArrayList<>();
        if (f.keystone != null) {
            AtlasModel.Condition k = new AtlasModel.Condition();
            k.source = "fundamental";
            k.rune = f.keystone;
            swaps.add(k);
        }
        for (String r : f.runes == null ? List.<String>of() : f.runes) {
            com.ornnplanner.seed.RuneCatalog.Rune rune = catalog.rune(r);
            AtlasModel.Condition c = new AtlasModel.Condition();
            c.source = "runa";
            c.rune = r;
            c.tree = rune == null ? null : rune.tree;
            c.row = rune == null ? null : rune.row;
            swaps.add(c);
        }
        if (f.spell != null) {
            AtlasModel.Condition s = new AtlasModel.Condition();
            s.source = "feitico";
            s.spell = f.spell;
            swaps.add(s);
        }
        ArenaModel.Slot s = slot(base, minute, swaps, team);
        s.holdItems = new ArrayList<>(f.items == null ? List.of() : f.items);
        return s;
    }

    /** A side's weights from what it fixed: items spread on the stat levels and their passives, runes, spell, conditions. */
    Map<String, Double> weights(AtlasModel.MinuteResult mr, String side, AtlasModel.Fixed f) {
        AtlasModel.Side s = "A".equals(side) ? mr.a : mr.b;
        Map<String, Double> w = new LinkedHashMap<>();
        Map<String, Double> stats = new LinkedHashMap<>();
        for (Long item : f.items == null ? List.<Long>of() : f.items) {
            catalog.flatStats(item).forEach((k, v) -> stats.merge(k, v, Double::sum));
            for (AtlasModel.Condition c : s.conditions) {
                if ("passiva".equals(c.source) && item.equals(c.itemId)) {
                    w.put(side + ":" + c.key, 1.0);
                }
            }
        }
        stats.forEach((stat, amount) -> {
            AtlasModel.Condition l1 = null;
            AtlasModel.Condition l2 = null;
            for (AtlasModel.Condition c : s.conditions) {
                if ("status".equals(c.source) && stat.equals(c.stat)) {
                    if (c.level == 1) {
                        l1 = c;
                    } else if (c.level == 2) {
                        l2 = c;
                    }
                }
            }
            if (l1 != null) {
                double[] ws = AtlasMath.statWeights(amount, l1.amount, l2 == null ? 0 : l2.amount);
                if (ws[0] > 0) {
                    w.put(side + ":" + l1.key, ws[0]);
                }
                if (ws[1] > 0 && l2 != null) {
                    w.put(side + ":" + l2.key, ws[1]);
                }
            }
        });
        if (f.keystone != null) {
            w.put(side + ":fundamental:" + f.keystone, 1.0);
        }
        for (String r : f.runes == null ? List.<String>of() : f.runes) {
            w.put(side + ":runa:" + r, 1.0);
        }
        if (f.spell != null) {
            w.put(side + ":feitico:" + f.spell, 1.0);
        }
        for (String k : f.conditions == null ? List.<String>of() : f.conditions) {
            w.put(side + ":" + k, 1.0);
        }
        // only conditions the side has (a keystone equal to the base's is no condition)
        Set<String> known = new HashSet<>();
        s.conditions.forEach(c -> known.add(side + ":" + c.key));
        w.keySet().removeIf(k -> !known.contains(k));
        return w;
    }

    private AtlasModel.SideGuide sideGuide(AtlasModel.MinuteResult mr, String side, AtlasModel.Fixed f,
                                           Map<String, Double> mine, Map<String, Double> theirs, int order) {
        AtlasModel.Side s = "A".equals(side) ? mr.a : mr.b;
        AtlasModel.SideGuide g = new AtlasModel.SideGuide();
        g.champion = s.champion;
        g.race = view(side, predict(mr, mine, theirs, order));
        Set<String> tags = tags(s, mine.keySet(), side);
        for (AtlasModel.Condition c : s.conditions) {
            String k = side + ":" + c.key;
            if (mine.containsKey(k) || !fits(c, tags)) {
                continue;
            }
            AtlasModel.Option o = option(mr, side, mine, theirs, order, Map.of(k, 1.0));
            o.key = c.key;
            o.name = c.name;
            switch (c.source) {
                case "fundamental":
                    g.keystones.add(o);
                    break;
                case "runa":
                    g.runes.add(o);
                    break;
                case "feitico":
                    g.spells.add(o);
                    break;
                default:
                    g.conditions.add(o);
            }
        }
        Set<Long> held = new HashSet<>(f.items == null ? List.of() : f.items);
        boolean hasBoots = held.stream().map(catalog::item).anyMatch(it -> it != null && it.boots());
        for (ItemDef it : catalog.buyable()) {
            if (held.contains(it.id) || it.boots() && hasBoots || !itemFits(it, f.items)) {
                continue;
            }
            g.items.add(itemOption(mr, side, f, mine, theirs, order, it));
        }
        g.conditions.sort(byGain());
        g.keystones.sort(byGain());
        g.runes.sort(byGain());
        g.spells.sort(byGain());
        g.items.sort(Comparator.comparingDouble((AtlasModel.Option o) -> -(o.perThousand == null ? o.gain : o.perThousand))
                .thenComparing(o -> o.name));
        cut(g.conditions, 40);
        cut(g.items, 40);
        build(mr, side, f, theirs, order, g);
        return g;
    }

    /** The build the notes assemble: items by gain per gold up to five and boots, then keystone, runes and spell. */
    private void build(AtlasModel.MinuteResult mr, String side, AtlasModel.Fixed f, Map<String, Double> theirs, int order,
                       AtlasModel.SideGuide g) {
        AtlasModel.Fixed cur = new AtlasModel.Fixed();
        cur.items.addAll(f.items == null ? List.of() : f.items);
        cur.keystone = f.keystone;
        cur.runes.addAll(f.runes == null ? List.of() : f.runes);
        cur.spell = f.spell;
        cur.conditions.addAll(f.conditions == null ? List.of() : f.conditions);
        for (Long id : cur.items) {
            ItemDef it = catalog.item(id);
            AtlasModel.Option o = new AtlasModel.Option();
            o.itemId = id;
            o.name = it == null ? String.valueOf(id) : it.name;
            o.cost = it == null ? null : it.cost;
            o.key = "fixo";
            g.build.add(o);
        }
        for (int guard = 0; guard < 8; guard++) {
            long items = cur.items.stream().map(catalog::item).filter(it -> it != null && !it.boots()).count();
            boolean boots = cur.items.stream().map(catalog::item).anyMatch(it -> it != null && it.boots());
            if (items >= 5 && boots) {
                break;
            }
            Map<String, Double> mine = weights(mr, side, cur);
            AtlasModel.Option best = null;
            for (ItemDef it : catalog.buyable()) {
                if (cur.items.contains(it.id) || it.boots() && boots || !it.boots() && items >= 5 || !itemFits(it, cur.items)) {
                    continue;
                }
                AtlasModel.Option o = itemOption(mr, side, cur, mine, theirs, order, it);
                double v = o.perThousand == null ? o.gain : o.perThousand;
                if (best == null || v > (best.perThousand == null ? best.gain : best.perThousand)) {
                    best = o;
                }
            }
            if (best == null) {
                break;
            }
            cur.items.add(best.itemId);
            g.build.add(best);
        }
        for (String kind : List.of("fundamental", "runa", "feitico")) {
            for (int guard = 0; guard < 4; guard++) {
                Map<String, Double> mine = weights(mr, side, cur);
                AtlasModel.Side s = "A".equals(side) ? mr.a : mr.b;
                Set<String> tags = tags(s, mine.keySet(), side);
                AtlasModel.Condition best = null;
                double bestGain = 0;
                for (AtlasModel.Condition c : s.conditions) {
                    if (!kind.equals(c.source) || mine.containsKey(side + ":" + c.key) || !fits(c, tags)) {
                        continue;
                    }
                    double gain = option(mr, side, mine, theirs, order, Map.of(side + ":" + c.key, 1.0)).gain;
                    if (gain > bestGain + 1e-9) {
                        best = c;
                        bestGain = gain;
                    }
                }
                if (best == null) {
                    break;
                }
                if ("fundamental".equals(kind)) {
                    cur.keystone = best.rune;
                } else if ("runa".equals(kind)) {
                    cur.runes.add(best.rune);
                } else {
                    cur.spell = best.spell;
                }
                if (!"runa".equals(kind)) {
                    break;
                }
            }
        }
        AtlasModel.Side s = "A".equals(side) ? mr.a : mr.b;
        g.buildKeystone = cur.keystone != null ? cur.keystone : s.keystone;
        g.buildRunes.addAll(cur.runes);
        g.buildSpell = cur.spell;
        g.buildRace = view(side, predict(mr, weights(mr, side, cur), theirs, order));
    }

    private AtlasModel.Option itemOption(AtlasModel.MinuteResult mr, String side, AtlasModel.Fixed f, Map<String, Double> mine,
                                         Map<String, Double> theirs, int order, ItemDef it) {
        AtlasModel.Fixed with = new AtlasModel.Fixed();
        with.items.addAll(f.items == null ? List.of() : f.items);
        with.items.add(it.id);
        with.keystone = f.keystone;
        with.runes.addAll(f.runes == null ? List.of() : f.runes);
        with.spell = f.spell;
        with.conditions.addAll(f.conditions == null ? List.of() : f.conditions);
        Map<String, Double> after = weights(mr, side, with);
        double before = predict(mr, mine, theirs, order);
        double now = predict(mr, after, theirs, order);
        AtlasModel.Option o = new AtlasModel.Option();
        o.key = "item:" + it.id;
        o.itemId = it.id;
        o.name = it.name;
        o.cost = it.cost;
        o.gain = "A".equals(side) ? now - before : before - now;
        o.perThousand = it.cost > 0 ? o.gain / it.cost * 1000 : null;
        Map<String, Double> parts = new LinkedHashMap<>();
        after.forEach((k, v) -> {
            if (!v.equals(mine.get(k))) {
                parts.put(k.substring(2), v - mine.getOrDefault(k, 0.0));
            }
        });
        o.parts = parts;
        return o;
    }

    private AtlasModel.Option option(AtlasModel.MinuteResult mr, String side, Map<String, Double> mine, Map<String, Double> theirs,
                                     int order, Map<String, Double> add) {
        Map<String, Double> after = new LinkedHashMap<>(mine);
        add.forEach((k, v) -> after.merge(k, v, Double::sum));
        double before = predict(mr, mine, theirs, order);
        double now = predict(mr, after, theirs, order);
        AtlasModel.Option o = new AtlasModel.Option();
        o.gain = "A".equals(side) ? now - before : before - now;
        return o;
    }

    private static double predict(AtlasModel.MinuteResult mr, Map<String, Double> mine, Map<String, Double> theirs, int order) {
        Map<String, Double> all = new LinkedHashMap<>(mine);
        all.putAll(theirs);
        if (order == 0 && mr.model != null) {
            return AtlasMath.expit(AtlasMath.modelLogit(mr.logits, AtlasMath.logit(mr.base), all, mr.model));
        }
        double[] f = order == 0 && mr.factors != null ? mr.factors : AtlasMath.factors(order);
        return mr.useLogit ? AtlasMath.predictLogit(mr.logits, AtlasMath.logit(mr.base), all, f)
                : AtlasMath.predict(mr.races, mr.base, all, f);
    }

    /** Side A's race in that side's own view (B's is 100 minus A's). */
    private static double view(String side, double raceA) {
        return "A".equals(side) ? raceA : 100 - raceA;
    }

    private static Set<String> tags(AtlasModel.Side s, Collection<String> keys, String side) {
        Set<String> tags = new HashSet<>();
        for (AtlasModel.Condition c : s.conditions) {
            if (keys.contains(side + ":" + c.key) && !"status".equals(c.source)) {
                tags.addAll(c.exclusive);
            }
        }
        return tags;
    }

    private static boolean fits(AtlasModel.Condition c, Set<String> tags) {
        if ("status".equals(c.source)) {
            return true;   // stats add up (the item spreads them on the levels)
        }
        for (String t : c.exclusive) {
            if (tags.contains(t)) {
                return false;
            }
        }
        return true;
    }

    /** An item can join these: not the same item group, no shared exclusive group. */
    private boolean itemFits(ItemDef it, List<Long> held) {
        for (Long id : held == null ? List.<Long>of() : held) {
            ItemDef h = catalog.item(id);
            if (h == null) {
                continue;
            }
            String gh = h.group == null ? h.name : h.group;
            String gi = it.group == null ? it.name : it.group;
            if (gh.equals(gi)) {
                return false;
            }
            for (String g : it.exclusiveGroups == null ? List.<String>of() : it.exclusiveGroups) {
                if (h.exclusiveGroups != null && h.exclusiveGroups.contains(g)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Comparator<AtlasModel.Option> byGain() {
        return Comparator.comparingDouble((AtlasModel.Option o) -> -o.gain).thenComparing(o -> o.name == null ? "" : o.name);
    }

    private static void cut(List<AtlasModel.Option> l, int n) {
        while (l.size() > n) {
            l.remove(l.size() - 1);
        }
    }

    private static double num(Object o, double def) {
        return o instanceof Number ? ((Number) o).doubleValue() : def;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String fmt(double v) {
        return Math.abs(v - Math.rint(v)) < 1e-9 ? String.valueOf((long) Math.rint(v)) : String.valueOf(v);
    }
}
