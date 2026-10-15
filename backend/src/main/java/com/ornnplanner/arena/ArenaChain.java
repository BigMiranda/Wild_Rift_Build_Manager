package com.ornnplanner.arena;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ornnplanner.arena.ai.Network;
import com.ornnplanner.arena.ai.Plateau;
import com.ornnplanner.arena.ai.PolicyRepository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Chain training: the scenario's champions trained one after the other, round after round, each stage against the
 * latest accepted version of the others (allies and enemies), so each one's progress pushes the next.
 *
 * <p>A stage starts from its champion's previous accepted version (warm start) and must beat it, and the script, to be
 * accepted. With a victory as the stop rule, a stage ends as soon as its champion's side wins the scenario's own fight
 * (with the chain's current versions in the other slots; those weights are saved, become its current version even if
 * the 55% rule does not accept them, and the chain moves on); a champion whose current version already wins it at the
 * start of its stage is not trained again: it is kept. A champion in two slots is trained once per round (its first slot); its version plays in both. The chain
 * ends after its last round, when a whole round accepts nothing (converged), when asked to stop after the current
 * stage, or when cancelled (the stage under way is thrown away). Same request, same database: same versions.
 */
public final class ArenaChain {

    /** What the screen asks for. */
    public static class Request {
        public ArenaModel.Scenario scenario;
        /** Slots to train, in order (null or empty = all, in slot order). */
        public List<Integer> fighters;
        /** Objective for all, and per slot when given (slot index -> objective). */
        public String objective = "win_fast";
        public Map<Integer, String> objectives = new LinkedHashMap<>();
        /** Most generations of each stage. */
        public int generations = 30;
        public int population = 32;
        public int episodesPerGeneration = 8;
        public long seed = 1;
        public int maxRounds = 3;
        /**
         * Stop rule of each stage: generations (all of them, the best by selection) | plateau | victory (when its side
         * wins the scenario's own fight).
         */
        public String stopWhen = "generations";
        /** victory: the champion must also end the fight alive. */
        public boolean aliveToo;
        /** Help level: sozinha | descobertos | dicas. */
        public String level = ArenaTraining.LEVEL_ALONE;
        /** Plateau: least gain in points over the window, after a minimum of generations (0 = off). */
        public double plateauPct = 2;
        public int plateauWindow = 6;
        public int minGenerations = 8;
    }

    /** One champion's training in one round. */
    public static class Stage {
        public String champion;
        public int slot;
        public int round;
        public String objective;
        /** waiting | training | accepted | rejected | won (wins the scenario, not accepted) | kept | skipped | cancelled */
        public volatile String status = "waiting";
        public volatile int generation;
        public int generations;
        public final List<ArenaTraining.Point> progress = new CopyOnWriteArrayList<>();
        public volatile String message;
        /** A previous version that could not be the start (another layout of the arena), so it started from zero. */
        public volatile String warning;
        /** The version it started from (null = zero weights). */
        public volatile Long parentId;
        /** The saved version, without weights. */
        public volatile PolicyRepository.Record policy;
        /** Validation against the opponents' previous chain versions: better than the script in this share. */
        public volatile Double vsOldOpponents;
        /** Stopped by the plateau rule before its last generation. */
        public volatile boolean plateau;
        /** Stopped because its side won the scenario's own fight (or kept: its version already did). */
        public volatile boolean victory;
        /** Skipped because the same champion is trained in this earlier slot. */
        public Integer sameAs;
    }

    public static class Job {
        public final String kind = "chain";
        public String id;
        /** queued | running | stopping | done | converged | stopped | cancelled | failed */
        public volatile String status = "queued";
        public String scenario;
        public volatile int round;
        public int maxRounds;
        /** Index in {@link #stages} of the stage under way (-1 = none yet). */
        public volatile int current = -1;
        public volatile boolean stopAfterCurrent;
        public final List<Stage> stages = new CopyOnWriteArrayList<>();
        public volatile String message;
        /** Each slot's latest accepted version in this chain (slot -> policy id): what "use these AIs" puts there. */
        public final Map<Integer, Long> latest = new ConcurrentHashMap<>();
        public long startedAt;
        public volatile Long finishedAt;
        @JsonIgnore
        public volatile boolean cancel;
    }

