package com.ornnplanner.arena;

import com.ornnplanner.arena.ai.ActionMask;
import com.ornnplanner.arena.ai.ActionSpace;
import com.ornnplanner.arena.ai.Network;
import com.ornnplanner.arena.ai.NeuralPolicy;
import com.ornnplanner.arena.ai.Observation;
import com.ornnplanner.arena.ai.Plateau;
import com.ornnplanner.arena.ai.Policy;
import com.ornnplanner.arena.ai.PolicyRepository;
import com.ornnplanner.arena.ai.ScriptedPolicy;
import com.ornnplanner.arena.ai.TacticalPolicy;
import com.ornnplanner.arena.ai.Trainer;
import com.ornnplanner.combat.CombatCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

/**
 * One training stage of a learned policy, used alone (a single job) and as a step of a chain.
 *
 * <p>From the scenario's builds (and their minute variants), episodes vary the starts and the enemies' brain:
 * scripted, tactical, the best saved policies of their champions — the hall of fame — and, in a mirror match, the
 * policy being trained as it was a few generations before (self-play). In a chain, half the training episodes put the
 * chain's current versions on the enemies (and always on the allies), and selection and validation use only them.
 *
 * <p>The trainer ({@link Trainer}) learns on a pool of these episodes. Each generation its weights are measured on a
 * separate selection set against the scripted autopilot in the same fights; that measure picks the best generation
 * and drives the plateau rule ({@link Plateau}). A validation set never used before gives the saved numbers: how
 * often it does better than the scripted and the tactical autopilots and than the previous accepted version. A version
 * is accepted when it does better than the script and the previous one in at least {@link #ACCEPT} of them.
 */
public final class ArenaTraining {

    public static final double ACCEPT = 0.55;
    public static final int POOL = 24;
    public static final int SELECTION = 16;
    public static final int VALIDATION = 16;
    static final int SELF_PLAY_EVERY = 5;
    /** The plateau metric: "fitness" (selection fitness over the script's, %) or "better" (% of selection fights). */
    public static final String METRIC = "better";

    /** What a stage trains and against what. */
    public static final class StageSpec {
        /** The scenario's builds first, then its minute variants (each a full list of inputs). */
        public List<List<Arena.Input>> variants;
        public List<ArenaModel.Action> actions = List.of();
        public double duration = 60;
        /** The champion trained (index in the inputs). */
        public int me;
        public String objective = "win_fast";
        public String scenario;
        public int generations = 30;
        public int population = 32;
        public int episodesPerGeneration = 8;
        public long seed = 1;
        /** Weights it starts from (null = zeros: the tactical autopilot) and the saved version they are. */
        public Network start;
        public Long parentId;
        /** The version it must beat to be accepted (null = only the script). */
        public PolicyRepository.Record previous;
        /** Chain: each champion's current version (lower-case name; with weights). Empty = the slots' brains. */
        public Map<String, PolicyRepository.Record> chain = Map.of();
        /**
         * Chain: the version each champion had before its current one (a null value = the slot's brain), to measure
         * the trained policy against the opponents as they were.
         */
        public Map<String, PolicyRepository.Record> chainBefore = Map.of();
        public String chainId;
        public Integer chainRound;
        public ArenaModel.TeamPlan teamPlan;
        public Plateau plateau = Plateau.off();
        /** Share of validation fights to beat the script and the previous version in (to be accepted). */
        public double accept = ACCEPT;
        public int pool = POOL;
        public int selection = SELECTION;
        public int validation = VALIDATION;
        public int threads = Runtime.getRuntime().availableProcessors();

        boolean chained() {
            return chainId != null;
        }
    }

    /** One generation as the screen shows it. */
    public static class Point {
        public int generation;
        /** Mean and best fitness of the candidates on the generation's episodes. */
        public double mean;
        public double best;
        /** Share of selection fights where the generation's weights did better than the script. */
        public Double selection;
        /** Selection fitness over the script's in the same fights, in % of the script's (see {@link #METRIC}). */
        public Double fitnessPct;
        /** Same as {@link #selection}, in %. */
        public Double betterPct;
        /** The value the plateau rule reads. */
        public Double metric;
    }

    /** Hears a stage: its messages and generations; may cancel it. */
    public interface Listener {
        default void message(String text) {
        }

        default void opponents(List<String> labels) {
        }

        default void point(Point p) {
        }

        default boolean cancelled() {
            return false;
        }
    }

