package com.ornnplanner.arena.rift;

import org.springframework.core.io.Resource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/** The match rules of {@code objetivos_<patch>.yml}: read by path, with the {@code valor} of a sourced number unwrapped. */
public final class RiftRules {

    public final Map<String, Object> root;

    public RiftRules(Map<String, Object> root) {
        this.root = root;
    }

    public static RiftRules load(Resource file) {
        try (InputStream in = file.getInputStream()) {
            Map<String, Object> m = new Yaml().load(in);
            return new RiftRules(m);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String... path) {
        Object at = root;
        for (String p : path) {
            at = at instanceof Map ? ((Map<String, Object>) at).get(p) : null;
        }
        return at instanceof Map ? (Map<String, Object>) at : null;
    }

    /** A number of the rules; a block with {@code valor} reads as its value. */
    public double num(double def, String... path) {
        String[] parent = java.util.Arrays.copyOf(path, path.length - 1);
        Map<String, Object> m = map(parent);
        return m == null ? def : value(m.get(path[path.length - 1]), def);
    }

    public static double value(Object o, double def) {
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        if (o instanceof Map) {
            return value(((Map<?, ?>) o).get("valor"), def);
        }
        return def;
    }

    /** Second of a match the next wave leaves after {@code second}: the first wave, then at each interval. */
    public double nextWave(double second) {
        double t = num(30, "tropas", "primeira_onda");
        while (t <= second + 1e-9) {
            t += waveGap(t);
        }
        return t;
    }

    /** How many waves left before {@code second}. */
    public int wavesBefore(double second) {
        int n = 0;
        double t = num(30, "tropas", "primeira_onda");
        while (t < second - 1e-9) {
            n++;
            t += waveGap(t);
        }
        return n;
    }

    /** Second a camp first appears: the jungle's first spawn, and the buff camps a moment later. */
    public double campAppears(String camp) {
        double first = num(20, "selva", "primeira_aparicao");
        Map<String, Object> c = map("selva", "acampamentos", camp);
        return c != null && c.get("buff") != null ? first + num(0, "selva", "buffs_depois") : first;
    }

    /** The elements of the drakes in the order they come in a match: drawn by the seed when the rules say so. */
    @SuppressWarnings("unchecked")
    public List<String> dragonOrder(long seed) {
        Map<String, Object> o = map("objetivos", "dragao", "ordem");
        List<String> out = new java.util.ArrayList<>();
        if (o != null && o.get("valores") instanceof List) {
            out.addAll((List<String>) o.get("valores"));
        } else {
            Map<String, Object> e = map("objetivos", "dragao", "elementos");
            if (e != null) {
                out.addAll(e.keySet());
            }
        }
        if (o != null && Boolean.TRUE.equals(o.get("sorteada"))) {
            java.util.SplittableRandom r = new java.util.SplittableRandom(seed * 0x9E3779B97F4A7C15L + 77);
            for (int i = out.size() - 1; i > 0; i--) {
                java.util.Collections.swap(out, i, r.nextInt(i + 1));
            }
        }
        return out;
    }

    /**
     * What {@code stacks} drakes of an element add to the stats: the fixed lines first, then the % of the total, which
     * reads the total after every other source and the fixed part of the drakes ({@code total} gives the stat before the
     * drakes). Each drake adds its lines again. Stat -> amount, in the order of the lines.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Double> dragonDelta(Map<String, Object> element, int stacks, java.util.function.ToDoubleFunction<String> total) {
        Map<String, Double> out = new java.util.LinkedHashMap<>();
        Object status = element == null ? null : element.get("status");
        if (!(status instanceof List) || stacks <= 0) {
            return out;
        }
        for (Map<String, Object> l : (List<Map<String, Object>>) status) {
            if (l.get("ratio") == null) {
                out.merge(String.valueOf(l.get("stat")), value(l.get("valor"), 0) * stacks, Double::sum);
            }
        }
        Map<String, Double> fixed = new java.util.LinkedHashMap<>(out);
        for (Map<String, Object> l : (List<Map<String, Object>>) status) {
            if (l.get("ratio") != null) {
                String ref = String.valueOf(l.getOrDefault("ref", l.get("stat")));
                double before = total.applyAsDouble(ref) + fixed.getOrDefault(ref, 0.0);
                out.merge(String.valueOf(l.get("stat")), value(l.get("ratio"), 0) * stacks * before, Double::sum);
            }
        }
        return out;
    }

    /**
     * Minions' move speed at {@code second}: the base, plus one step of {@code tropas.bonus_velocidade} at {@code aos} and
     * one more every {@code a_cada} seconds, the last at {@code ate}.
     */
    public double minionSpeed(double second) {
        double base = num(325, "tropas", "velocidade");
        Map<String, Object> bonus = map("tropas", "bonus_velocidade");
        if (bonus == null || second < value(bonus.get("aos"), Double.POSITIVE_INFINITY)) {
            return base;
        }
        double from = value(bonus.get("aos"), 0);
        double every = value(bonus.get("a_cada"), 0);
        double last = Math.min(second, value(bonus.get("ate"), from));
        int steps = 1 + (every > 0 ? (int) Math.floor((last - from) / every + 1e-9) : 0);
        return base + steps * value(bonus, 0);
    }

    /** Seconds from a wave at {@code second} to the next. */
    public double waveGap(double second) {
        Map<String, Object> fast = map("tropas", "intervalo_rapido");
        boolean quick = fast != null && second >= value(fast.get("de"), 0) && second < value(fast.get("ate"), 0);
        return quick ? value(fast.get("valor"), 25) : num(30, "tropas", "intervalo");
    }

    /** The composition of wave {@code n} (from 1), as minion kinds. */
    public List<String> wave(int n, boolean superMinions) {
        List<String> out = new java.util.ArrayList<>();
        Map<String, Object> w = map("tropas", "onda");
        for (int i = 0; i < value(w.get("combatente"), 3); i++) {
            out.add("combatente");
        }
        for (int i = 0; i < value(w.get("conjuradora"), 3); i++) {
            out.add("conjuradora");
        }
        if (n % (int) num(3, "tropas", "canhao_a_cada") == 0) {
            out.add("canhao");
        }
        if (superMinions) {
            out.add("super");
        }
        return out;
    }

    /** Attack damage of a tower of a kind at a second of the match. */
    public double towerDamage(Map<String, Object> t, double second) {
        double ad = value(t.get("dda"), 200);
        if (t.get("dda_ate") != null) {
            double top = value(t.get("dda_ate"), ad);
            return ad + (top - ad) * Math.min(1, second / value(t.get("dda_em"), 1050));
        }
        double from = value(t.get("dda_de"), 0);
        double passes = Math.floor(Math.max(0, second - from) / value(t.get("dda_a_cada"), 30));
        return ad + value(t.get("dda_passo"), 0) * Math.min(passes, value(t.get("dda_passos"), 1000));
    }
}
