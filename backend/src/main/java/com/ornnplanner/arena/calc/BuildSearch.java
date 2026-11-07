package com.ornnplanner.arena.calc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The search over purchase orders, apart from how an order is measured: a beam over the prefixes, the score of each at
 * the minutes asked, and what moving each item does to the score. Deterministic: ties keep the candidates' order.
 */
public final class BuildSearch {

    private BuildSearch() {
    }

    /** What the search needs to know about an order. */
    public interface Oracle {
        /** The shop's rules allow this order (exclusive groups, one pair of boots, components...). */
        boolean allowed(List<Long> order);

        /** The measure of the order at a minute, with what it holds by then: the higher the better. */
        double score(List<Long> order, double minute);

        /** Minute the item at this position is bought. */
        double readyAt(List<Long> order, int index);

        /** The budget ran out or someone pressed stop: what is known so far is the answer. */
        boolean stopped();
    }

    /** An order and its score. */
    public static class Ranked {
        public List<Long> order;
        public double score;
        /** The score at each minute asked. */
        public double[] curve;

        public Ranked(List<Long> order, double score, double[] curve) {
            this.order = order;
            this.score = score;
            this.curve = curve;
        }
    }

    /** What moving an item one position does to the score of the order. */
    public static class Importance {
        public long item;
        public int position;
        public double readyAt;
        /** Score lost by buying it one position later (null = it is the last). */
        public Double delayed;
        /** Score gained by buying it one position earlier (null = it is the first). */
        public Double advanced;
    }

    public static Ranked measure(Oracle o, List<Long> order, double[] minutes) {
        double[] curve = new double[minutes.length];
        double sum = 0;
        for (int i = 0; i < minutes.length; i++) {
            curve[i] = o.score(order, minutes[i]);
            sum += curve[i];
        }
        return new Ranked(order, sum / Math.max(1, minutes.length), curve);
    }

    /** The best orders of {@code length} items, best first. Items of {@code required} are always in them. */
    public static List<Ranked> beam(Oracle o, List<Long> candidates, List<Long> required, int length, int width, double[] minutes) {
        List<List<Long>> frontier = new ArrayList<>();
        frontier.add(new ArrayList<>());
        List<Ranked> kept = new ArrayList<>();
        for (int depth = 0; depth < length; depth++) {
            List<Ranked> next = new ArrayList<>();
            int slotsLeft = length - depth - 1;
            for (List<Long> prefix : frontier) {
                long missing = required.stream().filter(r -> !prefix.contains(r)).count();
                for (long item : candidates) {
                    if (o.stopped()) {
                        break;
                    }
                    if (prefix.contains(item) || missing > slotsLeft && !required.contains(item) && !prefix.contains(item)) {
                        continue;
                    }
                    List<Long> order = new ArrayList<>(prefix);
                    order.add(item);
                    if (o.allowed(order)) {
                        next.add(measure(o, order, minutes));
                    }
                }
            }
            if (next.isEmpty()) {
                break;
            }
            next.sort(Comparator.comparingDouble((Ranked r) -> -r.score));
            kept = new ArrayList<>(next.subList(0, Math.min(width, next.size())));
            frontier = new ArrayList<>();
            for (Ranked r : kept) {
                frontier.add(r.order);
            }
            if (o.stopped()) {
                break;
            }
        }
        return kept;
    }

    // ------------------------------------------------------------------ steps (Rodada 9, parte 0)

    /** What a step of an order is: a finished item, boots (tier 2 or 3) or a component bought on its own. */
    public enum Kind { ITEM, BOOTS, BOOTS3, COMPONENT }

    /**
     * The space of a step search: the candidates by kind, the components' parents, the number of item slots to fill and
     * how freely components may enter the order. Moderate: one isolated component at most. Aggressive: components may fill
     * the slots and be closed later, or never.
     */
    public static class Space {
        public final Map<Long, Kind> kinds = new LinkedHashMap<>();
        /** Component (or tier 2 boots) -> the candidates whose recipe holds it, anywhere in the tree. */
        public final Map<Long, Set<Long>> parents = new HashMap<>();
        public int length = 5;
        public boolean aggressive;
        /** No boots in the order (rare builds); otherwise one slot is always boots. */
        public boolean noBoots;
        public List<Long> required = new ArrayList<>();

        public Space add(long id, Kind k) {
            kinds.put(id, k);
            return this;
        }

        public Space parent(long component, long parent) {
            parents.computeIfAbsent(component, x -> new TreeSet<>()).add(parent);
            return this;
        }

        public Kind kind(long id) {
            return kinds.getOrDefault(id, Kind.ITEM);
        }

        int maxComponents() {
            return aggressive ? length : 1;
        }

        public int maxDepth() {
            return length + (noBoots ? 0 : 2) + maxComponents();
        }
    }

