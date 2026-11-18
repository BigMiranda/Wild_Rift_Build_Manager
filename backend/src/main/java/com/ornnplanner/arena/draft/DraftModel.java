package com.ornnplanner.arena.draft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rodada 20, Parte 3: the draft's note of each candidate, pure (no Spring, deterministic). For the side that picks next:
 * meta (presence, bans and wins in the lane, when imported), free lane, lane matchup against the revealed or predicted
 * rival, what the candidate adds to the team's composition, and the risk of a counter-pick by the other side's
 * available champions. Every part goes from -1 to 1 and the note is their sum by the seed's weights. The reasons are
 * keys with values (the screen words them in pt or en). No rule names a champion.
 */
public final class DraftModel {

    /** What the draft knows of a champion (from ChampionProfiles and the sheets). */
    public static class Champ {
        public String name;
        /** In the seed's order: the first is its main lane. */
        public List<String> lanes = new ArrayList<>();
        public List<String> subclasses = new ArrayList<>();
        public List<String> classes = new ArrayList<>();
        public double physical;
        public double magic;
        public boolean frontline;
        public boolean control;
        public boolean initiation;
        public boolean longRange;
        public boolean healShield;
        /** +1 strong early (fighters, assassins), -1 late (marksmen, mages), 0 neither. */
        public double early;
        /** log of a duel power proxy (damage per second x effective health at level 15). */
        public double logPower;
    }

    public static class Meta {
        public double presence;
        public double ban;
        public double win;
    }

    /** A measured lane advantage of a over b, -1..1 (null = not measured). */
    public interface Matchup {
        Double advantage(String a, String b, String lane);
    }

    public static class Weights {
        public double meta = 0.25;
        public double lane = 0.35;
        public double matchup = 0.30;
        public double composition = 0.30;
        public double counter = 0.20;
        public double metaPresence = 0.4;
        public double metaBan = 0.2;
        public double metaWin = 0.4;
        public double metaWinScale = 0.05;
        public double magicMin = 0.30;
        public double physicalMin = 0.30;
        public int frontline = 1;
        public int control = 2;
        public int initiation = 1;
        public int longRange = 1;
        public int healShield = 1;
        public double scaling = 0.15;
        /** Calibrated multipliers of the composition needs (feature -> weight); empty = all 1. */
        public Map<String, Double> calibration = new LinkedHashMap<>();
        public int beam = 5;
    }

    /** A champion on the board: picked, or predicted (fixed by the user, or a branch of the tree). */
    public static class Pick {
        public String side;
        public String champion;
        public String lane;
        public boolean predicted;

        public Pick() {
        }

        public Pick(String side, String champion, String lane, boolean predicted) {
            this.side = side;
            this.champion = champion;
            this.lane = lane;
            this.predicted = predicted;
        }
    }

    public static class Board {
        public List<Pick> picks = new ArrayList<>();
        /** Banned, and the Fearless series (per side: the scope may differ). */
        public Map<String, Set<String>> unavailable = new LinkedHashMap<>();

        public List<Pick> of(String side) {
            List<Pick> out = new ArrayList<>();
            for (Pick p : picks) {
                if (p.side.equals(side)) {
                    out.add(p);
                }
            }
            return out;
        }

        public Board with(Pick p) {
            Board b = new Board();
            b.picks.addAll(picks);
            b.picks.add(p);
            b.unavailable = unavailable;
            return b;
        }
    }

    /** One reason of a note: a key the screen words, with its values. */
    public static class Reason {
        public String key;
        public Map<String, Object> vars = new LinkedHashMap<>();

        public Reason(String key, Object... kv) {
            this.key = key;
            for (int i = 0; i + 1 < kv.length; i += 2) {
                vars.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        }
    }

    public static class Scored {
        public String champion;
        public String lane;
        /** The note, x 100. */
        public double score;
        public Map<String, Double> parts = new LinkedHashMap<>();
        public List<Reason> reasons = new ArrayList<>();
    }

