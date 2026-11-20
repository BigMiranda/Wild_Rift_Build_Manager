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
 *
 * <p>Rodada 20.b, Parte 2: picks are free of lanes. Each pick has a set of possible lanes (the seed's and the meta's, the
 * user's, or undefined) and a team's lanes are a distribution ({@link LaneAssignment}: the 120 assignments weighed by
 * each champion's chance in each lane). Everything reads the distribution: the free lane is the chance of the
 * candidate's lanes being open, the matchup is the mean over the likely lane rivals, the meta is the mean over the
 * candidate's likely lanes, the counter-pick risk weighs each lane by the chance of the rival lane still being open.
 *
 * <p>Rodada 20.b, Parte 3: the note understands the order. <b>Flex</b>: a champion with real presence in two or more open
 * lanes is worth more early (it hides the lane and keeps options) and nothing on the team's last pick. <b>Counter-pick
 * risk</b> depends on the position: how much the available champions punish the candidate (the measured matchup, played
 * with the likely builds, plus the mechanics that punish, {@link Punish}) times the other side's picks still to come
 * after this one; the last pick has none. The <b>bans</b> also weigh how much a candidate counter-picks the picks
 * already made.
 */
public final class DraftModel {

    /** What the draft knows of a champion (from ChampionProfiles and the sheets). */
    public static class Champ {
        public String name;
        /** In the seed's order: the first is its main lane. */
        public List<String> lanes = new ArrayList<>();
        /**
         * Rodada 20.b: the chance of each lane when the champion is picked (sums to 1): the meta's presence share, else
         * the seed's order. Empty = from {@link #lanes} by the seed's order.
         */
        public Map<String, Double> lanePrior = new LinkedHashMap<>();
        /** Rodada 20.b: the lanes a pick of it may play when the user did not say (the seed's, plus the meta's above the minimum). */
        public List<String> possibleLanes = new ArrayList<>();
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

    /** Rodada 20.b: how much b's mechanics punish a's (0..1) and the vulnerability it answers (null = none). */
    public interface Punish {
        double of(String b, String a);

        String why(String b, String a);
    }

    public static class Weights {
        public double meta = 0.25;
        public double lane = 0.35;
        public double matchup = 0.30;
        public double composition = 0.30;
        public double counter = 0.20;
        /** Rodada 20.b: flex (two or more open lanes with real presence), worth more early. */
        public double flex = 0.15;
        /** Rodada 20.b: a lane counts for the flex with at least this share of the champion's presence. */
        public double flexMin = 0.15;
        /** Rodada 20.b: the mechanics' punishment added to the measured advantage in the counter-pick risk. */
        public double counterMechanics = 0.3;
        /** Rodada 20.b: the counter-pick risk is full with this many picks of the other side still to come. */
        public int counterFullAfter = 5;
        /** Rodada 20.b: in a lane's counter-pick risk, the strongest counter's share (the mean of the 3 strongest, the rest). */
        public double counterTop = 0.6;
        /** Rodada 20.b: the bans' weight of counter-picking the picks already made. */
        public double banCounter = 0.5;
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
        /** Rodada 20.b: the seed's lane weights by order (main, 2nd...) when there is no meta. */
        public double[] seedLaneOrder = {1, 0.4, 0.2, 0.1, 0.05};
        /** Rodada 20.b: a lane the user marked weighs at least this share of the champion's best lane. */
        public double userLaneMin = 0.3;
        /** Rodada 20.b: an undefined pick's lanes outside its prior. */
        public double undefinedLane = 0.05;
        /** Rodada 20.b: a candidate whose lanes are this unlikely to be open is not listed. */
        public double laneFitMin = 0.08;
        /** Calibrated multipliers of the composition needs (feature -> weight); empty = all 1. */
        public Map<String, Double> calibration = new LinkedHashMap<>();
        public int beam = 5;
    }

    /** A champion on the board: picked, or predicted (fixed by the user, or a branch of the tree). */
    public static class Pick {
        public String side;
        public String champion;
        /** Rodada 20.b: the lane fixed on the loading screen (null = open: the distribution decides). */
        public String lane;
        /** Rodada 20.b: the lanes it may play (null = the champion's possible lanes; empty = undefined, any lane). */
        public List<String> lanes;
        public boolean predicted;
        /** Rodada 20.b: its card in the game (pick order; -1 = a hypothetical pick of the tree). */
        public int slot = -1;

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
        /** Rodada 20.b: the sides of the picks in the format's order (null = the ranked snake). */
        public List<String> order;
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
            b.order = order;
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
        /** Its most likely lane. */
        public String lane;
        /** Rodada 20.b: the chance of each lane if it is picked now (the team's open lanes and its own). */
        public Map<String, Double> lanes = new LinkedHashMap<>();
        /** The note, x 100. */
        public double score;
        public Map<String, Double> parts = new LinkedHashMap<>();
        public List<Reason> reasons = new ArrayList<>();
    }

    private final Map<String, Champ> champs = new LinkedHashMap<>();
    private final Map<String, Map<String, Meta>> meta;
    private final Matchup measured;
    private Punish punish;
    private final Weights w;
    private final List<String> lanes;
    private final Map<String, Double> subclassPower = new LinkedHashMap<>();
    /** How many children the tree has computed (the test checks the beam). */
    public int expanded;

    public DraftModel(List<Champ> champions, Map<String, Map<String, Meta>> meta, Matchup measured, Weights w, List<String> lanes) {
        champions.forEach(c -> champs.put(c.name, c));
        for (Champ c : champions) {
            if (c.lanePrior.isEmpty()) {
                c.lanePrior = seedPrior(c.lanes, w.seedLaneOrder);
            }
            if (c.possibleLanes.isEmpty()) {
                c.possibleLanes = new ArrayList<>(c.lanes);
            }
        }
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

    /** Rodada 20.b: the mechanics that punish (null = only the measured matchup). */
    public DraftModel withPunish(Punish p) {
        this.punish = p;
        return this;
    }

    /** The ranked snake, when a board has no order. */
    static final List<String> SNAKE = List.of(LiveGameModel.BLUE, LiveGameModel.RED, LiveGameModel.RED, LiveGameModel.BLUE,
            LiveGameModel.BLUE, LiveGameModel.RED, LiveGameModel.RED, LiveGameModel.BLUE, LiveGameModel.BLUE, LiveGameModel.RED);

    /**
     * Rodada 20.b: where a side's next pick falls in the order: {the other side's picks still to come after it, this
     * side's own picks still to come after it}.
     */
    public int[] after(Board b, String side) {
        List<String> order = b.order == null || b.order.isEmpty() ? SNAKE : b.order;
        int made = b.of(side).size();
        int seen = 0;
        int pos = -1;
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).equals(side) && seen++ == made) {
                pos = i;
                break;
            }
        }
        if (pos < 0) {
            return new int[] {0, 0};
        }
        int other = 0;
        int own = 0;
        for (int i = pos + 1; i < order.size(); i++) {
            if (order.get(i).equals(side)) {
                own++;
            } else {
                other++;
            }
        }
        return new int[] {other, own};
    }

    /**
     * Rodada 20.b: how much o punishes c in a lane, for the counter-pick risk and the bans: the measured (or profile)
     * advantage plus the mechanics' punishment, clamped to 0..1.
     */
    public double punishes(String o, String c, String lane) {
        double a = advantage(o, c, lane);
        double m = punish == null ? 0 : punish.of(o, c);
        return Math.max(0, Math.min(1, a + w.counterMechanics * m));
    }

    /** Rodada 20.b: a champion's lane chances from the seed's order alone (sums to 1). */
    public static Map<String, Double> seedPrior(List<String> lanes, double[] order) {
        Map<String, Double> out = new LinkedHashMap<>();
        double sum = 0;
        for (int i = 0; i < lanes.size(); i++) {
            double v = i < order.length ? order[i] : order[order.length - 1];
            out.merge(lanes.get(i), v, Double::sum);
            sum += v;
        }
        if (sum > 0) {
            for (Map.Entry<String, Double> e : out.entrySet()) {
                e.setValue(e.getValue() / sum);
            }
        }
        return out;
    }

    public List<String> lanes() {
        return lanes;
    }

    /**
     * Rodada 20.b: a pick's weight in each lane (in {@link #lanes} order): only its fixed lane; else the lanes it may play
     * (the user's, at least {@link Weights#userLaneMin} of its best; undefined = every lane, the unseen ones small; not
     * told = its possible lanes) by its prior.
     */
    public double[] weights(Pick p) {
        double[] out = new double[lanes.size()];
        if (p.lane != null && lanes.contains(p.lane)) {
            out[lanes.indexOf(p.lane)] = 1;
            return out;
        }
        Champ c = champs.get(p.champion);
        Map<String, Double> prior = c == null ? Map.of() : c.lanePrior;
        double max = 0;
        for (double v : prior.values()) {
            max = Math.max(max, v);
        }
        boolean undefined = p.lanes != null && p.lanes.isEmpty();
        boolean told = p.lanes != null && !p.lanes.isEmpty();
        for (int l = 0; l < lanes.size(); l++) {
            String lane = lanes.get(l);
            double pr = prior.getOrDefault(lane, 0.0);
            if (c == null || undefined) {
                out[l] = Math.max(pr, w.undefinedLane);
            } else if (told) {
                out[l] = p.lanes.contains(lane) ? Math.max(pr, w.userLaneMin * Math.max(max, 1e-9)) : 0;
            } else {
                out[l] = c.possibleLanes.contains(lane) ? Math.max(pr, LaneAssignment.RELAXED) : 0;
            }
        }
        return out;
    }

    /** Rodada 20.b: the likely assignment of a side's picks (and predictions) to the lanes, in board order. */
    public LaneAssignment.Result assignment(Board b, String side) {
        List<double[]> ws = new ArrayList<>();
        for (Pick p : b.of(side)) {
            ws.add(weights(p));
        }
        return LaneAssignment.of(lanes.size(), ws);
    }

    /** The lanes a side has likely not filled yet (open with a chance of 50% or more). */
    public List<String> openLanes(Board b, String side) {
        LaneAssignment.Result a = assignment(b, side);
        List<String> out = new ArrayList<>();
        for (int l = 0; l < lanes.size(); l++) {
            if (a.open[l] >= 0.5) {
                out.add(lanes.get(l));
            }
        }
        return out;
    }

    /** The most likely lane of each of a side's picks (board order), from the most likely whole assignment. */
    public List<String> likelyLanes(Board b, String side) {
        LaneAssignment.Result a = assignment(b, side);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.best.length; i++) {
            out.add(lanes.get(a.best[i]));
        }
        return out;
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
        Set<String> taken = new HashSet<>();
        b.picks.forEach(p -> taken.add(p.champion));
        Set<String> off = new HashSet<>(b.unavailable.getOrDefault(side, Set.of()));
        List<Scored> out = new ArrayList<>();
        if (b.of(side).size() >= lanes.size()) {
            return out;
        }
        Ctx x = new Ctx(b, side, other, taken);
        for (Champ c : champs.values()) {
            if (taken.contains(c.name) || off.contains(c.name)) {
                continue;
            }
            Scored s = score(x, c);
            if (s != null) {
                out.add(s);
            }
        }
        out.sort(Comparator.comparingDouble((Scored s) -> -s.score).thenComparing(s -> s.champion));
        return new ArrayList<>(out.subList(0, Math.min(k, out.size())));
    }

    /**
     * The bans: the other side's best candidates, the ones that threaten this side most. Rodada 20.b: a candidate that
     * counter-picks this side's picks already made goes up (in the lanes both are likely to play: the measured advantage
     * plus the mechanics, by {@link Weights#banCounter}).
     */
    public List<Scored> threats(Board b, String side, int k) {
        String other = LiveGameModel.BLUE.equals(side) ? LiveGameModel.RED : LiveGameModel.BLUE;
        List<Pick> mine = b.of(side);
        if (mine.isEmpty() || w.banCounter <= 0) {
            return rank(b, other, k);
        }
        LaneAssignment.Result myLanes = assignment(b, side);
        List<Scored> pool = rank(b, other, Math.max(4 * k, 40));
        for (Scored s : pool) {
            double best = 0;
            Pick victim = null;
            for (int i = 0; i < mine.size(); i++) {
                double v = 0;
                for (int l = 0; l < lanes.size(); l++) {
                    double po = s.lanes.getOrDefault(lanes.get(l), 0.0);
                    double pa = myLanes.marginal[i][l];
                    if (po > 0 && pa > 0) {
                        v += po * pa * punishes(s.champion, mine.get(i).champion, lanes.get(l));
                    }
                }
                if (v > best) {
                    best = v;
                    victim = mine.get(i);
                }
            }
            s.parts.put("contraEscolha", best);
            s.score += 100 * w.banCounter * best;
            if (victim != null && best >= 0.2) {
                String why = punish == null ? null : punish.why(s.champion, victim.champion);
                s.reasons.add(0, why == null ? new Reason("contraEscolhe", "champ", victim.champion)
                        : new Reason("contraEscolheMecanica", "champ", victim.champion, "mech", why));
            }
        }
        pool.sort(Comparator.comparingDouble((Scored s) -> -s.score).thenComparing(s -> s.champion));
        return new ArrayList<>(pool.subList(0, Math.min(k, pool.size())));
    }

    /** What every candidate of one ranking shares: the two teams' lane distributions. */
    final class Ctx {
        final Board b;
        final String side;
        final String other;
        final Set<String> taken;
        final List<Pick> mine;
        final List<Pick> theirs;
        final LaneAssignment.Result myLanes;
        final LaneAssignment.Result theirLanes;
        /** The other side's picks still to come after this one, and this side's own. */
        final int laterOther;
        final int laterOwn;

        Ctx(Board b, String side, String other, Set<String> taken) {
            this.b = b;
            this.side = side;
            this.other = other;
            this.taken = taken;
            this.mine = b.of(side);
            this.theirs = b.of(other);
            this.myLanes = assignment(b, side);
            this.theirLanes = assignment(b, other);
            int[] a = after(b, side);
            this.laterOther = a[0];
            this.laterOwn = a[1];
        }
    }

    /**
     * A candidate's lane chances if picked now: its weight in each lane times the chance of the lane being open in its
     * team, normalized; and the lane fit, the chance of its own lanes being open (1 = its lanes are surely free).
     */
    double[] candidateLanes(Ctx x, Champ c, double[] fitOut) {
        double[] wc = weights(new Pick(x.side, c.name, null, true));
        double sumW = 0;
        for (double v : wc) {
            sumW += v;
        }
        double[] pc = new double[lanes.size()];
        double z = 0;
        for (int l = 0; l < lanes.size(); l++) {
            pc[l] = x.myLanes.open[l] * wc[l];
            z += pc[l];
        }
        fitOut[0] = sumW <= 0 ? 0 : z / sumW;
        if (z > 0) {
            for (int l = 0; l < pc.length; l++) {
                pc[l] /= z;
            }
        }
        return pc;
    }

    private Scored score(Ctx x, Champ c) {
        double[] fit = new double[1];
        double[] pc = candidateLanes(x, c, fit);
        double laneFit = fit[0];
        if (laneFit < w.laneFitMin) {
            return null;   // its lanes are (almost surely) full: no candidate there
        }
        int best = 0;
        for (int l = 1; l < pc.length; l++) {
            if (pc[l] > pc[best]) {
                best = l;
            }
        }
        Scored s = new Scored();
        s.champion = c.name;
        s.lane = lanes.get(best);
        for (int l = 0; l < pc.length; l++) {
            if (pc[l] >= 0.005) {
                s.lanes.put(lanes.get(l), round3(pc[l]));
            }
        }
        double mainShare = c.lanePrior.getOrDefault(s.lane, 0.0);
        double maxShare = 0;
        for (double v : c.lanePrior.values()) {
            maxShare = Math.max(maxShare, v);
        }
        s.reasons.add(new Reason(mainShare >= maxShare - 1e-9 ? "rotaAberta" : "rotaSecundaria", "lane", s.lane,
                "pct", round(100 * pc[best])));
        // meta: the mean over its likely lanes
        double pMeta = 0;
        Map<String, Meta> cm = meta.getOrDefault(c.name, Map.of());
        for (int l = 0; l < pc.length; l++) {
            Meta m = cm.get(lanes.get(l));
            if (m != null && pc[l] > 0) {
                pMeta += pc[l] * (w.metaPresence * Math.min(1, m.presence / 0.2) + w.metaBan * Math.min(1, m.ban / 0.2)
                        + w.metaWin * clamp((m.win - 0.5) / w.metaWinScale));
            }
        }
        Meta mb = cm.get(s.lane);
        if (mb != null) {
            s.reasons.add(new Reason("presenca", "pct", round(mb.presence * 100), "win", round(mb.win * 100)));
        }
        // lane matchup: the mean over the likely lane rivals of the other side
        double pMatch = 0;
        double topWeight = 0;
        Pick top = null;
        double topAdv = 0;
        for (int l = 0; l < pc.length; l++) {
            if (pc[l] <= 0) {
                continue;
            }
            for (int o = 0; o < x.theirs.size(); o++) {
                double po = x.theirLanes.marginal[o][l];
                if (po <= 0) {
                    continue;
                }
                double a = advantage(c.name, x.theirs.get(o).champion, lanes.get(l));
                pMatch += pc[l] * po * a;
                if (pc[l] * po > topWeight) {
                    topWeight = pc[l] * po;
                    top = x.theirs.get(o);
                    topAdv = a;
                }
            }
        }
        if (top != null && topWeight >= 0.25 && Math.abs(topAdv) >= 0.1) {
            s.reasons.add(new Reason(topAdv > 0 ? "ganhaRota" : "perdeRota", "champ", top.champion, "predicted", top.predicted,
                    "measured", measured(c.name, top.champion, lanes.get(best)), "pct", round(100 * topWeight)));
        }
        // what it punishes among the other side's picks (outside its lane too), in their likely lanes
        List<String> theirLikely = new ArrayList<>();
        for (int o = 0; o < x.theirs.size(); o++) {
            theirLikely.add(lanes.get(x.theirLanes.best[o]));
        }
        for (int o = 0; o < x.theirs.size(); o++) {
            Pick p = x.theirs.get(o);
            if (p != top && advantage(c.name, p.champion, theirLikely.get(o)) >= 0.5) {
                s.reasons.add(new Reason("pune", "champ", p.champion, "predicted", p.predicted));
                break;
            }
        }
        // composition
        double pComp = composition(x.mine, c, s);
        // Rodada 20.b: flex: two or more open lanes with real presence, worth more the more own picks are still to come
        List<String> flexLanes = new ArrayList<>();
        for (int l = 0; l < lanes.size(); l++) {
            if (c.lanePrior.getOrDefault(lanes.get(l), 0.0) >= w.flexMin && x.myLanes.open[l] >= 0.5) {
                flexLanes.add(lanes.get(l));
            }
        }
        double earliness = (double) x.laterOwn / Math.max(1, lanes.size() - 1);
        double pFlex = flexLanes.size() >= 2 ? earliness : 0;
        if (pFlex >= 0.25) {
            s.reasons.add(new Reason("flex", "lanes", String.join(" / ", flexLanes)));
        }
        // counter-pick risk: in each likely lane, by the chance of the other side's rival lane still being open, times
        // the other side's picks still to come after this one (the last pick has no risk)
        double pCounter = 0;
        String worst = null;
        double worstRisk = 0;
        double position = Math.min(1, (double) x.laterOther / Math.max(1, w.counterFullAfter));
        for (int l = 0; l < pc.length && position > 0; l++) {
            double openThere = x.theirLanes.open[l];
            if (pc[l] <= 0 || openThere <= 0) {
                continue;
            }
            Object[] r = laneRisk(x, c, lanes.get(l));
            double risk = (double) r[0];
            pCounter -= pc[l] * openThere * risk * position;
            if (pc[l] * openThere * risk > worstRisk) {
                worstRisk = pc[l] * openThere * risk;
                worst = (String) r[1];
            }
        }
        if (pCounter <= -0.15 && worst != null) {
            String why = punish == null ? null : punish.why(worst, c.name);
            s.reasons.add(why == null ? new Reason("risco", "champ", worst, "later", x.laterOther)
                    : new Reason("riscoMecanica", "champ", worst, "later", x.laterOther, "mech", why));
        }
        s.parts.put("meta", pMeta);
        s.parts.put("rota", laneFit);
        s.parts.put("matchup", pMatch);
        s.parts.put("composicao", pComp);
        s.parts.put("contra", pCounter);
        s.parts.put("flex", pFlex);
        s.score = 100 * (w.meta * pMeta + w.lane * laneFit + w.matchup * pMatch + w.composition * pComp + w.counter * pCounter
                + w.flex * pFlex);
        return s;
    }

    /**
     * How much the other side's available champions that play a lane punish a candidate there (0..1): the strongest
     * counter and the mean of the 3 strongest (Rodada 20.b), and the one that punishes most.
     */
    Object[] laneRisk(Ctx x, Champ c, String lane) {
        List<double[]> risks = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Set<String> otherOff = x.b.unavailable.getOrDefault(x.other, Set.of());
        for (Champ o : champs.values()) {
            if (o.name.equals(c.name) || x.taken.contains(o.name) || otherOff.contains(o.name) || !o.possibleLanes.contains(lane)) {
                continue;
            }
            double a = punishes(o.name, c.name, lane);
            if (a > 0) {
                risks.add(new double[] {a, names.size()});
                names.add(o.name);
            }
        }
        risks.sort(Comparator.comparingDouble((double[] r) -> -r[0]).thenComparingDouble(r -> r[1]));
        double sum = 0;
        int n = Math.min(3, risks.size());
        for (int i = 0; i < n; i++) {
            sum += risks.get(i)[0];
        }
        if (n == 0) {
            return new Object[] {0.0, null};
        }
        // Rodada 20.b: one strong counter is a risk by itself: the strongest weighs w.counterTop, the mean of the 3 the rest
        double risk = w.counterTop * risks.get(0)[0] + (1 - w.counterTop) * sum / 3;
        return new Object[] {risk, names.get((int) risks.get(0)[1])};
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

    private static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
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
