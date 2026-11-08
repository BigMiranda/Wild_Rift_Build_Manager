package com.ornnplanner.arena.rift;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * A whole match, 5 against 5, at the coarse step: champions walk the map and their macro decides ({@link MacroPolicy}),
 * waves meet and trade blows as batches, towers and camps are numbers; and when champions fight, that fight runs on the
 * arena at the fine step ({@link World#fight}). Gold and experience come out of what happens, never from the planner's
 * rates. Deterministic by the seed.
 */
public final class Match {

    /** The coarse step (s). */
    public static final double STEP = 0.25;

    /** What the match needs from the outside: the stats of a build at a point, its shop, and the fights. */
    public interface World {
        /** Max health, attack damage, attacks per second, ability power, move speed. */
        double[] stats(int seat, int steps, int level, double minute);

        /** How many steps the build has. */
        int steps(int seat);

        /** Gold the build has spent after its first {@code steps} steps. */
        double cost(int seat, int steps);

        /** The shop sells step {@code index} at this minute. */
        boolean sold(int seat, int index, double minute);

        int levelFor(double xp);

        /** The champion's name, for the log. */
        default String name(int seat) {
            return "campeão " + (seat + 1);
        }

        /** It carries Teleport. */
        default boolean teleport(int seat) {
            return false;
        }

        /** Its sheet lets it buy away from the base ({@code compra_fora_da_base}). */
        default boolean shopsAnywhere(int seat) {
            return false;
        }

        /** Its sheet lets nearby allies buy through it ({@code compra_para_aliados}). */
        default boolean sellsToAllies(int seat) {
            return false;
        }

        /** What the purchases from step {@code from} to {@code to} are worth in gold (the quick comparison's gain). */
        default double itemGain(int seat, int from, int to) {
            return cost(seat, to) - cost(seat, from);
        }

        /** Runs a fight among these seats at the fine step. */
        FightOutcome fight(List<Seat> who, double x, double y, double minute, List<int[]> fallen, Match match);
    }

    /** What a fight left. */
    public static class FightOutcome {
        public double seconds;
        public com.ornnplanner.arena.ArenaModel.Scenario scenario;
        public Map<Integer, double[]> after = new java.util.LinkedHashMap<>();   // seat -> {health share, x, y, dead, gold, xp, kills}
    }

    /** A champion on the map. */
    public static final class Seat {
        public final int index;
        public final int team;
        public final String role;
        public double x;
        public double y;
        public double hp = 1;
        public double gold;
        public double xp;
        public int level = 1;
        public int steps;
        public boolean dead;
        double respawnAt;
        double busyUntil;
        double recallAt = -1;
        double fightAfter;
        /** Two tempos: Teleport up again at, back to the lane by Teleport after the base, the Honeyfruit it walks to. */
        double teleportReadyAt;
        boolean teleportBack;
        double[] fruit;
        public TwoTempo.Choice lastChoice;
        /** The learned macro's last action that the script would not take (-1: none), for its notes in the log. */
        int macroAction = -1;
        MacroPolicy.Intent intent;
        double[][] path;
        int pathAt;
        double pathX = -1;
        double pathY = -1;
        public int kills;
        public int deaths;
        public int minions;
        public int monsters;
        double[] stats;
        public final List<double[]> purchases = new ArrayList<>();

        Seat(int index, int team, String role) {
            this.index = index;
            this.team = team;
            this.role = role;
        }

        public double hpFraction() {
            return hp;
        }

        public double goldInHand(Match m) {
            return gold - m.world.cost(index, steps);
        }
    }

    /** A wave on the march, as one batch: how many of each kind and their health. */
    static final class Wave {
        final int team;
        final String lane;
        double along;
        final List<String> kinds = new ArrayList<>();
        final List<Double> hp = new ArrayList<>();
        final List<Double> unit = new ArrayList<>();
        double dps;

        Wave(int team, String lane) {
            this.team = team;
            this.lane = lane;
        }
    }

    /** A tower or a Nexus. */
    public static final class Tower {
        public final int team;
        final String lane;
        final String kind;
        final int order;
        public final double x;
        public final double y;
        final double along;
        public double hp;
        final double maxHp;
        int plates;
        /** Its shots at champions: the seat it aims at, how many in a row, when the next one goes. */
        int aim = -1;
        int streak;
        double nextShot;

        Tower(int team, String lane, String kind, int order, double x, double y, double along, double hp) {
            this.team = team;
            this.lane = lane;
            this.kind = kind;
            this.order = order;
            this.x = x;
            this.y = y;
            this.along = along;
            this.hp = hp;
            this.maxHp = hp;
        }
    }

    private static final class Camp {
        final RiftMap.Spot spot;
        double upAt;
        double hp;

        Camp(RiftMap.Spot spot, double upAt) {
            this.spot = spot;
            this.upAt = upAt;
        }
    }

    private static final List<String> LANES = List.of("barao", "meio", "dragao");

    public final RiftMap map;
    public final RiftRules rules;
    final Map<String, Object> macro;
    final World world;
    final MacroPolicy policy;
    private final SplittableRandom rng;
    final List<Seat> seats = new ArrayList<>();
    final List<Wave> waves = new ArrayList<>();
    public final List<Tower> towers = new ArrayList<>();
    private final List<Camp> camps = new ArrayList<>();
    private final Map<String, Double> laneLength = new java.util.LinkedHashMap<>();
    public final MatchModel.Result result = new MatchModel.Result();
    private double t;
    private double nextWave;
    private int wave;
    private double dragonAt;
    private double baronAt;
    private double dragonHp;
    private double baronHp;
    private boolean firstBlood;
    private final int[] drakes = new int[2];
    /** Honeyfruits: {x, y, up at}. */
    final List<double[]> fruits = new ArrayList<>();
    /** The two-tempo estimate (null: off, the old thresholds). */
    public final TwoTempo tempo;
    private boolean firstTowerDown;
    /** The order of the drakes in this match, and how many were taken by both sides. */
    public final List<String> dragonOrder;
    private int drakesTaken;
    public final com.ornnplanner.arena.ArenaModel.ObjectiveBuffs[] buffs = {new com.ornnplanner.arena.ArenaModel.ObjectiveBuffs(), new com.ornnplanner.arena.ArenaModel.ObjectiveBuffs()};
    final double[] baronUntil = new double[2];

    public Match(RiftMap map, RiftRules rules, Map<String, Object> macro, World world, MacroPolicy policy, long seed, List<String> roles, List<Integer> teams) {
        this.map = map;
        this.rules = rules;
        this.macro = macro;
        this.world = world;
        this.policy = policy;
        this.rng = new SplittableRandom(seed);
        this.dragonOrder = rules.dragonOrder(seed);
        for (int i = 0; i < roles.size(); i++) {
            Seat s = new Seat(i, teams.get(i), roles.get(i));
            double[] f = s.team == 0 ? map.fountainBlue : map.fountainRed;
            s.x = f[0];
            s.y = f[1];
            s.gold = rules.num(500, "ouro", "inicial");
            seats.add(s);
        }
        map.lanes.forEach((name, pts) -> {
            double len = 0;
            for (int i = 0; i + 1 < pts.length; i++) {
                len += Math.hypot(pts[i + 1][0] - pts[i][0], pts[i + 1][1] - pts[i][1]);
            }
            laneLength.put(name, len);
        });
        for (RiftMap.Spot s : map.towers) {
            Map<String, Object> m = rules.map("torres", "tipos", s.kind);
            double hp = RiftRules.value(m.get("vida"), 3000);
            int order = "externa".equals(s.kind) ? 1 : "interna".equals(s.kind) ? 2 : 3;
            Tower tw = new Tower(s.team, s.lane, s.kind, order, s.x, s.y, projected(s.lane, s.team, s.x, s.y), hp);
            tw.plates = order == 1 ? 5 : 0;
            towers.add(tw);
        }
        double nexus = RiftRules.value(rules.map("torres", "nexo").get("vida"), 4000);
        towers.add(new Tower(0, null, "nexo", 4, map.nexusBlue[0], map.nexusBlue[1], 0, nexus));
        towers.add(new Tower(1, null, "nexo", 4, map.nexusRed[0], map.nexusRed[1], 0, nexus));
        for (RiftMap.Spot c : map.camps) {
            camps.add(new Camp(c, rules.campAppears(c.name)));
        }
        double fruitUp = RiftRules.value(rules.map("plantas", "frutomel").get("aparece"), 360);
        for (RiftMap.Spot p : map.plants) {
            if ("frutomel".equals(p.kind)) {
                fruits.add(new double[] {p.x, p.y, fruitUp});
            }
        }
        Object two = macro.get("dois_tempos");
        @SuppressWarnings("unchecked")
        Map<String, Object> weights = two instanceof Map ? (Map<String, Object>) two : null;
        tempo = weights == null || Boolean.FALSE.equals(weights.get("ligado")) ? null : new TwoTempo(weights);
        nextWave = rules.num(30, "tropas", "primeira_onda");
        dragonAt = rules.num(360, "objetivos", "dragao", "aparece");
        baronAt = rules.num(660, "objetivos", "barao", "aparece");
    }

    private double param(String key, double def) {
        return RiftRules.value(macro.get(key), def);
    }

    public double seconds() {
        return t;
    }

    public static String laneOf(String role) {
        return "barao".equals(role) ? "barao" : "meio".equals(role) ? "meio" : "dragao";
    }

    /** Distance along a lane, from the side's own Nexus, of the point nearest to (x, y). */
    private double projected(String lane, int team, double x, double y) {
        double[][] p = map.lanes.get(lane);
        double along = 0;
        double best = Double.MAX_VALUE;
        double at = 0;
        for (int i = 0; i + 1 < p.length; i++) {
            double dx = p[i + 1][0] - p[i][0];
            double dy = p[i + 1][1] - p[i][1];
            double l = Math.hypot(dx, dy);
            double u = Math.max(0, Math.min(1, ((x - p[i][0]) * dx + (y - p[i][1]) * dy) / (l * l)));
            double d = Math.hypot(p[i][0] + dx * u - x, p[i][1] + dy * u - y);
            if (d < best) {
                best = d;
                at = along + l * u;
            }
            along += l;
        }
        return team == 0 ? at : along - at;
    }

    /** The point at a distance along a lane from a side's Nexus. */
    double[] onLane(String lane, int team, double along) {
        double[][] p = map.lanes.get(lane);
        double total = laneLength.get(lane);
        double at = team == 0 ? along : total - along;
        for (int i = 0; i + 1 < p.length; i++) {
            double l = Math.hypot(p[i + 1][0] - p[i][0], p[i + 1][1] - p[i][1]);
            if (at <= l || i + 2 == p.length) {
                double u = Math.min(1, at / l);
                return new double[] {p[i][0] + (p[i + 1][0] - p[i][0]) * u, p[i][1] + (p[i + 1][1] - p[i][1]) * u};
            }
            at -= l;
        }
        return p[p.length - 1];
    }

    // ------------------------------------------------------------------ what the macro asks

    public boolean atBase(Seat s) {
        double[] f = s.team == 0 ? map.fountainBlue : map.fountainRed;
        return Math.hypot(s.x - f[0], s.y - f[1]) <= f[2];
    }

    public boolean canBuy(Seat s) {
        return s.steps < world.steps(s.index) && s.gold >= world.cost(s.index, s.steps + 1)
                && world.sold(s.index, s.steps, t / 60);
    }

    /** The objective up now: {x, y, 1 for Baron / 0 for the drake}, or null. */
    public double[] objectiveUp() {
        if (baronHp > 0) {
            return new double[] {map.baronPit[0], map.baronPit[1], 1};
        }
        if (dragonHp > 0) {
            return new double[] {map.dragonPit[0], map.dragonPit[1], 0};
        }
        return null;
    }

    /** The camp the jungler goes to: the nearest one up on its side, {x, y, index}. */
    public double[] nextCamp(Seat s) {
        double best = Double.MAX_VALUE;
        double[] out = null;
        for (int i = 0; i < camps.size(); i++) {
            Camp c = camps.get(i);
            if (c.spot.team == s.team && t >= c.upAt) {
                double d = Math.hypot(c.spot.x - s.x, c.spot.y - s.y);
                if (d < best) {
                    best = d;
                    out = new double[] {c.spot.x, c.spot.y, i};
                }
            }
        }
        return out;
    }

    /** Where a laner stands: behind its wave at the front, or at its outermost tower. */
    public double[] laneSpot(int team, String lane) {
        double front = -1;
        for (Wave w : waves) {
            if (w.team == team && w.lane.equals(lane)) {
                front = Math.max(front, w.along);
            }
        }
        for (Tower tw : towers) {
            if (tw.team == team && lane.equals(tw.lane) && tw.hp > 0) {
                front = Math.max(front, front < 0 ? tw.along : Math.min(front, tw.along + 1500));
                break;
            }
        }
        return onLane(lane, team, Math.max(300, front - 150));
    }

    /** The lane the side pushes after grouping: the one with the fewest enemy towers left. */
    public String pushLane(int team) {
        String best = "meio";
        int fewest = Integer.MAX_VALUE;
        for (String lane : LANES) {
            int n = 0;
            for (Tower tw : towers) {
                if (tw.team != team && lane.equals(tw.lane) && tw.hp > 0) {
                    n++;
                }
            }
            if (n < fewest) {
                fewest = n;
                best = lane;
            }
        }
        return best;
    }

    /**
     * Where the side groups to push: the enemy's next structure in the push lane once its own minions are there (the
     * tower shoots them first, and the anti-backdoor cut is off), behind its front wave of that lane until then.
     */
    public double[] rally(int team) {
        String lane = pushLane(team);
        Tower next = nextTower(1 - team, lane);
        if (next == null) {
            return team == 0 ? map.nexusRed : map.nexusBlue;
        }
        return escorted(team, next) ? new double[] {next.x, next.y} : laneSpot(team, lane);
    }

    /** The next structure of a side in a lane; its Nexus when the lane is open. */
    Tower nextTower(int team, String lane) {
        Tower out = null;
        for (Tower tw : towers) {
            if (tw.team == team && tw.hp > 0 && (lane.equals(tw.lane) || tw.lane == null) && (out == null || tw.order < out.order)) {
                out = tw;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ what the learned macro reads

    /** Seconds until the drake comes (0: up now; -1: no more drakes). */
    double dragonIn() {
        return dragonHp > 0 ? 0 : dragonAt < 0 ? -1 : Math.max(0, dragonAt - t);
    }

    /** Seconds until Baron comes (0: up now; -1: not again). */
    double baronIn() {
        return baronHp > 0 ? 0 : baronAt < 0 ? -1 : Math.max(0, baronAt - t);
    }

    int drakes(int team) {
        return drakes[team];
    }

    boolean baronBuff(int team) {
        return t < baronUntil[team];
    }

    double laneLength(String lane) {
        return laneLength.get(lane);
    }

    /** Teleport is up and has somewhere to go. */
    boolean teleportReady(Seat s) {
        return world.teleport(s.index) && t >= s.teleportReadyAt && teleportTarget(s) != null;
    }

    /** It may buy where it stands: its sheet, or an ally that sells to it close by. */
    boolean shopsHere(Seat s) {
        if (world.shopsAnywhere(s.index)) {
            return true;
        }
        for (Seat a : seats) {
            if (a != s && a.team == s.team && !a.dead && world.sellsToAllies(a.index) && Math.hypot(a.x - s.x, a.y - s.y) <= 1500) {
                return true;
            }
        }
        return false;
    }

    /** A champion's strength in the fights' rough measure (health × damage). */
    double strength(Seat s) {
        return power(s);
    }

    String name(Seat s) {
        return world.name(s.index);
    }

    // ------------------------------------------------------------------ the run

    public MatchModel.Result run(double limitSeconds) {
        double nextSecond = 0;
        for (Seat s : seats) {
            step0(s);
        }
        while (t <= limitSeconds && result.winner < 0) {
            tickClock();
            tickWaves();
            for (Seat s : seats) {
                tickSeat(s);
            }
            tickFights();
            tickTowersByChampions();
            if (t >= nextSecond) {
                snapshot();
                nextSecond += 1;
            }
            t += STEP;
        }
        result.seconds = Math.min(t, limitSeconds);
        return result;
    }

    private void step0(Seat s) {
        s.stats = world.stats(s.index, 0, 1, 0);
    }

    /** "do Oceano", "da Montanha", "Infernal": the element after "Dragão" or "Alma". */
    public static String elementName(String e) {
        return "oceano".equals(e) ? "do Oceano" : "montanha".equals(e) ? "da Montanha" : "infernal".equals(e) ? "Infernal" : e;
    }

    void note(Integer team, String text) {
        result.events.add(new MatchModel.Event(Math.round(t * 10) / 10.0, team, text));
    }

    private void tickClock() {
        if (t >= nextWave) {
            nextWave = t + rules.waveGap(t);
            wave++;
            for (int team = 0; team < 2; team++) {
                for (String lane : LANES) {
                    spawnWave(team, lane);
                }
            }
        }
        if (dragonAt > 0 && t >= dragonAt && dragonHp <= 0) {
            boolean elder = drakes[0] >= 3 || drakes[1] >= 3;
            Map<String, Object> d = rules.map("objetivos", elder ? "anciao" : "dragao");
            dragonHp = RiftRules.value(d.get("vida"), 4000) + 90 * t / 60;
            dragonAt = -1;
            note(null, elder ? "o Dragão Ancião apareceu" : "o Dragão apareceu");
        }
        if (baronAt > 0 && t >= baronAt && baronHp <= 0) {
            baronHp = rules.num(9500, "objetivos", "barao", "vida") + 180 * t / 60;
            baronAt = -1;
            note(null, "o Barão apareceu");
        }
        if (Math.abs(t % 1) < 1e-9 && t >= rules.num(20, "ouro", "passivo_por_s", "de")) {
            for (Seat s : seats) {
                s.gold += rules.num(3, "ouro", "passivo_por_s");
            }
        }
    }

    private void spawnWave(int team, String lane) {
        Wave w = new Wave(team, lane);
        boolean strong = false;
        for (Tower tw : towers) {
            strong |= tw.team != team && lane.equals(tw.lane) && "base".equals(tw.kind) && tw.hp <= 0;
        }
        double minute = t / 60;
        for (String kind : rules.wave(wave, strong)) {
            Map<String, Object> m = rules.map("tropas", "tipos", kind);
            double hp = RiftRules.value(m.get("vida"), 300) + RiftRules.value(m.get("vida_por_min"), 0) * minute;
            double ad = RiftRules.value(m.get("dda"), 10) + RiftRules.value(m.get("dda_por_min"), 0) * minute;
            w.kinds.add(kind);
            w.hp.add(hp);
            w.unit.add(hp);
            w.dps += ad * RiftRules.value(m.get("velocidade_ataque"), 1);
        }
        waves.add(w);
    }

    /** Waves as batches: they march, trade blows when they meet, and hit the next structure. */
    private void tickWaves() {
        double speed = rules.minionSpeed(t);
        for (Wave w : new ArrayList<>(waves)) {
            Wave enemy = frontOf(1 - w.team, w.lane);
            double total = laneLength.get(w.lane);
            Tower next = nextTower(1 - w.team, w.lane);
            double towerAt = next == null ? total : next.lane == null ? total - 300 : total - next.along;
            boolean clash = enemy != null && total - enemy.along - w.along <= 300;
            if (clash) {
                hitWave(enemy, w.dps * STEP);
            } else if (w.along >= towerAt - 250 && next != null) {
                hitStructure(next, w.dps * STEP, null);
            } else {
                w.along += speed * STEP;
            }
        }
        for (Tower tw : towers) {
            if (tw.hp > 0 && tw.lane != null) {
                Wave near = frontOf(1 - tw.team, tw.lane);
                if (near != null && laneLength.get(tw.lane) - near.along - tw.along <= 750) {
                    Map<String, Object> m = rules.map("torres", "tipos", tw.kind);
                    hitWave(near, rules.towerDamage(m, t) * RiftRules.value(rules.map("torres").get("velocidade_ataque"), 0.83) * STEP);
                }
            }
        }
        waves.removeIf(w -> w.hp.isEmpty());
    }

    Wave frontOf(int team, String lane) {
        Wave best = null;
        for (Wave w : waves) {
            if (w.team == team && w.lane.equals(lane) && !w.hp.isEmpty() && (best == null || w.along > best.along)) {
                best = w;
            }
        }
        return best;
    }

    private void hitWave(Wave w, double damage) {
        while (damage > 0 && !w.hp.isEmpty()) {
            double left = w.hp.get(0);
            if (damage < left) {
                w.hp.set(0, left - damage);
                return;
            }
            damage -= left;
            String kind = w.kinds.remove(0);
            w.hp.remove(0);
            double unit = w.unit.remove(0);
            double[] at = onLane(w.lane, w.team, w.along);
            Map<String, Object> m = rules.map("tropas", "tipos", kind);
            w.dps -= (RiftRules.value(m.get("dda"), 10) + RiftRules.value(m.get("dda_por_min"), 0) * t / 60) * RiftRules.value(m.get("velocidade_ataque"), 1);
            minionDied(w.team, m, at, unit);
        }
    }

    /** A minion falls: the champions around share the experience, and one of them may take the last hit. */
    private void minionDied(int team, Map<String, Object> m, double[] at, double hp) {
        double radius = rules.num(1400, "xp", "raio");
        List<Seat> near = new ArrayList<>();
        for (Seat s : seats) {
            if (s.team != team && !s.dead && Math.hypot(s.x - at[0], s.y - at[1]) <= radius) {
                near.add(s);
            }
        }
        if (near.isEmpty()) {
            return;
        }
        for (Seat s : near) {
            s.xp += RiftRules.value(m.get("xp"), 30) / near.size();
        }
        Seat best = null;
        for (Seat s : near) {
            if (best == null || lastHitShare(s) > lastHitShare(best)) {
                best = s;
            }
        }
        double share = lastHitShare(best);
        if (rng.nextDouble() < share) {
            best.gold += RiftRules.value(m.get("ouro"), 40);
            best.minions++;
        }
    }

    private double lastHitShare(Seat s) {
        Map<String, Object> by = (Map<String, Object>) macroMap("ultimos_golpes");
        return RiftRules.value(by.get(s.role), 0.6);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> macroMap(String key) {
        return (Map<String, Object>) macro.get(key);
    }

    /** Damage on a tower or Nexus; a side's last hit pays. */
    private void hitStructure(Tower tw, double damage, Seat by) {
        if (tw.plates > 0 && t < RiftRules.value(rules.map("torres", "placas").get("ate"), 360)) {
            double next = tw.maxHp * (tw.plates - 1) / 5;
            if (tw.hp - damage <= next) {
                tw.plates--;
                if (by != null) {
                    by.gold += RiftRules.value(rules.map("torres", "placas").get("ouro"), 150);
                }
            }
        }
        tw.hp -= damage;
        if (tw.hp > 0) {
            return;
        }
        tw.hp = 0;
        if (tw.lane == null) {
            result.winner = 1 - tw.team;
            note(1 - tw.team, "o Nexo caiu");
            return;
        }
        Map<String, Object> m = rules.map("torres", "tipos", tw.kind);
        for (Seat s : seats) {
            if (s.team != tw.team) {
                s.gold += RiftRules.value(m.get("ouro_global"), 75);
            }
        }
        if (by != null) {
            by.gold += RiftRules.value(m.get("ouro_local"), 200);
        }
        note(1 - tw.team, "torre " + tw.kind + " da rota " + tw.lane + " caiu");
        if (!firstTowerDown) {
            firstTowerDown = true;
            if (Boolean.TRUE.equals(rules.map("torres", "placas").get("caem_com_a_primeira_torre"))) {
                for (Tower o : towers) {
                    o.plates = 0;
                }
                note(1 - tw.team, "primeira torre da partida: as placas das outras caíram");
            }
        }
    }

    private void tickSeat(Seat s) {
        if (s.dead) {
            if (t >= s.respawnAt) {
                s.dead = false;
                s.hp = 1;
                double[] f = s.team == 0 ? map.fountainBlue : map.fountainRed;
                s.x = f[0];
                s.y = f[1];
            }
            return;
        }
        int level = world.levelFor(s.xp);
        if (level != s.level) {
            s.level = level;
            s.stats = world.stats(s.index, s.steps, s.level, t / 60);
        }
        if (atBase(s)) {
            s.hp = Math.min(1, s.hp + rules.num(0.12, "fonte_base", "cura_por_s") * STEP);
            shop(s);
            if (s.teleportBack && s.hp >= 0.95 && t >= s.busyUntil) {
                teleportBack(s);
                return;
            }
        } else if (tempo != null) {
            s.hp = Math.min(1, s.hp + param("regen_por_s", 0.004) * STEP);   // out of the trade, it heals a little
        }
        if (t < s.busyUntil) {
            return;
        }
        if (s.recallAt >= 0) {
            if (t >= s.recallAt) {
                s.recallAt = -1;
                double[] f = s.team == 0 ? map.fountainBlue : map.fountainRed;
                s.x = f[0];
                s.y = f[1];
                s.path = null;
            }
            return;
        }
        if (atBase(s) && s.hp < 0.95) {
            return;   // healing up
        }
        if (s.fruit != null) {
            eatFruit(s);
            return;
        }
        s.intent = policy.decide(this, s);
        switch (s.intent.goal) {
            case RECALL:
                s.recallAt = t + param("volta_s", 8);
                s.teleportBack = "teleporte".equals(s.intent.what);
                break;
            case RETREAT:
                s.fruit = nearestFruit(s);
                if (s.fruit == null) {
                    walk(s, s.intent.x, s.intent.y);
                }
                break;
            case SHOP:
                shop(s);
                note(s.team, world.name(s.index) + " comprou sem voltar à base");
                break;
            case JUNGLE:
                walk(s, s.intent.x, s.intent.y);
                clearCamp(s);
                break;
            case OBJECTIVE:
                walk(s, s.intent.x, s.intent.y);
                workObjective(s);
                break;
            default:
                walk(s, s.intent.x, s.intent.y);
                break;
        }
    }

    // ------------------------------------------------------------------ two tempos

    /** The nearest Honeyfruit up within reach, {x, y, up at}, or null. */
    double[] nearestFruit(Seat s) {
        double reach = param("frutomel_ate", 3000);
        double[] best = null;
        for (double[] f : fruits) {
            double d = Math.hypot(f[0] - s.x, f[1] - s.y);
            if (t >= f[2] && d <= reach && (best == null || d < Math.hypot(best[0] - s.x, best[1] - s.y))) {
                best = f;
            }
        }
        return best;
    }

    /** Walks to its Honeyfruit and breaks it: the fruits heal it. Another champion may take it first. */
    private void eatFruit(Seat s) {
        double[] f = s.fruit;
        if (t < f[2]) {
            s.fruit = null;   // gone
            return;
        }
        if (Math.hypot(f[0] - s.x, f[1] - s.y) > 150) {
            walk(s, f[0], f[1]);
            return;
        }
        Map<String, Object> p = rules.map("plantas", "frutomel");
        double heal = Math.min(1 - s.hp, RiftRules.value(p.get("cura_pct"), 0.035) * RiftRules.value(p.get("frutos"), 3));
        s.hp += heal;
        f[2] = t + RiftRules.value(p.get("renasce"), 360);
        s.fruit = null;
        note(s.team, world.name(s.index) + " comeu o Frutomel: +" + Math.round(heal * s.stats[0]) + " de vida");
    }

    /** After the base, Teleport to the lane: to its front tower in the first minutes, later to an ally out there. */
    private void teleportBack(Seat s) {
        s.teleportBack = false;
        double[] to = teleportTarget(s);
        if (to == null) {
            return;
        }
        s.busyUntil = t + param("teleporte_s", 3.5);
        s.teleportReadyAt = t + param("teleporte_recarga", 150);
        s.x = to[0];
        s.y = to[1];
        s.path = null;
        note(s.team, world.name(s.index) + " voltou à rota de Teleporte");
    }

    private double[] teleportTarget(Seat s) {
        String lane = laneOf(s.role);
        if (t < param("teleporte_estruturas_ate", 360)) {
            for (Tower tw : towers) {
                if (tw.team == s.team && lane.equals(tw.lane) && tw.hp > 0) {
                    return new double[] {tw.x, tw.y};
                }
            }
        }
        double[] spot = laneSpot(s.team, lane);
        Seat best = null;
        for (Seat a : seats) {
            if (a != s && a.team == s.team && !a.dead && !atBase(a)
                    && (best == null || Math.hypot(a.x - spot[0], a.y - spot[1]) < Math.hypot(best.x - spot[0], best.y - spot[1]))) {
                best = a;
            }
        }
        return best == null ? null : new double[] {best.x, best.y};
    }

    /** What the champion sees now, for the two-tempo estimate. */
    public TwoTempo.Situation situation(Seat s) {
        TwoTempo.Situation x = new TwoTempo.Situation();
        x.hp = s.hp;
        x.maxHp = s.stats[0];
        x.moveSpeed = Math.max(200, s.stats[4]);
        x.regenPerS = param("regen_por_s", 0.004);
        x.recallSeconds = param("volta_s", 8);
        x.teleportSeconds = param("teleporte_s", 3.5);
        // the lane: a wave's gold and experience over the wave gap, by the share of last hits of the role
        String lane = laneOf(s.role);
        double waveGold = 0;
        double waveXp = 0;
        for (String kind : rules.wave(3, false)) {
            Map<String, Object> m = rules.map("tropas", "tipos", kind);
            waveGold += RiftRules.value(m.get("ouro"), 0) / 3 * ("canhao".equals(kind) ? 1 : 3);
            waveXp += RiftRules.value(m.get("xp"), 0) / 3 * ("canhao".equals(kind) ? 1 : 3);
        }
        double gap = rules.waveGap(t);
        double share = RiftRules.value(macroMap("ultimos_golpes").get(s.role), 0.5);
        boolean laner = !"selva".equals(s.role);
        x.laneGoldPerS = laner ? waveGold / gap * share : 0;
        x.laneXpPerS = laner ? waveXp / gap : 0;
        // the trade: the strongest enemy champion near it, by the same power as the fights
        double danger = 0;
        double mine = Math.max(1e-9, dps(s));
        for (Seat e : seats) {
            if (e.team != s.team && !e.dead && Math.hypot(e.x - s.x, e.y - s.y) <= param("luta_distancia", 900) * 1.5) {
                danger = Math.max(danger, param("dano_troca_por_s", 0.012) * dps(e) / mine * e.hp);
            }
        }
        x.dangerPerS = danger;
        x.deathCost = param("custo_morte", 600) + (param("renascer_base", 6) + param("renascer_por_nivel", 2.5) * s.level)
                * (x.laneGoldPerS + x.laneXpPerS * 0.6);
        Tower front = null;
        for (Tower tw : towers) {
            if (tw.team == s.team && lane.equals(tw.lane) && tw.hp > 0 && (front == null || tw.order < front.order)) {
                front = tw;
            }
        }
        boolean platesUp = t < RiftRules.value(rules.map("torres", "placas").get("ate"), 360);
        x.plates = front != null && platesUp && laner ? front.plates : 0;
        x.platesPerS = danger > 0 ? param("placas_por_s_ausente", 0.03) : 0;
        double[] f = nearestFruit(s);
        if (f != null) {
            x.fruitDistance = Math.hypot(f[0] - s.x, f[1] - s.y) * 1.2;
            Map<String, Object> p = rules.map("plantas", "frutomel");
            x.fruitHeal = RiftRules.value(p.get("cura_pct"), 0.035) * RiftRules.value(p.get("frutos"), 3);
        }
        double[] base = s.team == 0 ? map.fountainBlue : map.fountainRed;
        x.baseDistance = Math.hypot(base[0] - s.x, base[1] - s.y) * 1.2;
        x.teleportReady = world.teleport(s.index) && t >= s.teleportReadyAt && teleportTarget(s) != null;
        boolean ally = false;
        for (Seat a : seats) {
            ally |= a != s && a.team == s.team && !a.dead && world.sellsToAllies(a.index) && Math.hypot(a.x - s.x, a.y - s.y) <= 1500;
        }
        x.shopsHere = world.shopsAnywhere(s.index) || ally;
        int to = s.steps;
        while (to < world.steps(s.index) && s.gold >= world.cost(s.index, to + 1) && world.sold(s.index, to, t / 60)) {
            to++;
        }
        x.itemGold = world.cost(s.index, to) - world.cost(s.index, s.steps);
        x.itemGain = to > s.steps ? world.itemGain(s.index, s.steps, to) : 0;
        return x;
    }

    private double dps(Seat s) {
        return s.stats[1] * s.stats[2] + s.stats[3] * 0.5 + 40;
    }

    /** The two-tempo decision, kept and told in the log when it is not "stay". */
    public void decided(Seat s, TwoTempo.Choice c) {
        boolean changed = s.lastChoice == null || s.lastChoice.option != c.option;
        s.lastChoice = c;
        if (!changed || c.option == TwoTempo.Option.STAY) {
            return;
        }
        String what;
        switch (c.option) {
            case HONEYFRUIT:
                what = "recuou para o Frutomel";
                break;
            case RECALL_TELEPORT:
                what = "voltou à base para voltar de Teleporte";
                break;
            case SHOP_HERE:
                what = "comprou onde estava";
                break;
            default:
                what = "voltou à base a pé";
                break;
        }
        double stay = c.values.get(TwoTempo.Option.STAY);
        note(s.team, world.name(s.index) + " " + what + ": deve ganhar " + Math.round(c.hpGain * s.stats[0]) + " de vida e perder "
                + Math.round(c.minionsLost) + " tropas (ficar: " + Math.round(stay) + "; " + what.split(" ")[0] + ": "
                + Math.round(c.values.get(c.option)) + " de ouro em " + Math.round(RiftRules.value(macroMap("dois_tempos").get("horizonte_s"), 40)) + " s)");
        decisions++;
    }

    /** How many two-tempo decisions (not "stay") the match took. */
    public int decisions;

    private void walk(Seat s, double tx, double ty) {
        double step = s.stats[4] * STEP;
        double d = Math.hypot(tx - s.x, ty - s.y);
        if (d < 30) {
            return;
        }
        if (s.path == null || Math.hypot(s.pathX - tx, s.pathY - ty) > 200) {
            s.path = map.path(s.x, s.y, tx, ty);
            s.pathAt = 0;
            s.pathX = tx;
            s.pathY = ty;
        }
        while (step > 0 && s.pathAt < s.path.length) {
            double[] p = s.path[s.pathAt];
            double left = Math.hypot(p[0] - s.x, p[1] - s.y);
            if (left <= step) {
                s.x = p[0];
                s.y = p[1];
                step -= left;
                s.pathAt++;
            } else {
                s.x += (p[0] - s.x) / left * step;
                s.y += (p[1] - s.y) / left * step;
                step = 0;
            }
        }
    }

    /** Buys what the gold covers, in the build's order and by the shop's rules. */
    private void shop(Seat s) {
        boolean bought = false;
        while (canBuy(s)) {
            s.steps++;
            bought = true;
            s.purchases.add(new double[] {Math.round(t * 10) / 10.0, s.steps});
        }
        if (bought) {
            s.stats = world.stats(s.index, s.steps, s.level, t / 60);
        }
    }

    private double clearDps(Seat s) {
        return (s.stats[1] * s.stats[2] + s.stats[3] * 0.5 + 40) * param("ritmo_monstros", 2.5);
    }

    private void clearCamp(Seat s) {
        if (Math.hypot(s.x - s.intent.x, s.y - s.intent.y) > 300) {
            return;
        }
        Camp c = camps.get(Integer.parseInt(s.intent.what));
        Map<String, Object> m = rules.map("selva", "acampamentos", c.spot.name);
        if (c.hp <= 0) {
            c.hp = RiftRules.value(m.get("vida"), 2500) * (1 + t / 2400);
        }
        c.hp -= clearDps(s) * STEP;
        s.hp = Math.max(0.15, s.hp - RiftRules.value(m.get("dda"), 80) * param("dano_monstros", 0.2) * STEP / s.stats[0]);
        if (c.hp <= 0) {
            s.gold += RiftRules.value(m.get("ouro"), 95);
            s.xp += RiftRules.value(m.get("xp"), 150);
            s.monsters++;
            c.upAt = t + RiftRules.value(m.get("renasce"), 135);
        }
    }

    /** Hits the objective it stands at; the side that lands the last hit takes the reward. */
    private void workObjective(Seat s) {
        boolean baron = "barao".equals(s.intent.what);
        double[] pit = baron ? map.baronPit : map.dragonPit;
        if (Math.hypot(s.x - pit[0], s.y - pit[1]) > pit[2] + 200) {
            return;
        }
        double dps = clearDps(s) * STEP;
        if (baron && baronHp > 0) {
            baronHp -= dps;
            if (baronHp <= 0) {
                took(s, "barao");
                baronAt = t + rules.num(210, "objetivos", "barao", "renasce");
            }
        } else if (!baron && dragonHp > 0) {
            dragonHp -= dps;
            if (dragonHp <= 0) {
                boolean elder = drakes[0] >= 3 || drakes[1] >= 3;
                took(s, elder ? "anciao" : "dragao");
                dragonAt = t + rules.num(270, "objetivos", elder ? "anciao" : "dragao", "renasce");
            }
        }
    }

    private void took(Seat s, String what) {
        Map<String, Object> m = rules.map("objetivos", what);
        s.gold += RiftRules.value(m.get("ouro"), 0);
        double duration = 90;
        for (Seat a : seats) {
            if (a.team == s.team) {
                a.xp += RiftRules.value(m.get("xp"), 0) / 5;
                a.gold += RiftRules.value(m.get("ouro_time"), 0);
            }
        }
        com.ornnplanner.arena.ArenaModel.ObjectiveBuffs b = buffs[s.team];
        if ("barao".equals(what)) {
            baronUntil[s.team] = t + duration;
            b.baron = true;
        } else if ("anciao".equals(what)) {
            b.elder = true;
        } else {
            // Wild Rift: the drakes come in the match's order (drawn by the seed); each gives its element's effect, which
            // stacks; the first drake of the match sets the soul, for the side that takes the drakes for it
            String element = dragonOrder.get(drakesTaken % dragonOrder.size());
            drakesTaken++;
            drakes[s.team]++;
            b.dragons.merge(element, 1, Integer::sum);
            note(s.team, "pegou o Dragão " + elementName(element));
            if (drakes[s.team] == (int) rules.num(3, "objetivos", "dragao", "acumulos_para_alma")) {
                b.soul = dragonOrder.get(0);
                note(s.team, "pegou a Alma " + elementName(dragonOrder.get(0)));
            }
            return;
        }
        note(s.team, "pegou " + ("barao".equals(what) ? "o Barão" : "anciao".equals(what) ? "o Ancião" : "o Dragão"));
    }

    private void tickTowersByChampions() {
        for (int team = 0; team < 2; team++) {
            if (t >= baronUntil[team]) {
                buffs[team].baron = false;
            }
        }
        for (Seat s : seats) {
            if (s.dead || s.intent == null || s.intent.goal != MacroPolicy.Goal.PUSH || t < s.busyUntil) {
                continue;
            }
            Tower next = nextTower(1 - s.team, s.intent.what);
            if (next != null && Math.hypot(s.x - next.x, s.y - next.y) <= 700) {
                hitStructure(next, structureDamage(s, next) * STEP, s);
                if (result.winner >= 0) {
                    return;
                }
            }
        }
        tickTowerShots();
    }

    /**
     * A champion's damage per second on a structure, as the arena's: attack damage plus 40% of ability power per attack,
     * through the structure's armor (and the early defense of the mid and Baron lanes), and the anti-backdoor cut when
     * no allied minions escort it.
     */
    private double structureDamage(Seat s, Tower tw) {
        Map<String, Object> all = rules.map("torres");
        double raw = (s.stats[1] + RiftRules.value(all.get("dano_estrutura_pdh"), 0.4) * s.stats[3]) * s.stats[2];
        Map<String, Object> kind = tw.lane == null ? rules.map("torres", "nexo") : rules.map("torres", "tipos", tw.kind);
        double armor = RiftRules.value(kind.get("armadura"), 0);
        Map<String, Object> early = rules.map("torres", "defesa_inicio");
        if (early != null && tw.lane != null && t < RiftRules.value(early.get("ate"), 120) && early.get("rotas") instanceof List
                && ((List<?>) early.get("rotas")).contains(tw.lane)) {
            armor += RiftRules.value(early.get("armadura"), 50);
        }
        double mult = 100 / (100 + Math.max(0, armor));
        if (!escorted(s.team, tw)) {
            Map<String, Object> cut = rules.map("torres", "anti_backdoor");
            mult *= 1 - (cut == null ? 0 : RiftRules.value(cut.get(tw.lane == null ? "nexo" : "torre"), 0));
        }
        return raw * mult;
    }

    /** A wave of this side is at the structure (within 1200), as the arena's escort for the anti-backdoor rule. */
    private boolean escorted(int team, Tower tw) {
        for (Wave w : waves) {
            if (w.team != team || w.hp.isEmpty()) {
                continue;
            }
            double[] at = onLane(w.lane, w.team, w.along);
            if (Math.hypot(at[0] - tw.x, at[1] - tw.y) <= 1200) {
                return true;
            }
        }
        return false;
    }

    /**
     * Towers and Nexus shoot champions at the coarse step, as in the arena: the nearest enemy champion in reach when no
     * enemy minions are in reach (those come first), each shot in a row on the same champion stronger
     * ({@code torres.ataques_seguidos}). Champions in a fight are left to the fight, which has the towers around.
     */
    private void tickTowerShots() {
        Map<String, Object> all = rules.map("torres");
        double reach = RiftRules.value(all.get("alcance"), 750);
        double speed = RiftRules.value(all.get("velocidade_ataque"), 0.83);
        for (Tower tw : towers) {
            if (tw.hp <= 0 || t < tw.nextShot) {
                continue;
            }
            if (minionsAt(1 - tw.team, tw, reach)) {
                tw.aim = -1;
                continue;
            }
            Seat aim = null;
            double best = reach;
            for (Seat s : seats) {
                if (s.team == tw.team || s.dead || t < s.busyUntil) {
                    continue;
                }
                double d = Math.hypot(s.x - tw.x, s.y - tw.y);
                if (d <= best) {
                    best = d;
                    aim = s;
                }
            }
            if (aim == null) {
                tw.aim = -1;
                continue;
            }
            tw.streak = aim.index == tw.aim ? tw.streak + 1 : 0;
            tw.aim = aim.index;
            tw.nextShot = t + 1 / speed;
            List<?> ramp = (List<?>) rules.map("torres", "ataques_seguidos").get("valores");
            double mult = ((Number) ramp.get(Math.min(tw.streak, ramp.size() - 1))).doubleValue();
            Map<String, Object> kind = tw.lane == null ? rules.map("torres", "nexo") : rules.map("torres", "tipos", tw.kind);
            aim.hp -= rules.towerDamage(kind, t) * mult / Math.max(1, aim.stats[0]);
            if (aim.hp <= 0) {
                aim.dead = true;
                aim.deaths++;
                aim.hp = 0;
                aim.path = null;
                aim.recallAt = -1;
                aim.teleportBack = false;
                aim.respawnAt = t + param("renascer_base", 6) + param("renascer_por_nivel", 2.5) * aim.level;
                tw.aim = -1;
                note(tw.team, world.name(aim.index) + " morreu para " + (tw.lane == null ? "o Nexo" : "a torre " + tw.kind));
            }
        }
    }

    /** Minions of this side within reach of the structure. */
    private boolean minionsAt(int team, Tower tw, double reach) {
        for (Wave w : waves) {
            if (w.team != team || w.hp.isEmpty()) {
                continue;
            }
            double[] at = onLane(w.lane, w.team, w.along);
            if (Math.hypot(at[0] - tw.x, at[1] - tw.y) <= reach) {
                return true;
            }
        }
        return false;
    }

    private double power(Seat s) {
        return s.hp * s.stats[0] * (s.stats[1] * s.stats[2] + s.stats[3] * 0.5 + 1);
    }

    /** Enemies close enough, and the micro would take it: the fight runs at the fine step. */
    private void tickFights() {
        double reach = param("luta_distancia", 900);
        for (Seat a : seats) {
            if (a.dead || t < a.busyUntil || t < a.fightAfter || a.team != 0) {
                continue;
            }
            for (Seat b : seats) {
                if (b.team == a.team || b.dead || t < b.busyUntil || t < b.fightAfter
                        || Math.hypot(a.x - b.x, a.y - b.y) > reach || !map.clear(a.x, a.y, b.x, b.y)) {
                    continue;
                }
                List<Seat> who = around((a.x + b.x) / 2, (a.y + b.y) / 2, param("luta_raio", 1300));
                double[] sides = new double[2];
                for (Seat s : who) {
                    sides[s.team] += power(s);
                }
                double ratio = Math.max(sides[0], sides[1]) / Math.max(1, Math.min(sides[0], sides[1]));
                boolean contest = a.intent != null && b.intent != null && a.intent.goal == MacroPolicy.Goal.OBJECTIVE && b.intent.goal == MacroPolicy.Goal.OBJECTIVE;
                if (contest || ratio >= param("luta_vantagem", 1.4) || rng.nextDouble() < param("luta_chance", 0.02)) {
                    runFight(who, (a.x + b.x) / 2, (a.y + b.y) / 2);
                    return;
                }
            }
        }
    }

    private List<Seat> around(double x, double y, double radius) {
        List<Seat> out = new ArrayList<>();
        for (Seat s : seats) {
            if (!s.dead && t >= s.busyUntil && Math.hypot(s.x - x, s.y - y) <= radius) {
                out.add(s);
            }
        }
        return out;
    }

    private void runFight(List<Seat> who, double x, double y) {
        List<int[]> fallen = new ArrayList<>();
        for (int i = 0; i < towers.size(); i++) {
            if (towers.get(i).hp <= 0) {
                fallen.add(new int[] {i});
            }
        }
        FightOutcome o = world.fight(who, x, y, t / 60, fallen, this);
        StringBuilder text = new StringBuilder();
        for (Seat s : who) {
            double[] r = o.after.get(s.index);
            if (r == null) {
                continue;
            }
            s.hp = r[0];
            s.x = r[1];
            s.y = r[2];
            s.gold += r[4];
            s.xp += r[5];
            s.kills += (int) r[6];
            s.busyUntil = t + o.seconds;
            s.fightAfter = t + o.seconds + param("luta_pausa", 20);
            s.path = null;
            s.recallAt = -1;
            s.teleportBack = false;
            if (r[3] > 0) {
                s.dead = true;
                s.deaths++;
                s.hp = 0;
                s.respawnAt = t + o.seconds + param("renascer_base", 6) + param("renascer_por_nivel", 2.5) * s.level;
                text.append(text.length() == 0 ? "" : ", ").append(result.players.isEmpty() ? "assento " + s.index : result.players.get(s.index).champion);
            }
        }
        MatchModel.Fight f = new MatchModel.Fight();
        f.t = Math.round(t * 10) / 10.0;
        f.x = x;
        f.y = y;
        f.seconds = o.seconds;
        f.text = who.size() + " campeões" + (text.length() == 0 ? "" : ", caíram: " + text);
        f.scenario = o.scenario;
        result.fights.add(f);
        if (text.length() > 0) {
            if (!firstBlood) {
                firstBlood = true;
            }
            note(null, "luta: caíram " + text);
        }
    }

    private void snapshot() {
        MatchModel.Second s = new MatchModel.Second();
        int n = seats.size();
        s.t = Math.round(t);
        s.xy = new double[n][];
        s.hp = new double[n];
        s.gold = new double[n];
        s.level = new int[n];
        s.items = new int[n];
        s.dead = new boolean[n];
        for (int i = 0; i < n; i++) {
            Seat x = seats.get(i);
            s.xy[i] = new double[] {Math.round(x.x), Math.round(x.y)};
            s.hp[i] = Math.round(x.hp * 1000) / 1000.0;
            s.gold[i] = Math.round(x.gold);
            s.level[i] = x.level;
            s.items[i] = x.steps;
            s.dead[i] = x.dead;
        }
        s.towers = new double[towers.size()];
        for (int i = 0; i < towers.size(); i++) {
            s.towers[i] = Math.round(towers.get(i).hp);
        }
        for (Wave w : waves) {
            double[] at = onLane(w.lane, w.team, w.along);
            s.waves.add(new double[] {Math.round(at[0]), Math.round(at[1]), w.team, w.hp.size()});
        }
        result.timeline.add(s);
    }

    public List<Seat> seats() {
        return seats;
    }
}