    private final Map<String, Champ> champs = new LinkedHashMap<>();
    private final Map<String, Map<String, Meta>> meta;
    private final Matchup measured;
    private final Weights w;
    private final List<String> lanes;
    private final Map<String, Double> subclassPower = new LinkedHashMap<>();
    /** How many children the tree has computed (the test checks the beam). */
    public int expanded;

    public DraftModel(List<Champ> champions, Map<String, Map<String, Meta>> meta, Matchup measured, Weights w, List<String> lanes) {
        champions.forEach(c -> champs.put(c.name, c));
        this.meta = meta == null ? Map.of() : meta;
        this.measured = measured;
        this.w = w;
        this.lanes = lanes;
        Map<String, double[]> acc = new LinkedHashMap<>();
        for (Champ c : champions) {
            for (String s : c.subclasses) {
                double[] a = acc.computeIfAbsent(s, k -> new double[2]);
                a[0] += c.logPower;
                a[1]++;
            }
        }
        acc.forEach((s, a) -> subclassPower.put(s, a[0] / a[1]));
    }

    public Champ champ(String name) {
        return champs.get(name);
    }

    /** The lanes a side has not filled yet (picks and predictions). */
    public List<String> openLanes(Board b, String side) {
        Set<String> taken = new HashSet<>();
        for (Pick p : b.of(side)) {
            taken.add(p.lane != null ? p.lane : mainLane(p.champion));
        }
        List<String> out = new ArrayList<>();
        for (String l : lanes) {
            if (!taken.contains(l)) {
                out.add(l);
            }
        }
        return out;
    }

    private String mainLane(String champion) {
        Champ c = champs.get(champion);
        return c == null || c.lanes.isEmpty() ? null : c.lanes.get(0);
    }

    /** The lane advantage of a over b: measured, else the subclass against the subclass by the profile. */
    public double advantage(String a, String b, String lane) {
        Double m = measured == null ? null : measured.advantage(a, b, lane);
        if (m != null) {
            return clamp(m);
        }
        Champ x = champs.get(a);
        Champ y = champs.get(b);
        if (x == null || y == null) {
            return 0;
        }
        double px = x.subclasses.isEmpty() ? x.logPower : subclassPower.getOrDefault(x.subclasses.get(0), x.logPower);
        double py = y.subclasses.isEmpty() ? y.logPower : subclassPower.getOrDefault(y.subclasses.get(0), y.logPower);
        return clamp((px - py) / Math.log(3));
    }

    public boolean measured(String a, String b, String lane) {
        return measured != null && measured.advantage(a, b, lane) != null;
    }

    /** The k best candidates for a side's next pick, best first. */
    public List<Scored> rank(Board b, String side, int k) {
        String other = LiveGameModel.BLUE.equals(side) ? LiveGameModel.RED : LiveGameModel.BLUE;
        List<String> open = openLanes(b, side);
        Set<String> taken = new HashSet<>();
        b.picks.forEach(p -> taken.add(p.champion));
        Set<String> off = new HashSet<>(b.unavailable.getOrDefault(side, Set.of()));
        List<Scored> out = new ArrayList<>();
        if (open.isEmpty()) {
            return out;
        }
        for (Champ c : champs.values()) {
            if (taken.contains(c.name) || off.contains(c.name)) {
                continue;
            }
            String lane = null;
            double laneFit = 0;
            for (int i = 0; i < c.lanes.size(); i++) {
                if (open.contains(c.lanes.get(i))) {
                    lane = c.lanes.get(i);
                    laneFit = i == 0 ? 1 : 0.4;
                    break;
                }
            }
            if (lane == null) {
                continue;   // a full lane gets no candidates
            }
            out.add(score(b, side, other, c, lane, laneFit, taken, off));
        }
        out.sort(Comparator.comparingDouble((Scored s) -> -s.score).thenComparing(s -> s.champion));
        return new ArrayList<>(out.subList(0, Math.min(k, out.size())));
    }

    /** The bans: the other side's best candidates, the ones that threaten this side most. */
    public List<Scored> threats(Board b, String side, int k) {
        String other = LiveGameModel.BLUE.equals(side) ? LiveGameModel.RED : LiveGameModel.BLUE;
        return rank(b, other, k);
    }

