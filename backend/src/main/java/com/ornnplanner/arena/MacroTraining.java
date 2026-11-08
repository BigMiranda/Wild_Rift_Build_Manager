package com.ornnplanner.arena;

import com.ornnplanner.arena.ai.Network;
import com.ornnplanner.arena.ai.Trainer;
import com.ornnplanner.arena.rift.MacroPolicy;
import com.ornnplanner.arena.rift.MatchModel;
import com.ornnplanner.arena.rift.NeuralMacro;
import com.ornnplanner.arena.rift.ScriptedMacro;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Training the whole match's macro (proposta-macro-treinada.md, Parte 3): OpenAI-ES ({@link Trainer}) over a
 * {@link NeuralMacro}'s weights, every candidate playing whole matches against an opponent macro (the script, or the
 * script and earlier versions). An episode is one seed of one draft played twice, the candidate once on each side, so
 * the map's sides cancel out. Fitness: the win first, then winning early, then the gold lead (weights in
 * {@code macro_<patch>.yml}, {@code treino_macro}). Validation on seeds never trained on, always against the script.
 *
 * <p>Deterministic: the drafts' matches are, and the trainer is by its seed, whatever the threads.
 */
public final class MacroTraining {

    /**
     * The version of the match's rules a macro is trained on: raise it when a change of the match (economy, map, the
     * fights' trigger) makes the saved macros play a different game; the screen warns about the older ones.
     */
    public static final int RULES = 2;

    /** Seeds of the validation matches start here; the training ones are below it. */
    public static final long VALIDATION_SEEDS = 1_000_000;

    public static final class Config {
        public int population = 24;
        public int generations = 30;
        /** Episodes per candidate per generation (each one is two matches, one per side). */
        public int episodesPerGeneration = 4;
        /** Seeds in the training pool, per draft. */
        public int seedsPerDraft = 64;
        public int validationSeeds = 20;
        public double sigma = 0.1;
        public double learningRate = 0.05;
        public long seed = 1;
        public double limitMinutes = 20;
        public int threads = Runtime.getRuntime().availableProcessors();
    }

    /** What the fitness weighs ({@code treino_macro} of the macro file). */
    public static final class Weights {
        public double win = 1;
        public double early = 0.2;
        public double gold = 0.5;
        /** Validation winrate against the script to accept a version. */
        public double accept = 0.55;

