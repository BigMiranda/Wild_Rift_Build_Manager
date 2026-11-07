package com.ornnplanner.arena.calc;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.ArenaService;
import com.ornnplanner.arena.EffectModel;
import com.ornnplanner.arena.EffectTestService;
import com.ornnplanner.engine.Model;
import com.ornnplanner.engine.ShopRuleCheck;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.service.PlannerService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The build calculator: the purchase order that does best against a target (6.1), and the tree of both sides' choices
 * (6.2). The shop's rules are the planner's: an order is only what its timeline can buy. Runs one job at a time, in the
 * background, with progress and a stop.
 */
@Service
public class CalculatorService {

    /** What is asked. */
    public static class Request {
        /** The build that gives the champion, runes, spells, gold and experience. */
        public long buildId;
        /** basico | completo (a report) | testes (the keys in {@link #tests}) | arena (a saved scenario). */
        public String target = "basico";
        public List<String> tests = new ArrayList<>();
        public Long scenarioId;
        /** The other side's build: the search plays against it, and a tree needs it. */
        public Long opponentBuildId;
        public List<Long> candidates = new ArrayList<>();
        public List<String> categories = new ArrayList<>();
        public boolean tanksOnly;
        public List<Long> forbidden = new ArrayList<>();
        public List<Long> required = new ArrayList<>();
        public Double goldPerMin;
        /** Early minutes weigh in, so what a component or half item does early shows (Rodada 9). */
        public List<Double> minutes = new ArrayList<>(DEFAULT_MINUTES);
        /** Item slots to fill besides the boots (1..5): the 6th slot is always boots, unless {@link #noBoots}. */
        public int length = 5;
        public int beam = 3;
        /** Most measures a run may take. */
        public int budget = 600;
        /** moderado: one isolated component at most, and which component first after the search | agressivo: any. */
        public String mode = MODERATE;
        public boolean noBoots;
        /** classe: the class score (Rodada 9, part 1) | relatorio: the defensive report's index alone. */
        public String measure = "classe";
        /** After the items, search the page of runes too (Rodada 9, part 4). */
        public boolean runes;
        /** The tree: choices of each side at each step, and items of each side. */
        public boolean tree;
        public int k = 6;
        public int depth = 3;
    }

    public static final String MODERATE = "moderado";
    public static final String AGGRESSIVE = "agressivo";
    public static final List<Double> DEFAULT_MINUTES = List.of(1.0, 2.0, 3.0, 4.0, 8.0, 12.0, 16.0, 20.0);

    /** A step of an order: what it buys, its kind (item, boots, boots3, component) and when it is ready. */
    public static class StepView {
        public long id;
        public String name;
        public String kind;
        public Double readyAt;
        /** A component: the item of the order that closes it (null = never closed). */
        public String closedBy;
    }

    /** One order of the answer. */
    public static class Line {
        public List<Long> order;
        public List<String> names = new ArrayList<>();
        public List<StepView> steps = new ArrayList<>();
        public double score;
        public double[] curve;
        /** Alternatives: the whole build against the best order at the last minute ({@link BuildDeltaService}). */
        public com.ornnplanner.arena.BuildDeltaService.Delta delta;
    }

    /** One row of the tree's reading. */
    public static class TreeRow {
        public Line ours;
        public double winRate;
        public double worst;
        public Line worstAnswer;
        public double mix;
        /** Its row in {@link Answer#matrix}. */
        public int matrixRow;
    }

    public static class Answer {
        public Line best;
        /** The search's space: candidates by kind, and the mode. */
        public Map<String, Integer> space = new LinkedHashMap<>();
        public String mode;
        /** Moderate: which component of each item to buy first (kept in {@link #best}). */
        public List<Map<String, Object>> componentFirst = new ArrayList<>();
        public List<Line> alternatives = new ArrayList<>();
        public List<Map<String, Object>> importance = new ArrayList<>();
        public String boots;
        public Double bootsFrom;
        public double[] minutes;
        /** The tree: rows by how often they win, the answers and the matrix (rows of {@link #rows}, columns of {@link #answers}). */
        public List<TreeRow> rows = new ArrayList<>();
        public List<Line> answers = new ArrayList<>();
        public double[][] matrix;
        public Integer maximin;
        public double value;
        public long evaluations;
        public double seconds;
        public boolean truncated;
    }

