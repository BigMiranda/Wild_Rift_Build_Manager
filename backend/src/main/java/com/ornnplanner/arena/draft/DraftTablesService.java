package com.ornnplanner.arena.draft;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.ArenaService;
import com.ornnplanner.arena.EffectModel;
import com.ornnplanner.arena.EffectTestService;
import com.ornnplanner.arena.discovery.CampaignService;
import com.ornnplanner.arena.fight.ChampionProfiles;
import com.ornnplanner.arena.progressao.ProgressionService;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.seed.ChampionCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rodada 20, Parte 3: the draft's precomputed tables (table {@code draft_tabelas}), made by a button of the Admin in the
 * background, like the campaigns: the 1x1 lane matchup of the champions of each lane at 3, 8 and 15 minutes (the
 * campaign's standard build, or the progression model's most likely build when there is none, timed by the planner's
 * clock), the Dragon lane's duo (2x2) and the calibration of the composition weights (5x5 fights of drawn teams and a
 * regression, as in the Atlas). Fights are the Atlas's: the single lane, the tactical brain without retreat, the
 * lifebar race of {@link EffectTestService#race}.
 */
@Service
public class DraftTablesService {

    private static final Logger log = LoggerFactory.getLogger(DraftTablesService.class);
    static final String DUO = "Dragão 2x2";
    static final String CALIBRATION = "_calibracao";
    private static final double[] DISTANCES = {700, 900, 500, 1100};
    private static final double[] LATERAL = {0, 150, -150, 300};

    /** The state of the job, read by the Admin. */
    public static class Status {
        public boolean running;
        public String stage;
        public int done;
        public int total;
        public String startedAt;
        public Double seconds;
        public String error;
        /** Seconds per stage (lane x minute, the duo, the calibration). */
        public Map<String, Double> times = new LinkedHashMap<>();
        public Map<String, Integer> counts = new LinkedHashMap<>();
        public int rows;
        public String updatedAt;
    }

    private final JdbcTemplate jdbc;
    private final DraftCatalog catalog;
    private final ChampionProfiles profiles;
    private final ChampionCatalog champions;
    private final MetaStats meta;
    private final ArenaService arena;
    private final CampaignService campaign;
    private final ProgressionService progression;
    private final Status status = new Status();
    private volatile boolean cancel;
    private Map<String, double[]> cache;
    private int cacheRows = -1;
    /** The table is looked at again at most every few seconds (a draft request asks thousands of matchups). */
    private long checkedAt;
    private static final long CHECK_EVERY_NANOS = 5_000_000_000L;

    public DraftTablesService(JdbcTemplate jdbc, DraftCatalog catalog, ChampionProfiles profiles, ChampionCatalog champions,
                              MetaStats meta, ArenaService arena, @Lazy CampaignService campaign, @Lazy ProgressionService progression) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.profiles = profiles;
        this.champions = champions;
        this.meta = meta;
        this.arena = arena;
        this.campaign = campaign;
        this.progression = progression;
    }

    // ------------------------------------------------------------------ reading

    public synchronized Status status() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM draft_tabelas", Integer.class);
        status.rows = n == null ? 0 : n;
        status.updatedAt = jdbc.queryForObject("SELECT MAX(created_at) FROM draft_tabelas", String.class);
        return status;
    }

    /** lane|a|b -> races at the minutes measured. Reloaded when the row count changes. */
    private synchronized Map<String, double[]> table() {
        if (cache != null && System.nanoTime() - checkedAt < CHECK_EVERY_NANOS) {
            return cache;
        }
        checkedAt = System.nanoTime();
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM draft_tabelas", Integer.class);
        if (cache == null || n == null || n != cacheRows) {
            Map<String, double[]> m = new LinkedHashMap<>();
            jdbc.query("SELECT lane, a, b, race FROM draft_tabelas WHERE lane <> ?", rs -> {
                String k = rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3);
                double[] old = m.get(k);
                double r = rs.getDouble(4);
                m.put(k, old == null ? new double[] {r, 1} : new double[] {old[0] + r, old[1] + 1});
            }, CALIBRATION);
            cache = m;
            cacheRows = n == null ? 0 : n;
        }
        return cache;
    }

    /** The measured lane advantage of a over b (-1..1, the mean race over the minutes), or null. */
    public Double advantage(String a, String b, String lane) {
        Map<String, double[]> t = table();
        double[] x = t.get(lane + "|" + a + "|" + b);
        if (x != null) {
            return (x[0] / x[1] - 50) / 50;
        }
        double[] y = t.get(lane + "|" + b + "|" + a);
        return y == null ? null : -(y[0] / y[1] - 50) / 50;
    }

    /** The calibrated composition multipliers (feature -> weight), empty when not calibrated. */
    public Map<String, Double> calibration() {
        Map<String, Double> out = new LinkedHashMap<>();
        jdbc.query("SELECT a, race FROM draft_tabelas WHERE lane = ? AND b = 'peso'", rs -> {
            out.put(rs.getString(1), rs.getDouble(2));
        }, CALIBRATION);
        return out;
    }

    /** The calibration's numbers (correlation, sample, cost), for the screen. */
    public Map<String, Double> calibrationInfo() {
        Map<String, Double> out = new LinkedHashMap<>();
        jdbc.query("SELECT a, race FROM draft_tabelas WHERE lane = ? AND b = 'info'", rs -> {
            out.put(rs.getString(1), rs.getDouble(2));
        }, CALIBRATION);
        return out;
    }

    // ------------------------------------------------------------------ the job

    public synchronized Status start(boolean withCalibration, List<String> onlyLanes) {
        if (status.running) {
            throw new IllegalStateException("As tabelas do draft já estão sendo calculadas.");
        }
        cancel = false;
        status.running = true;
        status.error = null;
        status.times.clear();
        status.counts.clear();
        status.done = 0;
        status.total = 0;
        status.seconds = null;
        status.startedAt = Instant.now().toString();
        Thread t = new Thread(() -> run(withCalibration, onlyLanes), "draft-tabelas");
        t.setDaemon(true);
        t.start();
        return status;
    }

    public void cancel() {
        cancel = true;
    }

    @SuppressWarnings("unchecked")
    private void run(boolean withCalibration, List<String> onlyLanes) {
        long t0 = System.nanoTime();
        try {
            Map<String, Object> cfg = catalog.section("tabelas");
            List<Number> minutes = (List<Number>) cfg.getOrDefault("minutos", List.of(3, 8, 15));
            int starts = ((Number) cfg.getOrDefault("largadas", 3)).intValue();
            double duration = ((Number) cfg.getOrDefault("duracao", 30)).doubleValue();
            int top = ((Number) cfg.getOrDefault("por_rota_com_meta", 20)).intValue();
            Prep prep = new Prep();
            for (String lane : catalog.lanes()) {
                if (cancel || onlyLanes != null && !onlyLanes.isEmpty() && !onlyLanes.contains(lane)) {
                    continue;
                }
                List<String> champs = laneChampions(lane, top);
                for (Number mn : minutes) {
                    if (cancel) {
                        break;
                    }
                    double minute = mn.doubleValue();
                    String stage = lane + " " + mn + "'";
                    long s0 = System.nanoTime();
                    List<String[]> pairs = new ArrayList<>();
                    for (int i = 0; i < champs.size(); i++) {
                        for (int j = i + 1; j < champs.size(); j++) {
                            pairs.add(new String[] {champs.get(i), champs.get(j)});
                        }
                    }
                    stage(stage, pairs.size());
                    List<double[]> races = fightPairs(prep, pairs, minute, starts, duration);
                    String now = Instant.now().toString();
                    List<Object[]> rows = new ArrayList<>();
                    for (int k = 0; k < pairs.size(); k++) {
                        if (!Double.isNaN(races.get(k)[0])) {
                            rows.add(new Object[] {lane, pairs.get(k)[0], pairs.get(k)[1], minute, races.get(k)[0], now});
                        }
                    }
                    save(lane, minute, rows);
                    time(stage, s0, pairs.size());
                }
            }
            if (!cancel && Boolean.TRUE.equals(cfg.getOrDefault("dupla_dragao", true))
                    && (onlyLanes == null || onlyLanes.isEmpty() || onlyLanes.contains("Dragão"))) {
                duo(prep, minutes, starts, duration);
            }
            if (!cancel && withCalibration) {
                calibrate(prep, starts);
            }
            status.times.put("preparo dos lutadores", prep.seconds);
            status.counts.put("preparo dos lutadores", prep.count);
        } catch (Exception e) {
            log.warn("Draft tables failed", e);
            status.error = e.getMessage() == null ? e.toString() : e.getMessage();
        } finally {
            status.seconds = (System.nanoTime() - t0) / 1e9;
            status.running = false;
            status.stage = cancel ? "cancelado" : null;
            synchronized (this) {
                cache = null;
            }
        }
    }

    /** A lane's rows at a minute, replaced in one transaction (the pool has one connection: keep writes short). */
    private void save(String lane, double minute, List<Object[]> rows) {
        jdbc.execute((java.sql.Connection c) -> {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (java.sql.PreparedStatement del = c.prepareStatement("DELETE FROM draft_tabelas WHERE lane = ? AND minute = ?");
                 java.sql.PreparedStatement ins = c.prepareStatement(
                         "INSERT OR REPLACE INTO draft_tabelas (lane, a, b, minute, race, created_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                del.setString(1, lane);
                del.setDouble(2, minute);
                del.executeUpdate();
                for (Object[] r : rows) {
                    ins.setString(1, (String) r[0]);
                    ins.setString(2, (String) r[1]);
                    ins.setString(3, (String) r[2]);
                    ins.setDouble(4, (Double) r[3]);
                    ins.setDouble(5, (Double) r[4]);
                    ins.setString(6, (String) r[5]);
                    ins.addBatch();
                }
                ins.executeBatch();
                c.commit();
            } catch (java.sql.SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
            return null;
        });
        synchronized (this) {
            cache = null;
        }
    }

    private void stage(String stage, int total) {
        status.stage = stage;
        status.done = 0;
        status.total = total;
    }

    private void time(String stage, long s0, int n) {
        status.times.put(stage, (System.nanoTime() - s0) / 1e9);
        status.counts.put(stage, n);
    }

    /** The champions measured in a lane: the most played by the meta when imported, else every one of the lane. */
    List<String> laneChampions(String lane, int top) {
        List<String> all = new ArrayList<>();
        for (ChampionProfiles.Profile p : profiles.all()) {
            if (p.lanes.contains(lane)) {
                all.add(p.name);
            }
        }
        Collections.sort(all);
        Map<String, Map<String, DraftModel.Meta>> m = meta.of(null);
        if (m.isEmpty()) {
            return all;
        }
        all.sort(Comparator.comparingDouble((String c) -> {
            DraftModel.Meta x = m.getOrDefault(c, Map.of()).get(lane);
            return x == null ? 0 : -x.presence;
        }).thenComparing(c -> c));
        return new ArrayList<>(all.subList(0, Math.min(top, all.size())));
    }

    /** The prepared fighters, one per champion, minute and team (prepared once, on this thread). */
    private final class Prep {
        final Map<String, Arena.Input> inputs = new ConcurrentHashMap<>();
        double seconds;
        int count;

        Arena.Input of(String champion, double minute, int team) {
            String k = champion + "|" + minute + "|" + team;
            Arena.Input in = inputs.get(k);
            if (in == null) {
                long t0 = System.nanoTime();
                in = prepare(champion, minute, team);
                seconds += (System.nanoTime() - t0) / 1e9;
                count++;
                inputs.put(k, in);
            }
            return in;
        }
    }

    /** A champion's fighter at a minute: the campaign's standard build, or the progression's most likely one. */
    Arena.Input prepare(String champion, double minute, int team) {
        BuildRepository.Build b = buildOf(champion);
        ArenaModel.Slot me = new ArenaModel.Slot();
        me.team = team;
        me.build = b;
        me.minute = minute;
        me.brain = ArenaService.STAND;
        ArenaModel.Slot dummy = new ArenaModel.Slot();
        dummy.team = 1 - team;
        dummy.dummy = new ArenaModel.Dummy();
        ArenaModel.Scenario sc = new ArenaModel.Scenario();
        sc.fighters = team == 0 ? List.of(me, dummy) : List.of(dummy, me);
        return arena.prepareSlots(sc).get(team == 0 ? 0 : 1);
    }

    private final Map<String, BuildRepository.Build> builds = new ConcurrentHashMap<>();

    /** The build a champion plays in the tables (cached for the job). */
    BuildRepository.Build buildOf(String champion) {
        return builds.computeIfAbsent(champion, c -> {
            BuildRepository.Build b = campaign.standardBuild(unitOf(c));
            if (b.steps == null || b.steps.isEmpty()) {
                b.steps = new ArrayList<>();
                ProgressionService.Answer a = progression.answer(c, List.of(), List.of(), List.of(), null, 1);
                if (!a.continuations.isEmpty()) {
                    for (ProgressionService.Step s : a.continuations.get(0).steps) {
                        b.steps.add(new BuildRepository.Step(s.itemId, true));
                    }
                }
            }
            return b;
        });
    }

    String unitOf(String champion) {
        for (Map<String, Object> s : champions.summaries()) {
            if (champion.equals(s.get("nome"))) {
                return String.valueOf(s.get("code"));
            }
        }
        return champion.toUpperCase();
    }

    /** Side A's race (0..100) in each pair, the mean over the starts; NaN when a fighter could not be prepared. */
    private List<double[]> fightPairs(Prep prep, List<String[]> pairs, double minute, int starts, double duration) {
        List<double[]> out = new ArrayList<>();
        final int batch = 200;
        for (int from = 0; from < pairs.size() && !cancel; from += batch) {
            List<Callable<double[]>> jobs = new ArrayList<>();
            for (String[] p : pairs.subList(from, Math.min(pairs.size(), from + batch))) {
                Arena.Input a;
                Arena.Input b;
                try {
                    a = prep.of(p[0], minute, 0);
                    b = prep.of(p[1], minute, 1);
                } catch (Exception e) {
                    jobs.add(() -> new double[] {Double.NaN});
                    continue;
                }
                jobs.add(() -> {
                    double sum = 0;
                    for (int k = 0; k < starts; k++) {
                        sum += race(List.of(a), List.of(b), k, duration);
                    }
                    return new double[] {sum / starts};
                });
            }
            out.addAll(ArenaService.parallel(jobs));
            status.done = Math.min(pairs.size(), from + batch);
        }
        while (out.size() < pairs.size()) {
            out.add(new double[] {Double.NaN});
        }
        return out;
    }

    /** The race of side A (its first fighter's view, with its allies) in start k. */
    double race(List<Arena.Input> sideA, List<Arena.Input> sideB, int k, double duration) {
        return raceWithTrade(sideA, sideB, k, duration, -1)[0];
    }

    /**
     * Side A's race (0..100) in start k and, when {@code tradeAt} &gt;= 0, the first trade: side A's health lead at that
     * second (the share of the lifebars lost by B minus A's, -100..100), from the per-second health trail.
     */
    public double[] raceWithTrade(List<Arena.Input> sideA, List<Arena.Input> sideB, int k, double duration, double tradeAt) {
        List<Arena.Input> all = new ArrayList<>();
        double d = DISTANCES[k % DISTANCES.length];
        double lat = LATERAL[(k + k / DISTANCES.length) % LATERAL.length];
        for (int i = 0; i < sideA.size(); i++) {
            Arena.Input x = sideA.get(i).copy();
            x.x = 4600 - 150 * i;
            x.y = 120 * i;
            x.healthTrail = true;
            all.add(x);
        }
        List<Arena.Input> them = new ArrayList<>();
        for (int i = 0; i < sideB.size(); i++) {
            Arena.Input y = sideB.get(i).copy();
            y.x = 4600 + d + 150 * i;
            y.y = lat + 120 * i;
            y.healthTrail = true;
            them.add(y);
        }
        // EffectTestService.race reads [me, the other side..., my allies...]
        List<Arena.Input> order = new ArrayList<>();
        order.add(all.get(0));
        order.addAll(them);
        order.addAll(all.subList(1, all.size()));
        ArenaModel.Result res = arena.runPlain(order, duration);
        EffectModel.Measures m = new EffectModel.Measures();
        EffectTestService.race(m, res, 0, them.size(), all.size() - 1);
        double trade = 0;
        if (tradeAt >= 0) {
            double[] lostA = new double[2];
            double[] lostB = new double[2];
            for (int i = 0; i < res.fighters.size(); i++) {
                ArenaModel.FighterStats f = res.fighters.get(i);
                boolean mine = i == 0 || i > them.size();
                double h = healthAt(f, tradeAt);
                double[] acc = mine ? lostA : lostB;
                acc[0] += Math.max(0, f.maxHealth - h);
                acc[1] += f.maxHealth;
            }
            trade = 100 * (lostB[0] / Math.max(1, lostB[1]) - lostA[0] / Math.max(1, lostA[1]));
        }
        return new double[] {m.race, trade};
    }

    /** A fighter's health at a second of the fight (its trail; dead = 0; past the trail = its end health). */
    static double healthAt(ArenaModel.FighterStats f, double second) {
        if (f.deathTime != null && f.deathTime <= second) {
            return 0;
        }
        List<Double> t = f.healthTrail;
        if (t == null || t.isEmpty()) {
            return Math.max(0, f.health);
        }
        int i = (int) Math.floor(second);
        return Math.max(0, i < t.size() ? t.get(i) : f.health);
    }

    /** The Dragon lane's duo: the most played (or first) marksmen and supports, every duo against every other. */
    @SuppressWarnings("unchecked")
    private void duo(Prep prep, List<Number> minutes, int starts, double duration) {
        int n = ((Number) catalog.section("tabelas").getOrDefault("dupla_por_rota", 4)).intValue();
        List<String> adcs = laneChampions("Dragão", n);
        List<String> sups = laneChampions("Suporte", n);
        adcs = new ArrayList<>(adcs.subList(0, Math.min(n, adcs.size())));
        sups = new ArrayList<>(sups.subList(0, Math.min(n, sups.size())));
        List<String[]> duos = new ArrayList<>();
        for (String a : adcs) {
            for (String s : sups) {
                if (!a.equals(s)) {
                    duos.add(new String[] {a, s});
                }
            }
        }
        for (Number mn : minutes) {
            if (cancel) {
                return;
            }
            double minute = mn.doubleValue();
            String stage = DUO + " " + mn + "'";
            long s0 = System.nanoTime();
            List<String[][]> pairs = new ArrayList<>();
            for (int i = 0; i < duos.size(); i++) {
                for (int j = i + 1; j < duos.size(); j++) {
                    String[] x = duos.get(i);
                    String[] y = duos.get(j);
                    if (!x[0].equals(y[0]) && !x[1].equals(y[1]) && !x[0].equals(y[1]) && !x[1].equals(y[0])) {
                        pairs.add(new String[][] {x, y});
                    }
                }
            }
            stage(stage, pairs.size());
            List<Callable<Double>> jobs = new ArrayList<>();
            for (String[][] p : pairs) {
                List<Arena.Input> a = List.of(prep.of(p[0][0], minute, 0), prep.of(p[0][1], minute, 0));
                List<Arena.Input> b = List.of(prep.of(p[1][0], minute, 1), prep.of(p[1][1], minute, 1));
                jobs.add(() -> {
                    double sum = 0;
                    for (int k = 0; k < starts; k++) {
                        sum += race(a, b, k, duration);
                    }
                    return sum / starts;
                });
            }
            List<Double> races = ArenaService.parallel(jobs);
            String now = Instant.now().toString();
            List<Object[]> rows = new ArrayList<>();
            for (int k = 0; k < pairs.size(); k++) {
                rows.add(new Object[] {DUO, pairs.get(k)[0][0] + "+" + pairs.get(k)[0][1], pairs.get(k)[1][0] + "+" + pairs.get(k)[1][1], minute, races.get(k), now});
            }
            save(DUO, minute, rows);
            status.done = pairs.size();
            time(stage, s0, pairs.size());
        }
    }

    // ------------------------------------------------------------------ the calibration of the composition weights

    static final List<String> FEATURES = List.of("dano_magico", "dano_fisico", "linha_de_frente", "controle", "iniciacao", "alcance_longo", "cura_escudo");

    /**
     * Drawn teams (one champion per lane) fight 5x5 at minute 15 on the single lane; the composition needs of each team
     * (DraftModel.Champ features) against the race, by a ridge regression on the differences between the teams. The
     * multipliers are the coefficients over their mean size (clamped to 0..2). Partial: no map and no whole match.
     */
    @SuppressWarnings("unchecked")
    private void calibrate(Prep prep, int starts) {
        Map<String, Object> cfg = catalog.section("tabelas");
        int samples = ((Number) cfg.getOrDefault("calibracao_amostras", 60)).intValue();
        String stage = "calibração (5x5, " + samples + " lutas)";
        long s0 = System.nanoTime();
        stage(stage, samples);
        Map<String, List<String>> byLane = new LinkedHashMap<>();
        for (String lane : catalog.lanes()) {
            byLane.put(lane, laneChampions(lane, 12));
        }
        Map<String, DraftModel.Champ> champs = new LinkedHashMap<>();
        DraftService.champs(profiles).forEach(c -> champs.put(c.name, c));
        Random rnd = new Random(20);
        List<double[]> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<Callable<Double>> jobs = new ArrayList<>();
        for (int s = 0; s < samples; s++) {
            List<String> blue = new ArrayList<>();
            List<String> red = new ArrayList<>();
            for (List<String> pool : byLane.values()) {
                List<String> free = new ArrayList<>(pool);
                free.removeAll(blue);
                free.removeAll(red);
                if (free.size() < 2) {
                    continue;
                }
                blue.add(free.remove(rnd.nextInt(free.size())));
                red.add(free.remove(rnd.nextInt(free.size())));
            }
            double[] fb = features(blue, champs);
            double[] fr = features(red, champs);
            double[] diff = new double[FEATURES.size()];
            for (int i = 0; i < diff.length; i++) {
                diff[i] = fb[i] - fr[i];
            }
            xs.add(diff);
            List<Arena.Input> a = new ArrayList<>();
            List<Arena.Input> b = new ArrayList<>();
            blue.forEach(c -> a.add(prep.of(c, 15, 0)));
            red.forEach(c -> b.add(prep.of(c, 15, 1)));
            jobs.add(() -> {
                double sum = 0;
                for (int k = 0; k < Math.max(1, starts - 1); k++) {
                    sum += race(a, b, k, 40);
                }
                return sum / Math.max(1, starts - 1);
            });
        }
        ys.addAll(ArenaService.parallel(jobs));
        status.done = samples;
        double[] beta = ridge(xs, ys, 1.0);
        double mean = 0;
        for (double v : beta) {
            mean += Math.abs(v);
        }
        mean = mean / beta.length;
        String now = Instant.now().toString();
        jdbc.update("DELETE FROM draft_tabelas WHERE lane = ?", CALIBRATION);
        for (int i = 0; i < beta.length; i++) {
            double mult = mean <= 0 ? 1 : Math.max(0, Math.min(2, beta[i] / mean));
            jdbc.update("INSERT INTO draft_tabelas (lane, a, b, minute, race, created_at) VALUES (?, ?, 'peso', 0, ?, ?)",
                    CALIBRATION, FEATURES.get(i), mult, now);
        }
        double r = correlation(xs, ys, beta);
        double cv = crossValidated(xs, ys);
        double secs = (System.nanoTime() - s0) / 1e9;
        for (Object[] kv : new Object[][] {{"correlacao", r}, {"correlacao_validada", cv}, {"amostras", (double) samples}, {"segundos", secs}, {"parcial", 1.0}}) {
            jdbc.update("INSERT INTO draft_tabelas (lane, a, b, minute, race, created_at) VALUES (?, ?, 'info', 0, ?, ?)",
                    CALIBRATION, kv[0], kv[1], now);
        }
        time(stage, s0, samples);
    }

    /** A team's composition needs met, in FEATURES order. */
    static double[] features(List<String> team, Map<String, DraftModel.Champ> champs) {
        double[] f = new double[FEATURES.size()];
        int n = 0;
        for (String name : team) {
            DraftModel.Champ c = champs.get(name);
            if (c == null) {
                continue;
            }
            n++;
            f[0] += c.magic;
            f[1] += c.physical;
            f[2] += c.frontline ? 1 : 0;
            f[3] += c.control ? 1 : 0;
            f[4] += c.initiation ? 1 : 0;
            f[5] += c.longRange ? 1 : 0;
            f[6] += c.healShield ? 1 : 0;
        }
        if (n > 0) {
            // damage balance: how far the share of each type is from missing (both near 0.5 is best)
            double m = f[0] / n;
            double p = f[1] / n;
            f[0] = Math.min(m, 0.5);
            f[1] = Math.min(p, 0.5);
        }
        return f;
    }

    /** Ridge least squares (with an intercept, not penalized): the coefficients of the features. */
    static double[] ridge(List<double[]> xs, List<Double> ys, double lambda) {
        int p = xs.isEmpty() ? 0 : xs.get(0).length;
        int q = p + 1;
        double[][] a = new double[q][q];
        double[] b = new double[q];
        for (int r = 0; r < xs.size(); r++) {
            double[] x = new double[q];
            x[0] = 1;
            System.arraycopy(xs.get(r), 0, x, 1, p);
            double y = ys.get(r);
            for (int i = 0; i < q; i++) {
                b[i] += x[i] * y;
                for (int j = 0; j < q; j++) {
                    a[i][j] += x[i] * x[j];
                }
            }
        }
        for (int i = 1; i < q; i++) {
            a[i][i] += lambda;
        }
        double[] sol = solve(a, b);
        double[] out = new double[p];
        System.arraycopy(sol, 1, out, 0, p);
        return out;
    }

    private static double[] solve(double[][] a, double[] b) {
        int n = b.length;
        double[][] m = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n] = b[i];
        }
        for (int c = 0; c < n; c++) {
            int piv = c;
            for (int r = c + 1; r < n; r++) {
                if (Math.abs(m[r][c]) > Math.abs(m[piv][c])) {
                    piv = r;
                }
            }
            double[] tmp = m[c];
            m[c] = m[piv];
            m[piv] = tmp;
            if (Math.abs(m[c][c]) < 1e-12) {
                continue;
            }
            for (int r = 0; r < n; r++) {
                if (r != c) {
                    double f = m[r][c] / m[c][c];
                    for (int k = c; k <= n; k++) {
                        m[r][k] -= f * m[c][k];
                    }
                }
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = Math.abs(m[i][i]) < 1e-12 ? 0 : m[i][n] / m[i][i];
        }
        return x;
    }

    private static double predict(double[] beta, double[] x, double intercept) {
        double s = intercept;
        for (int i = 0; i < beta.length; i++) {
            s += beta[i] * x[i];
        }
        return s;
    }

    /** Pearson correlation of the fitted and the measured races (in sample). */
    static double correlation(List<double[]> xs, List<Double> ys, double[] beta) {
        double my = ys.stream().mapToDouble(d -> d).average().orElse(0);
        List<Double> fit = new ArrayList<>();
        for (double[] x : xs) {
            fit.add(predict(beta, x, 0));
        }
        return pearson(fit, ys, my);
    }

    /** 5-fold cross-validated correlation (each fold fitted without it). */
    static double crossValidated(List<double[]> xs, List<Double> ys) {
        List<Double> pred = new ArrayList<>(Collections.nCopies(xs.size(), 0.0));
        for (int f = 0; f < 5; f++) {
            List<double[]> tx = new ArrayList<>();
            List<Double> ty = new ArrayList<>();
            for (int i = 0; i < xs.size(); i++) {
                if (i % 5 != f) {
                    tx.add(xs.get(i));
                    ty.add(ys.get(i));
                }
            }
            double[] beta = ridge(tx, ty, 1.0);
            for (int i = f; i < xs.size(); i += 5) {
                pred.set(i, predict(beta, xs.get(i), 0));
            }
        }
        return pearson(pred, ys, ys.stream().mapToDouble(d -> d).average().orElse(0));
    }

    private static double pearson(List<Double> a, List<Double> b, double mb) {
        double ma = a.stream().mapToDouble(d -> d).average().orElse(0);
        double sab = 0;
        double saa = 0;
        double sbb = 0;
        for (int i = 0; i < a.size(); i++) {
            sab += (a.get(i) - ma) * (b.get(i) - mb);
            saa += (a.get(i) - ma) * (a.get(i) - ma);
            sbb += (b.get(i) - mb) * (b.get(i) - mb);
        }
        return saa <= 0 || sbb <= 0 ? 0 : sab / Math.sqrt(saa * sbb);
    }
}
