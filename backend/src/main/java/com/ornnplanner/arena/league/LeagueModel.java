package com.ornnplanner.arena.league;

import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.ArenaTraining;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Rodada 21: the pure part of the league. A {@link Bout} is one fight of the validation bank or of a training pool
 * (who plays, against whom, which fight type, minute, seed and style); a {@link Matrix} is an AI's success per opponent
 * class x fight type next to the tactical rules' in the same fights; {@link #accept} is the rule that lets a new
 * version replace the one in use. No Spring and no fights here: the service builds and plays the bouts.
 */
public final class LeagueModel {

    /** The fight types: a lane duel with minions (full map), an open duel, 2 x 2, and a chase (one side flees). */
    public static final List<String> TYPES = List.of("rota", "aberto", "dupla", "fuga");
    /** The requested styles (Observation schema 4); "padrao" reads as the schema 3 vector did. */
    public static final List<String> STYLES = List.of("padrao", "agressivo", "cauteloso", "enrolar", "fugir", "farmar");
    public static final String DEFAULT_STYLE = "padrao";

    private LeagueModel() {
    }

    /** One fight of the bank or of a training pool. */
    public static class Bout {
        /** The champion the AI plays (team 0, index 0). */
        public String player;
        public String opponent;
        /** The opponent's class in the bank (the matrix's row). */
        public String opponentClass;
        /** rota | aberto | dupla | fuga */
        public String type;
        public double minute;
        /** Index of the seed within its cell (0..). */
        public int seed;
        /** The number every random choice of this fight comes from (builds, jitters). */
        public long sample;
        public String style = DEFAULT_STYLE;
        /** fuga: the player is the one who flees (else it chases). */
        public boolean playerFlees;
        /** fuga: the share of health the one who flees starts with (null = the bank's, 40%). */
        public Double fleeHealth;
        /** dupla: each side's ally. */
        public String myAlly;
        public String theirAlly;
        /** rota: the lane of the full map. */
        public String lane;
        public double duration = 40;
        /** Training: a build drawn off the usual archetype (a likely "wrong" build), and a spell / rune swap. */
        public String archetype;
        public boolean variedLoadout;

        public String key() {
            return String.join("|", player, opponent, type, fmt(minute), String.valueOf(seed), String.valueOf(sample), style,
                    String.valueOf(playerFlees), String.valueOf(myAlly), String.valueOf(theirAlly), String.valueOf(lane),
                    String.valueOf(archetype), String.valueOf(variedLoadout), String.valueOf(fleeHealth));
        }

        @Override
        public String toString() {
            return player + " x " + opponent + " (" + type + ", " + fmt(minute) + " min, semente " + seed + ", " + style + ")";
        }
    }

    /** One cell: an opponent class x a fight type. Success in % of its fights. */
    public static class Cell {
        public String opponentClass;
        public String type;
        public int fights;
        public double success;
        public double tactical;

        public double gap() {
            return success - tactical;
        }
    }

    /** An AI's matrix on a set of bouts. */
    public static class Matrix {
        public List<Cell> cells = new ArrayList<>();
        public int fights;
        /** Success over every fight (%), and the tactical rules'. */
        public double mean;
        public double tacticalMean;
        /** Per fight type and per opponent class (%), the AI's and the tactical's. */
        public Map<String, double[]> byType = new LinkedHashMap<>();
        public Map<String, double[]> byClass = new LinkedHashMap<>();
        /** The same AI on the scenario it was trained on (null = not measured): success %, the tactical's, fights, name. */
        public Map<String, Object> trainingScenario;

        public Cell cell(String opponentClass, String type) {
            for (Cell c : cells) {
                if (c.opponentClass.equals(opponentClass) && c.type.equals(type)) {
                    return c;
                }
            }
            return null;
        }

        public double gap() {
            return mean - tacticalMean;
        }
    }

