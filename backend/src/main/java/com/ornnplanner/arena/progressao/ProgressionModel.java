package com.ornnplanner.arena.progressao;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Rodada 19, the natural progression of builds: given what a champion already holds (finished items and boots, in
 * order, and components bought), which item comes next and when. A chain "state -> next item" read from the builds known
 * of the champion (the Discovery's, the saved ones, meta builds, guides), smoothed by the builds of champions of the same
 * subclass and of the same build archetype, and by an even share over what the shop allows; components held raise the
 * items that use them, and the enemies' composition leans it a little (seed rules, never an item or champion by name).
 * Times come from the planner's gold clock (starting gold + pace x minute; upgraded boots wait for their minute).
 * Pure: no Spring, no database; deterministic.
 */
public final class ProgressionModel {

    /** What the model knows of a shop item. */
    public static class Item {
        public long id;
        public String name;
        public int cost;
        /** Takes the boots slot. */
        public boolean boots;
        /** Upgraded boots (tier 3): sold only from the shop rule's minute. */
        public boolean upgradedBoots;
        /** A support item (and its evolutions): only in a support build. */
        public boolean support;
        public String group;
        public List<String> exclusive = new ArrayList<>();
        public Map<String, Double> stats = new LinkedHashMap<>();
        /** Its combat effects' kinds (combate_&lt;patch&gt;.yml {@code tipo}: feridas, amplifica...). */
        public Set<String> effects = new LinkedHashSet<>();
        /** Every component in its recipe, nested ones included, with how many. */
        public Map<Long, Integer> components = new LinkedHashMap<>();
    }

    /** A known build in purchase order: its finished items and boots. */
    public static class Trajectory {
        public String champion;
        /** descoberta | salvas | meta | guias */
        public String source;
        public double weight = 1;
        public String archetype;
        public List<String> subclasses = new ArrayList<>();
        public List<Long> items = new ArrayList<>();
        public String label;

        private transient String key;

        /** The same items in the same order: what leave-one-out drops together. */
        public String key() {
            if (key == null) {
                key = champion + "|" + items;
            }
            return key;
        }
    }

    /** The enemies as the composition rules read them. */
    public static class Composition {
        /** Mean share of their damage that is magic / physical (0..1). */
        public double magic;
        public double physical;
        /** Enemies that heal or shield themselves, and tanks. */
        public int heals;
        public int tanks;
        public int enemies;
    }

    /** A seed rule of the composition: when it holds, items with these stats or effects weigh more. */
    public static class Rule {
        public String name;
        public Double magicMin;
        public Double physicalMin;
        public Integer healsMin;
        public Integer tanksMin;
        public Map<String, Double> stats = new LinkedHashMap<>();
        public Map<String, Double> effects = new LinkedHashMap<>();

        boolean holds(Composition c) {
            if (c == null || c.enemies == 0) {
                return false;
            }
            return (magicMin == null || c.magic >= magicMin) && (physicalMin == null || c.physical >= physicalMin)
                    && (healsMin == null || c.heals >= healsMin) && (tanksMin == null || c.tanks >= tanksMin);
        }
    }

    /** The seed's numbers ({@code progressao_<patch>.yml}). */
    public static class Settings {
        /** Weight of each source's builds. */
        public Map<String, Double> sources = new LinkedHashMap<>(Map.of("descoberta", 1.0, "salvas", 1.5, "meta", 2.0, "guias", 1.5));
        /** Mixture of the (normalized) parts: the champion's builds by overlap with the state, its items' frequency, ... */
        public double champion = 1.0;
        public double championItems = 0.8;
        public double subclass = 0.3;
        public double archetype = 0.3;
        public double uniform = 0.02;
        /** A known build counts by (shared items / items held) to this power. */
        public double overlapPower = 4;
        /** A component held multiplies the items that use it by 1 + this x the component's share of their cost. */
        public double component = 1.5;
        /** Gold per minute of each pace, and how often each is drawn. */
        public Map<String, Double> paces = new LinkedHashMap<>(Map.of("atras", 600.0, "padrao", 700.0, "frente", 800.0));
        public Map<String, Double> paceWeights = new LinkedHashMap<>(Map.of("atras", 0.25, "padrao", 0.5, "frente", 0.25));
        public double startingGold = 500;
        public double upgradedBootsMinute = 10;
        /** Finished items besides the boots in a complete build. */
        public int maxItems = 5;
        public List<Rule> composition = new ArrayList<>();
        /** States kept by the search of whole continuations, and branches tried from each. */
        public int beam = 60;
        public int branches = 10;
    }

    /** What the champion holds and against whom. */
    public static class Context {
        public String champion;
        public List<String> subclasses = new ArrayList<>();
        /** The champion's usual build archetype (null = unknown). */
        public String archetype;
        /** Finished items and boots held, in purchase order. */
        public List<Long> held = new ArrayList<>();
        /** Components bought (not yet part of a held item). */
        public List<Long> components = new ArrayList<>();
        public Composition enemies;
        /** Plays support: support items allowed. */
        public boolean support;
        /** Known builds left out (leave-one-out), by {@link Trajectory#key()}. */
        public Set<String> exclude = new HashSet<>();

        Context copy() {
            Context c = new Context();
            c.champion = champion;
            c.subclasses = subclasses;
            c.archetype = archetype;
            c.held = new ArrayList<>(held);
            c.components = new ArrayList<>(components);
            c.enemies = enemies;
            c.support = support;
            c.exclude = exclude;
            return c;
        }
    }

    /** One continuation: the items still to buy, the probability of the whole path and each step's. */
    public static class Continuation {
        public List<Long> items = new ArrayList<>();
        public List<Double> stepProbability = new ArrayList<>();
        public double probability;
        /** Each item's minute by pace (atras / padrao / frente). */
        public Map<String, List<Double>> minutes = new LinkedHashMap<>();
    }

    /** A build drawn at a minute: what it has finished by then, at the pace drawn. */
    public static class Sample {
        public List<Long> items = new ArrayList<>();
        /** The whole path drawn (held first) and when each was finished at the pace drawn. */
        public List<Long> path = new ArrayList<>();
        public List<Double> minutes = new ArrayList<>();
        public String pace;
        public double probability;
    }

    private final Map<Long, Item> items;
    private final List<Trajectory> known;
    private final Settings s;

    /** The known builds by champion (lower case), by subclass and by archetype. */
    private final Map<String, List<Trajectory>> byChampion = new HashMap<>();
    private final Map<String, List<Trajectory>> bySubclass = new HashMap<>();
    private final Map<String, List<Trajectory>> byArchetype = new HashMap<>();

    public ProgressionModel(Map<Long, Item> items, List<Trajectory> known, Settings settings) {
        this.items = new LinkedHashMap<>(items);
        this.known = new ArrayList<>(known);
        this.s = settings;
        for (Trajectory t : this.known) {
            if (t.champion != null) {
                byChampion.computeIfAbsent(t.champion.toLowerCase(), k -> new ArrayList<>()).add(t);
            }
            for (String sub : t.subclasses) {
                bySubclass.computeIfAbsent(sub, k -> new ArrayList<>()).add(t);
            }
            if (t.archetype != null) {
                byArchetype.computeIfAbsent(t.archetype, k -> new ArrayList<>()).add(t);
            }
        }
    }

    public Settings settings() {
        return s;
    }

    public List<Trajectory> known() {
        return known;
    }

    public Item item(long id) {
        return items.get(id);
    }

    // ------------------------------------------------------------------ the next item

    /** Each item the shop allows next, with its probability (summing to 1), most likely first. Empty when complete. */
    public Map<Long, Double> next(Context ctx) {
        List<Item> cands = candidates(ctx);
        Map<Long, Double> out = new LinkedHashMap<>();
        if (cands.isEmpty()) {
            return out;
        }
        Set<Long> allowed = new LinkedHashSet<>();
        cands.forEach(i -> allowed.add(i.id));
        String me = ctx.champion == null ? "" : ctx.champion.toLowerCase();
        List<Trajectory> mine = new ArrayList<>();
        for (Trajectory t : byChampion.getOrDefault(me, List.of())) {
            if (ctx.exclude.isEmpty() || !ctx.exclude.contains(t.key())) {
                mine.add(t);
            }
        }
        // another champion's builds: once even when it shares several subclasses
        Set<Trajectory> alikeSet = new LinkedHashSet<>();
        for (String sub : ctx.subclasses) {
            for (Trajectory t : bySubclass.getOrDefault(sub, List.of())) {
                if (t.champion != null && !t.champion.equalsIgnoreCase(ctx.champion)) {
                    alikeSet.add(t);
                }
            }
        }
        List<Trajectory> alike = new ArrayList<>(alikeSet);
        List<Trajectory> sameArch = new ArrayList<>();
        for (Trajectory t : ctx.archetype == null ? List.<Trajectory>of() : byArchetype.getOrDefault(ctx.archetype, List.of())) {
            if (t.champion != null && !t.champion.equalsIgnoreCase(ctx.champion)) {
                sameArch.add(t);
            }
        }
        List<double[]> parts = new ArrayList<>();
        List<Map<Long, Double>> dists = new ArrayList<>();
        add(dists, parts, overlapNext(mine, ctx.held, allowed), s.champion);
        add(dists, parts, frequency(mine, ctx.held, allowed), s.championItems);
        add(dists, parts, overlapNext(alike, ctx.held, allowed), s.subclass);
        add(dists, parts, overlapNext(sameArch, ctx.held, allowed), s.archetype);
        Map<Long, Double> even = new LinkedHashMap<>();
        allowed.forEach(id -> even.put(id, 1.0));
        add(dists, parts, even, s.uniform);
        double wsum = 0;
        for (double[] p : parts) {
            wsum += p[0];
        }
        Map<Long, Double> mix = new LinkedHashMap<>();
        for (int k = 0; k < dists.size(); k++) {
            double w = parts.get(k)[0] / wsum;
            dists.get(k).forEach((id, v) -> mix.merge(id, w * v, Double::sum));
        }
        // components held and the enemies' composition
        List<Rule> rules = new ArrayList<>();
        for (Rule r : s.composition) {
            if (r.holds(ctx.enemies)) {
                rules.add(r);
            }
        }
        for (Item it : cands) {
            double v = mix.getOrDefault(it.id, 0.0) * componentFactor(it, ctx.components) * compositionFactor(it, rules);
            out.put(it.id, v);
        }
        normalize(out);
        return sorted(out);
    }

    private static void add(List<Map<Long, Double>> dists, List<double[]> parts, Map<Long, Double> d, double weight) {
        double sum = d.values().stream().mapToDouble(Double::doubleValue).sum();
        if (weight <= 0 || sum <= 0) {
            return;
        }
        Map<Long, Double> n = new LinkedHashMap<>();
        d.forEach((k, v) -> n.put(k, v / sum));
        dists.add(n);
        parts.add(new double[] {weight});
    }

    /** Each known build votes for its first item not held (and allowed), by its weight times its overlap with the state. */
    private Map<Long, Double> overlapNext(List<Trajectory> trajs, List<Long> held, Set<Long> allowed) {
        Map<Long, Double> acc = new LinkedHashMap<>();
        Set<Long> h = new HashSet<>(held);
        for (Trajectory t : trajs) {
            double frac = 1;
            if (!h.isEmpty()) {
                int shared = 0;
                for (Long id : t.items) {
                    if (h.contains(id)) {
                        shared++;
                    }
                }
                frac = Math.pow((double) shared / h.size(), s.overlapPower);
            }
            if (frac <= 0) {
                continue;
            }
            for (Long id : t.items) {
                if (!h.contains(id) && allowed.contains(id)) {
                    acc.merge(id, t.weight * frac, Double::sum);
                    break;
                }
            }
        }
        return acc;
    }

    /** How often each allowed item is in the champion's builds at all (any position). */
    private static Map<Long, Double> frequency(List<Trajectory> trajs, List<Long> held, Set<Long> allowed) {
        Map<Long, Double> acc = new LinkedHashMap<>();
        Set<Long> h = new HashSet<>(held);
        for (Trajectory t : trajs) {
            for (Long id : t.items) {
                if (!h.contains(id) && allowed.contains(id)) {
                    acc.merge(id, t.weight / Math.max(1, t.items.size()), Double::sum);
                }
            }
        }
        return acc;
    }

    /** 1 + factor x the share of the item's cost in components held that it uses. */
    double componentFactor(Item it, List<Long> components) {
        if (components == null || components.isEmpty() || it.cost <= 0) {
            return 1;
        }
        Map<Long, Integer> left = new HashMap<>(it.components);
        double used = 0;
        for (Long c : components) {
            Integer n = left.get(c);
            Item ci = items.get(c);
            if (n != null && n > 0 && ci != null) {
                used += ci.cost;
                left.put(c, n - 1);
            }
        }
        return 1 + s.component * Math.min(1, used / it.cost);
    }

    /** The largest multiplier among the rules that hold, for the item's stats and effects (1 = none). */
    static double compositionFactor(Item it, List<Rule> rules) {
        double f = 1;
        for (Rule r : rules) {
            for (Map.Entry<String, Double> e : r.stats.entrySet()) {
                if (it.stats.getOrDefault(e.getKey(), 0.0) > 0) {
                    f = Math.max(f, e.getValue());
                }
            }
            for (Map.Entry<String, Double> e : r.effects.entrySet()) {
                if (it.effects.contains(e.getKey())) {
                    f = Math.max(f, e.getValue());
                }
            }
        }
        return f;
    }

    /** What the shop allows next: a finished item or boots not held, of no group held, within the slots. */
    List<Item> candidates(Context ctx) {
        List<Item> held = new ArrayList<>();
        for (Long id : ctx.held) {
            Item it = items.get(id);
            if (it != null) {
                held.add(it);
            }
        }
        boolean boots = held.stream().anyMatch(i -> i.boots);
        long finished = held.stream().filter(i -> !i.boots).count();
        List<Item> out = new ArrayList<>();
        for (Item it : items.values()) {
            if (ctx.held.contains(it.id) || it.boots && boots || !it.boots && finished >= s.maxItems || it.support && !ctx.support) {
                continue;
            }
            if (fits(it, held)) {
                out.add(it);
            }
        }
        return out;
    }

    /** Not the same item group as one held, no exclusive group shared. */
    static boolean fits(Item it, Collection<Item> held) {
        for (Item h : held) {
            String gh = h.group == null ? h.name : h.group;
            String gi = it.group == null ? it.name : it.group;
            if (gh != null && gh.equals(gi)) {
                return false;
            }
            for (String g : it.exclusive) {
                if (h.exclusive.contains(g)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Whether the build is complete (no more slots the shop would fill). */
    public boolean complete(Context ctx) {
        return candidates(ctx).isEmpty();
    }

    // ------------------------------------------------------------------ whole continuations

    /**
     * The k most likely ways to complete the build from the context, by a beam over the chain (each state's most likely
     * branches), with each item's minute at the three paces.
     */
    public List<Continuation> continuations(Context ctx, int k) {
        final class State {
            Context c;
            List<Long> added = new ArrayList<>();
            List<Double> steps = new ArrayList<>();
            double logp;
        }
        State first = new State();
        first.c = ctx.copy();
        List<State> beam = new ArrayList<>(List.of(first));
        List<State> done = new ArrayList<>();
        for (int depth = 0; depth < s.maxItems + 2 && !beam.isEmpty(); depth++) {
            List<State> nextBeam = new ArrayList<>();
            for (State st : beam) {
                Map<Long, Double> nx = next(st.c);
                if (nx.isEmpty()) {
                    done.add(st);
                    continue;
                }
                int taken = 0;
                for (Map.Entry<Long, Double> e : nx.entrySet()) {
                    if (taken++ >= s.branches || e.getValue() <= 0) {
                        break;
                    }
                    State n = new State();
                    n.c = st.c.copy();
                    buy(n.c, e.getKey());
                    n.added = new ArrayList<>(st.added);
                    n.added.add(e.getKey());
                    n.steps = new ArrayList<>(st.steps);
                    n.steps.add(e.getValue());
                    n.logp = st.logp + Math.log(e.getValue());
                    nextBeam.add(n);
                }
            }
            nextBeam.sort(Comparator.comparingDouble((State x) -> -x.logp).thenComparing(x -> x.added.toString()));
            beam = new ArrayList<>(nextBeam.subList(0, Math.min(s.beam, nextBeam.size())));
        }
        done.addAll(beam);
        done.sort(Comparator.comparingDouble((State x) -> -x.logp).thenComparing(x -> x.added.toString()));
        List<Continuation> out = new ArrayList<>();
        for (State st : done.subList(0, Math.min(k, done.size()))) {
            Continuation c = new Continuation();
            c.items = st.added;
            c.stepProbability = st.steps;
            c.probability = Math.exp(st.logp);
            List<Long> path = new ArrayList<>(ctx.held);
            path.addAll(st.added);
            for (Map.Entry<String, Double> pace : s.paces.entrySet()) {
                List<Double> all = minutes(path, pace.getValue());
                c.minutes.put(pace.getKey(), new ArrayList<>(all.subList(ctx.held.size(), all.size())));
            }
            out.add(c);
        }
        return out;
    }

    /** The context after buying an item: it is held, and the components it uses are gone. */
    void buy(Context c, long id) {
        c.held.add(id);
        Item it = items.get(id);
        if (it == null) {
            return;
        }
        Map<Long, Integer> left = new HashMap<>(it.components);
        List<Long> keep = new ArrayList<>();
        for (Long comp : c.components) {
            Integer n = left.get(comp);
            if (n != null && n > 0) {
                left.put(comp, n - 1);
            } else {
                keep.add(comp);
            }
        }
        c.components = keep;
    }

    // ------------------------------------------------------------------ the clock

    /**
     * The minute each item of a purchase order is finished at a pace: the planner's gold (starting gold + pace x minute)
     * covers the cumulative cost, in order; upgraded boots wait for the shop rule's minute, and what comes after them waits
     * for them.
     */
    public List<Double> minutes(List<Long> order, double pace) {
        List<Double> out = new ArrayList<>();
        double cum = 0;
        double last = 0;
        for (Long id : order) {
            Item it = items.get(id);
            cum += it == null ? 0 : it.cost;
            double t = Math.max(0, (cum - s.startingGold) / Math.max(1, pace));
            if (it != null && it.upgradedBoots) {
                t = Math.max(t, s.upgradedBootsMinute);
            }
            t = Math.max(t, last);
            out.add(t);
            last = t;
        }
        return out;
    }

    // ------------------------------------------------------------------ samples at a minute

    /**
     * n builds drawn from the chain, each at a pace drawn by the seed's weights: what each has finished by the minute.
     * Deterministic by the seed.
     */
    public List<Sample> sample(Context ctx, double minute, int n, long seed) {
        Random rng = new Random(seed);
        List<Sample> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String pace = drawPace(rng);
            double gold = s.paces.getOrDefault(pace, 700.0);
            Context c = ctx.copy();
            double p = 1;
            for (int guard = 0; guard < s.maxItems + 2; guard++) {
                List<Double> mins = minutes(c.held, gold);
                if (!mins.isEmpty() && mins.get(mins.size() - 1) > minute) {
                    break;
                }
                Map<Long, Double> nx = next(c);
                if (nx.isEmpty()) {
                    break;
                }
                long pick = draw(rng, nx);
                p *= nx.get(pick);
                buy(c, pick);
            }
            Sample sm = new Sample();
            sm.pace = pace;
            sm.path = new ArrayList<>(c.held);
            sm.minutes = minutes(c.held, gold);
            for (int k = 0; k < sm.path.size(); k++) {
                if (sm.minutes.get(k) <= minute + 1e-9 || k < ctx.held.size()) {
                    sm.items.add(sm.path.get(k));
                }
            }
            sm.probability = p;
            out.add(sm);
        }
        return out;
    }

    private String drawPace(Random rng) {
        double total = s.paceWeights.values().stream().mapToDouble(Double::doubleValue).sum();
        double r = rng.nextDouble() * total;
        String last = "padrao";
        for (Map.Entry<String, Double> e : s.paceWeights.entrySet()) {
            last = e.getKey();
            r -= e.getValue();
            if (r <= 0) {
                return e.getKey();
            }
        }
        return last;
    }

    private static long draw(Random rng, Map<Long, Double> dist) {
        double r = rng.nextDouble();
        long last = -1;
        for (Map.Entry<Long, Double> e : dist.entrySet()) {
            last = e.getKey();
            r -= e.getValue();
            if (r <= 0) {
                return e.getKey();
            }
        }
        return last;
    }

    // ------------------------------------------------------------------ leave-one-out

    /** The leave-one-out check: how often the real next item is among the k most likely. */
    public static class Check {
        public int cases;
        public int top1;
        public int top3;
        /** The cases after the first two purchases (the late ones, hidden from the end of each build). */
        public int lateCases;
        public int lateTop3;
        /** The same cases by a plain baseline: the items most frequent in the champion's other builds. */
        public int baselineTop3;
        public Map<String, int[]> byChampion = new LinkedHashMap<>();
    }

    public interface ContextMaker {
        Context make(Trajectory t);
    }

    /**
     * For each known build of the champions asked (all when empty): every prefix is a state, the real next item is
     * hidden, and the model, without that build (nor any with the same items in the same order), ranks the next items.
     */
    public Check leaveOneOut(Collection<String> champions, ContextMaker maker) {
        Check out = new Check();
        Set<String> seen = new HashSet<>();
        for (Trajectory t : known) {
            if (champions != null && !champions.isEmpty() && champions.stream().noneMatch(c -> c.equalsIgnoreCase(t.champion))) {
                continue;
            }
            if (!seen.add(t.key()) || t.items.size() < 2) {
                continue;
            }
            Context base = maker.make(t);
            base.exclude = Set.of(t.key());
            for (int k = 0; k < t.items.size(); k++) {
                Context c = base.copy();
                c.held = new ArrayList<>(t.items.subList(0, k));
                Map<Long, Double> nx = next(c);
                if (nx.isEmpty()) {
                    continue;
                }
                List<Long> rank = new ArrayList<>(nx.keySet());
                long real = t.items.get(k);
                int at = rank.indexOf(real);
                out.cases++;
                int[] ch = out.byChampion.computeIfAbsent(t.champion, x -> new int[3]);
                ch[0]++;
                if (at == 0) {
                    out.top1++;
                }
                if (at >= 0 && at < 3) {
                    out.top3++;
                    ch[1]++;
                }
                List<Long> pop = baseline(c, nx.keySet());
                if (pop.indexOf(real) >= 0 && pop.indexOf(real) < 3) {
                    out.baselineTop3++;
                    ch[2]++;
                }
                if (k >= 2) {
                    out.lateCases++;
                    if (at >= 0 && at < 3) {
                        out.lateTop3++;
                    }
                }
            }
        }
        return out;
    }

    /** The allowed items by how often they are in the champion's builds (the leave-one-out's baseline). */
    private List<Long> baseline(Context c, Set<Long> allowed) {
        List<Trajectory> mine = new ArrayList<>();
        for (Trajectory t : byChampion.getOrDefault(c.champion == null ? "" : c.champion.toLowerCase(), List.of())) {
            if (!c.exclude.contains(t.key())) {
                mine.add(t);
            }
        }
        Map<Long, Double> f = frequency(mine, c.held, allowed);
        allowed.forEach(id -> f.putIfAbsent(id, 0.0));
        return new ArrayList<>(sorted(f).keySet());
    }

    // ------------------------------------------------------------------ helpers

    static void normalize(Map<Long, Double> m) {
        double sum = m.values().stream().mapToDouble(Double::doubleValue).sum();
        if (sum <= 0) {
            double even = m.isEmpty() ? 0 : 1.0 / m.size();
            m.replaceAll((k, v) -> even);
            return;
        }
        m.replaceAll((k, v) -> v / sum);
    }

    static Map<Long, Double> sorted(Map<Long, Double> m) {
        List<Map.Entry<Long, Double>> l = new ArrayList<>(m.entrySet());
        l.sort(Comparator.comparingDouble((Map.Entry<Long, Double> e) -> -e.getValue()).thenComparingLong(Map.Entry::getKey));
        Map<Long, Double> out = new LinkedHashMap<>();
        l.forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }
}
