package com.ornnplanner.arena.calc;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.ArenaService;
import com.ornnplanner.arena.EffectModel;
import com.ornnplanner.arena.EffectTestService;
import com.ornnplanner.arena.OffenseModel;
import com.ornnplanner.arena.OffenseTestService;
import com.ornnplanner.arena.fight.BuildArchetypes;
import com.ornnplanner.arena.fight.ChampionProfiles;
import com.ornnplanner.arena.fight.FighterBuilder;
import com.ornnplanner.engine.Model;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.service.PlannerService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a "good build" is (Rodada 9, part 1): the score of a build at a minute by the champion's class and the build's
 * archetype, over a set of evaluation scenarios (the defensive report, the offensive targets, optionally duels), with
 * the weights of {@code seed/nota_builds_<patch>.yml}. Robust: the weighted mean and the 20th percentile of the scenarios,
 * and over several minutes. No rule names a champion, item or rune.
 */
@Service
public class BuildScore {

    public static final String DEFENSIVE = "defensiva";
    public static final String OFFENSIVE = "ofensiva";
    public static final String UTILITY = "utilidade";
    public static final String DUELS = "duelos";
    public static final List<String> PARTS = List.of(DEFENSIVE, OFFENSIVE, UTILITY, DUELS);

    /** One scenario's reading, in points (0 = as the reference with no items, 100 = perfect). */
    public static class Reading {
        public String part;
        public String key;
        public String name;
        public double points;
        /** The raw index the points came from (defensive score, offensive index). */
        public Double index;
        public double weight = 1;
    }

    /** The score of a build at one minute. */
    public static class Score {
        public double minute;
        public String champion;
        public String championClass;
        public String archetype;
        /** The weights used, normalized (class + archetype). */
        public Map<String, Double> weights = new LinkedHashMap<>();
        public Map<String, Double> parts = new LinkedHashMap<>();
        public double mean;
        public double p20;
        public double floorPenalty;
        public double total;
        /** The chance of reaching this minute the robust score gave it (1 = the first minute). */
        public double reach = 1;
        public List<Reading> readings = new ArrayList<>();
    }

    /** The score over minutes: each minute's, and their weighted mean (the early ones weigh more). */
    public static class Robust {
        public List<Score> minutes = new ArrayList<>();
        public double total;
        public double mean;
        public double p20;
        /** Share of the scenario-minutes where it beats the reference build (null without one). */
        public Double beatsReference;
    }

    /**
     * Options of a reading (the discovery's funnel): the reading modes and starts instead of the YAML's, and at most
     * {@code limit} scenarios of each report (0 = all), for the quick fights and the confirmation.
     */
    public static class Options {
        public String defensiveMode;
        public String offensiveMode;
        public Integer starts;
        public int limit;

        public static Options quick() {
            Options o = new Options();
            o.defensiveMode = EffectModel.AS_PLAYED;
            o.offensiveMode = EffectModel.AS_PLAYED;
            o.limit = 3;
            return o;
        }

        /** Every scenario, one start each (the rune search's reading at one minute). */
        public static Options single() {
            Options o = new Options();
            o.defensiveMode = EffectModel.AS_PLAYED;
            o.offensiveMode = EffectModel.AS_PLAYED;
            return o;
        }

        /** Starts the search never saw: the mean of other starts on both sides. */
        public static Options confirm() {
            Options o = new Options();
            o.defensiveMode = EffectModel.STARTS;
            o.offensiveMode = EffectModel.STARTS;
            o.starts = 6;
            return o;
        }
    }

    private final EffectTestService effect;
    private final OffenseTestService offense;
    private final ArenaService arena;
    private final ChampionProfiles profiles;
    private final BuildArchetypes archetypes;
    private final PlannerService planner;
    private final BuildRepository builds;
    private Map<String, Object> rules = Map.of();