    /** The matrix of a set of bouts from each fight's success with the AI and with the tactical rules. */
    public static Matrix matrix(List<Bout> bouts, boolean[] mine, boolean[] tactical) {
        Matrix m = new Matrix();
        Map<String, Cell> cells = new LinkedHashMap<>();
        Map<String, double[]> types = new LinkedHashMap<>();
        Map<String, double[]> classes = new LinkedHashMap<>();
        double s = 0;
        double t = 0;
        for (int i = 0; i < bouts.size(); i++) {
            Bout b = bouts.get(i);
            Cell c = cells.computeIfAbsent(b.opponentClass + "|" + b.type, k -> {
                Cell x = new Cell();
                x.opponentClass = b.opponentClass;
                x.type = b.type;
                return x;
            });
            c.fights++;
            c.success += mine[i] ? 1 : 0;
            c.tactical += tactical[i] ? 1 : 0;
            add(types, b.type, mine[i], tactical[i]);
            add(classes, b.opponentClass, mine[i], tactical[i]);
            s += mine[i] ? 1 : 0;
            t += tactical[i] ? 1 : 0;
        }
        for (Cell c : cells.values()) {
            c.success = 100 * c.success / Math.max(1, c.fights);
            c.tactical = 100 * c.tactical / Math.max(1, c.fights);
            m.cells.add(c);
        }
        m.byType = percent(types);
        m.byClass = percent(classes);
        m.fights = bouts.size();
        m.mean = 100 * s / Math.max(1, bouts.size());
        m.tacticalMean = 100 * t / Math.max(1, bouts.size());
        return m;
    }

    private static void add(Map<String, double[]> m, String k, boolean mine, boolean tactical) {
        double[] v = m.computeIfAbsent(k, x -> new double[3]);
        v[0] += mine ? 1 : 0;
        v[1] += tactical ? 1 : 0;
        v[2]++;
    }