    public static final class StageResult {
        /** The saved version, with its weights (null when cancelled). */
        public PolicyRepository.Record policy;
        public boolean cancelled;
        /** Stopped by the plateau rule before its last generation. */
        public boolean plateau;
        public int generations;
        /** Validation against the opponents' previous chain versions: better than the script in this share. */
        public Double vsOldOpponents;
        public List<String> opponents = new ArrayList<>();
    }

    private final Gym gym;
    private final PolicyRepository policies;

    public ArenaTraining(CombatCatalog catalog, Map<String, Object> map, PolicyRepository policies) {
        this.gym = new Gym(catalog, map);
        this.policies = policies;
    }

    /** One kind of opponent: its label and, for an enemy input, its policy (null = not available for it). */
    private interface Opponent {
        String label();

        Policy policyFor(Arena.Input enemy);
    }

    public StageResult trainStage(StageSpec spec, Listener listener) {
        StageResult out = new StageResult();
        List<Arena.Input> base = spec.variants.get(0);
        int me = spec.me;
        int team = base.get(me).f.team;
        String myChampion = base.get(me).f.champion;
        List<String> priority = base.get(me).priority;
        String objective = spec.objective;
        Random rnd = new Random(spec.seed);
        listener.message("preparando os episódios");

        // opponents: script, tactical, the hall of fame, self-play in a mirror; in a chain, the chain's versions too
        AtomicReference<Policy> self = new AtomicReference<>();
        Opponent script = opponent("roteiro", in -> new ScriptedPolicy(in.priority));
        Opponent tactical = opponent("tático", in -> new TacticalPolicy(in.priority));
        Opponent chain = opponent("cadeia", in -> current(spec.chain, in));
        List<Opponent> others = new ArrayList<>(List.of(script, tactical));
        Map<String, Opponent> hall = new LinkedHashMap<>();
        for (Arena.Input in : base) {
            String champion = in.f.champion;
            if (in.f.team != team && !hall.containsKey(champion.toLowerCase())) {
                policies.list(champion).stream().filter(r -> r.accepted && r.compatible()).limit(2).forEach(r -> {
                    PolicyRepository.Record full = policies.find(r.id).orElseThrow();
                    hall.put(champion.toLowerCase() + r.id, opponent("IA v" + r.version + " " + champion,
                            x -> x.f.champion.equalsIgnoreCase(champion) ? neural(full, x) : null));
                });
                if (champion.equalsIgnoreCase(myChampion)) {
                    hall.put("self", opponent("ela mesma (self-play)", x -> x.f.champion.equalsIgnoreCase(myChampion)
                            ? new SelfPlay(self, x.priority) : null));
                }
            }
        }
        others.addAll(hall.values());
        List<Opponent> training = new ArrayList<>();
        for (Opponent o : others) {
            if (spec.chained()) {
                training.add(chain);   // half the fights against the chain's current versions
            }
            training.add(o);
        }
        training.forEach(o -> out.opponents.add(o.label()));
        listener.opponents(out.opponents);

        List<Opponent> fixed = spec.chained() ? List.of(chain) : List.of(script, tactical);
        List<Gym.Episode> pool = episodes(spec, team, training, spec.pool, rnd);
        List<Gym.Episode> selection = episodes(spec, team, fixed, spec.selection, rnd);
        List<Gym.Episode> validation = episodes(spec, team, fixed, spec.validation, new Random(spec.seed * 7919 + 17));

        Trainer.Config cfg = new Trainer.Config();
        cfg.population = Math.max(4, Math.min(128, spec.population));
        cfg.generations = Math.max(1, spec.generations);
        cfg.episodesPerGeneration = Math.max(1, Math.min(pool.size(), spec.episodesPerGeneration));
        cfg.seed = spec.seed;
        cfg.threads = spec.threads;
        Network start = spec.start != null ? spec.start : Network.zeros(Network.DEFAULT_LAYERS);
        self.set(new NeuralPolicy(start, priority));
        ScriptedPolicy reference = new ScriptedPolicy(priority);

        // the script's fitness on the selection fights, once: every generation is measured against it
        double[] scriptFit = fitness(selection, reference, objective);
        double scriptMean = mean(scriptFit);
        Point first = measure(selection, scriptFit, scriptMean, new NeuralPolicy(start, priority), objective);
        // the best generation: most selection fights better than the script, ties by selection fitness (a share that
        // saturates, e.g. always winning, must not let slower wins replace faster ones)
        Network[] best = {start};
        double[] bestSelection = {first.selection, first.fitnessPct};
        List<Double> metric = new ArrayList<>();
        boolean[] plateau = {false};
        int[] done = {0};
        listener.message("treinando");
        new Trainer(cfg).train(start, pool.size(), (net, e) -> {
            Gym.Episode ep = pool.get(e);
            return ArenaScore.fitness(gym.play(ep, new NeuralPolicy(net, priority)), objective, ep.me, team);
        }, new Trainer.Listener() {
            @Override
            public void generation(Trainer.Generation g) {
                NeuralPolicy now = new NeuralPolicy(g.center, priority);
                Point p = measure(selection, scriptFit, scriptMean, now, objective);
                p.generation = g.index;
                p.mean = g.mean;
                p.best = g.best;
                if (p.selection > bestSelection[0] + 1e-9
                        || Math.abs(p.selection - bestSelection[0]) <= 1e-9 && p.fitnessPct >= bestSelection[1]) {
                    bestSelection[0] = p.selection;
                    bestSelection[1] = p.fitnessPct;
                    best[0] = g.center;
                }
                if (g.index % SELF_PLAY_EVERY == 0) {
                    self.set(now);
                }
                metric.add(p.metric);
                done[0] = g.index;
                plateau[0] = g.index < cfg.generations && spec.plateau.reached(metric);
                listener.point(p);
            }

            @Override
            public boolean cancelled() {
                return listener.cancelled();
            }

            @Override
            public boolean stop() {
                return plateau[0];
            }
        });
        out.generations = done[0];
        out.plateau = plateau[0];
        if (listener.cancelled()) {
            out.cancelled = true;
            return out;
        }

        listener.message(out.plateau ? "platô na geração " + done[0] + "; validando" : "validando");
        NeuralPolicy ai = new NeuralPolicy(best[0], priority);
        Gym.Report vsScript = gym.compare(validation, ai, reference, objective);
        Gym.Report vsTactical = gym.compare(validation, ai, new TacticalPolicy(priority), objective);
        Double vsPrevious = spec.previous == null ? null : gym.compare(validation, ai,
                new NeuralPolicy(spec.previous.weights.network(), priority, spec.previous.weights.bonus), objective).better;
        if (spec.chained() && changed(spec, base, team)) {
            Opponent before = opponent("cadeia anterior", in -> before(spec, in));
            List<Gym.Episode> old = episodes(spec, team, List.of(before), spec.validation, new Random(spec.seed * 7919 + 17));
            out.vsOldOpponents = gym.compare(old, ai, reference, objective).better;
        }

        PolicyRepository.Record r = new PolicyRepository.Record();
        r.champion = myChampion;
        r.objective = objective;
        r.fitness = vsScript.meanFitness;
        r.betterVsScript = vsScript.better;
        r.betterVsTactical = vsTactical.better;
        r.betterVsPrevious = vsPrevious;
        r.winrate = vsScript.winRate;
        r.winrateScript = vsScript.referenceWinRate;
        r.accepted = vsScript.better >= spec.accept && (vsPrevious == null || vsPrevious >= spec.accept);
        r.generations = done[0];
        r.population = cfg.population;
        r.seed = spec.seed;
        r.scenario = spec.scenario;
        r.obsSchema = Observation.SCHEMA_VERSION;
        r.actions = ActionSpace.SIZE;
        r.chainId = spec.chainId;
        r.chainRound = spec.chainRound;
        r.teamPlan = spec.teamPlan;
        r.parentId = spec.parentId;
        r.weights = PolicyRepository.Weights.of(best[0], NeuralPolicy.BASE_BONUS);
        out.policy = policies.insert(r);
        return out;
    }

