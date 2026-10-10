package com.ornnplanner.seed;

import com.ornnplanner.engine.Model.RefScope;
import com.ornnplanner.engine.Model.StatLine;
import com.ornnplanner.engine.TimelineEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * Modeled effects shared by champions ({@code campeoes_<patch>.yml}, block {@code efeitos}) and items
 * ({@code efeitos_itens_<patch>.yml}): a group of stat lines with the build's choices (on / off, an option, a stack
 * rate). Lines: {@code tipo} (shop stat name), {@code valor} (+ {@code valor_nv15}: range by champion level) or
 * {@code ratio} + {@code ref} + {@code escopo}; {@code valor_por_opcao} / {@code ratio_por_opcao} are added x option.
 * Values given as lists are per ability rank (champions; items are always rank 1).
 */
public final class EffectSpec {

    /** Build option of an effect (health lost, stacks, allies nearby...). */
    public static class Option {
        public String name;
        public int min;
        public int max;
        public int defaultValue;
    }

    /** Stack rate of an effect that grows without limit (souls, stacks, kills): the build gives stacks per minute. */
    public static class Rate {
        public String name;
        /** First line's stat and value of one stack (rank 1), for the rate editor's estimate. */
        public String stat;
        public double perStack;
    }

    /** A modeled passive / ability / item effect as the planner shows it. */
    public static class Effect {
        public String name;
        /** P, 1, 2, 3 or R (champions); null for items. */
        public String ability;
        /** Item passive the effect models (items): the shop's passive name, so the passive counts as "known". */
        public String passive;
        /** Some lines only count when the build switches them on. */
        public boolean hasConditional;
        /** Conditional lines on unless the build says otherwise. */
        public boolean defaultOn;
        public Option option;
        public Rate rate;
        public String note;
        /** Lines in words ("+25/30/35/40% da Armadura total"). */
        public List<String> summary = new ArrayList<>();
        transient List<Map<String, Object>> lines = new ArrayList<>();
    }

    private final Map<String, String> statCodes;

    EffectSpec(Map<String, String> statCodes) {
        this.statCodes = statCodes;
    }

    @SuppressWarnings("unchecked")
    Effect parse(String name, Map<String, Object> e) {
        Effect f = new Effect();
        f.name = name;
        f.ability = e.get("habilidade") == null ? null : String.valueOf(e.get("habilidade"));
        f.passive = e.get("passiva") == null ? null : String.valueOf(e.get("passiva"));
        f.defaultOn = Boolean.TRUE.equals(e.get("padrao"));
        f.note = e.get("nota") == null ? null : String.valueOf(e.get("nota"));
        boolean groupCond = Boolean.TRUE.equals(e.get("condicional"));
        for (Map<String, Object> l : (List<Map<String, Object>>) e.get("linhas")) {
            Map<String, Object> line = new LinkedHashMap<>(l);
            if (groupCond) {
                line.put("condicional", true);
            }
            f.lines.add(line);
            f.summary.add(describe(line));
        }
        f.hasConditional = f.lines.stream().anyMatch(l -> Boolean.TRUE.equals(l.get("condicional")));
        Map<String, Object> o = (Map<String, Object>) e.get("opcao");
        if (o != null) {
            f.option = new Option();
            f.option.name = String.valueOf(o.get("nome"));
            f.option.min = ((Number) o.get("min")).intValue();
            f.option.max = ((Number) o.get("max")).intValue();
            f.option.defaultValue = ((Number) o.get("padrao")).intValue();
        }
        if (e.get("por_minuto") != null) {
            f.rate = new Rate();
            f.rate.name = String.valueOf(e.get("por_minuto"));
            f.rate.stat = stat(f.lines.get(0).get("tipo"));
            f.rate.perStack = first(f.lines.get(0).get("valor") != null ? f.lines.get(0).get("valor") : 0);
        }
        return f;
    }

    /** The option value chosen by the build, clamped to the effect's range (its default when not chosen). */
    static int option(Effect f, Map<String, Integer> options, String key) {
        int opt = f.option == null ? 0 : f.option.defaultValue;
        if (f.option != null && options != null && options.get(key) != null) {
            opt = Math.max(f.option.min, Math.min(f.option.max, options.get(key)));
        }
        return opt;
    }

    /** Valid rate periods {start minute, stacks per minute}, sorted; null when the effect has a rate but no stacks. */
    static List<double[]> periods(Effect f, Map<String, List<double[]>> rates, String key) {
        List<double[]> periods = new ArrayList<>();
        if (f.rate == null) {
            return periods;
        }
        if (rates == null || rates.get(key) == null) {
            return null;
        }
        for (double[] p : rates.get(key)) {
            if (p != null && p.length >= 2 && p[0] >= 0 && p[1] >= 0) {
                periods.add(new double[] {p[0], p[1]});
            }
        }
        periods.sort(java.util.Comparator.comparingDouble(p -> p[0]));
        return periods.stream().anyMatch(p -> p[1] > 0) ? periods : null;
    }

    /** Engine lines of an effect: values by level from {@code rankAt} (rank of the effect's ability at a level). */
    List<StatLine> lines(Effect f, IntUnaryOperator rankAt, int opt, List<double[]> periods, String passive) {
        List<StatLine> out = new ArrayList<>();
        for (Map<String, Object> l : f.lines) {
            out.add(line(f, l, rankAt, opt, periods, passive));
        }
        return out;
    }

