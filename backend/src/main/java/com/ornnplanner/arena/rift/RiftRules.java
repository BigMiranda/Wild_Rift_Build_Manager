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
