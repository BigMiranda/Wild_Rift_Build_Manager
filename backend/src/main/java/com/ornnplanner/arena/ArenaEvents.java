package com.ornnplanner.arena;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The structured events of a fighter ({@link ArenaModel.FighterStats#events}): counters by key, generic for every
 * champion.
 *
 * <pre>
 *   marca_aplicada:&lt;mark&gt;              marca_consumida:&lt;mark&gt;:&lt;source&gt;   (ataque, 1, 2, 3, R)
 *   marca_sobrescrita:&lt;mark&gt;           marca_expirou:&lt;mark&gt;
 *   cc_aplicado:&lt;kind&gt;                 cc_encadeado   cc_sobreposto (more than 0.3s wasted)
 *   tempo_controlado (s under control)  controle_causado (s of enemies' control it caused)
 *   habilidade_acertou:&lt;slot&gt;   habilidade_errou:&lt;slot&gt;   reconjuracao_acertou:&lt;slot&gt;   reconjuracao_errou:&lt;slot&gt;
 *   reconjuracao_perdida:&lt;slot&gt;:&lt;why&gt;  (fora de alcance | em controle | ocupado com X | não tentou)
 *   runa_ativou:&lt;rune&gt;   item_ativou:&lt;item&gt;
 * </pre>
 *
 * A mark is Ornn's Brittle, Darius' bleed, Vayne's bolts, K'Sante's marks: whatever the sheet keeps on a target.
 */
public final class ArenaEvents {

    private ArenaEvents() {
    }

    /** The counters in words, for the report: "Frágil: 5 aplicados, 2 consumidos (ataque 1, R 1) · controle: 3,1 s". */
    public static String summary(Map<String, Double> events) {
        if (events == null || events.isEmpty()) {
            return "";
        }
        // marks: name -> {applied, consumed, overwritten, expired} and consumed by source
        Map<String, double[]> marks = new LinkedHashMap<>();
        Map<String, Map<String, Double>> by = new LinkedHashMap<>();
        Map<String, double[]> abilities = new LinkedHashMap<>();   // slot -> {hit, missed, recast hit, recast missed}
        Map<String, Double> lost = new LinkedHashMap<>();
        Map<String, Double> procs = new LinkedHashMap<>();
        double control = 0;
        double chained = 0;
        double overlapped = 0;
        for (Map.Entry<String, Double> e : events.entrySet()) {
            String[] k = e.getKey().split(":", 3);
            double v = e.getValue();
            switch (k[0]) {
                case "marca_aplicada":
                    marks.computeIfAbsent(k[1], x -> new double[4])[0] += v;
                    break;
                case "marca_consumida":
                    marks.computeIfAbsent(k[1], x -> new double[4])[1] += v;
                    by.computeIfAbsent(k[1], x -> new LinkedHashMap<>()).merge(k.length > 2 ? k[2] : "?", v, Double::sum);
                    break;
                case "marca_sobrescrita":
                    marks.computeIfAbsent(k[1], x -> new double[4])[2] += v;
                    break;
                case "marca_expirou":
                    marks.computeIfAbsent(k[1], x -> new double[4])[3] += v;
                    break;
                case "controle_causado":
                    control += v;
                    break;
                case "cc_encadeado":
                    chained += v;
                    break;
                case "cc_sobreposto":
                    overlapped += v;
                    break;
                case "habilidade_acertou":
                    abilities.computeIfAbsent(k[1], x -> new double[4])[0] += v;
                    break;
                case "habilidade_errou":
                    abilities.computeIfAbsent(k[1], x -> new double[4])[1] += v;
                    break;
                case "reconjuracao_acertou":
                    abilities.computeIfAbsent(k[1], x -> new double[4])[2] += v;
                    break;
                case "reconjuracao_errou":
                    abilities.computeIfAbsent(k[1], x -> new double[4])[3] += v;
                    break;
                case "reconjuracao_perdida":
                    lost.merge(k[1] + (k.length > 2 ? " (" + k[2] + ")" : ""), v, Double::sum);
                    break;
                case "runa_ativou":
                case "item_ativou":
                    procs.merge(k[1], v, Double::sum);
                    break;
                default:
                    break;
            }
        }
        List<String> parts = new ArrayList<>();
        marks.forEach((name, m) -> {
            List<String> p = new ArrayList<>();
            p.add(n(m[0]) + (m[0] == 1 ? " aplicado" : " aplicados"));
            if (m[1] > 0) {
                List<String> src = new ArrayList<>();
                by.getOrDefault(name, Map.of()).forEach((s, v) -> src.add(s + " " + n(v)));
                p.add(n(m[1]) + (m[1] == 1 ? " consumido" : " consumidos") + " (" + String.join(", ", src) + ")");
            }
            if (m[2] > 0) {
                p.add(n(m[2]) + (m[2] == 1 ? " sobrescrito" : " sobrescritos"));
            }
            if (m[3] > 0) {
                p.add(n(m[3]) + (m[3] == 1 ? " expirou" : " expiraram"));
            }
            parts.add(name + ": " + String.join(", ", p));
        });
        if (control >= 0.05 || chained > 0 || overlapped > 0) {
            String c = "controle: " + String.format(java.util.Locale.ROOT, "%.1f", control).replace('.', ',') + " s";
            if (chained > 0) {
                c += ", " + n(chained) + (chained == 1 ? " encadeado" : " encadeados");
            }
            if (overlapped > 0) {
                c += ", " + n(overlapped) + (overlapped == 1 ? " sobreposto" : " sobrepostos");
            }
            parts.add(c);
        }
        if (!abilities.isEmpty()) {
            List<String> a = new ArrayList<>();
            abilities.forEach((slot, h) -> {
                String x = slot + " " + n(h[0]) + "/" + n(h[0] + h[1]);
                if (h[2] + h[3] > 0) {
                    x += " (reconj. " + n(h[2]) + "/" + n(h[2] + h[3]) + ")";
                }
                a.add(x);
            });
            parts.add("acertos: " + String.join(", ", a));
        }
        if (!lost.isEmpty()) {
            List<String> l = new ArrayList<>();
            lost.forEach((k, v) -> l.add(k + (v > 1 ? " ×" + n(v) : "")));
            parts.add("reconjuração perdida: " + String.join(", ", l));
        }
        procs.forEach((name, v) -> parts.add(name + " ×" + n(v)));
        return String.join(" · ", parts);
    }

    private static String n(double v) {
        return Math.abs(v - Math.rint(v)) < 1e-9 ? String.valueOf((long) Math.rint(v))
                : String.format(java.util.Locale.ROOT, "%.1f", v).replace('.', ',');
    }
}
