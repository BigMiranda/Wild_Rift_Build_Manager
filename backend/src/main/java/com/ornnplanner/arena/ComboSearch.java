package com.ornnplanner.arena;

import com.ornnplanner.arena.ArenaModel.Combo;
import com.ornnplanner.arena.ArenaModel.ComboStep;
import com.ornnplanner.arena.ArenaModel.Condition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

/**
 * Finds combos of a champion against the ragdoll ({@link ComboBench}), for any champion with a sheet, by a beam search
 * over sequences of steps:
 * <ul>
 *   <li>only steps valid in the simulated state where the prefix ended (its snapshot): abilities up (or about to be),
 *       recasts open or coming, the attack, actives up;</li>
 *   <li>a step may first wait for an event of the fight (never for a clock time): the target about to land or on the
 *       ground, the control about to end, a mark applied, the recast would hit; its Δ from a small set;</li>
 *   <li>each prefix is measured by playing it from the start (fights are deterministic and short: no state to clone);
 *       the {@link Config#beam} best prefixes are expanded, and those worse than the best combo so far by
 *       {@link Config#prune} are dropped;</li>
 *   <li>at the end the Δ of the best ones are calibrated (coordinate by coordinate on a finer grid), and the best
 *       {@link Config#keep} distinct sequences are kept, with their numbers and how they differ from the best.</li>
 * </ul>
 * Deterministic: the same inputs and seed give the same combos, whatever the threads. Unlike the timeline search
 * removed in round 2 (random seconds on a clock), it only tries valid actions, waits relative to events, keeps a beam
 * and calibrates.
 */
public final class ComboSearch {

    public static class Config {
        public int beam = 64;
        public int maxSteps = 10;
        /** Most candidate combos measured (each on every behavior of the bench). */
        public int budget = 6000;
        /** The Δ tried for the waits (s). */
        public List<Double> deltas = new ArrayList<>(List.of(0.0, 0.1, 0.2, 0.35));
        /**
         * Drops prefixes scoring below the best combo by this share, from {@link #pruneFrom} steps on (shorter ones
         * are still building up: a pull that sets up the rest scores little alone).
         */
        public double prune = 0.5;
        public int pruneFrom = 3;
        /** How many of the best are calibrated, and how many distinct ones are kept. */
        public int calibrate = 8;
        public int keep = 8;
        /** An ability not up at the end of the prefix is still tried when it is up within this many seconds. */
        public double readyWithin = 0.6;
        public long seed = 1;
        public int threads = Runtime.getRuntime().availableProcessors();
        /** Steps that are never tried (a test's way to keep a known combo out). */
        public Set<String> forbidden = new LinkedHashSet<>();
    }

    /** A combo found, with its numbers. */
    public static class Found {
        public Combo combo;
        public ComboBench.Report report;
        public double score;
        /** Its steps that differ from the best combo's (index -> step), the best has none. */
        public Map<Integer, String> diff = new LinkedHashMap<>();
    }

    public static class Result {
        public List<Found> best = new ArrayList<>();
        public int evaluated;
        public int depth;
        /** Why it stopped: budget | exhausted | cancelled. */
        public String stopped;
        public List<String> log = new ArrayList<>();
    }

    /** Hears the search: how many measured so far, may cancel it. */
    public interface Listener {
        default void progress(int evaluated, int depth, double best) {
        }

        default boolean cancelled() {
            return false;
        }
    }

    private final ComboBench bench;

    public ComboSearch(ComboBench bench) {
        this.bench = bench;
    }

    /** A candidate and what it gave. */
    private static final class Node {
        final Combo combo;
        final String key;
        ComboBench.Report report;
        double score;
        Map<String, Double> snapshot;
        boolean done;

        Node(Combo combo) {
            this.combo = combo;
            this.key = key(combo);
        }
    }