    private Scored score(Board b, String side, String other, Champ c, String lane, double laneFit, Set<String> taken, Set<String> off) {
        Scored s = new Scored();
        s.champion = c.name;
        s.lane = lane;
        s.reasons.add(new Reason(laneFit >= 1 ? "rotaAberta" : "rotaSecundaria", "lane", lane));
        // meta
        Meta m = meta.getOrDefault(c.name, Map.of()).get(lane);
        double pMeta = 0;
        if (m != null) {
            pMeta = w.metaPresence * Math.min(1, m.presence / 0.2) + w.metaBan * Math.min(1, m.ban / 0.2)
                    + w.metaWin * clamp((m.win - 0.5) / w.metaWinScale);
            s.reasons.add(new Reason("presenca", "pct", round(m.presence * 100), "win", round(m.win * 100)));
        }
        // lane matchup
        double pMatch = 0;
        Pick rival = null;
        for (Pick p : b.of(other)) {
            String pl = p.lane != null ? p.lane : mainLane(p.champion);
            if (lane.equals(pl)) {
                rival = p;
            }
        }
        if (rival != null) {
            pMatch = advantage(c.name, rival.champion, lane);
            if (Math.abs(pMatch) >= 0.1) {
                s.reasons.add(new Reason(pMatch > 0 ? "ganhaRota" : "perdeRota", "champ", rival.champion, "predicted", rival.predicted,
                        "measured", measured(c.name, rival.champion, lane)));
            }
        }
        // what it punishes among the other side's picks (outside its lane too)
        for (Pick p : b.of(other)) {
            if (p != rival && advantage(c.name, p.champion, lane) >= 0.5) {
                s.reasons.add(new Reason("pune", "champ", p.champion, "predicted", p.predicted));
                break;
            }
        }
        // composition
        double pComp = composition(b.of(side), c, s);
        // counter-pick risk: the other side's available champions that play this lane
        double pCounter = 0;
        if (rival == null && openLanes(b, other).contains(lane)) {
            List<double[]> risks = new ArrayList<>();
            List<String> names = new ArrayList<>();
            Set<String> otherOff = b.unavailable.getOrDefault(other, Set.of());
            for (Champ o : champs.values()) {
                if (o.name.equals(c.name) || taken.contains(o.name) || otherOff.contains(o.name) || !o.lanes.contains(lane)) {
                    continue;
                }
                double a = advantage(o.name, c.name, lane);
                if (a > 0) {
                    risks.add(new double[] {a, names.size()});
                    names.add(o.name);
                }
            }
            risks.sort(Comparator.comparingDouble((double[] x) -> -x[0]).thenComparingDouble(x -> x[1]));
            double sum = 0;
            int n = Math.min(3, risks.size());
            for (int i = 0; i < n; i++) {
                sum += risks.get(i)[0];
            }
            pCounter = n == 0 ? 0 : -sum / 3;
            if (pCounter <= -0.3) {
                s.reasons.add(new Reason("risco", "champ", names.get((int) risks.get(0)[1])));
            }
        }
        s.parts.put("meta", pMeta);
        s.parts.put("rota", laneFit);
        s.parts.put("matchup", pMatch);
        s.parts.put("composicao", pComp);
        s.parts.put("contra", pCounter);
        s.score = 100 * (w.meta * pMeta + w.lane * laneFit + w.matchup * pMatch + w.composition * pComp + w.counter * pCounter);
        return s;
    }

    private double cal(String feature) {
        return w.calibration.getOrDefault(feature, 1.0);
    }

