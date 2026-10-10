package com.ornnplanner.combat;

import com.ornnplanner.engine.Stats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Damage of a champion's abilities read from the game's texts (same reading as the frontend's abilityMath.js): the
 * first "N de Dano Físico/Mágico/Verdadeiro (formula)" of each ability, with the formula's numbers and ratios taken
 * from the rank table ("Dano base: 40, 80, 120, 160", "DdA: 25%, 30%...") and "Nx" after it as the number of hits.
 * Damage based on the target's health, or a formula the reader does not understand, is left out (the ability then
 * counts as utility in the fight).
 */
public final class AbilityParser {
    private AbilityParser() {
    }

    /** Text markers -> stat code. */
    static final Map<String, String> MARKER_STAT = new LinkedHashMap<>();

    static {
        MARKER_STAT.put("DdA", Stats.ATTACK_DAMAGE);
        MARKER_STAT.put("PdH", Stats.ABILITY_POWER);
        MARKER_STAT.put("Arm", Stats.ARMOR);
        MARKER_STAT.put("RM", Stats.MAGIC_RESIST);
        MARKER_STAT.put("Vida", Stats.MAX_HEALTH);
        MARKER_STAT.put("Mana", Stats.MAX_MANA);
        MARKER_STAT.put("VdM", Stats.MOVE_SPEED);
        MARKER_STAT.put("Crit", "% Critical Rate");
        MARKER_STAT.put("VdA", "% Attack Speed");
        MARKER_STAT.put("Let", "Armor Penetration");
    }

    /** One term of a damage formula: a constant, or ratio x stat (total or bonus), each by ability rank. */
    public static class Term {
        public double[] byRank;
        /** Null for a constant. */
        public String stat;
        public boolean bonus;
        public String marker;
    }

    /** Damage of an ability: sum of its terms at a rank, times the hits. */
    public static class Damage {
        /** fisico | magico | verdadeiro */
        public String type;
        public List<Term> terms = new ArrayList<>();
        public int hits = 1;
        /** The text the damage was read from (for the report). */
        public String source;

        public double at(int rank, Map<String, Double> total, Map<String, Double> base) {
            double v = 0;
            for (Term t : terms) {
                double r = t.byRank[Math.max(0, Math.min(t.byRank.length, rank) - 1)];
                if (t.stat == null) {
                    v += r;
                } else {
                    double amount = total.getOrDefault(t.stat, 0.0) - (t.bonus ? base.getOrDefault(t.stat, 0.0) : 0);
                    v += r * amount;
                }
            }
            return v * hits;
        }
    }

    /** An ability as the fight uses it. */
    public static class Ability {
        public String slot;
        public String name;
        /** Cooldown by rank (s, before haste). */
        public double[] cooldown;
        /** Null when no damage could be read. */
        public Damage damage;
    }

    private static final String NUM = "\\d+(?:\\.\\d{3})*(?:,\\d+)?";
    private static final Pattern FORMULA = Pattern.compile(
            "(" + NUM + ")(%?)([^()\\d{}]{0,60}?)\\(([^()]*\\{[^()]*)\\)(\\s*(\\d+)x)?");
    private static final Pattern PLAIN = Pattern.compile("(" + NUM + ")(%?) de (Dano (?:Físico|Mágico|Verdadeiro))");
    private static final Pattern EQUAL = Pattern.compile("(Dano (?:Físico|Mágico|Verdadeiro)) (?:igual|equivalente) a (" + NUM + ")(?!\\s*%| ?mais \\d+%)");
    private static final Pattern TYPE = Pattern.compile("Dano (Físico|Mágico|Verdadeiro)");

