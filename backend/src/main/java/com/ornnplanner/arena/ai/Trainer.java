package com.ornnplanner.arena.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Evolution strategy (OpenAI-ES) over a network's weights. Each generation draws {@code population / 2} noise
 * directions and tries the weights moved both ways along each (mirrored sampling), every candidate on the same
 * episodes of the generation; the fitnesses become centered ranks (only their order counts, so the objective's scale
 * does not matter) and the weights take an Adam step along the rank-weighted noise, with a little weight decay.
 *
 * <p>Deterministic: the noise and the episodes come from {@link Config#seed}, and the fights are, so the same seed and
 * episodes give the same weights whatever the number of threads.
 */
public final class Trainer {

    public static final class Config {
        /** Candidates per generation (rounded up to even). */
        public int population = 32;
        public int generations = 40;
        /** Episodes each candidate plays per generation (drawn from the pool). */
        public int episodesPerGeneration = 8;
        public double sigma = 0.1;
        public double learningRate = 0.1;
        public double weightDecay = 0.002;
        public long seed = 1;
        public int threads = Runtime.getRuntime().availableProcessors();
    }

    /** Fitness (higher is better) of a candidate network on one episode of the pool. */
    @FunctionalInterface
    public interface Evaluator {
        double evaluate(Network candidate, int episode);
    }

    /** One generation's outcome. */
    public static final class Generation {
        public int index;
        /** Mean fitness of the candidates, and of the best one, on this generation's episodes. */
        public double mean;
        public double best;
        /** The weights after this generation's step. */
        public Network center;
    }

    /** Hears every generation; may end the training early. */
    public interface Listener {
        void generation(Generation g);

        /** Abort: the caller throws the result away. */
        default boolean cancelled() {
            return false;
        }

        /** Stop here (a plateau): the weights so far are the result, and the caller goes on to validate them. */
        default boolean stop() {
            return false;
        }
    }

    private final Config cfg;

    public Trainer(Config cfg) {
        this.cfg = cfg;
    }

    public Network train(Network start, int episodes, Evaluator eval, Listener listener) {
        int pairs = Math.max(1, (cfg.population + 1) / 2);
        int n = start.size();
        double[] theta = start.weights();
        double[] m = new double[n];
        double[] v = new double[n];
        int[] layers = start.layers();
        Network center = start;
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, cfg.threads));
        try {
            for (int gen = 1; gen <= cfg.generations && !listener.cancelled() && !listener.stop(); gen++) {
                Random rnd = new Random(cfg.seed * 1_000_003L + gen);
                int[] chosen = pick(episodes, cfg.episodesPerGeneration, rnd);
                double[][] noise = new double[pairs][n];
                for (double[] e : noise) {
                    for (int i = 0; i < n; i++) {
                        e[i] = rnd.nextGaussian();
                    }
                }
                List<Network> candidates = new ArrayList<>();
                for (double[] e : noise) {
                    for (int sign : new int[] {1, -1}) {
                        double[] w = new double[n];
                        for (int i = 0; i < n; i++) {
                            w[i] = theta[i] + sign * cfg.sigma * e[i];
                        }
                        candidates.add(new Network(layers, w));
                    }
                }
                double[] fitness = evaluate(pool, candidates, chosen, eval);
                double[] rank = centeredRanks(fitness);
                double[] grad = new double[n];
                for (int k = 0; k < pairs; k++) {
                    double d = rank[2 * k] - rank[2 * k + 1];
                    double[] e = noise[k];
                    for (int i = 0; i < n; i++) {
                        grad[i] += d * e[i];
                    }
                }
                // Adam (ascent), then decay
                double b1 = 0.9;
                double b2 = 0.999;
                for (int i = 0; i < n; i++) {
                    double g = grad[i] / (2 * pairs * cfg.sigma);
                    m[i] = b1 * m[i] + (1 - b1) * g;
                    v[i] = b2 * v[i] + (1 - b2) * g * g;
                    double mh = m[i] / (1 - Math.pow(b1, gen));
                    double vh = v[i] / (1 - Math.pow(b2, gen));
                    theta[i] += cfg.learningRate * mh / (Math.sqrt(vh) + 1e-8) - cfg.weightDecay * cfg.learningRate * theta[i];
                }
                center = new Network(layers, theta);
                Generation g = new Generation();
                g.index = gen;
                g.mean = Arrays.stream(fitness).average().orElse(0);
                g.best = Arrays.stream(fitness).max().orElse(0);
                g.center = center;
                listener.generation(g);
            }
        } finally {
            pool.shutdownNow();
        }
        return center;
    }

    /** Mean fitness of each candidate over the episodes, every (candidate, episode) fight in parallel. */
    private static double[] evaluate(ExecutorService pool, List<Network> candidates, int[] episodes, Evaluator eval) {
        List<Future<Double>> futures = new ArrayList<>();
        for (Network c : candidates) {
            for (int e : episodes) {
                futures.add(pool.submit(() -> eval.evaluate(c, e)));
            }
        }
        double[] out = new double[candidates.size()];
        try {
            for (int i = 0; i < futures.size(); i++) {
                out[i / episodes.length] += futures.get(i).get() / episodes.length;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("training interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("a training fight failed: " + e.getCause(), e.getCause());
        }
        return out;
    }

    /** k distinct episodes out of n (all of them when k >= n), in a seeded order. */
    static int[] pick(int n, int k, Random rnd) {
        int[] all = new int[n];
        for (int i = 0; i < n; i++) {
            all[i] = i;
        }
        for (int i = n - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int t = all[i];
            all[i] = all[j];
            all[j] = t;
        }
        return Arrays.copyOf(all, Math.min(n, Math.max(1, k)));
    }

    /** Ranks scaled to [-0.5, 0.5] (ties share their mean rank). */
    static double[] centeredRanks(double[] x) {
        Integer[] idx = new Integer[x.length];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, (a, b) -> Double.compare(x[a], x[b]));
        double[] r = new double[x.length];
        int i = 0;
        while (i < idx.length) {
            int j = i;
            while (j + 1 < idx.length && x[idx[j + 1]] == x[idx[i]]) {
                j++;
            }
            double mean = (i + j) / 2.0;
            for (int k = i; k <= j; k++) {
                r[idx[k]] = x.length > 1 ? mean / (x.length - 1) - 0.5 : 0;
            }
            i = j + 1;
        }
        return r;
    }
}
