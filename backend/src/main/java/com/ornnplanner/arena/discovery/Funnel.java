package com.ornnplanner.arena.discovery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * The build discovery's funnel (Rodada 9, parts 2 and 3), apart from how a build is measured: a short list by
 * screening with a diversity quota and jokers; a genetic search over the set of items and boots, with the surrogate
 * ranking the children, quick fights cutting the worse half (successive halving) and the full battery on the rest; an
 * archive of the best build of each niche (MAP-Elites); the confirmation of the finalists; and the synergies of their
 * pairs of items. Deterministic by the seed: one {@link Random}, lists in order, ties by key.
 */
public final class Funnel {

    private Funnel() {
    }

    /** A set of items (sorted ids, the order does not matter here) and one pair of boots (null = none). */
    public static final class Kit {
        public final List<Long> items;
        public final Long boots;

        public Kit(Collection<Long> items, Long boots) {
            this.items = new ArrayList<>(new TreeSet<>(items));
            this.boots = boots;
        }

        public String key() {
            return items + "|" + boots;
        }

        public Set<Long> all() {
            Set<Long> s = new TreeSet<>(items);
            if (boots != null) {
                s.add(boots);
            }
            return s;
        }

        public Kit without(long id) {
            List<Long> l = new ArrayList<>(items);
            l.remove(Long.valueOf(id));
            return new Kit(l, boots != null && boots == id ? null : boots);
        }

        @Override
        public String toString() {
            return key();
        }
    }

    /** What the full battery says about a kit: its score, each scenario's points and its niche. */
    public static class Eval {
        public double score;
        public Map<String, Double> readings = new LinkedHashMap<>();
        /** The niche's coordinates (already binned), in the order of the axes. */
        public List<String> niche = new ArrayList<>();
        /** Anything the service wants to keep (the minutes' scores, the archetype...). */
        public Map<String, Object> extra = new LinkedHashMap<>();
    }

    /** How a kit is measured, from the cheap to the dear. */
    public interface Evaluator {
        /** The shop's rules on the set (exclusive groups, duplicates). */
        boolean allowed(Kit k);

        /** Quick fights: a couple of short scenarios at one minute. */
        double quick(Kit k);

        /** The full battery: every scenario, at the minutes. */
        Eval full(Kit k);

        /** Confirmation: more starts the search never saw. */
        Eval confirm(Kit k);

        boolean stopped();

        /** Progress of a stage (for the bar); may ignore. */
        default void progress(String stage, int done, int total) {
        }
    }

    /**
     * Kits measured at the same time (each kit's fights already run on all cores, but a kit's batch is small, so one kit
     * at a time leaves most cores idle). Every kit's reading is deterministic on its own and written to its own entry, so
     * the result does not depend on it.
     */
    public static int parallel = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

    /** Runs {@code fn} on each element, {@link #parallel} at a time, until the evaluator stops; returns when all are done. */
    static <T> void each(Evaluator ev, List<T> list, java.util.function.Consumer<T> fn) {
        each(ev::stopped, list, fn);
    }