    /** The 4 abilities (1, 2, 3, R) of a champion entry of the seed file. */
    @SuppressWarnings("unchecked")
    public static List<Ability> abilities(Map<String, Object> champion) {
        List<Ability> out = new ArrayList<>();
        List<Map<String, Object>> list = (List<Map<String, Object>>) champion.getOrDefault("habilidades", List.of());
        String[] slots = {"1", "2", "3", "R"};
        for (int i = 0; i < Math.min(4, list.size()); i++) {
            Map<String, Object> a = list.get(i);
            Ability ab = new Ability();
            ab.slot = slots[i];
            ab.name = String.valueOf(a.get("nome"));
            Map<String, Object> table = (Map<String, Object>) a.getOrDefault("niveis", Map.of());
            ab.cooldown = cooldown(a, table);
            ab.damage = damage(String.valueOf(a.getOrDefault("texto", "")), table);
            out.add(ab);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static double[] cooldown(Map<String, Object> a, Map<String, Object> table) {
        for (Map.Entry<String, Object> e : table.entrySet()) {
            if (e.getKey().startsWith("Tempo de Recarga") && e.getValue() instanceof List) {
                List<Object> v = (List<Object>) e.getValue();
                double[] out = new double[v.size()];
                for (int i = 0; i < v.size(); i++) {
                    out[i] = parseNum(String.valueOf(v.get(i)));
                }
                return out;
            }
        }
        Object r = a.get("recarga");
        return new double[] {r == null ? 10 : parseNum(String.valueOf(r))};
    }

    /** First damage of the text, or null. */
    static Damage damage(String text, Map<String, Object> table) {
        List<Object[]> rows = rows(table);
        Matcher m = FORMULA.matcher(text);
        int firstFormula = Integer.MAX_VALUE;
        Damage found = null;
        while (m.find()) {
            String type = typeNear(text, m.start(), m.end(), m.group(3));
            if (type == null || !m.group(2).isEmpty() || m.group(4).contains(" - ")) {
                continue;
            }
            List<Term> terms = new ArrayList<>();
            boolean ok = true;
            for (String raw : m.group(4).split(" \\+ ")) {
                Term t = term(raw.trim(), rows);
                if (t == null) {
                    ok = false;
                    break;
                }
                terms.add(t);
            }
            if (!ok) {
                continue;
            }
            found = new Damage();
            found.type = type;
            found.terms = terms;
            found.hits = m.group(6) == null ? 1 : Integer.parseInt(m.group(6));
            found.source = m.group();
            firstFormula = m.start();
            break;
        }
        // A plain "N de Dano X" (or "Dano X igual a N") before the first formula wins.
        for (Pattern p : List.of(PLAIN, EQUAL)) {
            Matcher q = p.matcher(text);
            while (q.find() && q.start() < firstFormula) {
                boolean plain = p == PLAIN;
                if (plain && !q.group(2).isEmpty()) {
                    continue;
                }
                String num = plain ? q.group(1) : q.group(2);
                String type = typeOf(plain ? q.group(3) : q.group(1));
                Damage d = new Damage();
                d.type = type;
                Term t = new Term();
                t.byRank = byRank(rows, parseNum(num), false, null);
                d.terms.add(t);
                d.source = q.group();
                found = d;
                firstFormula = q.start();
                break;
            }
        }
        return found;
    }

    private static String typeNear(String text, int start, int end, String between) {
        Matcher t = TYPE.matcher(between);
        if (t.find()) {
            return typeOf(t.group());
        }
        // "causando Dano Mágico igual a 80 (...)": the type right before the number
        String before = text.substring(Math.max(0, start - 30), start);
        Matcher b = TYPE.matcher(before);
        String last = null;
        while (b.find()) {
            last = b.group();
        }
        if (last != null && before.substring(before.lastIndexOf(last)).matches("Dano \\S+ (?:igual|equivalente) a\\s*")) {
            return typeOf(last);
        }
        return null;
    }

    private static String typeOf(String s) {
        return s.contains("Físico") ? "fisico" : s.contains("Mágico") ? "magico" : "verdadeiro";
    }

    private static final Pattern RATIO = Pattern.compile(
            "^(" + NUM + ")%\\s*(?:(?:d[aoe]s?|de)\\s+)?(adicion\\w*\\s*)?\\{(\\w+)\\}\\s*(adicion\\w*)?$");

    private static Term term(String raw, List<Object[]> rows) {
        Term t = new Term();
        if (raw.matches(NUM)) {
            t.byRank = byRank(rows, parseNum(raw), false, null);
            return t;
        }
        Matcher m = RATIO.matcher(raw);
        if (!m.find() || !MARKER_STAT.containsKey(m.group(3)) || raw.contains("{nível}")) {
            return null;
        }
        double[] pct = byRank(rows, parseNum(m.group(1)), true, m.group(3));
        t.byRank = new double[pct.length];
        for (int i = 0; i < pct.length; i++) {
            t.byRank[i] = pct[i] / 100;
        }
        t.marker = m.group(3);
        t.stat = MARKER_STAT.get(m.group(3));
        t.bonus = m.group(2) != null || m.group(4) != null;
        return t;
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> rows(Map<String, Object> table) {
        List<Object[]> rows = new ArrayList<>();
        table.forEach((label, v) -> rows.add(new Object[] {label, v instanceof List ? v : List.of(v)}));
        return rows;
    }

    /** Values of a rank-1 number at every rank, from the first table row starting with it (else the number itself). */
    @SuppressWarnings("unchecked")
    private static double[] byRank(List<Object[]> rows, double value, boolean pct, String labelHint) {
        Object[] chosen = null;
        for (Object[] row : rows) {
            List<Object> vals = (List<Object>) row[1];
            String first = String.valueOf(vals.get(0));
            if (first.contains("%") == pct && Math.abs(parseNum(first) - value) < 1e-6) {
                if (chosen == null || labelHint != null && String.valueOf(row[0]).contains(labelHint)) {
                    chosen = row;
                }
            }
        }
        if (chosen == null) {
            return new double[] {value};
        }
        List<Object> vals = (List<Object>) chosen[1];
        double[] out = new double[vals.size()];
        for (int i = 0; i < vals.size(); i++) {
            out[i] = parseNum(String.valueOf(vals.get(i)));
        }
        return out;
    }

    /** "1.200" -> 1200, "4,5" -> 4.5, also table values like "4.5" / "75%". */
    static double parseNum(String s) {
        String t = s.trim().replace("%", "");
        if (t.matches("\\d+(\\.\\d{3})+(,\\d+)?")) {
            return Double.parseDouble(t.replace(".", "").replace(',', '.'));
        }
        return Double.parseDouble(t.replace(',', '.'));
    }
}
