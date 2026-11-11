package com.ornnplanner.arena.discovery;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ornnplanner.arena.ArenaService;
import com.ornnplanner.arena.BuildDeltaService;
import com.ornnplanner.arena.calc.BuildScore;
import com.ornnplanner.arena.fight.BuildArchetypes;
import com.ornnplanner.arena.fight.ChampionProfiles;
import com.ornnplanner.engine.Model;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.service.PlannerService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The build discovery (Rodada 9, parts 2 to 6): the funnel of {@link Funnel} measured by the class score of
 * {@link BuildScore}. Screening without fights (the planner's whole-build delta of each item, by the class), quick fights
 * (three scenarios of each report, one start, one minute), the full battery (the robust score) and the confirmation (other
 * starts). One job at a time, in the background, with progress and a stop.
 */
@Service
public class DiscoveryService {

    private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    /** The minute the screening and the quick fights read. */
    static final double QUICK_MINUTE = 15;

    final PlannerService planner;
    final BuildRepository builds;
    final BuildScore scorer;
    final BuildDeltaService deltas;
    final ChampionProfiles profiles;
    final BuildArchetypes archetypes;
    final ArenaService arena;
    final com.ornnplanner.arena.calc.CalculatorService calc;
    final com.ornnplanner.seed.RuneCatalog runes;
    final com.ornnplanner.repo.DiscoveryRepository repo;
    private final Map<String, List<String>> families = new LinkedHashMap<>();
    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "discovery");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, DiscoveryModel.Job> jobs = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    /** Seconds a full battery and a quick reading took lately (what the estimate multiplies). */
    volatile double secondsPerFull = 6;
    volatile double secondsPerQuick = 0.8;
    volatile double secondsPerOrder = 0.5;

    @SuppressWarnings("unchecked")
    public DiscoveryService(PlannerService planner, BuildRepository builds, BuildScore scorer, BuildDeltaService deltas,
                            ChampionProfiles profiles, BuildArchetypes archetypes, ArenaService arena,
                            com.ornnplanner.arena.calc.CalculatorService calc, com.ornnplanner.seed.RuneCatalog runes,
                            com.ornnplanner.repo.DiscoveryRepository repo,
                            @Value("${planner.seed.classification:classpath:seed/classificacao_7_3d.yml}") Resource file) {
        this.calc = calc;
        this.repo = repo;
        this.runes = runes;
        this.planner = planner;
        this.builds = builds;
        this.scorer = scorer;
        this.deltas = deltas;
        this.profiles = profiles;
        this.archetypes = archetypes;
        this.arena = arena;
        try (InputStream in = file.getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            ((Map<String, Object>) root.getOrDefault("familias", Map.of())).forEach((k, v) -> families.put(k, (List<String>) v));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + file, e);
        }
    }

    // ------------------------------------------------------------------ jobs

    public DiscoveryModel.Estimate estimate(DiscoveryModel.Request r) {
        DiscoveryModel.Budget b = r.custom != null ? r.custom : DiscoveryModel.Budget.of(r.budget);
        long[] c = b.counts();
        DiscoveryModel.Estimate e = new DiscoveryModel.Estimate();
        e.full = c[0];
        e.quick = c[1];
        e.order = r.order ? c[2] * b.search.confirm : 0;
        e.candidates = b.shortlist + b.jokers;
        // the known builds (saved and meta) go through the same battery, and the best one is confirmed
        int known = 0;
        try {
            BuildRepository.Build base = base(r);
            known = known(base).size() + repo.metaBuilds(base.unitCode).size();
        } catch (ResponseStatusException ex) {
            // no build yet: the estimate leaves them out
        }
        e.full += known + 1;
        e.seconds = e.full * secondsPerFull + e.quick * secondsPerQuick + e.order * secondsPerOrder
                + (r.runes ? b.search.confirm * 20 * secondsPerFull : 0);
        return e;
    }

    public DiscoveryModel.Job start(DiscoveryModel.Request r) {
        DiscoveryModel.Job j = new DiscoveryModel.Job();
        j.id = ids.incrementAndGet();
        j.estimateSeconds = estimate(r).seconds;
        jobs.put(j.id, j);
        runner.submit(() -> {
            try {
                j.result = run(r, j);
                j.status = j.stop ? "stopped" : "done";
                keep(r, j);
            } catch (RuntimeException e) {
                j.status = "error";
                j.error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
        });
        return j;
    }

    /** A discovery run alone is kept in build_discovery too (no campaign), by patch, champion and configuration. */
    private void keep(DiscoveryModel.Request r, DiscoveryModel.Job j) {
        try {
            BuildRepository.Build base = base(r);
            com.ornnplanner.repo.DiscoveryRepository.DiscoveryRow row = new com.ornnplanner.repo.DiscoveryRepository.DiscoveryRow();
            row.patch = runes.catalog().patch;
            row.unitCode = base.unitCode;
            row.champion = j.result.champion;
            DiscoveryModel.Request cfg = copy(r, DiscoveryModel.Request.class);
            cfg.build = null;
            row.config = JSON.writeValueAsString(cfg);
            row.configKey = base.unitCode + "|avulsa|" + j.id + "|" + System.currentTimeMillis();
            row.status = j.stop ? "pausado" : "feito";
            row.step = "fim";
            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("discovery", j.result);
            row.result = JSON.writeValueAsString(wrap);
            row.seconds = j.result.seconds;
            repo.insertDiscovery(row);
        } catch (Exception e) {
            // keeping it is a convenience: the job's answer stands without it
        }
    }

    /** A finished item's section: upgraded, or an evolved form (Rodada 13: the support items were left out). */
    static boolean finished(String section) {
        return "aprimorado".equals(section) || "evolucao".equals(section);
    }

    /** The patch of the game data (what the stored results are kept by). */
    public String patch() {
        return runes.catalog().patch;
    }

    public DiscoveryModel.Job job(long id) {
        DiscoveryModel.Job j = jobs.get(id);
        if (j == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return j;
    }

    public void stop(long id) {
        job(id).stop = true;
    }

    // ------------------------------------------------------------------ the run

    BuildRepository.Build base(DiscoveryModel.Request r) {
        if (r.build != null) {
            return r.build;
        }
        if (r.buildId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha uma build.");
        }
        return builds.findBuild(r.buildId).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Build " + r.buildId + " não existe."));
    }

    /** The whole discovery on the calling thread (the job and the tests). */
    public DiscoveryModel.Result run(DiscoveryModel.Request r, DiscoveryModel.Job job) {
        long t0 = System.nanoTime();
        BuildRepository.Build base = base(r);
        DiscoveryModel.Budget budget = r.custom != null ? r.custom : DiscoveryModel.Budget.of(r.budget);
        budget.search.seed = r.seed;
        DiscoveryModel.Result res = new DiscoveryModel.Result();
        res.budget = r.budget;
        res.champion = scorer.championOf(base);
        res.championClass = scorer.classOf(base);
        res.quality = quality(res.champion);
        res.lowConfidence = !"A".equals(res.quality) && !"B".equals(res.quality);
        Evaluator ev = new Evaluator(this, base, r, job);
        // stage 1: screening, no fights
        stage(job, "triagem", 0, 1);
        Map<Long, Double> screen = ev.screen();
        Map<Long, List<String>> groups = new LinkedHashMap<>();
        for (PlannerService.ItemView i : planner.itemViews()) {
            if (screen.containsKey(i.id)) {
                List<String> g = new ArrayList<>(i.tabs);
                g.add(i.effects.isEmpty() && (i.passives == null || i.passives.isEmpty()) ? "so_status" : "com_passiva");
                groups.put(i.id, g);
            }
        }
        List<Long> shortlist = Funnel.shortlist(screen, groups, budget.shortlist, budget.perGroup, budget.jokers, r.seed);
        for (long req : r.required) {
            if (!shortlist.contains(req)) {
                shortlist.add(req);
            }
        }
        for (long id : shortlist) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("name", ev.name(id));
            row.put("value", screen.getOrDefault(id, 0.0));
            res.shortlist.add(row);
        }
        // the seeds: the champion's saved builds (part 4b) as kits
        List<Funnel.Kit> seeds = new ArrayList<>();
        for (BuildRepository.Build b : known(base)) {
            Funnel.Kit k = ev.kitOf(b);
            if (k != null) {
                if (r.seedsFromSaved) {
                    seeds.add(k);
                }
                DiscoveryModel.KitView kv = ev.view(k, null, "salva");
                kv.label = b.name;
                kv.keystone = b.runePage == null ? null : b.runePage.keystone;
                res.known.add(kv);
            }
        }
        for (com.ornnplanner.repo.DiscoveryRepository.MetaBuild m : repo.metaBuilds(base.unitCode)) {
            Funnel.Kit k = new Funnel.Kit(m.items, m.boots);
            if (k.items.isEmpty()) {
                continue;
            }
            if (r.seedsFromSaved && ev.allowed(k)) {
                seeds.add(k);
            }
            DiscoveryModel.KitView kv = ev.view(k, null, "meta");
            kv.label = m.name + (m.source == null || m.source.isBlank() ? "" : " (" + m.source + ")");
            kv.keystone = m.keystone;
            res.known.add(kv);
        }
        // a champion that plays support also starts from support versions of its seeds: each support item's evolved
        // form first, with the first four items of the first two other seeds (Rodada 13)
        if (ev.supportOk) {
            List<Funnel.Kit> others = new ArrayList<>();
            seeds.stream().filter(k -> ev.supports(k) == 0).limit(2).forEach(others::add);
            for (long sup : ev.support) {
                Model.ItemDef d = ev.items.get(sup);
                if (d == null || !"evolucao".equals(d.section) || r.forbidden.contains(sup)) {
                    continue;
                }
                for (Funnel.Kit o : others) {
                    List<Long> its = new ArrayList<>();
                    its.add(sup);
                    o.items.stream().limit(4).forEach(its::add);
                    Funnel.Kit k = new Funnel.Kit(its, o.boots);
                    if (ev.allowed(k)) {
                        seeds.add(k);
                    }
                }
            }
            ev.support.stream().filter(i -> ev.screen.containsKey(i) && !shortlist.contains(i)).forEach(shortlist::add);
        }
        // stages 2 to 4: the genetic search, quick fights, full battery, confirmation
        Funnel.Result fr = Funnel.search(ev, shortlist, ev.boots, seeds, budget.search);
        res.quickRuns = fr.quickRuns;
        res.fullRuns = fr.fullRuns;
        res.confirmRuns = fr.confirmRuns;
        res.surrogateCorrelation = fr.surrogateCorrelation;
        res.stopped = fr.stopped;
        for (Funnel.Entry e : fr.ranked.subList(0, Math.min(10, fr.ranked.size()))) {
            res.ranked.add(ev.view(e.kit, e, e.origin));
        }
        fr.niches.forEach((k, e) -> res.niches.put(k, ev.view(e.kit, e, e.origin)));
        res.standard = standard(res.ranked);
        // synergies of the best two finalists
        if (!job(job).stop && !fr.ranked.isEmpty()) {
            for (Funnel.Synergy s : Funnel.synergies(ev, fr.ranked.subList(0, Math.min(2, fr.ranked.size())), budget.search.synergyPairs)) {
                DiscoveryModel.SynergyView v = new DiscoveryModel.SynergyView();
                v.a = s.a;
                v.b = s.b;
                v.nameA = ev.name(s.a);
                v.nameB = ev.name(s.b);
                v.value = s.value;
                v.explain = String.format(java.util.Locale.ROOT, "%s + %s: %.1f juntos, %.1f e %.1f sozinhos, %.1f sem os dois",
                        v.nameA, v.nameB, s.withBoth, s.withA, s.withB, s.without);
                res.synergies.add(v);
            }
        }
        afterSearch(r, ev, fr, res, budget, shortlist, job);
        res.objective = DiscoveryModel.NOVELTY.equals(r.objective) ? DiscoveryModel.NOVELTY : DiscoveryModel.BEST;
        novelty(r, ev, res, job);
        res.support = bestSupport(ev);
        publish(r, ev, res, budget, job);
        res.seconds = (System.nanoTime() - t0) / 1e9;
        if (ev.fullCount > 0) {
            secondsPerFull = ev.fullSeconds / ev.fullCount;
        }
        if (ev.quickCount > 0) {
            secondsPerQuick = ev.quickSeconds / ev.quickCount;
        }
        return res;
    }

    /**
     * After the item search (part 4): the runes of the finalists, coordinate by coordinate, and their purchase order by
     * the calculator's step search (with the pruning by the saved builds' starts); then once more the items with the best
     * page, because a rune may change which item is worth most (items → runes → order → items).
     */
    void afterSearch(DiscoveryModel.Request r, Evaluator ev, Funnel.Result fr, DiscoveryModel.Result res, DiscoveryModel.Budget b,
                     List<Long> shortlist, DiscoveryModel.Job job) {
        if (fr.ranked.isEmpty() || ev.stopped()) {
            return;
        }
        int finalists = Math.min(res.ranked.size(), DiscoveryModel.COMPLETE.equals(r.budget) ? 3 : 1);
        RuneSearch.Shape shape = RuneSearch.Shape.of(runes.catalog());
        BuildRepository.RunePage bestPage = null;
        for (int i = 0; i < finalists && !ev.stopped(); i++) {
            DiscoveryModel.KitView v = res.ranked.get(i);
            finish(r, ev, v, b, res, shape, job, i, finalists);
            if (i == 0 && "busca".equals(v.runesFrom)) {
                bestPage = v.runePage;
            }
        }
        // items again with the best page: a rune may change which item is worth most
        if (bestPage != null && !ev.stopped() && !res.ranked.get(0).runeChanges.isEmpty()) {
            stage(job, "itens com as runas", 0, 1);
            BuildRepository.Build base2 = copy(ev.base, BuildRepository.Build.class);
            base2.runePage = bestPage;
            Evaluator ev2 = new Evaluator(this, base2, r, job);
            ev2.screen.putAll(ev.screen);
            Funnel.Config c2 = copy(b.search, Funnel.Config.class);
            c2.generations = Math.max(1, b.search.generations / 3);
            c2.population = Math.min(8, b.search.population);
            c2.confirm = 1;
            List<Funnel.Kit> seeds = new ArrayList<>();
            fr.ranked.stream().limit(c2.population).forEach(e -> seeds.add(e.kit));
            Funnel.Result again = Funnel.search(ev2, shortlist, ev.boots, seeds, c2);
            res.fullRuns += again.fullRuns;
            res.quickRuns += again.quickRuns;
            if (!again.ranked.isEmpty()) {
                Funnel.Entry top = again.ranked.get(0);
                boolean changed = !top.kit.key().equals(fr.ranked.get(0).kit.key());
                res.notes.add(changed
                        ? "Com as runas novas, o melhor conjunto mudou: " + String.join(", ", ev.view(top.kit, top, "").names)
                        : "Com as runas novas, o melhor conjunto continua o mesmo.");
                if (changed) {
                    DiscoveryModel.KitView nv = ev2.view(top.kit, top, "runas");
                    nv.runePage = bestPage;
                    nv.runesFrom = "busca";
                    res.ranked.add(0, nv);
                    res.standard = standard(res.ranked);
                }
            }
        }
    }

    /**
     * The runes and the purchase order of one kit (part 4), unless already done: the runes coordinate by coordinate at
     * the quick minute, then the calculator's order with them. A cut order (the budget ran out before a whole one) is
     * not kept: the screening's order stays and a note says so (Rodada 13; before, a build of two items reached the site).
     */
    void finish(DiscoveryModel.Request r, Evaluator ev, DiscoveryModel.KitView v, DiscoveryModel.Budget b, DiscoveryModel.Result res,
                RuneSearch.Shape shape, DiscoveryModel.Job job, int i, int n) {
        Funnel.Kit k = new Funnel.Kit(v.items, v.boots);
        BuildRepository.Build b0 = ev.build(k);
        if (v.runePage != null) {
            b0.runePage = v.runePage;
        }
        if (r.runes && !"busca".equals(v.runesFrom) && !ev.stopped()) {
            stage(job, "runas", i, n);
            double meanWeight = scorer.versatilityWeight("runas");
            RuneSearch.Outcome o = RuneSearch.search(b0.runePage, shape, page -> {
                BuildRepository.Build bp = copy(b0, BuildRepository.Build.class);
                bp.runePage = page;
                List<Double> points = new ArrayList<>();
                scorer.score(bp, QUICK_MINUTE, BuildScore.Options.single()).readings.forEach(x -> points.add(x.points));
                return RuneSearch.versatility(points, meanWeight)[0];
            }, 2, ev::stopped);
            v.runePage = o.page;
            v.runeChanges = o.changes;
            v.runesFrom = "busca";
            b0.runePage = o.page;
            res.notes.add(String.format(java.util.Locale.ROOT, "Runas de %s: %d páginas medidas, %.1f → %.1f%s", String.join(", ", v.names),
                    o.measured, o.start, o.score, o.changes.isEmpty() ? " (a página da build já era a melhor)" : " (" + String.join("; ", o.changes) + ")"));
        }
        if (r.order && !v.orderSearched && !ev.stopped()) {
            stage(job, "ordem", i, n);
            com.ornnplanner.arena.calc.CalculatorService.Request cr = new com.ornnplanner.arena.calc.CalculatorService.Request();
            cr.build = b0;
            cr.candidates = new ArrayList<>(k.items);
            cr.required = new ArrayList<>(k.items);
            cr.length = k.items.size();
            cr.budget = b.orderBudget;
            cr.beam = 3;
            cr.boots = new ArrayList<>();
            if (k.boots != null) {
                cr.boots.add(k.boots);
                Model.ItemDef bd = ev.items.get(k.boots);
                if (bd != null) {
                    bd.components.forEach(c -> cr.boots.add(c.itemId));
                }
            } else {
                cr.noBoots = true;
            }
            com.ornnplanner.arena.calc.CalculatorService.Answer ans = calc.compute(cr, null);
            res.orderMeasures += (int) ans.evaluations;
            res.prunedByStart += ans.prunedByStart;
            v.orderSearched = true;
            if (ans.best != null && whole(ans.best.order, k)) {
                v.order = ans.best.order;
                v.importance = ans.importance;
                v.componentFirst = ans.componentFirst;
            } else {
                v.orderCut = true;
                res.notes.add("Ordem de " + String.join(", ", v.names) + ": o orçamento da ordem acabou antes de uma ordem "
                        + "completa; fica a ordem da triagem (o item que mais vale primeiro).");
            }
        }
    }

    /** Whether an order buys every item of the kit and its boots. */
    static boolean whole(List<Long> order, Funnel.Kit k) {
        return order != null && order.containsAll(k.items) && (k.boots == null || order.contains(k.boots));
    }

    /**
     * What goes out (Rodada 13): the standard build and every discovery get their own runes and order; every other kit
     * shown takes them from a finished kit with the same items, else the standard's page (marked "padrao").
     */
    void publish(DiscoveryModel.Request r, Evaluator ev, DiscoveryModel.Result res, DiscoveryModel.Budget b, DiscoveryModel.Job job) {
        if (res.ranked.isEmpty()) {
            return;
        }
        RuneSearch.Shape shape = RuneSearch.Shape.of(runes.catalog());
        List<DiscoveryModel.KitView> todo = new ArrayList<>();
        if (res.standard != null) {
            todo.add(res.standard);
        }
        if (res.support != null) {
            todo.add(res.support);
        }
        res.discoveries.forEach(f -> todo.add(f.kit));
        List<DiscoveryModel.KitView> all = new ArrayList<>(res.ranked);
        all.addAll(res.niches.values());
        Map<String, DiscoveryModel.KitView> done = new LinkedHashMap<>();
        for (DiscoveryModel.KitView v : all) {
            if ("busca".equals(v.runesFrom) && v.orderSearched) {
                done.putIfAbsent(v.items + "|" + v.boots, v);
            }
        }
        for (int i = 0; i < todo.size() && !ev.stopped(); i++) {
            DiscoveryModel.KitView v = todo.get(i);
            DiscoveryModel.KitView same = done.get(v.items + "|" + v.boots);
            if (same != null && same != v) {
                inherit(v, same);
                continue;
            }
            finish(r, ev, v, b, res, shape, job, i, todo.size());
            done.putIfAbsent(v.items + "|" + v.boots, v);
        }
        BuildRepository.RunePage page = res.standard == null || !"busca".equals(res.standard.runesFrom) ? null : res.standard.runePage;
        all.addAll(todo);
        for (DiscoveryModel.KitView v : all) {
            if ("busca".equals(v.runesFrom)) {
                continue;
            }
            DiscoveryModel.KitView same = done.get(v.items + "|" + v.boots);
            if (same != null && same != v) {
                inherit(v, same);
            } else if (page != null) {
                v.runePage = page;
                v.runesFrom = "padrao";
            }
        }
    }

    /** A kit's runes and order taken from a finished kit with the same items. */
    private static void inherit(DiscoveryModel.KitView v, DiscoveryModel.KitView from) {
        v.runePage = from.runePage;
        v.runeChanges = from.runeChanges;
        v.runesFrom = from.runesFrom;
        v.order = from.order;
        v.importance = from.importance;
        v.componentFirst = from.componentFirst;
        v.orderSearched = from.orderSearched;
        v.orderCut = from.orderCut;
    }

    // ------------------------------------------------------------------ part 5: novelty against the meta

    /** Novelty: 1 − the Jaccard of the items (and boots), plus a step when the keystone differs, at most 1. */
    public static double novelty(DiscoveryModel.KitView a, DiscoveryModel.KitView b, double keystoneStep) {
        java.util.Set<Long> x = new java.util.TreeSet<>(a.items);
        java.util.Set<Long> y = new java.util.TreeSet<>(b.items);
        if (a.boots != null) {
            x.add(a.boots);
        }
        if (b.boots != null) {
            y.add(b.boots);
        }
        java.util.Set<Long> inter = new java.util.TreeSet<>(x);
        inter.retainAll(y);
        java.util.Set<Long> union = new java.util.TreeSet<>(x);
        union.addAll(y);
        double d = union.isEmpty() ? 0 : 1 - (double) inter.size() / union.size();
        String ka = a.runePage != null ? a.runePage.keystone : a.keystone;
        if (ka != null && b.keystone != null && !ka.equals(b.keystone)) {
            d += keystoneStep;
        }
        return Math.min(1, d);
    }

    /**
     * Part 5: the known builds (the champion's saved builds and the meta builds) measured by the same battery; the novelty
     * of each found build (its distance to the closest known one); the share of scenarios where it beats the best known;
     * and the "discoveries": score at least the best known's, novelty high, held in the confirmation, each explained.
     */
    @SuppressWarnings("unchecked")
    void novelty(DiscoveryModel.Request r, Evaluator ev, DiscoveryModel.Result res, DiscoveryModel.Job job) {
        if (res.known.isEmpty() || res.ranked.isEmpty() || ev.stopped()) {
            return;
        }
        Map<String, Object> cfg = (Map<String, Object>) scorer.rules().getOrDefault("novidade", Map.of());
        double threshold = cfg.get("limiar") instanceof Number ? ((Number) cfg.get("limiar")).doubleValue() : 0.4;
        double keystoneStep = cfg.get("fundamental") instanceof Number ? ((Number) cfg.get("fundamental")).doubleValue() : 0.15;
        double tolerance = cfg.get("tolerancia_confirmacao") instanceof Number ? ((Number) cfg.get("tolerancia_confirmacao")).doubleValue() : 1.0;
        // the known builds through the same battery (cached when the search already met them as seeds)
        int n = 0;
        DiscoveryModel.KitView bestKnown = null;
        for (DiscoveryModel.KitView k : res.known) {
            if (ev.stopped()) {
                return;
            }
            Funnel.Kit kit = new Funnel.Kit(k.items, k.boots);
            if (!ev.allowed(kit)) {
                k.niche = "fora das regras da loja";
                continue;
            }
            Funnel.Entry e = new Funnel.Entry();
            e.kit = kit;
            e.full = ev.full(kit);
            DiscoveryModel.KitView measured = ev.view(kit, e, k.origin);
            k.score = measured.score;
            k.mean = measured.mean;
            k.p20 = measured.p20;
            k.readings = measured.readings;
            k.curve = measured.curve;
            k.niche = measured.niche;
            k.worst = measured.worst;
            k.versatile = measured.versatile;
            stage(job, "conhecidas", ++n, res.known.size());
            if (bestKnown == null || k.score > bestKnown.score) {
                bestKnown = k;
            }
        }
        if (bestKnown == null) {
            return;
        }
        List<DiscoveryModel.KitView> views = new ArrayList<>(res.ranked);
        views.addAll(res.niches.values());
        for (DiscoveryModel.KitView v : views) {
            double best = 2;
            for (DiscoveryModel.KitView k : res.known) {
                double d = novelty(v, k, keystoneStep);
                if (d < best) {
                    best = d;
                    v.closestKnown = k.label;
                }
            }
            v.novelty = best;
            int wins = 0;
            int total = 0;
            for (Map.Entry<String, Double> x : v.readings.entrySet()) {
                Double y = bestKnown.readings.get(x.getKey());
                if (y != null) {
                    total++;
                    wins += x.getValue() > y + 1e-9 ? 1 : 0;
                }
            }
            v.beatsKnown = total == 0 ? null : 100.0 * wins / total;
        }
        // Rodada 13: aiming at the best builds, the niches' best are shown apart (not discoveries), and with fewer known
        // builds than the YAML's minimum every kit is "new": no discovery is highlighted
        boolean best = !DiscoveryModel.NOVELTY.equals(r.objective);
        int minKnown = cfg.get("minimo_conhecidas") instanceof Number ? ((Number) cfg.get("minimo_conhecidas")).intValue() : 2;
        if (best && n < minKnown) {
            res.notes.add(String.format(java.util.Locale.ROOT, "Só %d build(s) conhecida(s) do campeão (mínimo %d): a novidade não diz "
                    + "muito, nenhuma descoberta destacada. Com o objetivo \"novidade\" elas aparecem mesmo assim.", n, minKnown));
            return;
        }
        List<DiscoveryModel.KitView> candidates = best ? new ArrayList<>(res.ranked) : views;
        // the discoveries, confirmed on starts the search did not see against the best known confirmed the same way
        Funnel.Eval knownConfirmed = ev.confirm(new Funnel.Kit(bestKnown.items, bestKnown.boots));
        bestKnown.confirmed = knownConfirmed.score;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (DiscoveryModel.KitView v : candidates) {
            String key = v.items + "|" + v.boots;
            if (!seen.add(key) || v.novelty == null || v.novelty < threshold || v.score + 1e-9 < bestKnown.score || ev.stopped()) {
                continue;
            }
            if (v.confirmed == null) {
                v.confirmed = ev.confirm(new Funnel.Kit(v.items, v.boots)).score;
            }
            DiscoveryModel.Finding f = explain(v, bestKnown, res, ev);
            f.confirmed = v.confirmed + tolerance >= bestKnown.confirmed;
            if (f.confirmed && !res.lowConfidence) {
                res.discoveries.add(f);
            } else {
                res.notes.add((f.confirmed ? "Descoberta não destacada (ficha de baixa confiança): " : "Não se manteve na confirmação: ") + f.text);
            }
        }
    }

    /** Why a found build beats the known one: the scenarios where it wins and loses, the items swapped and the synergy. */
    DiscoveryModel.Finding explain(DiscoveryModel.KitView v, DiscoveryModel.KitView known, DiscoveryModel.Result res, Evaluator ev) {
        DiscoveryModel.Finding f = new DiscoveryModel.Finding();
        f.kit = v;
        f.against = known.label;
        // by scenario (the mean over the minutes), the difference in points
        Map<String, double[]> by = new LinkedHashMap<>();
        for (Map.Entry<String, Double> x : v.readings.entrySet()) {
            Double y = known.readings.get(x.getKey());
            if (y == null) {
                continue;
            }
            String scenario = x.getKey().substring(x.getKey().indexOf('|') + 1);
            double[] acc = by.computeIfAbsent(scenario, k -> new double[2]);
            acc[0] += x.getValue() - y;
            acc[1]++;
        }
        List<Map.Entry<String, double[]>> diffs = new ArrayList<>(by.entrySet());
        diffs.sort(Comparator.comparingDouble((Map.Entry<String, double[]> e) -> -e.getValue()[0] / e.getValue()[1]).thenComparing(Map.Entry::getKey));
        for (Map.Entry<String, double[]> d : diffs) {
            double mean = d.getValue()[0] / d.getValue()[1];
            String label = String.format(java.util.Locale.ROOT, "%s %+.1f", scenarioName(d.getKey(), ev), mean);
            if (mean > 0.5 && f.wins.size() < 3) {
                f.wins.add(label);
            }
        }
        for (int i = diffs.size() - 1; i >= 0 && f.losses.size() < 3; i--) {
            double mean = diffs.get(i).getValue()[0] / diffs.get(i).getValue()[1];
            if (mean < -0.5) {
                f.losses.add(String.format(java.util.Locale.ROOT, "%s %+.1f", scenarioName(diffs.get(i).getKey(), ev), mean));
            }
        }
        List<String> out = new ArrayList<>();
        List<String> in = new ArrayList<>();
        for (long i : known.items) {
            if (!v.items.contains(i)) {
                out.add(ev.name(i));
            }
        }
        for (long i : v.items) {
            if (!known.items.contains(i)) {
                in.add(ev.name(i));
            }
        }
        if (!java.util.Objects.equals(known.boots, v.boots) && v.boots != null) {
            out.add(known.boots == null ? "sem bota" : ev.name(known.boots));
            in.add(ev.name(v.boots));
        }
        List<String> kept = new ArrayList<>();
        for (long i : v.items) {
            if (known.items.contains(i)) {
                kept.add(ev.name(i));
            }
        }
        if (in.size() > 3) {
            // most of it changed: a list of swaps says nothing, what stays and what comes in does (Rodada 13)
            f.swaps.add("outro conjunto: " + (kept.isEmpty() ? "nenhum item em comum" : "mantém " + String.join(", ", kept))
                    + "; entram " + String.join(", ", in));
        } else if (!out.isEmpty() || !in.isEmpty()) {
            f.swaps.add("troca " + (out.isEmpty() ? "nada" : String.join(" + ", out)) + " por " + (in.isEmpty() ? "nada" : String.join(" + ", in)));
        }
        String ka = v.runePage != null ? v.runePage.keystone : null;
        if (ka != null && known.keystone != null && !ka.equals(known.keystone)) {
            f.swaps.add("fundamental " + known.keystone + " → " + ka);
        }
        for (DiscoveryModel.SynergyView s : res.synergies) {
            if (s.value > 0.5 && v.items.contains(s.a) && v.items.contains(s.b)) {
                f.synergy = String.format(java.util.Locale.ROOT, "a sinergia %s + %s sustenta (%+.1f)", s.nameA, s.nameB, s.value);
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(java.util.Locale.ROOT, "Nota %.1f contra %.1f de %s (a melhor conhecida). ", v.score, known.score, known.label));
        sb.append(f.swaps.isEmpty() ? "Mesmos itens" : Character.toUpperCase(String.join("; ", f.swaps).charAt(0)) + String.join("; ", f.swaps).substring(1));
        sb.append('.');
        if (!f.wins.isEmpty()) {
            sb.append(" Ganha em ").append(String.join(", ", f.wins)).append('.');
        }
        if (!f.losses.isEmpty()) {
            sb.append(" Perde em ").append(String.join(", ", f.losses)).append('.');
        }
        if (f.synergy != null) {
            sb.append(' ').append(Character.toUpperCase(f.synergy.charAt(0))).append(f.synergy.substring(1)).append('.');
        }
        if (!f.wins.isEmpty() || !f.losses.isEmpty()) {
            sb.append(" (pontos por cenário, na média dos minutos da nota.)");
        }
        f.text = sb.toString();
        return f;
    }

    /** A scenario's name from "part|key" (the evaluator kept the names it saw). */
    static String scenarioName(String partKey, Evaluator ev) {
        String name = ev.names.get(partKey);
        return name == null ? partKey : name;
    }

    /** Versatility of a kit as an item choice (part 4b): mean, worst and P20 of its scenario-minutes. */
    void versatility(DiscoveryModel.KitView v) {
        if (v.readings.isEmpty()) {
            return;
        }
        double[] x = RuneSearch.versatility(new ArrayList<>(v.readings.values()), scorer.versatilityWeight("itens"));
        v.worst = x[2];
        v.versatile = x[0];
    }

    private static DiscoveryModel.Job job(DiscoveryModel.Job j) {
        return j == null ? new DiscoveryModel.Job() : j;
    }

    static void stage(DiscoveryModel.Job job, String stage, int done, int total) {
        if (job != null) {
            job.stage = stage;
            job.done = done;
            job.total = total;
        }
    }

    /**
     * The robust default build: the best mean among the ranked whose P20 is at least the median P20 of the top five
     * (an acceptable worst cases), else the first.
     */
    static DiscoveryModel.KitView standard(List<DiscoveryModel.KitView> ranked) {
        if (ranked.isEmpty()) {
            return null;
        }
        List<Double> p20 = new ArrayList<>();
        for (DiscoveryModel.KitView k : ranked.subList(0, Math.min(5, ranked.size()))) {
            p20.add(k.p20 == null ? 0 : k.p20);
        }
        double bar = BuildScore.percentile(p20, 0.5);
        DiscoveryModel.KitView best = null;
        for (DiscoveryModel.KitView k : ranked) {
            if ((k.p20 == null ? 0 : k.p20) + 1e-9 >= bar && (best == null || (k.mean == null ? k.score : k.mean) > (best.mean == null ? best.score : best.mean))) {
                best = k;
            }
        }
        return best == null ? ranked.get(0) : best;
    }

    /** Whether a champion plays support (Rodada 13): a support lane, or a saved or meta build of it with a support item. */
    boolean supportCapable(BuildRepository.Build base, java.util.Set<Long> support) {
        ChampionProfiles.Profile p = profiles.of(scorer.championOf(base));
        if (p != null && p.lanes.contains("Suporte")) {
            return true;
        }
        for (BuildRepository.Build b : known(base)) {
            if (b.steps.stream().anyMatch(st -> st.itemId != null && support.contains(st.itemId))) {
                return true;
            }
        }
        return repo.metaBuilds(base.unitCode).stream().anyMatch(m -> m.items.stream().anyMatch(support::contains));
    }

    /**
     * A support build's gold and XP per minute (Rodada 13): the mean of the champion's saved support builds, else the
     * base build's times the YAML's {@code suporte} shares.
     */
    @SuppressWarnings("unchecked")
    double[] supportPace(BuildRepository.Build base, java.util.Set<Long> support) {
        double g = 0;
        double x = 0;
        int n = 0;
        for (BuildRepository.Build b : known(base)) {
            if (b.steps.stream().anyMatch(st -> st.itemId != null && support.contains(st.itemId))) {
                g += b.goldPerMin;
                x += b.xpPerMin;
                n++;
            }
        }
        if (n > 0) {
            return new double[] {g / n, x / n};
        }
        Map<String, Object> cfg = (Map<String, Object>) scorer.rules().getOrDefault("suporte", Map.of());
        double fg = cfg.get("ouro") instanceof Number ? ((Number) cfg.get("ouro")).doubleValue() : 0.86;
        double fx = cfg.get("xp") instanceof Number ? ((Number) cfg.get("xp")).doubleValue() : 0.9;
        return new double[] {base.goldPerMin * fg, base.xpPerMin * fx};
    }

    /**
     * The best support build (Rodada 13): ranked apart, since it plays with less gold. Among every kit the full battery
     * measured with a support item, the best score; null when the champion does not play support or none was measured.
     */
    DiscoveryModel.KitView bestSupport(Evaluator ev) {
        if (!ev.supportOk) {
            return null;
        }
        Funnel.Kit best = null;
        double score = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, Funnel.Kit> e : new java.util.TreeMap<>(ev.kits).entrySet()) {
            Funnel.Eval f = ev.fulls.get(e.getKey());
            if (f != null && ev.supports(e.getValue()) == 1 && f.score > score) {
                score = f.score;
                best = e.getValue();
            }
        }
        if (best == null) {
            return null;
        }
        Funnel.Entry entry = new Funnel.Entry();
        entry.kit = best;
        entry.full = ev.fulls.get(best.key());
        DiscoveryModel.KitView v = ev.view(best, entry, "suporte");
        v.goldPerMin = ev.supportPace[0];
        v.xpPerMin = ev.supportPace[1];
        return v;
    }

    /** The champion's other saved builds (known builds: seeds and the novelty's references). */
    List<BuildRepository.Build> known(BuildRepository.Build base) {
        List<BuildRepository.Build> out = new ArrayList<>();
        for (BuildRepository.Build s : builds.findBuildSummaries()) {
            if (base.unitCode != null && base.unitCode.equals(s.unitCode)) {
                builds.findBuild(s.id).ifPresent(out::add);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    String quality(String champion) {
        Object kit = ((Map<String, Object>) arena.kits().getOrDefault("campeoes", Map.of())).get(champion);
        Object q = kit instanceof Map ? ((Map<String, Object>) kit).get("qualidade") : null;
        return q == null ? null : String.valueOf(q);
    }

    List<String> family(String key) {
        return families.getOrDefault(key, List.of());
    }

    static <T> T copy(Object o, Class<T> type) {
        return JSON.convertValue(o, type);
    }

    // ------------------------------------------------------------------ the evaluator on the game

    /** The funnel's evaluator on the real game: kits become builds by the planner, scores come from the class score. */
    static final class Evaluator implements Funnel.Evaluator {
        final DiscoveryService s;
        final BuildRepository.Build base;
        final DiscoveryModel.Request r;
        final DiscoveryModel.Job job;
        final Map<Long, Model.ItemDef> items;
        final List<Long> boots = new ArrayList<>();
        final Map<Long, Double> screen = new LinkedHashMap<>();
        final Map<String, Boolean> allowed = new ConcurrentHashMap<>();
        final Map<String, Funnel.Eval> fulls = new ConcurrentHashMap<>();
        /** The kits measured by the full battery, by key (the best support build is picked among them). */
        final Map<String, Funnel.Kit> kits = new ConcurrentHashMap<>();
        /** The support items (Rodada 13), whether this champion plays support, and the support build's pace. */
        final Set<Long> support;
        final boolean supportOk;
        final double[] supportPace;
        final Map<String, Double> quicks = new ConcurrentHashMap<>();
        /** "part|key" -> the scenario's name, as the score's readings gave them. */
        final Map<String, String> names = new ConcurrentHashMap<>();
        double fullSeconds;
        double quickSeconds;
        int fullCount;
        int quickCount;

        Evaluator(DiscoveryService s, BuildRepository.Build base, DiscoveryModel.Request r, DiscoveryModel.Job job) {
            this.s = s;
            this.base = base;
            this.r = r;
            this.job = job;
            this.items = s.planner.referenceData().items;
            this.support = s.planner.supportItems();
            this.supportOk = s.supportCapable(base, support);
            this.supportPace = s.supportPace(base, support);
            for (PlannerService.ItemView i : s.planner.itemViews()) {
                if (i.boots() && ("tier_medio".equals(i.section) || "aprimorado".equals(i.section)) && !r.forbidden.contains(i.id)) {
                    boots.add(i.id);
                }
            }
        }

        String name(long id) {
            Model.ItemDef d = items.get(id);
            return d == null ? String.valueOf(id) : d.name;
        }

        /**
         * Stage 1: each upgraded item's worth for the class, with no fight: the whole build's delta of buying it alone at
         * the quick minute (BuildDeltaService), its stats' gold split into the offensive families (weighed by the
         * champion's physical and magic shares) and the defensive one, plus the modeled passives, over its cost.
         */
        Map<Long, Double> screen() {
            String cls = s.scorer.classOf(base);
            Map<String, Double> w = s.scorer.weights(cls, null);
            ChampionProfiles.Profile p = s.profiles.of(s.scorer.championOf(base));
            double phys = p == null || p.physical + p.magic <= 0 ? 0.5 : p.physical / (p.physical + p.magic);
            Map<String, Double> prices = s.planner.referenceData().statPrices;
            BuildRepository.Build empty = copy(base, BuildRepository.Build.class);
            empty.steps = new ArrayList<>();
            empty.id = null;
            List<PlannerService.ItemView> views = s.planner.itemViews();
            int n = 0;
            for (PlannerService.ItemView i : views) {
                // finished items: the upgraded ones and the evolved forms (Bastião da Montanha, Fimbulwinter: Rodada 13)
                if (!finished(i.section) || i.boots() || r.forbidden.contains(i.id)) {
                    continue;
                }
                BuildDeltaService.Delta d = s.deltas.delta(empty, BuildDeltaService.Change.item(null, i.id, null), QUICK_MINUTE);
                double off = 0;
                double def = 0;
                double util = 0;
                for (Map.Entry<String, Double> e : d.statsDelta.entrySet()) {
                    double g = e.getValue() * prices.getOrDefault(e.getKey(), 0.0);
                    if (s.family("defesa").contains(e.getKey())) {
                        def += g;
                    } else if (s.family("magia").contains(e.getKey())) {
                        off += g * (1 - phys);
                    } else if (s.family("suporte").contains(e.getKey())) {
                        util += g;
                    } else if (s.family("ataque").contains(e.getKey()) || s.family("critico").contains(e.getKey())
                            || s.family("velocidade").contains(e.getKey()) || s.family("letalidade").contains(e.getKey())) {
                        off += g * phys;
                    }
                }
                double passive = d.passiveGoldAfter - d.passiveGoldBefore;
                double value = w.get(BuildScore.OFFENSIVE) * off + (w.get(BuildScore.DEFENSIVE) + 0.15) * def
                        + w.get(BuildScore.UTILITY) * util + 0.5 * passive;
                screen.put(i.id, value / Math.max(1, i.cost));
                stage(job, "triagem", ++n, views.size());
            }
            return screen;
        }

        /** A kit as a build: the base build (champion, runes, spells, gold) with the kit's items in a sensible order. */
        BuildRepository.Build build(Funnel.Kit k) {
            BuildRepository.Build b = copy(base, BuildRepository.Build.class);
            b.id = null;
            b.name = base.name + " · descoberta";
            b.matchEnd = null;
            if (supports(k) > 0) {
                // a support build earns less (the support item gives the minions' gold away): its own pace
                b.goldPerMin = supportPace[0];
                b.xpPerMin = supportPace[1];
            }
            b.steps = new ArrayList<>();
            for (long id : order(k)) {
                b.steps.add(new BuildRepository.Step(id, true));
            }
            return b;
        }

        /**
         * The order a kit is bought in before the order search (part 4): the item worth most by the screening first,
         * then the tier 2 boots, the other items by worth, and the tier 3 boots after the third item.
         */
        List<Long> order(Funnel.Kit k) {
            List<Long> its = new ArrayList<>(k.items);
            // a starter's evolved form (the support item) is bought first, then by worth
            its.sort(Comparator.comparing((Long i) -> !starter(i)).thenComparingDouble((Long i) -> -screen.getOrDefault(i, 0.0))
                    .thenComparing(i -> i));
            List<Long> out = new ArrayList<>();
            Long tier2 = null;
            Long tier3 = null;
            if (k.boots != null) {
                Model.ItemDef bd = items.get(k.boots);
                if (bd != null && "aprimorado".equals(bd.section)) {
                    tier3 = k.boots;
                    for (Model.ComponentRef c : bd.components) {
                        Model.ItemDef cd = items.get(c.itemId);
                        if (cd != null && cd.boots()) {
                            tier2 = cd.id;
                        }
                    }
                } else {
                    tier2 = k.boots;
                }
            }
            for (int i = 0; i < its.size(); i++) {
                out.add(its.get(i));
                if (i == 0 && tier2 != null) {
                    out.add(tier2);
                }
                if (i == 2 && tier3 != null) {
                    out.add(tier3);
                }
            }
            if (its.isEmpty() && tier2 != null) {
                out.add(tier2);
            }
            if (tier3 != null && !out.contains(tier3)) {
                out.add(tier3);
            }
            return out;
        }

        /** An evolved form of a starting item (preparacao: the support item's Bastião da Montanha...): bought first. */
        boolean starter(long id) {
            return support.contains(id);
        }

        /** How many support items a kit has. */
        int supports(Funnel.Kit k) {
            return (int) k.items.stream().filter(support::contains).count();
        }

        /** A saved build's kit: its finished items (at most five) and its final boots. */
        Funnel.Kit kitOf(BuildRepository.Build b) {
            List<Long> its = new ArrayList<>();
            Long bt = null;
            for (BuildRepository.Step st : b.steps) {
                if (st.itemId == null || st.moment() || st.sell()) {
                    continue;
                }
                Model.ItemDef d = items.get(st.itemId);
                if (d == null) {
                    continue;
                }
                if (d.boots()) {
                    bt = d.id;
                } else if (d.finished() && !its.contains(d.id) && its.size() < 5) {
                    its.add(d.id);
                }
            }
            return its.isEmpty() ? null : new Funnel.Kit(its, bt);
        }

        @Override
        public boolean allowed(Funnel.Kit k) {
            // one support item at most, and only for a champion that plays support (Rodada 13)
            int sup = supports(k);
            if (sup > 1 || sup == 1 && !supportOk) {
                return false;
            }
            if (!k.items.containsAll(r.required.stream().filter(i -> !boots.contains(i)).collect(java.util.stream.Collectors.toList()))) {
                return false;
            }
            return allowed.computeIfAbsent(k.key(), x -> {
                Model.TimelineResult t = s.planner.calculate(build(k));
                int bought = 0;
                for (Model.TimelineStep st : t.steps) {
                    if (!st.violations.isEmpty()) {
                        return false;
                    }
                    bought += st.implied ? 0 : 1;
                }
                return t.ignored.isEmpty() && bought == order(k).size();
            });
        }

        @Override
        public double quick(Funnel.Kit k) {
            return quicks.computeIfAbsent(k.key(), x -> {
                long t0 = System.nanoTime();
                double v = s.scorer.score(build(k), QUICK_MINUTE, BuildScore.Options.quick()).total;
                quickSeconds += (System.nanoTime() - t0) / 1e9;
                quickCount++;
                return v;
            });
        }

        @Override
        public Funnel.Eval full(Funnel.Kit k) {
            kits.putIfAbsent(k.key(), k);
            return fulls.computeIfAbsent(k.key(), x -> {
                long t0 = System.nanoTime();
                Funnel.Eval e = eval(build(k), null);
                fullSeconds += (System.nanoTime() - t0) / 1e9;
                fullCount++;
                return e;
            });
        }

        @Override
        public Funnel.Eval confirm(Funnel.Kit k) {
            return eval(build(k), BuildScore.Options.confirm());
        }

        /** The robust score as the funnel's reading: the total, each scenario-minute's points and the niche. */
        Funnel.Eval eval(BuildRepository.Build b, BuildScore.Options o) {
            BuildScore.Robust rob = s.scorer.robust(b, null, o);
            Funnel.Eval e = new Funnel.Eval();
            e.score = rob.total;
            e.extra.put("mean", rob.mean);
            e.extra.put("p20", rob.p20);
            Map<String, Double> curve = new LinkedHashMap<>();
            String archetype = null;
            Double durability = null;
            for (BuildScore.Score sc : rob.minutes) {
                curve.put(String.valueOf(sc.minute), sc.total);
                for (BuildScore.Reading rd : sc.readings) {
                    e.readings.put(sc.minute + "|" + rd.part + "|" + rd.key, rd.points);
                    if (rd.name != null) {
                        names.put(rd.part + "|" + rd.key, rd.name);
                    }
                }
                archetype = sc.archetype;
                durability = sc.parts.get(BuildScore.DEFENSIVE);
            }
            e.extra.put("curve", curve);
            e.extra.put("archetype", archetype);
            for (String axis : r.axes) {
                e.niche.add(axis(axis, b, archetype, durability));
            }
            return e;
        }

        /** One niche coordinate, binned. */
        String axis(String axis, BuildRepository.Build b, String archetype, Double durability) {
            switch (axis) {
                case "arquetipo":
                    return archetype == null ? "?" : archetype;
                case "durabilidade":
                    if (durability == null) {
                        return "durab. ?";
                    }
                    return durability < 15 ? "durab. baixa" : durability < 35 ? "durab. média" : "durab. alta";
                case "dano": {
                    double phys = 0;
                    double mag = 0;
                    Map<String, Double> prices = s.planner.referenceData().statPrices;
                    for (BuildRepository.Step st : b.steps) {
                        Model.ItemDef d = items.get(st.itemId);
                        if (d == null) {
                            continue;
                        }
                        for (Model.StatLine l : d.stats) {
                            double g = (l.value == null ? 0 : l.value) * prices.getOrDefault(l.type, 0.0);
                            if (s.family("magia").contains(l.type)) {
                                mag += g;
                            } else if (s.family("ataque").contains(l.type) || s.family("critico").contains(l.type)
                                    || s.family("letalidade").contains(l.type) || s.family("velocidade").contains(l.type)) {
                                phys += g;
                            }
                        }
                    }
                    if (phys + mag < 500) {
                        return "sem dano";
                    }
                    double share = phys / (phys + mag);
                    return share > 0.7 ? "físico" : share < 0.3 ? "mágico" : "misto";
                }
                case "passivas": {
                    int with = 0;
                    int n = 0;
                    for (BuildRepository.Step st : b.steps) {
                        Model.ItemDef d = items.get(st.itemId);
                        if (d != null && !d.boots()) {
                            n++;
                            with += d.passives != null && !d.passives.isEmpty() ? 1 : 0;
                        }
                    }
                    return n == 0 ? "?" : with * 2 > n ? "passivas fortes" : "status puros";
                }
                default:
                    return "?";
            }
        }

        /** The kit as the screen shows it. */
        DiscoveryModel.KitView view(Funnel.Kit k, Funnel.Entry e, String origin) {
            DiscoveryModel.KitView v = new DiscoveryModel.KitView();
            v.items = new ArrayList<>(k.items);
            k.items.forEach(i -> v.names.add(name(i)));
            v.boots = k.boots;
            v.bootsName = k.boots == null ? null : name(k.boots);
            v.order = order(k);
            v.origin = origin;
            if (e != null) {
                v.generation = e.generation;
                v.quick = e.quick;
                if (e.full != null) {
                    v.score = e.full.score;
                    v.mean = (Double) e.full.extra.get("mean");
                    v.p20 = (Double) e.full.extra.get("p20");
                    v.niche = String.join(" · ", e.full.niche);
                    v.archetype = (String) e.full.extra.get("archetype");
                    @SuppressWarnings("unchecked")
                    Map<String, Double> curve = (Map<String, Double>) e.full.extra.get("curve");
                    if (curve != null) {
                        v.curve = curve;
                    }
                }
                if (e.confirmed != null) {
                    v.confirmed = e.confirmed.score;
                }
                if (e.full != null) {
                    v.readings = new LinkedHashMap<>(e.full.readings);
                    s.versatility(v);
                }
            }
            return v;
        }

        @Override
        public boolean stopped() {
            return job != null && job.stop;
        }

        @Override
        public void progress(String stage, int done, int total) {
            stage(job, stage, done, total);
        }

        Set<String> keys() {
            return fulls.keySet();
        }
    }
}