    public Result search(Arena.Input champion, Arena.Input dummy, ComboBench.Config bc, Config cfg, Listener listener) {
        Result out = new Result();
        Map<String, Node> seen = new LinkedHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, cfg.threads));
        try {
            List<Node> beam = new ArrayList<>();
            beam.add(new Node(newCombo(champion)));   // the empty prefix: everything is up
            double best = Double.NEGATIVE_INFINITY;
            out.stopped = "exhausted";
            for (int depth = 1; depth <= cfg.maxSteps; depth++) {
                List<Node> next = new ArrayList<>();
                for (Node n : beam) {
                    if (n.combo.steps.isEmpty() || n.done) {
                        for (Combo c : expand(champion, n, cfg, bc)) {
                            Node x = new Node(c);
                            if (!seen.containsKey(x.key)) {
                                seen.put(x.key, x);
                                next.add(x);
                            }
                        }
                    }
                }
                if (next.isEmpty()) {
                    break;
                }
                int room = cfg.budget - out.evaluated;
                if (room <= 0) {
                    out.stopped = "budget";
                    break;
                }
                if (next.size() > room) {
                    next.sort(Comparator.comparing(x -> x.key));
                    next = new ArrayList<>(next.subList(0, room));
                    out.stopped = "budget";
                }
                evaluate(pool, champion, dummy, bc, next, cancel(listener));
                out.evaluated += next.size();
                out.depth = depth;
                for (Node x : next) {
                    best = Math.max(best, x.score);
                }
                double floor = best > 0 && depth >= cfg.pruneFrom ? best * (1 - cfg.prune) : Double.NEGATIVE_INFINITY;
                List<Node> kept = new ArrayList<>();
                for (Node x : next) {
                    if (x.done && x.score >= floor) {
                        kept.add(x);
                    }
                }
                kept.sort(ORDER);
                beam = kept.size() > cfg.beam ? new ArrayList<>(kept.subList(0, cfg.beam)) : kept;
                out.log.add("profundidade " + depth + ": " + next.size() + " combos medidos, melhor nota " + Math.round(best));
                listener.progress(out.evaluated, depth, best);
                if (listener.cancelled()) {
                    out.stopped = "cancelled";
                    break;
                }
                if ("budget".equals(out.stopped) || beam.isEmpty()) {
                    break;
                }
            }
            // the best distinct sequences, calibrated
            List<Node> all = new ArrayList<>(seen.values());
            all.removeIf(x -> x.report == null);
            all.sort(ORDER);
            List<Node> top = distinct(all, cfg.calibrate);
            List<Node> calibrated = new ArrayList<>();
            for (Node n : top) {
                if (listener.cancelled()) {
                    break;
                }
                calibrated.add(calibrate(pool, champion, dummy, bc, n, out, cancel(listener)));
            }
            calibrated.sort(ORDER);
            List<Node> keep = distinct(calibrated, cfg.keep);
            Combo first = keep.isEmpty() ? null : keep.get(0).combo;
            for (Node n : keep) {
                Found f = new Found();
                f.combo = n.combo;
                f.report = n.report;
                f.score = n.score;
                if (first != null && n.combo != first) {
                    for (int i = 0; i < Math.max(first.steps.size(), n.combo.steps.size()); i++) {
                        String a = i < first.steps.size() ? first.steps.get(i).toString() : "—";
                        String b = i < n.combo.steps.size() ? n.combo.steps.get(i).toString() : "—";
                        if (!a.equals(b)) {
                            f.diff.put(i, b);
                        }
                    }
                }
                finish(f, champion, bc);
                out.best.add(f);
            }
        } finally {
            pool.shutdownNow();
        }
        return out;
    }

    /** Best score first, ties by the shorter combo, then its key (deterministic). */
    private static final Comparator<Node> ORDER = Comparator.<Node>comparingDouble(x -> -x.score)
            .thenComparingInt(x -> x.combo.steps.size()).thenComparing(x -> x.key);

    private static BooleanSupplier cancel(Listener l) {
        return l::cancelled;
    }

    private void evaluate(ExecutorService pool, Arena.Input champion, Arena.Input dummy, ComboBench.Config bc, List<Node> nodes,
                          BooleanSupplier cancelled) {
        List<Future<?>> futures = new ArrayList<>();
        for (Node n : nodes) {
            futures.add(pool.submit(() -> {
                if (!cancelled.getAsBoolean()) {
                    measure(champion, dummy, bc, n);
                }
            }));
        }
        try {
            for (Future<?> f : futures) {
                f.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("combo search interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("a combo fight failed: " + e.getCause(), e.getCause());
        }
    }

    private void measure(Arena.Input champion, Arena.Input dummy, ComboBench.Config bc, Node n) {
        n.report = bench.evaluate(champion, dummy, n.combo, bc);
        n.score = n.report.score;
        ComboBench.Metrics first = n.report.byBehavior.values().iterator().next();
        n.done = first.done;
        n.snapshot = first.snapshot;   // where the prefix ended, on the first behavior (parado by default)
    }

    /** The prefix with one more step: each action valid where it ended, with no wait or a wait for an event. */
    private List<Combo> expand(Arena.Input champion, Node n, Config cfg, ComboBench.Config bc) {
        Map<String, Double> snap = n.snapshot != null ? n.snapshot : initial(champion);
        List<String> actions = new ArrayList<>();
        double left = n.combo.maxTime;
        for (Map.Entry<String, Double> e : snap.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("pronto:") && e.getValue() <= cfg.readyWithin + 1e-9) {
                actions.add("habilidade:" + k.substring("pronto:".length()));
            } else if (k.startsWith("reconjurar:") && e.getValue() < left) {
                actions.add("reconjurar:" + k.substring("reconjurar:".length()));
            } else if (k.startsWith("ativo:") && e.getValue() <= 1e-9 && "ativo:Flash".equals(k)) {
                actions.add("ativo:Flash");
            }
        }
        actions.add("atacar");
        actions.removeIf(cfg.forbidden::contains);
        List<Combo> out = new ArrayList<>();
        double air = snap.getOrDefault("alvo_no_ar", 0.0);
        double held = snap.getOrDefault("alvo_controle", 0.0);
        double mark = snap.getOrDefault("alvo_marca", 0.0);
        for (String a : actions) {
            List<ComboStep> steps = new ArrayList<>();
            steps.add(new ComboStep(a));
            if (a.startsWith("reconjurar:")) {
                steps.add(waitFor(a, "reconjuracao_acertaria", 0, null, null));
            }
            if (a.startsWith("habilidade:")) {
                steps.add(waitFor(a, "acertaria", 0, null, null));   // e.g. until a pillar stands on the way
            }
            if (air > 0) {
                for (double d : cfg.deltas) {
                    steps.add(waitFor(a, "alvo_pousa", d, 0.0, 0.6));
                }
                for (double d : List.of(0.0, 0.1, 0.2)) {
                    steps.add(waitFor(a, "alvo_no_chao", d, 0.0, 0.5));
                }
            } else if (held > 0) {
                for (double d : cfg.deltas) {
                    steps.add(waitFor(a, "controle_restante", d, 0.0, 0.6));
                }
            }
            if (mark <= 0 && !n.combo.steps.isEmpty()) {
                steps.add(waitFor(a, "marca_aplicada", 0, null, null));
            }
            for (ComboStep st : steps) {
                if (cfg.forbidden.contains(st.toString())) {
                    continue;
                }
                Combo c = copy(n.combo);
                c.steps.add(st);
                c.maxTime = maxTime(c, bc);
                out.add(c);
            }
        }
        return out;
    }

    private static ComboStep waitFor(String action, String event, double delta, Double min, Double max) {
        ComboStep s = new ComboStep(action, event, delta);
        s.deltaMin = min;
        s.deltaMax = max;
        s.max = 3;
        return s;
    }

    /** Before anything: every learned ability up, the attack up, Flash when it has it. */
    private static Map<String, Double> initial(Arena.Input champion) {
        Map<String, Double> m = new LinkedHashMap<>();
        Map<String, Object> abs = Arena.map(champion.kit == null ? null : champion.kit.get("habilidades"));
        int[] ranks = champion.f.ranks;
        String[] slots = {"1", "2", "3", "R"};
        for (int i = 0; i < 4; i++) {
            if (ranks[i] > 0 && (abs == null || abs.get(slots[i]) != null)) {
                m.put("pronto:" + slots[i], 0.0);
            }
        }
        if (champion.f.spells.contains("Flash")) {
            m.put("ativo:Flash", 0.0);
        }
        m.put("ataque", 0.0);
        return m;
    }

    /** Room for the combo: a few seconds per step, at most 10s (the bench runs it until its limit). */
    private static double maxTime(Combo c, ComboBench.Config bc) {
        return Math.min(10, 1.5 + 1.2 * c.steps.size());
    }

    /** One combo with its calibratable waits moved to their best values (the lab's "Calibrar"). */
    public Combo calibrate(Arena.Input champion, Arena.Input dummy, Combo c, ComboBench.Config bc, int threads) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        try {
            Node n = new Node(copy(c));
            evaluate(pool, champion, dummy, bc, List.of(n), () -> false);
            Node best = calibrate(pool, champion, dummy, bc, n, new Result(), () -> false);
            Combo out = best.combo;
            out.id = c.id;
            out.name = c.name;
            out.origin = c.origin;
            out.active = c.active;
            out.start = new ArrayList<>(c.start);
            out.abort = new ArrayList<>(c.abort);
            out.maxTime = c.maxTime;
            out.build = c.build;
            out.buildId = c.buildId;
            out.minute = c.minute;
            out.note = c.note;
            out.score = Math.round(best.score * 10) / 10.0;
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Coordinate by coordinate, each calibratable Δ over a 0.05s grid within its bounds, twice. */
    private Node calibrate(ExecutorService pool, Arena.Input champion, Arena.Input dummy, ComboBench.Config bc, Node n,
                           Result out, BooleanSupplier cancelled) {
        Node best = n;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < best.combo.steps.size(); i++) {
                ComboStep st = best.combo.steps.get(i);
                if (!st.calibratable()) {
                    continue;
                }
                List<Node> tries = new ArrayList<>();
                for (double d = st.deltaMin; d <= st.deltaMax + 1e-9; d += 0.05) {
                    double v = Math.round(d * 100) / 100.0;
                    if (Math.abs(v - st.delta) < 1e-9) {
                        continue;
                    }
                    Combo c = copy(best.combo);
                    c.steps.get(i).delta = v;
                    tries.add(new Node(c));
                }
                evaluate(pool, champion, dummy, bc, tries, cancelled);
                out.evaluated += tries.size();
                for (Node t : tries) {
                    if (t.report != null && t.done && (t.score > best.score + 1e-6
                            || Math.abs(t.score - best.score) <= 1e-6 && t.key.compareTo(best.key) < 0)) {
                        best = t;
                    }
                }
            }
        }
        return best;
    }

    /** The first ones of different action sequences (their waits apart). */
    private static List<Node> distinct(List<Node> sorted, int k) {
        List<Node> out = new ArrayList<>();
        Set<String> seq = new LinkedHashSet<>();
        for (Node n : sorted) {
            if (out.size() >= k) {
                break;
            }
            if (n.done && seq.add(sequence(n.combo))) {
                out.add(n);
            }
        }
        return out;
    }

    /** A found combo's name, start conditions and numbers. */
    private static void finish(Found f, Arena.Input champion, ComboBench.Config bc) {
        Combo c = f.combo;
        c.champion = champion.f.champion;
        c.origin = "descoberto";
        c.score = Math.round(f.score * 10) / 10.0;
        c.name = name(c);
        c.start = new ArrayList<>();
        Set<String> slots = new LinkedHashSet<>();
        for (ComboStep st : c.steps) {
            if (st.action.startsWith("habilidade:")) {
                slots.add(st.action.substring("habilidade:".length()));
            }
        }
        for (String s : slots) {
            c.start.add(new Condition("pronto:" + s, "==", 1));
        }
        c.start.add(new Condition("alvo.distancia", "<=", bc.distance + 200));
        ComboBench.Metrics m = f.report.byBehavior.values().iterator().next();
        c.maxTime = Math.min(c.maxTime, Math.max(1.5, m.duration + 1.0));
        c.metrics = new LinkedHashMap<>();
        c.metrics.put("dano", Math.round(m.damage));
        c.metrics.put("controle", Math.round(m.control * 100) / 100.0);
        c.metrics.put("marcas_consumidas", m.marksConsumed);
        c.metrics.put("duracao", Math.round(m.duration * 100) / 100.0);
        Map<String, Object> per = new LinkedHashMap<>();
        f.report.byBehavior.forEach((b, x) -> per.put(b, Math.round(x.score)));
        c.metrics.put("nota_por_comportamento", per);
    }

    /** "1 → R → 3 → ataque → (pousa −0,1) ataque". */
    public static String name(Combo c) {
        List<String> parts = new ArrayList<>();
        for (ComboStep st : c.steps) {
            String a = st.action.startsWith("habilidade:") ? st.action.substring(11)
                    : st.action.startsWith("reconjurar:") ? st.action.substring(11) + "²"
                    : st.action.startsWith("ativo:") ? st.action.substring(6) : "atq".equals(st.action) ? "atq" : "ataque";
            if (st.until != null) {
                a = "(" + st.until.replace("alvo_", "").replace("_", " ")
                        + (st.delta != 0 ? " " + String.valueOf(st.delta).replace('.', ',') : "") + ") " + a;
            }
            parts.add(a);
        }
        return String.join(" → ", parts);
    }

    /** The actions alone: two combos that differ only in their waits are the same sequence. */
    private static String sequence(Combo c) {
        StringBuilder sb = new StringBuilder();
        for (ComboStep st : c.steps) {
            sb.append(st.action).append(';');
        }
        return sb.toString();
    }

    private static String key(Combo c) {
        StringBuilder sb = new StringBuilder();
        for (ComboStep st : c.steps) {
            sb.append(st).append(';');
        }
        return sb.toString();
    }

    private static Combo newCombo(Arena.Input champion) {
        Combo c = new Combo();
        c.champion = champion.f.champion;
        c.name = "busca";
        c.maxTime = 1.5;
        return c;
    }

    private static Combo copy(Combo c) {
        Combo o = new Combo();
        o.champion = c.champion;
        o.name = c.name;
        o.maxTime = c.maxTime;
        for (ComboStep st : c.steps) {
            ComboStep s = new ComboStep(st.action, st.until, st.delta);
            s.deltaMin = st.deltaMin;
            s.deltaMax = st.deltaMax;
            s.max = st.max;
            s.slot = st.slot;
            o.steps.add(s);
        }
        o.start = new ArrayList<>(c.start);
        o.abort = new ArrayList<>(c.abort);
        return o;
    }
}