    /** What an order holds, counted the way the space asks. */
    static final class Count {
        int items;
        int components;
        /** Components not consumed by a later item of the order. */
        int open;
        int boots;
        boolean boots3;
        Long heldBoots;

        Count(Space sp, List<Long> order) {
            for (int i = 0; i < order.size(); i++) {
                long id = order.get(i);
                switch (sp.kind(id)) {
                    case ITEM:
                        items++;
                        break;
                    case BOOTS:
                        boots++;
                        heldBoots = id;
                        break;
                    case BOOTS3:
                        boots++;
                        boots3 = true;
                        heldBoots = id;
                        break;
                    default:
                        components++;
                        if (!closed(sp, order, i)) {
                            open++;
                        }
                }
            }
        }

        /** Item slots the order fills at its end: items and the components still held. */
        int slots() {
            return items + open;
        }
    }

    /** Whether the component at {@code i} is consumed by an item bought after it. */
    static boolean closed(Space sp, List<Long> order, int i) {
        Set<Long> ps = sp.parents.getOrDefault(order.get(i), Set.of());
        for (int k = i + 1; k < order.size(); k++) {
            if (ps.contains(order.get(k))) {
                return true;
            }
        }
        return false;
    }

    /** The order is a whole build: its slots filled the way the space asks, with boots unless there are none. */
    public static boolean complete(Space sp, List<Long> order) {
        Count c = new Count(sp, order);
        boolean boots = sp.noBoots ? c.boots == 0 : c.boots > 0;
        boolean filled = sp.aggressive ? c.slots() == sp.length : c.items == sp.length;
        return boots && filled && order.containsAll(sp.required);
    }

    /** The steps that may follow a prefix. */
    static List<Long> next(Space sp, List<Long> prefix) {
        Count c = new Count(sp, prefix);
        Set<Long> heldOpen = new HashSet<>();
        for (int i = 0; i < prefix.size(); i++) {
            if (sp.kind(prefix.get(i)) == Kind.COMPONENT && !closed(sp, prefix, i)) {
                heldOpen.add(prefix.get(i));
            }
        }
        int room = sp.aggressive ? sp.length - c.slots() : sp.length - c.items;
        long missing = sp.required.stream().filter(r -> !prefix.contains(r)).count();
        List<Long> out = new ArrayList<>();
        for (Map.Entry<Long, Kind> e : sp.kinds.entrySet()) {
            long id = e.getKey();
            if (prefix.contains(id)) {
                continue;
            }
            boolean closes = false;
            for (long h : heldOpen) {
                closes |= sp.parents.getOrDefault(h, Set.of()).contains(id);
            }
            switch (e.getValue()) {
                case ITEM:
                    if (room > 0 || closes && sp.aggressive) {
                        boolean free = sp.required.contains(id) || closes && sp.aggressive || missing < room;
                        if (free) {
                            out.add(id);
                        }
                    }
                    break;
                case BOOTS:
                    if (!sp.noBoots && c.boots == 0) {
                        out.add(id);
                    }
                    break;
                case BOOTS3:
                    if (!sp.noBoots && !c.boots3
                            && (c.heldBoots == null || sp.parents.getOrDefault(c.heldBoots, Set.of()).contains(id))) {
                        out.add(id);
                    }
                    break;
                default:
                    boolean useful = false;
                    for (long p : sp.parents.getOrDefault(id, Set.of())) {
                        useful |= !prefix.contains(p) && sp.kind(p) == Kind.ITEM;
                    }
                    if (useful && c.components < sp.maxComponents() && room > 0 && missing < room) {
                        out.add(id);
                    }
            }
        }
        return out;
    }

    /** Hears each depth of a step search: its prefixes, best first (the funnel prunes by the start with it). */
    public interface DepthListener {
        /** Returns the prefixes to keep (a sublist of {@code ranked}, in order). */
        List<Ranked> depth(int depth, List<Ranked> ranked);
    }

    /**
     * The best whole orders of the space, best first: a beam over the steps (items, boots, components), every complete
     * order seen kept in a pool, so a short order is not lost to longer ones.
     */
    public static List<Ranked> steps(Oracle o, Space sp, int width, double[] minutes) {
        return steps(o, sp, width, minutes, null);
    }