    private StatLine line(Effect f, Map<String, Object> l, IntUnaryOperator rankAt, int opt, List<double[]> periods,
                          String passive) {
        StatLine s = new StatLine();
        s.type = stat(l.get("tipo"));
        s.passive = passive;
        s.conditional = Boolean.TRUE.equals(l.get("condicional"));
        s.refPre = Boolean.TRUE.equals(l.get("ref_pre"));
        boolean ratio = l.get("ratio") != null;
        List<Double> values = new ArrayList<>();
        for (int level = 1; level <= TimelineEngine.MAX_LEVEL; level++) {
            int rank = rankAt.applyAsInt(level);
            double v = 0;
            if (rank > 0) {
                if (ratio) {
                    v = at(l.get("ratio"), rank, level, l.get("ratio_nv15"))
                            + at(l.get("ratio_por_opcao"), rank, level, null) * opt;
                } else {
                    v = at(l.get("valor"), rank, level, l.get("valor_nv15"))
                            + at(l.get("valor_por_opcao"), rank, level, l.get("valor_por_opcao_nv15")) * opt;
                }
            }
            values.add(v);
        }
        if (ratio) {
            s.levelRatios = values;
            s.ratio = values.get(values.size() - 1);
            s.refType = stat(l.get("ref"));
            Object scope = l.get("escopo");
            s.refScope = "bonus".equals(scope) ? RefScope.BONUS : "base".equals(scope) ? RefScope.BASE : RefScope.TOTAL;
        } else {
            s.levelValues = values;
            s.value = 0.0;
        }
        if (f.rate != null) {
            s.perMinute = periods;   // a ratio line grows with the stacks too (e.g. Coração de Aço)
        }
        return s;
    }

    /** A value at a rank (lists are per rank) or, with a level-15 value, interpolated by the champion level. */
    @SuppressWarnings("unchecked")
    private static double at(Object v, int rank, int level, Object atMax) {
        if (v == null) {
            return 0;
        }
        if (v instanceof List) {
            List<Object> list = (List<Object>) v;
            return num(list.get(Math.max(1, Math.min(list.size(), rank)) - 1));
        }
        double a = num(v);
        return atMax == null ? a : a + (num(atMax) - a) * (level - 1) / 14.0;
    }

    @SuppressWarnings("unchecked")
    private static double first(Object v) {
        return v instanceof List ? num(((List<Object>) v).get(0)) : v == null ? 0 : num(v);
    }

    String stat(Object shopName) {
        String s = String.valueOf(shopName);
        return statCodes.getOrDefault(s, s);
    }

    /** "+25/30/35/40% da Armadura total", "+18/20/22/24 Armadura", "Vida Máxima: −75% do adicional"... */
    private static String describe(Map<String, Object> l) {
        String tipo = String.valueOf(l.get("tipo"));
        StringBuilder b = new StringBuilder();
        if (l.get("ratio") != null) {
            String scope = "bonus".equals(l.get("escopo")) ? " adicional" : "base".equals(l.get("escopo")) ? " base" : "";
            String ref = String.valueOf(l.get("ref"));
            b.append(tipo.equals(ref) ? "" : tipo + ": ");
            if (l.get("ratio_por_opcao") != null && num(l.get("ratio")) == 0) {
                b.append(signed(l.get("ratio_por_opcao"), 100)).append("% de ").append(ref)
                        .append(scope.isEmpty() ? " total" : scope).append(" por ponto da opção");
            } else {
                b.append(signed(l.get("ratio"), 100));
                if (l.get("ratio_nv15") != null) {
                    b.append("–").append(fmt(num(l.get("ratio_nv15")) * 100));
                }
                b.append("% de ").append(ref).append(scope.isEmpty() ? " total" : scope);
                if (l.get("ratio_por_opcao") != null) {
                    b.append(" ").append(signed(l.get("ratio_por_opcao"), 100)).append("% por ponto da opção");
                }
            }
            if (Boolean.TRUE.equals(l.get("ref_pre"))) {
                b.append(" (só o adicional de itens e runas)");
            }
        } else {
            boolean pct = tipo.startsWith("% ");
            String name = pct ? tipo.substring(2) : tipo;
            String unit = pct ? "%" : "";
            if (l.get("valor_por_opcao") != null && (l.get("valor") == null || num(l.get("valor")) == 0)) {
                b.append(signed(l.get("valor_por_opcao"), 1));
                if (l.get("valor_por_opcao_nv15") != null) {
                    b.append("–").append(fmt(num(l.get("valor_por_opcao_nv15"))));
                }
                b.append(unit).append(" ").append(name).append(" por ponto da opção");
            } else if (l.get("valor_nv15") != null) {
                b.append(signed(l.get("valor"), 1)).append("–").append(fmt(num(l.get("valor_nv15")))).append(unit).append(" ")
                        .append(name).append(" (nível)");
            } else {
                b.append(signed(l.get("valor"), 1)).append(unit).append(" ").append(name);
            }
            if (l.get("valor_por_opcao") != null && l.get("valor") != null && num(l.get("valor")) != 0) {
                b.append(" ").append(signed(l.get("valor_por_opcao"), 1)).append(unit).append(" por ponto da opção");
            }
        }
        if (Boolean.TRUE.equals(l.get("condicional"))) {
            b.append(" (condicional)");
        }
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static String signed(Object v, double scale) {
        List<Object> list = v instanceof List ? (List<Object>) v : List.of(v);
        StringBuilder b = new StringBuilder(num(list.get(0)) < 0 ? "−" : "+");
        for (int i = 0; i < list.size(); i++) {
            b.append(i > 0 ? "/" : "").append(fmt(Math.abs(num(list.get(i)) * scale)));
        }
        return b.toString();
    }

    private static String fmt(double v) {
        return Math.abs(v - Math.rint(v)) < 1e-6 ? String.valueOf((long) Math.rint(v))
                : String.format(java.util.Locale.ROOT, "%.2f", v).replaceAll("0+$", "").replace('.', ',');
    }

    static double num(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : Double.parseDouble(String.valueOf(o));
    }
}