    /** A request resolved by the service: the inputs are prepared, the numbers clamped. */
    public static final class Spec {
        public List<List<Arena.Input>> variants;
        public List<ArenaModel.Action> actions = List.of();
        public double duration = 60;
        public String scenario;
        public ArenaModel.TeamPlan[] teams;
        public ArenaModel.SafeZone safeZone;
        /** Help level of every stage, and per champion (lower case): its combos, guidelines and reward weights. */
        public String level = ArenaTraining.LEVEL_ALONE;
        public Map<String, List<ArenaModel.Combo>> combos = Map.of();
        public Map<String, List<ArenaModel.Guideline>> guidelines = Map.of();
        public Map<String, Map<String, Double>> rewards = Map.of();
        public List<Integer> slots;
        public String objective = "win_fast";
        public Map<Integer, String> objectives = Map.of();
        public int generations = 30;
        public int population = 32;
        public int episodesPerGeneration = 8;
        public long seed = 1;
        public int maxRounds = 3;
        public Plateau plateau = Plateau.off();
        public boolean stopAtScenarioWin;
        public boolean aliveToo;
        public double accept = ArenaTraining.ACCEPT;
        public int pool = ArenaTraining.POOL;
        public int selection = ArenaTraining.SELECTION;
        public int validation = ArenaTraining.VALIDATION;
        public int threads = Runtime.getRuntime().availableProcessors();
    }

    private final ArenaTraining training;
    private final PolicyRepository policies;
    /** Called after each generation of each stage (tests use it to stop or cancel at a known point). */
    private java.util.function.BiConsumer<Job, Stage> onGeneration = (j, s) -> { };

    public ArenaChain(ArenaTraining training, PolicyRepository policies) {
        this.training = training;
        this.policies = policies;
    }

    public ArenaChain onGeneration(java.util.function.BiConsumer<Job, Stage> hook) {
        this.onGeneration = hook;
        return this;
    }

    /** The seed of a stage: the chain's, its round and its place in the round. */
    static long stageSeed(long seed, int round, int index) {
        return seed * 1_000_003L + round * 1_009L + index;
    }

    public void run(Job job, Spec spec) {
        if (job.cancel) {
            finish(job, "cancelled", "cancelado antes de começar");
            return;
        }
        if (job.stopAfterCurrent) {
            finish(job, "stopped", "parado antes de começar");
            return;
        }
        job.status = "running";
        List<Arena.Input> base = spec.variants.get(0);
        // each champion's current version in the chain, and the one it had before (null = the slot's brain)
        Map<String, PolicyRepository.Record> chain = new HashMap<>();
        Map<String, PolicyRepository.Record> before = new HashMap<>();
        for (int round = 1; round <= spec.maxRounds; round++) {
            job.round = round;
            List<Stage> stages = new ArrayList<>();
            Map<String, Integer> first = new HashMap<>();
            for (int slot : spec.slots) {
                Stage s = new Stage();
                s.slot = slot;
                s.round = round;
                s.champion = base.get(slot).f.champion;
                s.objective = spec.objectives.getOrDefault(slot, spec.objective);
                s.generations = spec.generations;
                Integer earlier = first.putIfAbsent(s.champion.toLowerCase(), slot);
                if (earlier != null) {
                    s.status = "skipped";
                    s.sameAs = earlier;
                }
                stages.add(s);
            }
            int offset = job.stages.size();
            job.stages.addAll(stages);
            boolean accepted = false;
            boolean allKept = true;
            for (int i = 0; i < stages.size(); i++) {
                Stage s = stages.get(i);
                if ("skipped".equals(s.status)) {
                    continue;
                }
                if (job.cancel) {
                    skipRest(stages, i);
                    finish(job, "cancelled", "cancelado");
                    return;
                }
                job.current = offset + i;
                job.message = "rodada " + round + ": " + s.champion;
                ArenaTraining.StageResult r = stage(job, spec, s, round, i, chain, before);
                if (r.kept) {
                    for (int slot = 0; slot < base.size(); slot++) {
                        if (base.get(slot).f.champion.equalsIgnoreCase(s.champion)) {
                            job.latest.put(slot, s.policy.id);
                        }
                    }
                    if (job.stopAfterCurrent) {
                        skipRest(stages, i + 1);
                        finish(job, "stopped", "parado pelo usuário depois de " + s.champion + " (rodada " + round + ")");
                        return;
                    }
                    continue;   // its winning version stays the chain's
                }
                allKept = false;
                if (r.cancelled) {
                    s.status = "cancelled";
                    s.message = "cancelado na geração " + s.generation;
                    skipRest(stages, i + 1);
                    finish(job, "cancelled", "cancelado na rodada " + round + " (" + s.champion + ")");
                    return;
                }
                String key = s.champion.toLowerCase();
                // the chain plays on with an accepted version, or with one that wins the scenario's fight
                if (r.policy.accepted || r.victory) {
                    accepted = true;
                    before.put(key, chain.get(key));
                    chain.put(key, r.policy);
                    for (int slot = 0; slot < base.size(); slot++) {
                        if (base.get(slot).f.champion.equalsIgnoreCase(s.champion)) {
                            job.latest.put(slot, r.policy.id);
                        }
                    }
                }
                if (job.stopAfterCurrent) {
                    skipRest(stages, i + 1);
                    finish(job, "stopped", "parado pelo usuário depois de " + s.champion + " (rodada " + round + ")");
                    return;
                }
            }
            if (!accepted) {
                finish(job, "converged", allKept ? "convergiu: na rodada " + round + " todos já vencem o cenário"
                        : "convergiu: a rodada " + round + " não aceitou nenhuma versão");
                return;
            }
        }
        finish(job, "done", "terminou as " + spec.maxRounds + " rodadas");
    }

