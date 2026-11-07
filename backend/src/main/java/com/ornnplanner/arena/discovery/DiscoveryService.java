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
                            @Value("${planner.seed.classification:classpath:seed/classificacao_7_3d.yml}") Resource file) {
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
            } catch (RuntimeException e) {
                j.status = "error";
                j.error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
        });
        return j;
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
        if (r.seedsFromSaved) {
            for (BuildRepository.Build b : known(base)) {
                Funnel.Kit k = ev.kitOf(b);
                if (k != null) {
                    seeds.add(k);
                    res.known.add(ev.view(k, null, "conhecida"));
                }
            }
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
        afterSearch(r, ev, fr, res, budget);
        res.seconds = (System.nanoTime() - t0) / 1e9;
        if (ev.fullCount > 0) {
            secondsPerFull = ev.fullSeconds / ev.fullCount;
        }
        if (ev.quickCount > 0) {
            secondsPerQuick = ev.quickSeconds / ev.quickCount;
        }
        return res;
    }

    /** What runs after the item search (runes, order, discoveries: parts 4, 4b and 5). */
    void afterSearch(DiscoveryModel.Request r, Evaluator ev, Funnel.Result fr, DiscoveryModel.Result res, DiscoveryModel.Budget b) {
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
        final Map<String, Double> quicks = new ConcurrentHashMap<>();
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
                if (!"aprimorado".equals(i.section) || i.boots() || r.forbidden.contains(i.id)) {
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
            its.sort(Comparator.comparingDouble((Long i) -> -screen.getOrDefault(i, 0.0)).thenComparing(i -> i));
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
                } else if ("aprimorado".equals(d.section) && !its.contains(d.id) && its.size() < 5) {
                    its.add(d.id);
                }
            }
            return its.isEmpty() ? null : new Funnel.Kit(its, bt);
        }

        @Override
        public boolean allowed(Funnel.Kit k) {
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