    private static Map<String, double[]> percent(Map<String, double[]> m) {
        Map<String, double[]> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, new double[] {100 * v[0] / Math.max(1, v[2]), 100 * v[1] / Math.max(1, v[2])}));
        return out;
    }

    /** A fight's success for the player: a clean victory; in a chase it flees, reaching the safe zone alive too. */
    public static boolean success(ArenaModel.Result r, Bout b) {
        if (ArenaTraining.cleanWin(r, 0, 0)) {
            return true;
        }
        ArenaModel.FighterStats me = r.fighters.get(0);
        return "fuga".equals(b.type) && b.playerFlees && me.escapedAt != null && me.deathTime == null;
    }

    /** Whether a new version may replace the one in use, by the whole matrix (not only the mean). */
    public static class Acceptance {
        public boolean accepted;
        public String reason;
        /** The new mean minus the incumbent's (points). */
        public double meanGain;
        /** The cell where the new one loses the most (points; positive = no cell worse), and which. */
        public double worstCell;
        public String worstCellKey;
        /** What it was compared with: the version in use, or the tactical rules (no version yet). */
        public String against;
    }

    /**
     * The seed's rule: the new mean above the incumbent's by more than {@code meanAbove} points, and no cell worse than
     * {@code worstCellPoints} points. With no incumbent the new one is compared with the tactical rules in its own
     * fights (each cell's tactical number).
     */
    public static Acceptance accept(Matrix newer, Matrix incumbent, double meanAbove, double worstCellPoints) {
        Acceptance a = new Acceptance();
        a.against = incumbent == null ? "tatico" : "atual";
        double base = incumbent == null ? newer.tacticalMean : incumbent.mean;
        a.meanGain = newer.mean - base;
        a.worstCell = Double.POSITIVE_INFINITY;
        for (Cell c : newer.cells) {
            Cell old = incumbent == null ? null : incumbent.cell(c.opponentClass, c.type);
            double ref = incumbent == null ? c.tactical : old == null ? c.tactical : old.success;
            double d = c.success - ref;
            if (d < a.worstCell) {
                a.worstCell = d;
                a.worstCellKey = c.opponentClass + " · " + c.type;
            }
        }
        if (newer.cells.isEmpty()) {
            a.worstCell = 0;
        }
        boolean meanOk = a.meanGain > meanAbove + 1e-9;
        boolean cellsOk = a.worstCell >= -worstCellPoints - 1e-9;
        a.accepted = meanOk && cellsOk;
        a.reason = a.accepted ? String.format(Locale.ROOT, "média %+.1f pontos, pior célula %+.1f (%s)", a.meanGain, a.worstCell, a.worstCellKey)
                : !meanOk ? String.format(Locale.ROOT, "média %+.1f pontos, não acima de %+.1f do %s", a.meanGain, meanAbove,
                "tatico".equals(a.against) ? "Tático" : "atual")
                : String.format(Locale.ROOT, "a célula %s piora %.1f pontos (limite %.1f)", a.worstCellKey, -a.worstCell, worstCellPoints);
        return a;
    }

    // ------------------------------------------------------------------ the bank

    /** The bank's settings (ia_validacao_&lt;patch&gt;.yml). */
    public static class BankSettings {
        public List<String> classes = new ArrayList<>();
        public int adversaries = 2;
        public int players = 2;
        public List<Double> minutes = new ArrayList<>();
        public List<String> types = new ArrayList<>(TYPES);
        public int seeds = 3;
        public long baseSeed = 2111;
        public Map<String, Double> duration = new LinkedHashMap<>();
        public Map<String, String> style = new LinkedHashMap<>();
        public List<String> lanes = new ArrayList<>(List.of("barao", "meio", "dragao"));
        public int myAllyShift = 3;
        public int theirAllyShift = 5;
    }

    /**
     * The bank's bouts: every player (in turn by seed when there are several, a class AI's rotation) against each
     * adversary of each class, at each minute, in each fight type, with each seed. Deterministic: the same settings and
     * champions give the same bouts.
     */
    public static List<Bout> bank(BankSettings s, Map<String, List<String>> adversaries, List<String> players) {
        List<Bout> out = new ArrayList<>();
        List<String> classes = new ArrayList<>(s.classes);
        for (int ci = 0; ci < classes.size(); ci++) {
            String cls = classes.get(ci);
            List<String> advs = adversaries.getOrDefault(cls, List.of());
            for (int ai = 0; ai < advs.size(); ai++) {
                for (double minute : s.minutes) {
                    for (String type : s.types) {
                        for (int seed = 0; seed < s.seeds; seed++) {
                            Bout b = new Bout();
                            b.player = players.get((ai * s.seeds + seed) % players.size());
                            b.opponent = advs.get(ai);
                            b.opponentClass = cls;
                            b.type = type;
                            b.minute = minute;
                            b.seed = seed;
                            b.sample = mix(s.baseSeed, cls, b.opponent, minute, type, seed);
                            b.style = s.style.getOrDefault(type, DEFAULT_STYLE);
                            b.duration = s.duration.getOrDefault(type, 40.0);
                            b.playerFlees = seed % 2 == 0;
                            b.lane = s.lanes.isEmpty() ? "meio" : s.lanes.get(seed % s.lanes.size());
                            if ("dupla".equals(type)) {
                                b.myAlly = allyOf(adversaries, classes, ci + s.myAllyShift, 0, b.player, b.opponent);
                                b.theirAlly = allyOf(adversaries, classes, ci + s.theirAllyShift, 1, b.player, b.opponent);
                            }
                            out.add(b);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static String allyOf(Map<String, List<String>> adversaries, List<String> classes, int classIndex, int pick,
                                 String... avoid) {
        for (int k = 0; k < classes.size(); k++) {
            List<String> list = adversaries.getOrDefault(classes.get(Math.floorMod(classIndex + k, classes.size())), List.of());
            for (int j = 0; j < list.size(); j++) {
                String c = list.get((pick + j) % list.size());
                if (!List.of(avoid).contains(c)) {
                    return c;
                }
            }
        }
        return null;
    }

    /** A stable number from the parts (no hashCode of unordered things, no identity). */
    public static long mix(long seed, Object... parts) {
        long h = seed * 0x9E3779B97F4A7C15L + 1;
        for (Object p : parts) {
            String s = p instanceof Double ? fmt((Double) p) : String.valueOf(p);
            for (int i = 0; i < s.length(); i++) {
                h = (h ^ s.charAt(i)) * 0x100000001B3L;
            }
            h = Long.rotateLeft(h, 17) ^ 0x5DEECE66DL;
        }
        return h & Long.MAX_VALUE;
    }

    static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }

    /** A random choice weighted by the map's values (insertion order), from rnd. */
    public static String weighted(Map<String, Double> w, Random rnd) {
        double total = 0;
        for (double v : w.values()) {
            total += Math.max(0, v);
        }
        double x = rnd.nextDouble() * total;
        String last = null;
        for (Map.Entry<String, Double> e : w.entrySet()) {
            last = e.getKey();
            x -= Math.max(0, e.getValue());
            if (x < 0) {
                return e.getKey();
            }
        }
        return last;
    }
}