    public BuildScore(EffectTestService effect, OffenseTestService offense, ArenaService arena, ChampionProfiles profiles,
                      BuildArchetypes archetypes, PlannerService planner, BuildRepository builds,
                      @Value("${planner.seed.build-score:classpath:seed/nota_builds_7_3d.yml}") Resource file) {
        this.effect = effect;
        this.offense = offense;
        this.arena = arena;
        this.profiles = profiles;
        this.archetypes = archetypes;
        this.planner = planner;
        this.builds = builds;
        try (InputStream in = file.getInputStream()) {
            rules = new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + file, e);
        }
    }

    public Map<String, Object> rules() {
        return rules;
    }

    // ------------------------------------------------------------------ weights

    /** The champion's main class (the first of its official ones, or the suggested one); Especialista when unknown. */
    public String classOf(BuildRepository.Build b) {
        FighterBuilder.Combatant c = new FighterBuilder.Combatant();
        c.build = b;
        List<String> cs = profiles.classesOf(arena.championOf(c));
        return cs.isEmpty() ? "Especialista" : cs.get(0);
    }

    public String championOf(BuildRepository.Build b) {
        FighterBuilder.Combatant c = new FighterBuilder.Combatant();
        c.build = b;
        return arena.championOf(c);
    }

    /** The class's weights plus the archetype's adjustments, normalized (negative ones count as 0). */
    @SuppressWarnings("unchecked")
    public Map<String, Double> weights(String championClass, String archetype) {
        Map<String, Object> classes = (Map<String, Object>) rules.getOrDefault("classes", Map.of());
        Map<String, Object> mine = (Map<String, Object>) classes.getOrDefault(championClass,
                classes.getOrDefault("Especialista", Map.of(DEFENSIVE, 0.5, OFFENSIVE, 0.5)));
        Map<String, Double> w = new LinkedHashMap<>();
        for (String p : PARTS) {
            w.put(p, num(mine.get(p), 0));
        }
        Map<String, Object> adj = archetype == null ? null
                : (Map<String, Object>) ((Map<String, Object>) rules.getOrDefault("arquetipos", Map.of())).get(archetype);
        if (adj != null) {
            adj.forEach((k, v) -> w.computeIfPresent(k, (x, old) -> old + num(v, 0)));
        }
        Map<String, Object> duels = (Map<String, Object>) rules.getOrDefault("duelos", Map.of());
        w.put(DUELS, Math.max(w.get(DUELS), num(duels.get("peso"), 0)));
        double sum = 0;
        for (String p : PARTS) {
            w.put(p, Math.max(0, w.get(p)));
            sum += w.get(p);
        }
        if (sum <= 0) {
            w.put(DEFENSIVE, 0.5);
            w.put(OFFENSIVE, 0.5);
            sum = 1;
        }
        for (String p : PARTS) {
            w.put(p, w.get(p) / sum);
        }
        return w;
    }

    @SuppressWarnings("unchecked")
    double floor(String championClass) {
        Map<String, Object> classes = (Map<String, Object>) rules.getOrDefault("classes", Map.of());
        Map<String, Object> mine = (Map<String, Object>) classes.getOrDefault(championClass, Map.of());
        return mine.get("piso") == null ? Double.NEGATIVE_INFINITY : num(mine.get("piso"), 0);
    }

    /** Offensive index to points: the share of the reference's time to kill the build saves (index 200 = 50). */
    public static double offensivePoints(double index) {
        return 100 * (1 - 100 / Math.max(1, index));
    }

    // ------------------------------------------------------------------ the score

    /** The score of a build at a minute. */
    @SuppressWarnings("unchecked")
    public Score score(BuildRepository.Build build, double minute) {
        return score(build, minute, null);
    }

    /** The score with a reading's options (null = the YAML's). */
    @SuppressWarnings("unchecked")
    public Score score(BuildRepository.Build build, double minute, Options o) {
        Score s = new Score();
        s.minute = minute;
        s.champion = championOf(build);
        s.championClass = classOf(build);
        BuildArchetypes.Archetype a = archetypes.of(build, minute);
        s.archetype = a == null ? null : a.key;
        s.weights = weights(s.championClass, s.archetype);
        Map<String, Object> supportRules = supportRules(s.champion, s.championClass, s.archetype);
        if (supportRules != null) {
            // Rodada 14: a support is worth what it does for the carry
            s.weights.put(UTILITY, s.weights.get(UTILITY) + num(supportRules.get("peso_utilidade"), 0));
            double sum = s.weights.values().stream().mapToDouble(Double::doubleValue).sum();
            s.weights.replaceAll((k, v) -> v / Math.max(1e-9, sum));
        }
        Map<String, Object> read = (Map<String, Object>) rules.getOrDefault("leitura", Map.of());
        if (s.weights.get(DEFENSIVE) > 0 || s.weights.get(UTILITY) > 0 || floor(s.championClass) > Double.NEGATIVE_INFINITY) {
            defensive(build, minute, o != null && o.defensiveMode != null ? o.defensiveMode
                    : String.valueOf(read.getOrDefault("defensiva", EffectModel.FIXED)), o == null ? null : o.starts, o == null ? 0 : o.limit, s);
        }
        if (s.weights.get(OFFENSIVE) > 0) {
            OffenseModel.Request req = new OffenseModel.Request();
            req.build = build;
            req.minute = minute;
            req.readMode = o != null && o.offensiveMode != null ? o.offensiveMode : String.valueOf(read.getOrDefault("ofensiva", EffectModel.STARTS));
            req.starts = o != null && o.starts != null ? o.starts : (int) num(read.get("largadas"), 3);
            List<OffenseModel.Entry> targets = limited(offense.report().targets, o);
            for (OffenseModel.Entry e : targets) {
                OffenseModel.Job j = new OffenseModel.Job();
                j.targetKey = e.key;
                req.jobs.add(j);
            }
            List<OffenseModel.Outcome> out = offense.evaluate(req);
            for (int i = 0; i < out.size(); i++) {
                if (out.get(i).index == null) {
                    continue;
                }
                Reading r = new Reading();
                r.part = OFFENSIVE;
                r.key = targets.get(i).key;
                r.name = out.get(i).targetName;
                r.index = out.get(i).index;
                r.points = offensivePoints(out.get(i).index);
                r.weight = targets.get(i).weight;
                s.readings.add(r);
            }
        }
        if (supportRules != null && s.weights.get(UTILITY) > 0) {
            support(build, minute, supportRules, o == null ? null : o.starts, s);
        }
        if (s.weights.get(DUELS) > 0 && (o == null || o.limit == 0)) {
            duels(build, minute, s);
        }
        combine(s, floor(s.championClass), num(rules.get("piso_peso"), 1));
        return s;
    }

    @SuppressWarnings("unchecked")
    private void defensive(BuildRepository.Build build, double minute, String mode, Integer starts, int limit, Score s) {
        EffectModel.Report rep = effect.report(String.valueOf(rules.getOrDefault("relatorio_defensivo", "basico")));
        if (limit > 0 && rep.tests.size() > limit) {
            rep.tests = new ArrayList<>(rep.tests.subList(0, limit));
        }
        EffectModel.Request req = new EffectModel.Request();
        req.build = build;
        req.minute = minute;
        req.readMode = mode;
        req.starts = starts;
        for (EffectModel.Entry e : rep.tests) {
            EffectModel.Job j = new EffectModel.Job();
            j.testKey = e.key;
            req.jobs.add(j);
        }
        List<EffectModel.Outcome> out = effect.evaluate(req);
        Map<String, Object> util = (Map<String, Object>) rules.getOrDefault("utilidade", Map.of());
        double ccFactor = num(util.get("controle_por_10s"), 25);
        double absorbed = num(util.get("absorvido"), 1);
        double usum = 0;
        int un = 0;
        for (int i = 0; i < out.size(); i++) {
            EffectModel.Outcome o = out.get(i);
            if (o.index == null) {
                continue;
            }
            Reading r = new Reading();
            r.part = DEFENSIVE;
            r.key = rep.tests.get(i).key;
            r.name = o.testName;
            r.index = o.index;
            r.points = o.index;
            r.weight = rep.tests.get(i).weight;
            s.readings.add(r);
            double cc = o.build.fightLength > 0 ? o.build.controlApplied / o.build.fightLength * 10 * ccFactor : 0;
            double u = Math.min(100, cc + (o.build.absorbedShare == null ? 0 : o.build.absorbedShare * absorbed));
            usum += u;
            un++;
        }
        if (un > 0) {
            Reading r = new Reading();
            r.part = UTILITY;
            r.key = "utilidade";
            r.name = "Utilidade";
            r.points = usum / un;
            s.readings.add(r);
        }
    }

    /**
     * Rodada 14: the support rules (nota_builds' {@code utilidade.suporte}) when this champion plays support: its class,
     * one of its lanes or the build's archetype is in them; null otherwise.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> supportRules(String champion, String championClass, String archetype) {
        Map<String, Object> util = (Map<String, Object>) rules.getOrDefault("utilidade", Map.of());
        Map<String, Object> sup = (Map<String, Object>) util.get("suporte");
        if (sup == null) {
            return null;
        }
        List<Object> classes = (List<Object>) sup.getOrDefault("classes", List.of());
        List<Object> lanes = (List<Object>) sup.getOrDefault("rotas", List.of());
        List<Object> kinds = (List<Object>) sup.getOrDefault("arquetipos", List.of());
        com.ornnplanner.arena.fight.ChampionProfiles.Profile p = profiles.of(champion);
        boolean lane = p != null && p.lanes.stream().anyMatch(lanes::contains);
        return classes.contains(championClass) || lane || archetype != null && kinds.contains(archetype) ? sup : null;
    }

    /**
     * Rodada 14: the support report (the build next to an allied carry the attackers go after), each scenario a utility
     * reading: the carry's time alive, its damage, the control and the enemy damage cut, against the reference.
     */
    @SuppressWarnings("unchecked")
    private void support(BuildRepository.Build build, double minute, Map<String, Object> sup, Integer starts, Score s) {
        EffectModel.Report rep = effect.report(String.valueOf(sup.getOrDefault("relatorio", "suporte")));
        Map<String, Object> w = (Map<String, Object>) sup.getOrDefault("pesos", Map.of());
        Map<String, Object> util = (Map<String, Object>) rules.getOrDefault("utilidade", Map.of());
        double ccFactor = num(util.get("controle_por_10s"), 25);
        EffectModel.Request req = new EffectModel.Request();
        req.build = build;
        req.minute = minute;
        req.readMode = EffectModel.AS_PLAYED;
        req.starts = starts;
        for (EffectModel.Entry e : rep.tests) {
            EffectModel.Job j = new EffectModel.Job();
            j.testKey = e.key;
            req.jobs.add(j);
        }
        List<EffectModel.Outcome> out = effect.evaluate(req);
        Map<String, EffectModel.Test> tests = effect.presets();
        for (int i = 0; i < out.size(); i++) {
            EffectModel.Outcome o = out.get(i);
            if (o.build == null || o.reference == null) {
                continue;
            }
            EffectModel.Test t = tests.get(rep.tests.get(i).key);
            double duration = t == null ? 60 : t.duration;
            Reading r = new Reading();
            r.part = UTILITY;
            r.key = "suporte|" + rep.tests.get(i).key;
            r.name = o.testName;
            r.points = supportPoints(o.build, o.reference, duration, ccFactor, w);
            r.weight = rep.tests.get(i).weight;
            s.readings.add(r);
        }
    }

    /** One support reading: the weighted sum of its parts, each -100..100 against the reference. */
    public static double supportPoints(EffectModel.Measures b, EffectModel.Measures ref, double duration, double ccFactor, Map<String, Object> w) {
        double aliveB = b.alliesAlive == null ? 0 : b.alliesAlive;
        double aliveR = ref.alliesAlive == null ? 0 : ref.alliesAlive;
        double alive = clamp(100 * (aliveB - aliveR) / Math.max(1, duration - aliveR));
        double rateB = b.allyDealt / Math.max(1e-6, b.fightLength);
        double rateR = ref.allyDealt / Math.max(1e-6, ref.fightLength);
        double damage = clamp(100 * (rateB - rateR) / Math.max(1, rateR));
        double cc = clamp(b.fightLength > 0 ? b.controlApplied / b.fightLength * 10 * ccFactor : 0);
        double enemyB = b.enemyDealt / Math.max(1e-6, b.fightLength);
        double enemyR = ref.enemyDealt / Math.max(1e-6, ref.fightLength);
        double cut = clamp(100 * (enemyR - enemyB) / Math.max(1, enemyR));
        return num(w.get("carry_vivo"), 0.35) * alive + num(w.get("carry_dano"), 0.25) * damage
                + num(w.get("controle"), 0.2) * cc + num(w.get("dano_cortado"), 0.2) * cut;
    }

    private static double clamp(double v) {
        return Math.max(-100, Math.min(100, v));
    }

    private static <T> List<T> limited(List<T> l, Options o) {
        return o == null || o.limit <= 0 || l.size() <= o.limit ? l : new ArrayList<>(l.subList(0, o.limit));
    }

    /** x1 against the representatives of each class (sheets A or B) with a saved build, at the same minute. */
    @SuppressWarnings("unchecked")
    private void duels(BuildRepository.Build build, double minute, Score s) {
        Map<String, Object> cfg = (Map<String, Object>) rules.getOrDefault("duelos", Map.of());
        double duration = num(cfg.get("duracao"), 45);
        for (BuildRepository.Build rival : representatives(championOf(build))) {
            ArenaModel.Slot me = slot(build, minute, 0);
            ArenaModel.Slot them = slot(rival, minute, 1);
            ArenaModel.Scenario sc = new ArenaModel.Scenario();
            sc.fighters = List.of(me, them);
            List<Arena.Input> in = arena.prepareSlots(sc);
            Arena.Input a = in.get(0).copy();
            Arena.Input b = in.get(1).copy();
            a.x = 4600;
            a.y = 0;
            b.x = 5400;
            b.y = 0;
            ArenaModel.Result res = arena.runPlain(List.of(a, b), duration);
            ArenaModel.FighterStats us = res.fighters.get(0);
            ArenaModel.FighterStats other = res.fighters.get(1);
            Reading r = new Reading();
            r.part = DUELS;
            r.key = "x1:" + rival.id;
            r.name = "x1 " + championOf(rival) + " (" + rival.name + ")";
            r.points = 100 * (Math.max(0, us.health) / Math.max(1, us.maxHealth) - Math.max(0, other.health) / Math.max(1, other.maxHealth));
            s.readings.add(r);
        }
    }

    /** The first saved build of each other champion with a sheet of quality A or B, one per class. */
    @SuppressWarnings("unchecked")
    public List<BuildRepository.Build> representatives(String except) {
        Map<String, Object> kits = (Map<String, Object>) arena.kits().getOrDefault("campeoes", Map.of());
        Map<String, BuildRepository.Build> byClass = new LinkedHashMap<>();
        for (BuildRepository.Build b : builds.findBuildSummaries()) {
            String champ = championOf(b);
            Object kit = kits.get(champ);
            Object q = kit instanceof Map ? ((Map<String, Object>) kit).get("qualidade") : null;
            if (champ == null || champ.equals(except) || !("A".equals(q) || "B".equals(q))) {
                continue;
            }
            List<String> cs = profiles.classesOf(champ);
            String cls = cs.isEmpty() ? "Especialista" : cs.get(0);
            if (!byClass.containsKey(cls)) {
                builds.findBuild(b.id).ifPresent(full -> byClass.put(cls, full));
            }
        }
        return new ArrayList<>(byClass.values());
    }

    private static ArenaModel.Slot slot(BuildRepository.Build b, double minute, int team) {
        ArenaModel.Slot s = new ArenaModel.Slot();
        s.team = team;
        s.build = b;
        s.minute = minute;
        s.brain = com.ornnplanner.arena.AutoBrain.AUTO;
        return s;
    }

    /** The parts' means, the weighted mean, the 20th percentile of the weighted parts' scenarios, the floor and the total. */
    @SuppressWarnings("unchecked")
    void combine(Score s, double floor, double floorWeight) {
        Map<String, Object> robust = (Map<String, Object>) rules.getOrDefault("robustez", Map.of());
        double wMean = num(robust.get("media"), 0.6);
        double wP20 = num(robust.get("p20"), 0.4);
        List<Double> counted = new ArrayList<>();
        double mean = 0;
        double used = 0;
        for (String p : PARTS) {
            double sum = 0;
            double w = 0;
            for (Reading r : s.readings) {
                if (p.equals(r.part)) {
                    sum += r.weight * r.points;
                    w += r.weight;
                    if (s.weights.get(p) > 0) {
                        counted.add(r.points);
                    }
                }
            }
            if (w > 0) {
                s.parts.put(p, sum / w);
                mean += s.weights.get(p) * sum / w;
                used += s.weights.get(p);
            }
        }
        s.mean = used > 0 ? mean / used : 0;
        s.p20 = percentile(counted, 0.2);
        Double def = s.parts.get(DEFENSIVE);
        s.floorPenalty = def != null && floor > Double.NEGATIVE_INFINITY && def < floor ? (floor - def) * floorWeight : 0;
        s.total = (wMean * s.mean + wP20 * s.p20) / Math.max(1e-9, wMean + wP20) - s.floorPenalty;
    }

    /** Linear-interpolated percentile (q in 0..1) of the values; 0 for none. */
    public static double percentile(List<Double> values, double q) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Double> v = new ArrayList<>(values);
        Collections.sort(v);
        double pos = q * (v.size() - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(v.size() - 1, lo + 1);
        return v.get(lo) + (v.get(hi) - v.get(lo)) * (pos - lo);
    }

    // ------------------------------------------------------------------ robust, over minutes

    /** Share of the mean in a choice's versatility score (part 4b): {@code runas} or {@code itens}, from the YAML. */
    @SuppressWarnings("unchecked")
    public double versatilityWeight(String what) {
        Map<String, Object> v = (Map<String, Object>) rules.getOrDefault("versatilidade", Map.of());
        return num(v.get(what + "_media"), "runas".equals(what) ? 0.4 : 0.8);
    }

    /**
     * The chance of reaching a later minute (part 4b): each earlier minute's total through a logistic of the YAML's
     * {@code chegar} (threshold and scale), never below its floor. A build that loses early is worth less late.
     */
    @SuppressWarnings("unchecked")
    double reach(double earlierTotal) {
        Map<String, Object> c = (Map<String, Object>) rules.getOrDefault("chegar", Map.of());
        if (c.isEmpty()) {
            return 1;
        }
        double p = 1 / (1 + Math.exp(-(earlierTotal - num(c.get("limiar"), 10)) / Math.max(1e-6, num(c.get("escala"), 10))));
        return Math.max(num(c.get("piso"), 0.3), p);
    }

    /** The minutes of the robust score: the YAML's, with "fim" as the build's last purchase (or its match end). */
    @SuppressWarnings("unchecked")
    public List<double[]> minutes(BuildRepository.Build b) {
        List<Object> raw = (List<Object>) rules.getOrDefault("minutos", List.of(10, 15, 20, "fim"));
        List<Object> ws = (List<Object>) rules.getOrDefault("pesos_minuto", List.of());
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            double m;
            if (raw.get(i) instanceof Number) {
                m = ((Number) raw.get(i)).doubleValue();
            } else {
                m = end(b);
            }
            double w = i < ws.size() ? num(ws.get(i), 1) : 1;
            if (out.stream().noneMatch(x -> Math.abs(x[0] - m) < 1e-6)) {
                out.add(new double[] {m, w});
            }
        }
        return out;
    }

    /** The build's end: its match end, else its last purchase. */
    public double end(BuildRepository.Build b) {
        if (b.matchEnd != null) {
            return b.matchEnd;
        }
        Model.TimelineResult t = planner.calculate(b);
        double last = 20;
        for (Model.TimelineStep st : t.steps) {
            if (!st.ignored) {
                last = st.minute;
            }
        }
        return Math.max(1, last);
    }

    /** The robust score over the minutes; with a reference build, the share of scenario-minutes it beats it. */
    public Robust robust(BuildRepository.Build b, BuildRepository.Build reference) {
        return robust(b, reference, null);
    }

    public Robust robust(BuildRepository.Build b, BuildRepository.Build reference, Options o) {
        Robust r = new Robust();
        double sum = 0;
        double w = 0;
        double mean = 0;
        double p20 = 0;
        int beats = 0;
        int total = 0;
        double reach = 1;
        for (double[] mw : minutes(b)) {
            Score s = score(b, mw[0], o);
            r.minutes.add(s);
            // what a late minute is worth: its weight times the chance of getting there (the earlier minutes' totals)
            double wm = mw[1] * reach;
            s.reach = reach;
            sum += wm * s.total;
            mean += wm * s.mean;
            p20 += wm * s.p20;
            w += wm;
            reach *= reach(s.total);
            if (reference != null) {
                Score ref = score(reference, mw[0], o);
                Map<String, Double> theirs = new LinkedHashMap<>();
                ref.readings.forEach(x -> theirs.put(x.part + "|" + x.key, x.points));
                for (Reading x : s.readings) {
                    Double y = theirs.get(x.part + "|" + x.key);
                    if (y != null && s.weights.getOrDefault(x.part, 0.0) > 0) {
                        total++;
                        beats += x.points > y + 1e-9 ? 1 : 0;
                    }
                }
            }
        }
        r.total = w > 0 ? sum / w : 0;
        r.mean = w > 0 ? mean / w : 0;
        r.p20 = w > 0 ? p20 / w : 0;
        r.beatsReference = reference == null || total == 0 ? null : 100.0 * beats / total;
        return r;
    }

    private static double num(Object o, double def) {
        return o instanceof Number ? ((Number) o).doubleValue() : def;
    }
}