    private ArenaTraining.StageResult stage(Job job, Spec spec, Stage s, int round, int index,
                                            Map<String, PolicyRepository.Record> chain,
                                            Map<String, PolicyRepository.Record> before) {
        s.status = "training";
        String key = s.champion.toLowerCase();
        ArenaTraining.StageSpec st = new ArenaTraining.StageSpec();
        st.variants = spec.variants;
        st.actions = spec.actions;
        st.duration = spec.duration;
        st.me = s.slot;
        st.objective = s.objective;
        st.scenario = spec.scenario;
        st.generations = spec.generations;
        st.population = spec.population;
        st.episodesPerGeneration = spec.episodesPerGeneration;
        st.seed = stageSeed(spec.seed, round, index);
        st.chain = new HashMap<>(chain);
        st.chainBefore = new HashMap<>(before);
        st.chainId = job.id;
        st.chainRound = round;
        int team = spec.variants.get(0).get(s.slot).f.team;
        st.teamPlan = spec.teams != null && team < spec.teams.length ? spec.teams[team] : null;
        st.plateau = spec.plateau;
        st.safeZone = spec.safeZone;
        st.level = spec.level;
        st.combos = spec.combos.getOrDefault(key, List.of());
        st.guidelines = spec.guidelines.getOrDefault(key, List.of());
        st.rewards = spec.rewards.get(key);
        st.stopAtScenarioWin = spec.stopAtScenarioWin;
        st.aliveToo = spec.aliveToo;
        st.accept = spec.accept;
        st.pool = spec.pool;
        st.selection = spec.selection;
        st.validation = spec.validation;
        st.threads = spec.threads;
        // warm start: the chain's version of this champion, else its latest accepted saved one when it fits
        PolicyRepository.Record from = chain.get(key);
        if (from == null || !from.objective.equals(s.objective)) {
            from = null;
            Optional<PolicyRepository.Record> saved = policies.latestAccepted(s.champion, s.objective);
            if (saved.isPresent()) {
                PolicyRepository.Record r = saved.get();
                if (fits(r)) {
                    from = r;
                } else {
                    s.warning = "a IA v" + r.version + " de " + r.champion + " foi treinada para outra versão da arena; "
                            + "esta etapa começa do zero";
                }
            }
        }
        st.start = from == null ? null : from.weights.network();
        st.parentId = from == null ? null : from.id;
        s.parentId = st.parentId;
        st.previous = from != null ? from : policies.best(s.champion, s.objective).orElse(null);
        ArenaTraining.StageResult r = training.trainStage(st, new ArenaTraining.Listener() {
            @Override
            public void message(String text) {
                s.message = text;
            }

            @Override
            public void point(ArenaTraining.Point p) {
                s.progress.add(p);
                s.generation = p.generation;
                onGeneration.accept(job, s);
            }

            @Override
            public boolean cancelled() {
                return job.cancel;
            }
        });
        if (r.cancelled) {
            return r;
        }
        if (r.kept) {
            s.status = "kept";
            s.victory = true;
            s.policy = from.withoutWeights();
            s.message = "já vence o cenário: mantida a v" + from.version;
            return r;
        }
        s.plateau = r.plateau;
        s.victory = r.victory;
        s.vsOldOpponents = r.vsOldOpponents;
        s.policy = r.policy.withoutWeights();
        s.status = r.policy.accepted ? "accepted" : r.victory ? "won" : "rejected";
        s.message = (r.victory ? "venceu o cenário na geração " + r.generations + "; "
                : r.plateau ? "platô na geração " + r.generations + "; " : "")
                + (r.policy.accepted ? "versão aceita" : "não aceita");
        return r;
    }

    /** Weights of the current observation, actions and network layout. */
    static boolean fits(PolicyRepository.Record r) {
        return r.compatible() && r.weights != null && Arrays.equals(r.weights.layers, Network.DEFAULT_LAYERS);
    }

    private static void skipRest(List<Stage> stages, int from) {
        for (int i = from; i < stages.size(); i++) {
            if ("waiting".equals(stages.get(i).status)) {
                stages.get(i).status = "skipped";
            }
        }
    }

    private static void finish(Job job, String status, String message) {
        job.status = status;
        job.message = message;
        job.current = -1;
        job.finishedAt = System.currentTimeMillis();
    }
}