    /** What the candidate adds to its team (-1..1), with the reasons of the needs it fills. */
    double composition(List<Pick> team, Champ c, Scored s) {
        List<Champ> mates = new ArrayList<>();
        for (Pick p : team) {
            Champ x = champs.get(p.champion);
            if (x != null) {
                mates.add(x);
            }
        }
        int n = mates.size();
        double magic = 0;
        double physical = 0;
        int front = 0;
        int ctrl = 0;
        int init = 0;
        int range = 0;
        int sustain = 0;
        double early = 0;
        for (Champ x : mates) {
            magic += x.magic;
            physical += x.physical;
            front += x.frontline ? 1 : 0;
            ctrl += x.control ? 1 : 0;
            init += x.initiation ? 1 : 0;
            range += x.longRange ? 1 : 0;
            sustain += x.healShield ? 1 : 0;
            early += x.early;
        }
        double v = 0;
        // damage balance: what the team's damage would be with the candidate
        double magicShare = (magic + c.magic) / (n + 1);
        double physShare = (physical + c.physical) / (n + 1);
        if (n > 0 && magic / n < w.magicMin && c.magic >= 0.5) {
            v += cal("dano_magico");
            s.reasons.add(new Reason("falta", "need", "dano_magico"));
        } else if (n >= 2 && magicShare < w.magicMin * 0.5 && n + 1 >= 4) {
            v -= 0.5 * cal("dano_magico");
        }
        if (n > 0 && physical / n < w.physicalMin && c.physical >= 0.5) {
            v += cal("dano_fisico");
            s.reasons.add(new Reason("falta", "need", "dano_fisico"));
        } else if (n >= 2 && physShare < w.physicalMin * 0.5 && n + 1 >= 4) {
            v -= 0.5 * cal("dano_fisico");
        }
        v += need(front, w.frontline, c.frontline, "linha_de_frente", s);
        v += need(ctrl, w.control, c.control, "controle", s);
        v += need(init, w.initiation, c.initiation, "iniciacao", s);
        v += need(range, w.longRange, c.longRange, "alcance_longo", s);
        v += need(sustain, w.healShield, c.healShield, "cura_escudo", s);
        if (n > 0) {
            double mean = early / n;
            v += w.scaling * cal("escalonamento") * (-Math.signum(mean) * c.early) * Math.min(1, Math.abs(mean) * 2);
        }
        return clamp(v / 2);
    }

    private double need(int have, int want, boolean gives, String feature, Scored s) {
        if (!gives || have >= want) {
            return 0;
        }
        s.reasons.add(new Reason("falta", "need", feature));
        return 0.6 * cal(feature) * (want - have) / Math.max(1, want);
    }

    // ------------------------------------------------------------------ the tree of scenarios

    /** A node of the tree: the picks along its branch, the side that picks next and its children (computed on demand). */
    public static class Node {
        public List<Pick> path = new ArrayList<>();
        public String side;
        public List<Scored> children = new ArrayList<>();
    }

    /**
     * The children of a branch: the board with the branch's picks, the side picking next by the order, and only its
     * {@code beam} best candidates (never the whole tree; a child expands when the screen asks for it).
     */
    public Node expand(Board base, List<Pick> path, List<String> pickOrder) {
        Board b = base;
        for (Pick p : path) {
            b = b.with(p);
        }
        Node n = new Node();
        n.path = new ArrayList<>(path);
        n.side = nextSide(b, pickOrder);
        if (n.side != null) {
            n.children = rank(b, n.side, w.beam);
            expanded += n.children.size();
        }
        return n;
    }

    /** The side of the first pick of the order not made yet (null = both teams have five). */
    public static String nextSide(Board b, List<String> pickOrder) {
        Map<String, Integer> made = new LinkedHashMap<>();
        for (Pick p : b.picks) {
            made.merge(p.side, 1, Integer::sum);
        }
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (String side : pickOrder) {
            int k = seen.merge(side, 1, Integer::sum);
            if (made.getOrDefault(side, 0) < k) {
                return side;
            }
        }
        return null;
    }

    static double clamp(double v) {
        return Math.max(-1, Math.min(1, v));
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** For tests: the names of a ranking. */
    public static List<String> names(List<Scored> l) {
        List<String> out = new ArrayList<>();
        l.forEach(s -> out.add(s.champion));
        return out;
    }

    public static Set<String> set(String... names) {
        return new LinkedHashSet<>(List.of(names));
    }
}