        static Weights of(Map<String, Object> macro) {
            Weights w = new Weights();
            Object t = macro.get("treino_macro");
            if (t instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) t;
                w.win = com.ornnplanner.arena.rift.RiftRules.value(m.get("vitoria"), w.win);
                w.early = com.ornnplanner.arena.rift.RiftRules.value(m.get("cedo"), w.early);
                w.gold = com.ornnplanner.arena.rift.RiftRules.value(m.get("ouro"), w.gold);
                w.accept = com.ornnplanner.arena.rift.RiftRules.value(m.get("aceite"), w.accept);
            }
            return w;
        }
    }

    /** How a macro did against the script on the validation seeds. */
    public static final class Validation {
        public int games;
        public int wins;
        public int losses;
        public double winrate;
        public double fitness;
        public double goldPerMin;
        public double opponentGoldPerMin;
        public double minutes;
        /** How often each action was taken where the script would not have (deviations), per match. */
        public double deviations;
    }

    public interface Listener extends Trainer.Listener {
        default void message(String text) {
        }
    }

    private final MatchService matches;
    private final List<MatchService.Draft> drafts;
    private final Config cfg;
    private final Weights weights;
    private final ScriptedMacro script;
    private final List<MacroPolicy> opponents;

    /** @param opponents the macros the candidate plays against in training (empty: the script alone). */
    public MacroTraining(MatchService matches, List<MatchService.Draft> drafts, Config cfg, List<MacroPolicy> opponents) {
        this.matches = matches;
        this.drafts = drafts;
        this.cfg = cfg;
        this.weights = Weights.of(matches.macroParams());
        this.script = (ScriptedMacro) matches.scripted();
        this.opponents = opponents == null || opponents.isEmpty() ? List.of(script) : opponents;
    }

    public ScriptedMacro script() {
        return script;
    }

    /** The validation winrate that accepts a version. */
    public double acceptAt() {
        return weights.accept;
    }

    /** One side's score in a match: the win, how early, the gold lead. */
    public double fitness(MatchModel.Result r, int side) {
        double limit = cfg.limitMinutes * 60;
        double early = Math.max(0, 1 - r.seconds / limit);
        double f = r.winner == side ? weights.win + weights.early * early : r.winner == 1 - side ? -weights.win - weights.early * early : 0;
        double mine = 0;
        double theirs = 0;
        for (MatchModel.Player p : r.players) {
            if (p.team == side) {
                mine += p.goldEarned;
            } else {
                theirs += p.goldEarned;
            }
        }
        return f + weights.gold * (mine - theirs) / Math.max(1, mine + theirs);
    }

    /** Episode {@code e} of the pool: its draft, its seed and its opponent. */
    private double episode(Network candidate, int e) {
        int draft = e % drafts.size();
        long seed = 1 + e / drafts.size();
        MacroPolicy me = new NeuralMacro(candidate, script);
        MacroPolicy them = opponents.get((int) (seed % opponents.size()));
        MatchService.Draft d = drafts.get(draft);
        double blue = fitness(matches.run(d, seed, cfg.limitMinutes, me, them), 0);
        double red = fitness(matches.run(d, seed, cfg.limitMinutes, them, me), 1);
        return (blue + red) / 2;
    }

    /** Trains from {@code start} (zero weights: the script). */
    public Network train(Network start, Listener listener) {
        Trainer.Config tc = new Trainer.Config();
        tc.population = cfg.population;
        tc.generations = cfg.generations;
        tc.episodesPerGeneration = cfg.episodesPerGeneration;
        tc.sigma = cfg.sigma;
        tc.learningRate = cfg.learningRate;
        tc.seed = cfg.seed;
        tc.threads = cfg.threads;
        int pool = drafts.size() * cfg.seedsPerDraft;
        return new Trainer(tc).train(start, pool, this::episode, listener);
    }

    /** The macro against the script on the validation seeds, every draft, both sides. */
    public Validation validate(Network net) {
        MacroPolicy me = new NeuralMacro(net, script);
        List<long[]> games = new ArrayList<>();
        for (int d = 0; d < drafts.size(); d++) {
            for (int s = 0; s < cfg.validationSeeds; s++) {
                for (int side = 0; side < 2; side++) {
                    games.add(new long[] {d, VALIDATION_SEEDS + s, side});
                }
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, cfg.threads));
        List<Future<MatchModel.Result>> out = new ArrayList<>();
        try {
            for (long[] g : games) {
                MatchService.Draft d = drafts.get((int) g[0]);
                out.add(pool.submit(() -> g[2] == 0 ? matches.run(d, g[1], cfg.limitMinutes, me, script)
                        : matches.run(d, g[1], cfg.limitMinutes, script, me)));
            }
            Validation v = new Validation();
            for (int i = 0; i < games.size(); i++) {
                MatchModel.Result r = out.get(i).get();
                int side = (int) games.get(i)[2];
                v.games++;
                v.wins += r.winner == side ? 1 : 0;
                v.losses += r.winner == 1 - side ? 1 : 0;
                v.fitness += fitness(r, side);
                double minutes = Math.max(0.1, r.seconds / 60);
                v.minutes += minutes;
                for (MatchModel.Player p : r.players) {
                    double perMin = (p.goldEarned - 500) / minutes / 5;
                    if (p.team == side) {
                        v.goldPerMin += perMin;
                    } else {
                        v.opponentGoldPerMin += perMin;
                    }
                }
                v.deviations += r.events.stream().filter(e -> e.text.contains("(macro treinada)")).count();
            }
            v.winrate = (v.wins + 0.5 * (v.games - v.wins - v.losses)) / Math.max(1, v.games);
            v.fitness /= Math.max(1, v.games);
            v.goldPerMin /= Math.max(1, v.games);
            v.opponentGoldPerMin /= Math.max(1, v.games);
            v.minutes /= Math.max(1, v.games);
            v.deviations /= Math.max(1, v.games);
            return v;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("validation interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("a validation match failed: " + e.getCause(), e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Zero weights: plays exactly like the script. */
    public static Network zeros() {
        return Network.zeros(NeuralMacro.LAYERS);
    }

}