    /** The weights' plateau metrics on the selection fights, against the script's fitness there. */
    private Point measure(List<Gym.Episode> selection, double[] scriptFit, double scriptMean, Policy policy, String objective) {
        double[] fit = fitness(selection, policy, objective);
        double better = 0;
        for (int i = 0; i < fit.length; i++) {
            better += fit[i] > scriptFit[i] + 1e-9 ? 1 : Math.abs(fit[i] - scriptFit[i]) <= 1e-9 ? 0.5 : 0;
        }
        Point p = new Point();
        p.selection = fit.length == 0 ? 0 : better / fit.length;
        p.betterPct = 100 * p.selection;
        p.fitnessPct = 100 * (mean(fit) - scriptMean) / Math.max(1, Math.abs(scriptMean));
        p.metric = "fitness".equals(METRIC) ? p.fitnessPct : p.betterPct;
        return p;
    }

    /** The policy's fitness on each episode (fights in parallel; the order of the results is the episodes'). */
    private double[] fitness(List<Gym.Episode> episodes, Policy policy, String objective) {
        return IntStream.range(0, episodes.size()).parallel().mapToDouble(i -> {
            Gym.Episode e = episodes.get(i);
            return ArenaScore.fitness(gym.play(e, policy), objective, e.me, e.team());
        }).toArray();
    }