    /** {@link #each(Evaluator, List, java.util.function.Consumer)} with any stop check. */
    public static <T> void each(java.util.function.BooleanSupplier stopped, List<T> list, java.util.function.Consumer<T> fn) {
        int threads = Math.min(parallel, list.size());
        if (threads <= 1) {
            for (T t : list) {
                if (stopped.getAsBoolean()) {
                    break;
                }
                fn.accept(t);
            }
            return;
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "descoberta-kits");
            t.setDaemon(true);
            return t;
        });
        try {
            List<java.util.concurrent.Callable<Void>> runs = new ArrayList<>();
            for (T t : list) {
                runs.add(() -> {
                    if (!stopped.getAsBoolean()) {
                        fn.accept(t);
                    }
                    return null;
                });
            }
            for (java.util.concurrent.Future<Void> f : pool.invokeAll(runs)) {
                f.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof RuntimeException ? (RuntimeException) e.getCause() : new IllegalStateException(e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    public static class Config {
        public int length = 5;
        public int population = 12;
        public int generations = 5;
        public int children = 24;
        /** Children that reach the full battery each generation. */
        public int fullPerGeneration = 6;
        /** Random children that skip the surrogate's cut (the jokers of the search). */
        public int randomChildren = 2;
        public double crossover = 0.5;
        public boolean surrogate = true;
        public double ridgeLambda = 1.0;
        /** Finalists confirmed (plus each niche's best). */
        public int confirm = 3;
        /** Pairs of items of the best finalists whose synergy is measured. */
        public int synergyPairs = 10;
        public long seed = 1;
    }

    /** A kit and what the funnel knows of it. */
    public static class Entry {
        public Kit kit;
        public Double quick;
        public Eval full;
        public Eval confirmed;
        /** Where it came from: seed | random | child. */
        public String origin;
        public int generation;

        public double score() {
            return full == null ? Double.NEGATIVE_INFINITY : full.score;
        }
    }

    /** v(A+B) − v(A) − v(B), on the finalist without both as the base. */
    public static class Synergy {
        public long a;
        public long b;
        public Kit base;
        public double value;
        public double withBoth;
        public double withA;
        public double withB;
        public double without;
    }

    public static class Result {
        public List<Entry> ranked = new ArrayList<>();
        /** The best of each niche (key: the niche's coordinates joined by " · "). */
        public Map<String, Entry> niches = new LinkedHashMap<>();
        public List<Synergy> synergies = new ArrayList<>();
        /** The surrogate's correlation with the full battery, on kits it had not seen (null = not enough). */
        public Double surrogateCorrelation;
        public List<double[]> surrogatePairs = new ArrayList<>();
        public int quickRuns;
        public int fullRuns;
        public int confirmRuns;
        public int generations;
        public boolean stopped;
    }

    // ------------------------------------------------------------------ stage 1: the short list

    /**
     * The short list: the best {@code size} by the screening's value, with at least {@code perGroup} of each group (a tab
     * of the shop, an effect type) when the group has them, and {@code jokers} drawn among the left out. Ties by id.
     */
    public static List<Long> shortlist(Map<Long, Double> value, Map<Long, List<String>> groups, int size, int perGroup,
                                       int jokers, long seed) {
        List<Long> byValue = new ArrayList<>(value.keySet());
        byValue.sort(Comparator.comparingDouble((Long i) -> -value.get(i)).thenComparing(i -> i));
        LinkedHashSet<Long> out = new LinkedHashSet<>();
        Map<String, Integer> have = new HashMap<>();
        // the quota first: the best of each group
        Set<String> all = new TreeSet<>();
        groups.values().forEach(all::addAll);
        for (String g : all) {
            int n = 0;
            for (long i : byValue) {
                if (n >= perGroup) {
                    break;
                }
                if (groups.getOrDefault(i, List.of()).contains(g)) {
                    out.add(i);
                    n++;
                }
            }
            have.put(g, n);
        }
        for (long i : byValue) {
            if (out.size() >= size) {
                break;
            }
            out.add(i);
        }
        List<Long> left = new ArrayList<>();
        for (long i : byValue) {
            if (!out.contains(i)) {
                left.add(i);
            }
        }
        Random r = new Random(seed);
        for (int k = 0; k < jokers && !left.isEmpty(); k++) {
            out.add(left.remove(r.nextInt(left.size())));
        }
        return new ArrayList<>(out);
    }

    // ------------------------------------------------------------------ stages 2 to 4: the search

    public static Result search(Evaluator ev, List<Long> items, List<Long> boots, List<Kit> seeds, Config c) {
        Result res = new Result();
        Random rnd = new Random(c.seed);
        Map<String, Entry> seen = new LinkedHashMap<>();
        List<Entry> population = new ArrayList<>();
        // the start: the seeds (saved and meta builds), then random kits
        for (Kit k : seeds) {
            if (population.size() < c.population && ev.allowed(k) && !seen.containsKey(k.key())) {
                population.add(entry(k, "seed", 0, seen));
            }
        }
        int guard = 0;
        while (population.size() < c.population && guard++ < c.population * 50) {
            Kit k = random(items, boots, c.length, rnd);
            if (ev.allowed(k) && !seen.containsKey(k.key())) {
                population.add(entry(k, "random", 0, seen));
            }
        }
        full(ev, population, res);
        archive(population, res);
        Ridge model = null;
        List<Double> predicted = new ArrayList<>();
        List<Double> real = new ArrayList<>();
        for (int g = 1; g <= c.generations && !ev.stopped(); g++) {
            res.generations = g;
            List<Entry> children = new ArrayList<>();
            guard = 0;
            while (children.size() < c.children && guard++ < c.children * 50) {
                Entry p1 = tournament(population, rnd);
                Entry p2 = tournament(population, rnd);
                Kit k = rnd.nextDouble() < c.crossover ? cross(p1.kit, p2.kit, c.length, rnd) : p1.kit;
                k = mutate(k, items, boots, rnd);
                if (ev.allowed(k) && !seen.containsKey(k.key())) {
                    children.add(entry(k, "child", g, seen));
                }
            }
            // the surrogate ranks the children; the jokers skip its cut
            List<Entry> toQuick = new ArrayList<>(children);
            if (model != null && c.surrogate) {
                Ridge m = model;
                List<Entry> ranked = new ArrayList<>(children);
                ranked.sort(Comparator.comparingDouble((Entry e) -> -m.predict(e.kit.all())).thenComparing(e -> e.kit.key()));
                int keep = Math.max(c.fullPerGeneration * 2, children.size() / 2);
                toQuick = new ArrayList<>(ranked.subList(0, Math.min(keep, ranked.size())));
                List<Entry> rest = new ArrayList<>(ranked.subList(Math.min(keep, ranked.size()), ranked.size()));
                for (int k = 0; k < c.randomChildren && !rest.isEmpty(); k++) {
                    toQuick.add(rest.remove(rnd.nextInt(rest.size())));
                }
            }
            // quick fights: the worse half is cut (successive halving), then the full battery on the best
            java.util.concurrent.atomic.AtomicInteger quickDone = new java.util.concurrent.atomic.AtomicInteger();
            int quickTotal = toQuick.size();
            each(ev, toQuick, e -> {
                e.quick = ev.quick(e.kit);
                ev.progress("rapidas", quickDone.incrementAndGet(), quickTotal);
            });
            res.quickRuns += (int) toQuick.stream().filter(e -> e.quick != null).count();
            toQuick.removeIf(e -> e.quick == null);
            toQuick.sort(Comparator.comparingDouble((Entry e) -> -e.quick).thenComparing(e -> e.kit.key()));
            List<Entry> half = new ArrayList<>(toQuick.subList(0, (toQuick.size() + 1) / 2));
            List<Entry> fulls = new ArrayList<>(half.subList(0, Math.min(c.fullPerGeneration, half.size())));
            if (model != null) {
                for (Entry e : fulls) {
                    predicted.add(model.predict(e.kit.all()));
                }
            }
            full(ev, fulls, res);
            if (model != null) {
                for (Entry e : fulls) {
                    real.add(e.full == null ? Double.NaN : e.full.score);
                }
            }
            archive(fulls, res);
            // elitism: the best of everything measured make the next population
            List<Entry> measured = new ArrayList<>();
            for (Entry e : seen.values()) {
                if (e.full != null) {
                    measured.add(e);
                }
            }
            measured.sort(Comparator.comparingDouble((Entry e) -> -e.score()).thenComparing(e -> e.kit.key()));
            population = new ArrayList<>(measured.subList(0, Math.min(c.population, measured.size())));
            if (c.surrogate && measured.size() >= 8) {
                model = train(measured, items, boots, c.ridgeLambda);
            }
        }
        // the surrogate's hit on kits it had not seen when it predicted
        List<Double> p = new ArrayList<>();
        List<Double> r = new ArrayList<>();
        for (int i = 0; i < Math.min(predicted.size(), real.size()); i++) {
            if (!Double.isNaN(real.get(i))) {
                p.add(predicted.get(i));
                r.add(real.get(i));
                res.surrogatePairs.add(new double[] {predicted.get(i), real.get(i)});
            }
        }
        res.surrogateCorrelation = p.size() >= 4 ? Ridge.correlation(p, r) : null;
        // the ranking and the confirmation of the finalists (and each niche's best), on starts the search did not see
        List<Entry> ranked = new ArrayList<>();
        for (Entry e : seen.values()) {
            if (e.full != null) {
                ranked.add(e);
            }
        }
        ranked.sort(Comparator.comparingDouble((Entry e) -> -e.score()).thenComparing(e -> e.kit.key()));
        res.ranked = ranked;
        LinkedHashSet<Entry> toConfirm = new LinkedHashSet<>(ranked.subList(0, Math.min(c.confirm, ranked.size())));
        // and the best niches (by score), as many as the finalists: a confirmation costs about five batteries
        List<Entry> niches = new ArrayList<>(res.niches.values());
        niches.sort(Comparator.comparingDouble((Entry e) -> -e.score()).thenComparing(e -> e.kit.key()));
        toConfirm.addAll(niches.subList(0, Math.min(c.confirm, niches.size())));
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        each(ev, new ArrayList<>(toConfirm), e -> {
            e.confirmed = ev.confirm(e.kit);
            ev.progress("confirmacao", n.incrementAndGet(), toConfirm.size());
        });
        res.confirmRuns += (int) toConfirm.stream().filter(e -> e.confirmed != null).count();
        res.stopped = ev.stopped();
        return res;
    }

    private static Entry entry(Kit k, String origin, int g, Map<String, Entry> seen) {
        Entry e = new Entry();
        e.kit = k;
        e.origin = origin;
        e.generation = g;
        seen.put(k.key(), e);
        return e;
    }

    private static void full(Evaluator ev, List<Entry> list, Result res) {
        List<Entry> todo = new ArrayList<>();
        for (Entry e : list) {
            if (e.full == null) {
                todo.add(e);
            }
        }
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(list.size() - todo.size());
        each(ev, todo, e -> {
            e.full = ev.full(e.kit);
            ev.progress("bateria", done.incrementAndGet(), list.size());
        });
        res.fullRuns += (int) todo.stream().filter(e -> e.full != null).count();
    }

    /** MAP-Elites: each niche keeps its best kit. */
    static void archive(List<Entry> list, Result res) {
        for (Entry e : list) {
            if (e.full == null || e.full.niche.isEmpty()) {
                continue;
            }
            String key = String.join(" · ", e.full.niche);
            Entry old = res.niches.get(key);
            if (old == null || e.score() > old.score() + 1e-12) {
                res.niches.put(key, e);
            }
        }
    }

    private static Entry tournament(List<Entry> pop, Random rnd) {
        Entry a = pop.get(rnd.nextInt(pop.size()));
        Entry b = pop.get(rnd.nextInt(pop.size()));
        return a.score() >= b.score() ? a : b;
    }

    static Kit random(List<Long> items, List<Long> boots, int length, Random rnd) {
        List<Long> pool = new ArrayList<>(items);
        List<Long> pick = new ArrayList<>();
        while (pick.size() < length && !pool.isEmpty()) {
            pick.add(pool.remove(rnd.nextInt(pool.size())));
        }
        return new Kit(pick, boots.isEmpty() ? null : boots.get(rnd.nextInt(boots.size())));
    }

    /** A child with items drawn from both parents' union, and the boots of one of them. */
    static Kit cross(Kit a, Kit b, int length, Random rnd) {
        List<Long> union = new ArrayList<>(new TreeSet<>(a.items));
        for (long i : b.items) {
            if (!union.contains(i)) {
                union.add(i);
            }
        }
        List<Long> pick = new ArrayList<>();
        while (pick.size() < length && !union.isEmpty()) {
            pick.add(union.remove(rnd.nextInt(union.size())));
        }
        return new Kit(pick, rnd.nextBoolean() ? a.boots : b.boots);
    }

    /** One item swapped for another of the list (or, one time in six, the boots for other boots). */
    static Kit mutate(Kit k, List<Long> items, List<Long> boots, Random rnd) {
        if (!boots.isEmpty() && rnd.nextInt(6) == 0) {
            return new Kit(k.items, boots.get(rnd.nextInt(boots.size())));
        }
        List<Long> out = new ArrayList<>(k.items);
        List<Long> free = new ArrayList<>();
        for (long i : items) {
            if (!out.contains(i)) {
                free.add(i);
            }
        }
        if (free.isEmpty() || out.isEmpty()) {
            return k;
        }
        out.set(rnd.nextInt(out.size()), free.get(rnd.nextInt(free.size())));
        return new Kit(out, k.boots);
    }

    /** The surrogate on what was measured: items and boots present, plus the pairs of the best kits' items. */
    static Ridge train(List<Entry> measured, List<Long> items, List<Long> boots, double lambda) {
        List<Long> feats = new ArrayList<>(items);
        feats.addAll(boots);
        List<long[]> pairs = new ArrayList<>();
        Set<String> have = new TreeSet<>();
        for (Entry e : measured.subList(0, Math.min(5, measured.size()))) {
            for (int i = 0; i < e.kit.items.size(); i++) {
                for (int j = i + 1; j < e.kit.items.size(); j++) {
                    long a = e.kit.items.get(i);
                    long b = e.kit.items.get(j);
                    if (have.add(a + "+" + b)) {
                        pairs.add(new long[] {a, b});
                    }
                }
            }
        }
        List<Set<Long>> sets = new ArrayList<>();
        List<Double> y = new ArrayList<>();
        for (Entry e : measured) {
            sets.add(e.kit.all());
            y.add(e.full.score);
        }
        return new Ridge(feats, pairs, lambda).fit(sets, y);
    }

    // ------------------------------------------------------------------ synergies

    /**
     * The synergy of each pair of items of the best finalists: v(base + A + B) − v(base + A) − v(base + B) + v(base), the
     * base being the finalist without both (inclusion and exclusion: what the pair adds beyond each one alone). The
     * strongest first, by absolute value; at most {@code maxPairs} pairs measured.
     */
    public static List<Synergy> synergies(Evaluator ev, List<Entry> finalists, int maxPairs) {
        Map<String, Double> cache = new HashMap<>();
        List<Synergy> out = new ArrayList<>();
        for (Entry f : finalists) {
            if (f.full != null) {
                cache.put(f.kit.key(), f.full.score);
            }
        }
        int done = 0;
        for (Entry f : finalists) {
            List<Long> its = f.kit.items;
            for (int i = 0; i < its.size() && done < maxPairs; i++) {
                for (int j = i + 1; j < its.size() && done < maxPairs; j++) {
                    if (ev.stopped()) {
                        return sorted(out);
                    }
                    long a = its.get(i);
                    long b = its.get(j);
                    Kit both = f.kit;
                    Kit base = both.without(a).without(b);
                    Kit onlyA = new Kit(add(base.items, a), base.boots);
                    Kit onlyB = new Kit(add(base.items, b), base.boots);
                    Synergy s = new Synergy();
                    s.a = a;
                    s.b = b;
                    s.base = base;
                    s.withBoth = value(ev, both, cache);
                    s.withA = value(ev, onlyA, cache);
                    s.withB = value(ev, onlyB, cache);
                    s.without = value(ev, base, cache);
                    s.value = s.withBoth - s.withA - s.withB + s.without;
                    out.add(s);
                    done++;
                    ev.progress("sinergias", done, maxPairs);
                }
            }
        }
        return sorted(out);
    }

    private static List<Synergy> sorted(List<Synergy> l) {
        l.sort(Comparator.comparingDouble((Synergy s) -> -Math.abs(s.value)).thenComparing(s -> s.a).thenComparing(s -> s.b));
        return l;
    }

    private static List<Long> add(List<Long> l, long x) {
        List<Long> out = new ArrayList<>(l);
        out.add(x);
        return out;
    }

    private static double value(Evaluator ev, Kit k, Map<String, Double> cache) {
        return cache.computeIfAbsent(k.key(), x -> ev.full(k).score);
    }
}