    public static List<Ranked> steps(Oracle o, Space sp, int width, double[] minutes, DepthListener listener) {
        List<List<Long>> frontier = new ArrayList<>();
        frontier.add(new ArrayList<>());
        List<Ranked> pool = new ArrayList<>();
        List<Ranked> deepest = new ArrayList<>();
        Set<List<Long>> seen = new HashSet<>();
        for (int depth = 0; depth < sp.maxDepth() && !frontier.isEmpty(); depth++) {
            List<Ranked> next = new ArrayList<>();
            for (List<Long> prefix : frontier) {
                for (long id : next(sp, prefix)) {
                    if (o.stopped()) {
                        break;
                    }
                    List<Long> order = new ArrayList<>(prefix);
                    order.add(id);
                    if (!seen.add(order) || !o.allowed(order)) {
                        continue;
                    }
                    Ranked r = measure(o, order, minutes);
                    next.add(r);
                    if (complete(sp, order)) {
                        pool.add(r);
                    }
                }
            }
            next.sort(Comparator.comparingDouble((Ranked r) -> -r.score));
            if (listener != null) {
                next = listener.depth(depth, next);
            }
            if (!next.isEmpty()) {
                deepest = next;
            }
            frontier = new ArrayList<>();
            for (Ranked r : next.subList(0, Math.min(width, next.size()))) {
                frontier.add(r.order);
            }
            if (o.stopped()) {
                break;
            }
        }
        pool.sort(Comparator.comparingDouble((Ranked r) -> -r.score));
        if (pool.isEmpty()) {
            // the budget ran out before any whole order: the best prefixes so far, so the answer is not empty
            return new ArrayList<>(deepest.subList(0, Math.min(width, deepest.size())));
        }
        return pool;
    }

    /** "Which component of the next item first": the best of an item's components bought right before it. */
    public static class ComponentFirst {
        public long item;
        public long component;
        public double gain;
    }

    /**
     * For each item of the order, each of its components (the space's parents) is tried as a step right before it, so it
     * is bought as gold allows and the item closes later. A gain is kept when positive; {@code improved} gets the order
     * with all of them.
     */
    public static List<ComponentFirst> componentFirst(Oracle o, Space sp, Ranked best, double[] minutes, List<Long> improved) {
        List<ComponentFirst> out = new ArrayList<>();
        List<Long> order = new ArrayList<>(best.order);
        double score = best.score;
        for (long item : best.order) {
            if (sp.kind(item) != Kind.ITEM) {
                continue;
            }
            ComponentFirst top = null;
            List<Long> topOrder = null;
            double topScore = score;
            for (Map.Entry<Long, Set<Long>> e : new java.util.TreeMap<>(sp.parents).entrySet()) {
                if (!e.getValue().contains(item) || order.contains(e.getKey()) || sp.kind(e.getKey()) != Kind.COMPONENT || o.stopped()) {
                    continue;
                }
                List<Long> tried = new ArrayList<>(order);
                tried.add(tried.indexOf(item), e.getKey());
                if (!o.allowed(tried)) {
                    continue;
                }
                double s = measure(o, tried, minutes).score;
                if (s > topScore + 1e-9) {
                    top = new ComponentFirst();
                    top.item = item;
                    top.component = e.getKey();
                    top.gain = s - score;
                    topScore = s;
                    topOrder = tried;
                }
            }
            if (top != null) {
                out.add(top);
                order = topOrder;
                score = topScore;
            }
        }
        if (improved != null) {
            improved.clear();
            improved.addAll(order);
        }
        return out;
    }

    private static List<Long> swapped(List<Long> order, int a, int b) {
        List<Long> out = new ArrayList<>(order);
        out.set(a, order.get(b));
        out.set(b, order.get(a));
        return out;
    }

    /** "Early importance" of each item: the score lost by delaying it a position and gained by advancing it. */
    public static List<Importance> importance(Oracle o, Ranked best, double[] minutes) {
        List<Importance> out = new ArrayList<>();
        for (int i = 0; i < best.order.size(); i++) {
            Importance m = new Importance();
            m.item = best.order.get(i);
            m.position = i;
            m.readyAt = o.readyAt(best.order, i);
            if (i + 1 < best.order.size()) {
                List<Long> later = swapped(best.order, i, i + 1);
                if (o.allowed(later) && !o.stopped()) {
                    m.delayed = best.score - measure(o, later, minutes).score;
                }
            }
            if (i > 0) {
                List<Long> sooner = swapped(best.order, i, i - 1);
                if (o.allowed(sooner) && !o.stopped()) {
                    m.advanced = measure(o, sooner, minutes).score - best.score;
                }
            }
            out.add(m);
        }
        return out;
    }

    /**
     * When an item is worth buying: it is tried at each position of the order, and the answer is the minute of the earliest
     * position within {@code margin} (a share) of the best one. Null when it fits nowhere.
     */
    public static Double worthFrom(Oracle o, List<Long> order, long item, double[] minutes, double margin) {
        List<Long> rest = new ArrayList<>(order);
        rest.remove(item);
        List<double[]> tries = new ArrayList<>();
        double best = Double.NEGATIVE_INFINITY;
        for (int p = 0; p <= rest.size(); p++) {
            List<Long> at = new ArrayList<>(rest);
            at.add(p, item);
            if (o.allowed(at) && !o.stopped()) {
                double s = measure(o, at, minutes).score;
                tries.add(new double[] {s, o.readyAt(at, p)});
                best = Math.max(best, s);
            }
        }
        for (double[] t : tries) {
            if (t[0] >= best - Math.abs(best) * margin) {
                return t[1];
            }
        }
        return null;
    }
}
