package com.ornnplanner.arena.calc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

        Ranked(List<Long> order, double score, double[] curve) {
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

    static Ranked measure(Oracle o, List<Long> order, double[] minutes) {
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