    private static double mean(double[] x) {
        double s = 0;
        for (double v : x) {
            s += v;
        }
        return x.length == 0 ? 0 : s / x.length;
    }

    private static NeuralPolicy neural(PolicyRepository.Record r, Arena.Input in) {
        return new NeuralPolicy(r.weights.network(), in.priority, r.weights.bonus);
    }

    /** A champion's policy in the chain now: its current version, else its own brain (the slot's). */
    private static Policy current(Map<String, PolicyRepository.Record> chain, Arena.Input in) {
        PolicyRepository.Record r = chain.get(in.f.champion.toLowerCase());
        if (r != null) {
            return neural(r, in);
        }
        return in.policy != null ? in.policy : new ScriptedPolicy(in.priority);
    }

    /** A champion's policy before its current chain version (its own brain when it had none). */
    private static Policy before(StageSpec spec, Arena.Input in) {
        String key = in.f.champion.toLowerCase();
        if (!spec.chainBefore.containsKey(key)) {
            return current(spec.chain, in);
        }
        PolicyRepository.Record r = spec.chainBefore.get(key);
        return r != null ? neural(r, in) : in.policy != null ? in.policy : new ScriptedPolicy(in.priority);
    }

    /** Some enemy of the trained champion has a newer chain version than before. */
    private static boolean changed(StageSpec spec, List<Arena.Input> base, int team) {
        for (Arena.Input in : base) {
            if (in.f.team != team && spec.chainBefore.containsKey(in.f.champion.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static Opponent opponent(String label, java.util.function.Function<Arena.Input, Policy> f) {
        return new Opponent() {
            @Override
            public String label() {
                return label;
            }

            @Override
            public Policy policyFor(Arena.Input enemy) {
                return f.apply(enemy);
            }
        };
    }

    /**
     * Episodes: a build variant, an opponent kind for all the enemies (script where that kind has nothing for one of
     * them) and starts moved at random: the whole fight along the lane, the sides closer or farther, each champion
     * across it. Allies play the chain's current versions (their own brain outside a chain).
     */
    private static List<Gym.Episode> episodes(StageSpec spec, int team, List<Opponent> opponents, int count, Random rnd) {
        List<Gym.Episode> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            List<Arena.Input> base = spec.variants.get(i % spec.variants.size());
            Opponent opp = opponents.get(i % opponents.size());
            double shift = rnd.nextInt(3001) - 1500;
            double gap = rnd.nextInt(501) - 200;
            List<Arena.Input> in = new ArrayList<>();
            Policy[] policies = new Policy[base.size()];
            for (int k = 0; k < base.size(); k++) {
                Arena.Input c = base.get(k).copy();
                double side = c.f.team == team ? -1 : 1;
                c.x = Math.max(300, Math.min(9700, c.x + shift + side * gap / 2));
                c.y = Math.max(-450, Math.min(450, c.y + rnd.nextInt(601) - 300));
                in.add(c);
                if (c.f.team != team) {
                    Policy p = opp.policyFor(c);
                    policies[k] = p != null ? p : new ScriptedPolicy(c.priority);
                } else if (k != spec.me && !spec.chain.isEmpty()) {
                    policies[k] = current(spec.chain, c);
                }
            }
            out.add(new Gym.Episode(in, spec.actions, spec.duration, spec.me, policies, opp.label()));
        }
        return out;
    }

    /** The trained policy as it was at the last self-play snapshot (the opponent of a mirror match). */
    private static final class SelfPlay implements Policy {
        private final AtomicReference<Policy> current;
        private final List<String> priority;

        SelfPlay(AtomicReference<Policy> current, List<String> priority) {
            this.current = current;
            this.priority = priority;
        }

        @Override
        public Decision decide(Observation obs, ActionMask mask) {
            Policy p = current.get();
            if (p instanceof NeuralPolicy) {
                return new NeuralPolicy(((NeuralPolicy) p).network(), priority).decide(obs, mask);
            }
            return new TacticalPolicy(priority).decide(obs, mask);
        }

        @Override
        public double interval() {
            return NeuralPolicy.INTERVAL;
        }
    }
}