    /** A running job. */
    public static class Job {
        public long id;
        public String status = "running";
        public long done;
        public long total;
        public String error;
        public Answer answer;
        volatile boolean stop;
    }

    /** What a request will cost, before it runs. */
    public static class Estimate {
        public int candidates;
        public Map<String, Integer> space = new LinkedHashMap<>();
        /** Aggressive mode: the space is much larger (the screen warns). */
        public boolean large;
        public long measures;
        public double seconds;
    }

    /** The share of the budget the search may use: the rest reads the order it found. */
    private static final double SEARCH_SHARE = 0.8;
    private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final PlannerService planner;
    private final BuildRepository builds;
    private final EffectTestService effect;
    private final ArenaService arena;
    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "calculator");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    /** Seconds one measure took lately: what the estimate multiplies. */
    private volatile double secondsPerMeasure = 0.4;

    private final com.ornnplanner.arena.BuildDeltaService deltas;

    public CalculatorService(PlannerService planner, BuildRepository builds, EffectTestService effect, ArenaService arena,
                             com.ornnplanner.arena.BuildDeltaService deltas) {
        this.deltas = deltas;
        this.planner = planner;
        this.builds = builds;
        this.effect = effect;
        this.arena = arena;
    }

    /** The finished items a request may use (no boots): the upgraded ones of the shop, by its filters; the required always. */
    List<Long> candidates(Request r) {
        List<Long> out = new ArrayList<>();
        if (!r.candidates.isEmpty()) {
            out.addAll(r.candidates);
        } else {
            for (PlannerService.ItemView i : planner.itemViews()) {
                boolean upgraded = "aprimorado".equals(i.section) && !i.boots();
                boolean category = r.categories.isEmpty() || i.tabs.stream().anyMatch(r.categories::contains);
                boolean tank = !r.tanksOnly || i.tabs.contains("Defesa");
                if (upgraded && category && tank) {
                    out.add(i.id);
                }
            }
        }
        Map<Long, Model.ItemDef> items = planner.referenceData().items;
        out.removeIf(id -> items.get(id) != null && items.get(id).boots());
        out.removeAll(r.forbidden);
        r.required.stream().filter(i -> !out.contains(i) && !(items.get(i) != null && items.get(i).boots())).forEach(out::add);
        return out;
    }

    /**
     * The step search's space: the items, every boots of tier 2 and 3 (never by the tab filter: one slot is always boots),
     * and the components of the items (with their parents). Forbidden ones stay out.
     */
    public BuildSearch.Space space(Request r) {
        Map<Long, Model.ItemDef> items = planner.referenceData().items;
        BuildSearch.Space sp = new BuildSearch.Space();
        sp.length = Math.max(1, Math.min(com.ornnplanner.engine.TimelineEngine.ITEM_SLOTS, r.length));
        sp.aggressive = AGGRESSIVE.equals(r.mode);
        sp.noBoots = r.noBoots;
        List<Long> finished = candidates(r);
        for (long id : finished) {
            sp.add(id, BuildSearch.Kind.ITEM);
        }
        if (!r.noBoots) {
            for (PlannerService.ItemView i : planner.itemViews()) {
                if (!i.boots() || r.forbidden.contains(i.id)) {
                    continue;
                }
                if ("tier_medio".equals(i.section)) {
                    sp.add(i.id, BuildSearch.Kind.BOOTS);
                } else if ("aprimorado".equals(i.section)) {
                    sp.add(i.id, BuildSearch.Kind.BOOTS3);
                }
            }
            for (PlannerService.ItemView i : planner.itemViews()) {
                if (sp.kind(i.id) == BuildSearch.Kind.BOOTS3 && sp.kinds.containsKey(i.id)) {
                    for (Model.ComponentRef c : i.components) {
                        sp.parent(c.itemId, i.id);
                    }
                }
            }
        }
        for (long id : finished) {
            Model.ItemDef it = items.get(id);
            if (it != null) {
                components(it, id, items, sp, r);
            }
        }
        sp.required = new ArrayList<>(r.required);
        sp.required.removeIf(i -> !sp.kinds.containsKey(i));
        return sp;
    }

    /** Every component in the recipe tree of {@code it}, as a candidate step of {@code parent} (not boots, not forbidden). */
    private static void components(Model.ItemDef it, long parent, Map<Long, Model.ItemDef> items, BuildSearch.Space sp, Request r) {
        for (Model.ComponentRef c : it.components) {
            Model.ItemDef comp = items.get(c.itemId);
            if (comp == null || comp.boots() || r.forbidden.contains(comp.id)) {
                continue;
            }
            if (!sp.kinds.containsKey(comp.id)) {
                sp.add(comp.id, BuildSearch.Kind.COMPONENT);
            }
            if (sp.kind(comp.id) == BuildSearch.Kind.COMPONENT) {
                sp.parent(comp.id, parent);
                components(comp, parent, items, sp, r);
            }
        }
    }

    /** How many steps of each kind a space has. */
    static Map<String, Integer> census(BuildSearch.Space sp) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (BuildSearch.Kind k : BuildSearch.Kind.values()) {
            out.put(k.name().toLowerCase(java.util.Locale.ROOT), 0);
        }
        sp.kinds.values().forEach(k -> out.merge(k.name().toLowerCase(java.util.Locale.ROOT), 1, Integer::sum));
        return out;
    }

    public Estimate estimate(Request r) {
        Estimate e = new Estimate();
        BuildSearch.Space sp = space(r);
        e.candidates = sp.kinds.size();
        e.space = census(sp);
        e.large = sp.aggressive;
        long measures = (long) Math.max(r.beam, 4) * e.candidates * sp.maxDepth() * r.minutes.size();
        if (r.tree) {
            long lines = (long) Math.pow(r.k, r.depth);
            measures += lines * lines * Math.min(3, r.minutes.size());
        }
        e.measures = Math.min(measures, r.budget);
        e.seconds = e.measures * secondsPerMeasure;
        return e;
    }

    public Job start(Request r) {
        Job j = new Job();
        j.id = ids.incrementAndGet();
        j.total = estimate(r).measures;
        jobs.put(j.id, j);
        runner.submit(() -> {
            try {
                j.answer = compute(r, j);
                j.status = j.stop ? "stopped" : "done";
            } catch (RuntimeException e) {
                j.status = "error";
                j.error = e.getMessage();
            }
        });
        return j;
    }

    public Job job(long id) {
        Job j = jobs.get(id);
        if (j == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return j;
    }

    public void stop(long id) {
        job(id).stop = true;
    }

    /** The search, on the calling thread (the tests and the job). */
    public Answer compute(Request r, Job j) {
        long t0 = System.nanoTime();
        BuildRepository.Build base = builds.findBuild(r.buildId).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Build não existe."));
        BuildRepository.Build rival = r.opponentBuildId == null ? null : builds.findBuild(r.opponentBuildId).orElse(null);
        double[] minutes = r.minutes.stream().mapToDouble(Double::doubleValue).toArray();
        Side ours = new Side(base, r.goldPerMin, 0);
        Oracle oracle = rival != null ? new DuelOracle(r, j, ours, new Side(rival, null, 1), rival) : new TargetOracle(r, j, ours);
        BuildSearch.Space sp = space(r);
        Answer a = new Answer();
        a.minutes = minutes;
        a.mode = sp.aggressive ? AGGRESSIVE : MODERATE;
        a.space = census(sp);
        oracle.limit = (long) (r.budget * SEARCH_SHARE);
        List<BuildSearch.Ranked> found = new ArrayList<>(BuildSearch.steps(oracle, sp, Math.max(r.beam, 4), minutes));
        a.truncated = oracle.stopped();
        oracle.limit = r.budget;
        if (!found.isEmpty() && !sp.aggressive) {
            List<Long> improved = new ArrayList<>();
            for (BuildSearch.ComponentFirst cf : BuildSearch.componentFirst(oracle, sp, found.get(0), minutes, improved)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("item", name(cf.item));
                row.put("itemId", cf.item);
                row.put("component", name(cf.component));
                row.put("componentId", cf.component);
                row.put("gain", cf.gain);
                a.componentFirst.add(row);
            }
            if (!a.componentFirst.isEmpty()) {
                found.add(0, BuildSearch.measure(oracle, improved, minutes));
            }
        }
        if (!found.isEmpty()) {
            a.best = line(found.get(0), ours, sp);
            found.stream().skip(1).limit(3).forEach(x -> a.alternatives.add(line(x, ours, sp)));
            double last = minutes.length == 0 ? 20 : minutes[minutes.length - 1];
            for (Line alt : a.alternatives) {
                alt.delta = deltas.compare(ours.timeline(a.best.order), ours.timeline(alt.order), last,
                        com.ornnplanner.arena.BuildDeltaService.Change.order(alt.order), ours.order(a.best.order));
            }
            for (BuildSearch.Importance m : BuildSearch.importance(oracle, found.get(0), minutes)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("item", name(m.item));
                row.put("itemId", m.item);
                row.put("readyAt", m.readyAt);
                row.put("delayed", m.delayed);
                row.put("advanced", m.advanced);
                a.importance.add(row);
            }
            bootsMinute(oracle, found.get(0), minutes, a);
        }
        if (r.tree && rival != null) {
            tree(r, j, ours, new Side(rival, null, 1), base, rival, a);
        }
        a.evaluations = oracle.count();
        a.seconds = (System.nanoTime() - t0) / 1e9;
        if (a.evaluations > 0) {
            secondsPerMeasure = a.seconds / a.evaluations;
        }
        return a;
    }

    private void bootsMinute(BuildSearch.Oracle o, BuildSearch.Ranked best, double[] minutes, Answer a) {
        ShopRuleCheck shop = planner.shopRuleCheck();
        Map<Long, Model.ItemDef> items = planner.referenceData().items;
        for (long id : best.order) {
            Model.ItemDef item = items.get(id);
            if (item != null && ShopRuleCheck.upgradedBoots(item)) {
                a.boots = item.name;
                a.bootsFrom = BuildSearch.worthFrom(o, best.order, id, minutes, 0.01);
                if (a.bootsFrom != null) {
                    a.bootsFrom = Math.max(a.bootsFrom, shop.earliestMinute(item));
                }
                return;
            }
        }
    }

    private void tree(Request r, Job j, Side ours, Side theirs, BuildRepository.Build base, BuildRepository.Build rival, Answer a) {
        double[] minutes = Arrays.copyOf(r.minutes.stream().mapToDouble(Double::doubleValue).toArray(), Math.min(3, r.minutes.size()));
        DuelOracle mine = new DuelOracle(r, j, ours, theirs, rival);
        DuelOracle other = new DuelOracle(r, j, theirs, ours, base);
        Request forThem = JSON.convertValue(r, Request.class);
        forThem.required = new ArrayList<>();
        forThem.candidates = new ArrayList<>();
        List<BuildSearch.Ranked> rows = BuildSearch.beam(mine, candidates(r), List.of(), r.depth, r.k, minutes);
        List<BuildSearch.Ranked> cols = BuildSearch.beam(other, candidates(forThem), List.of(), r.depth, r.k, minutes);
        double[][] m = new double[rows.size()][cols.size()];
        for (int i = 0; i < rows.size(); i++) {
            for (int c = 0; c < cols.size(); c++) {
                double sum = 0;
                for (double minute : minutes) {
                    sum += mine.duel(rows.get(i).order, cols.get(c).order, minute);
                }
                m[i][c] = sum / Math.max(1, minutes.length);
            }
        }
        GameTree.Solution s = GameTree.solve(m, 3000);
        a.matrix = m;
        a.maximin = s.maximin;
        a.value = s.value;
        cols.forEach(c -> a.answers.add(line(c)));
        for (int i = 0; i < rows.size(); i++) {
            TreeRow row = new TreeRow();
            row.ours = line(rows.get(i));
            row.winRate = s.winRate[i];
            row.worst = s.worst[i];
            row.worstAnswer = cols.isEmpty() ? null : line(cols.get(s.worstAnswer[i]));
            row.mix = s.mixOurs[i];
            row.matrixRow = i;
            a.rows.add(row);
        }
        a.rows.sort((x, y) -> Double.compare(y.winRate, x.winRate));
    }

    private String name(long id) {
        Model.ItemDef i = planner.referenceData().items.get(id);
        return i == null ? String.valueOf(id) : i.name;
    }

    private Line line(BuildSearch.Ranked r) {
        Line l = new Line();
        l.order = r.order;
        l.score = r.score;
        l.curve = r.curve;
        r.order.forEach(i -> l.names.add(name(i)));
        return l;
    }

    /** A line with its steps: kind and the minute each one is ready, by the side's timeline. */
    private Line line(BuildSearch.Ranked r, Side side, BuildSearch.Space sp) {
        Line l = line(r);
        Model.TimelineResult t = side.timeline(r.order);
        for (int i = 0; i < r.order.size(); i++) {
            long id = r.order.get(i);
            StepView v = new StepView();
            v.id = id;
            v.name = name(id);
            v.kind = sp.kind(id).name().toLowerCase(java.util.Locale.ROOT);
            int bi = i;
            t.steps.stream().filter(s -> !s.implied && s.buildIndex == bi && !s.ignored).findFirst().ifPresent(s -> v.readyAt = s.minute);
            if (sp.kind(id) == BuildSearch.Kind.COMPONENT) {
                Set<Long> ps = sp.parents.getOrDefault(id, Set.of());
                for (int k = i + 1; k < r.order.size(); k++) {
                    if (ps.contains(r.order.get(k))) {
                        v.closedBy = name(r.order.get(k));
                        break;
                    }
                }
            }
            l.steps.add(v);
        }
        return l;
    }

    /** A side: its build, and what its timelines say. */
    private final class Side {
        final BuildRepository.Build base;
        final Double gold;
        final int team;
        final Map<String, Model.TimelineResult> timelines = new HashMap<>();
        final Map<String, Arena.Input> inputs = new HashMap<>();

        Side(BuildRepository.Build base, Double gold, int team) {
            this.base = base;
            this.gold = gold;
            this.team = team;
        }

        BuildRepository.Build order(List<Long> order) {
            BuildRepository.Build b = JSON.convertValue(base, BuildRepository.Build.class);
            b.id = null;
            b.name = base.name;
            b.steps = new ArrayList<>();
            for (long id : order) {
                b.steps.add(new BuildRepository.Step(id, true));
            }
            if (gold != null) {
                b.goldPerMin = gold;
            }
            b.matchEnd = null;
            return b;
        }

        Model.TimelineResult timeline(List<Long> order) {
            return timelines.computeIfAbsent(order.toString(), k -> planner.calculate(order(order)));
        }

        /**
         * What the order holds at a minute: the inventory after the last purchase by then (components held count, the
         * ones an item consumed do not), in a key that ignores the order.
         */
        Set<Long> held(List<Long> order, double minute) {
            List<Long> inv = List.of();
            for (Model.TimelineStep s : timeline(order).steps) {
                if (s.minute > minute + 1e-9) {
                    break;
                }
                if (!s.ignored) {
                    inv = s.inventoryIds;
                }
            }
            return new TreeSet<>(inv);
        }

        Arena.Input input(List<Long> order, double minute) {
            Set<Long> held = held(order, minute);
            return inputs.computeIfAbsent(held + "@" + minute, k -> {
                ArenaModel.Slot me = new ArenaModel.Slot();
                me.team = team;
                me.build = order(new ArrayList<>(held));
                me.minute = minute;
                me.brain = "tactical";
                ArenaModel.Slot other = new ArenaModel.Slot();
                other.team = 1 - team;
                other.dummy = new ArenaModel.Dummy();
                ArenaModel.Scenario sc = new ArenaModel.Scenario();
                sc.fighters = List.of(me, other);
                return arena.prepareSlots(sc).get(0);
            });
        }
    }

    /** The checks every oracle shares: the shop's rules by the timeline, the budget, the count. */
    private abstract class Oracle implements BuildSearch.Oracle {
        final Request r;
        final Job job;
        final Side side;
        final Map<String, Double> scores = new HashMap<>();
        long measures;
        long limit;

        Oracle(Request r, Job job, Side side) {
            this.r = r;
            this.job = job;
            this.side = side;
            this.limit = r.budget;
        }

        long count() {
            return measures;
        }

        @Override
        public boolean allowed(List<Long> order) {
            Model.TimelineResult t = side.timeline(order);
            int bought = 0;
            for (Model.TimelineStep s : t.steps) {
                if (!s.violations.isEmpty()) {
                    return false;
                }
                bought += s.implied ? 0 : 1;
            }
            return bought == order.size();
        }

        @Override
        public double readyAt(List<Long> order, int index) {
            long item = order.get(index);
            for (Model.TimelineStep s : side.timeline(order).steps) {
                if (!s.implied && s.itemId == item) {
                    return s.minute;
                }
            }
            return Double.NaN;
        }

        @Override
        public boolean stopped() {
            return job != null && job.stop || measures >= limit;
        }

        void counted() {
            measures++;
            if (job != null) {
                job.done = measures;
            }
        }
    }

    /** Against a report of the effectiveness, or a scenario of the Arena. */
    private final class TargetOracle extends Oracle {
        final List<EffectModel.Entry> entries = new ArrayList<>();

        TargetOracle(Request r, Job job, Side side) {
            super(r, job, side);
            if ("testes".equals(r.target)) {
                for (String key : r.tests) {
                    EffectModel.Entry e = new EffectModel.Entry();
                    e.key = key;
                    entries.add(e);
                }
            } else if (!"arena".equals(r.target)) {
                entries.addAll(effect.report(r.target).tests);
            }
        }

        @Override
        public double score(List<Long> order, double minute) {
            Set<Long> held = side.held(order, minute);
            return scores.computeIfAbsent(held + "@" + minute, k -> {
                counted();
                return "arena".equals(r.target) ? arenaScore(held, minute) : reportScore(held, minute);
            });
        }

        private double arenaScore(Set<Long> held, double minute) {
            return effect.savedScenario(r.scenarioId, side.order(new ArrayList<>(held)), minute, null).durability;
        }

        private double reportScore(Set<Long> held, double minute) {
            EffectModel.Request req = new EffectModel.Request();
            req.build = side.order(new ArrayList<>(held));
            req.minute = minute;
            for (EffectModel.Entry e : entries) {
                EffectModel.Job job = new EffectModel.Job();
                job.testKey = e.key;
                req.jobs.add(job);
            }
            List<EffectModel.Outcome> out = effect.evaluate(req);
            double sum = 0;
            double weight = 0;
            for (int i = 0; i < out.size(); i++) {
                Double index = out.get(i).index;
                if (index != null) {
                    sum += entries.get(i).weight * index;
                    weight += entries.get(i).weight;
                }
            }
            return weight > 0 ? sum / weight : 0;
        }
    }

    /** Against the other side's build: a fight at the minute, with what each holds by then. */
    private final class DuelOracle extends Oracle {
        final Side theirs;
        final List<Long> theirOrder = new ArrayList<>();

        DuelOracle(Request r, Job job, Side ours, Side theirs, BuildRepository.Build rival) {
            super(r, job, ours);
            this.theirs = theirs;
            rival.steps.forEach(s -> {
                if (s.itemId != null && !"moment".equals(s.kind) && !"sell".equals(s.kind)) {
                    theirOrder.add(s.itemId);
                }
            });
        }

        @Override
        public double score(List<Long> order, double minute) {
            return duel(order, theirOrder, minute);
        }

        /** Above 0 our side won: the difference in the share of health left. */
        double duel(List<Long> ours, List<Long> others, double minute) {
            String key = side.held(ours, minute) + "x" + theirs.held(others, minute) + "@" + minute;
            return scores.computeIfAbsent(key, k -> {
                counted();
                Arena.Input a = side.input(ours, minute).copy();
                Arena.Input b = theirs.input(others, minute).copy();
                boolean oursFirst = side.team == 0;
                Arena.Input blue = oursFirst ? a : b;
                Arena.Input red = oursFirst ? b : a;
                blue.x = 4600;
                blue.y = 0;
                red.x = 5400;
                red.y = 0;
                ArenaModel.Result res = arena.runPlain(List.of(blue, red), 60);
                ArenaModel.FighterStats us = res.fighters.get(oursFirst ? 0 : 1);
                ArenaModel.FighterStats them = res.fighters.get(oursFirst ? 1 : 0);
                return Math.max(0, us.health) / Math.max(1, us.maxHealth) - Math.max(0, them.health) / Math.max(1, them.maxHealth);
            });
        }
    }
}
