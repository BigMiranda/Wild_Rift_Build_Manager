package com.ornnplanner.arena.ai;

import java.util.Arrays;

/**
 * A small multilayer perceptron: tanh hidden layers, linear output. All weights live in one flat array (layer by layer,
 * each neuron's inputs then its bias), which is what the trainer perturbs. Immutable once built, so one network can
 * serve many fights at once.
 */
public final class Network {

    /**
     * Observation -> 16 -> one score per action: ~1.9k weights. Small on purpose: an evolution strategy with a few
     * dozen candidates learns it in a few dozen generations, where 32 -> 32 (4.9k) barely moved.
     */
    public static final int[] DEFAULT_LAYERS = {Observation.SIZE, 16, ActionSpace.SIZE};

    private final int[] layers;
    private final double[] weights;
    /**
     * Rodada 21: the last {@code skip} inputs (the requested style) also reach every output directly, one weight each,
     * after the layers' weights: a style moves an action by one weight, which an evolution strategy finds at once
     * (through the hidden layer it needs several changes together). 0 = none, as every network before the league.
     */
    private final int skip;

    public Network(int[] layers, double[] weights) {
        this(layers, weights, 0);
    }

    public Network(int[] layers, double[] weights, int skip) {
        if (weights.length != count(layers, skip)) {
            throw new IllegalArgumentException("expected " + count(layers, skip) + " weights, got " + weights.length);
        }
        this.layers = layers.clone();
        this.weights = weights.clone();
        this.skip = skip;
    }

    /** A network with every weight at zero (all scores 0). */
    public static Network zeros(int[] layers) {
        return new Network(layers, new double[count(layers)]);
    }

    /** The same with {@code skip} direct inputs (Rodada 21: the style). */
    public static Network zeros(int[] layers, int skip) {
        return new Network(layers, new double[count(layers, skip)], skip);
    }

    /** Rodada 21: a league network: the default layers, and the style's inputs straight to the outputs. */
    public static Network league() {
        return zeros(DEFAULT_LAYERS, Observation.STYLES.size());
    }

    public static int count(int[] layers, int skip) {
        return count(layers) + skip * layers[layers.length - 1];
    }

    /** The same layout with other weights (what the trainer perturbs). */
    public Network withWeights(double[] w) {
        return new Network(layers, w, skip);
    }

    public int skip() {
        return skip;
    }

    public static int count(int[] layers) {
        int n = 0;
        for (int i = 1; i < layers.length; i++) {
            n += (layers[i - 1] + 1) * layers[i];
        }
        return n;
    }

    public int[] layers() {
        return layers.clone();
    }

    public double[] weights() {
        return weights.clone();
    }

    public int size() {
        return weights.length;
    }

    public double[] forward(double[] input) {
        if (input.length != layers[0]) {
            throw new IllegalArgumentException("expected " + layers[0] + " inputs, got " + input.length);
        }
        double[] a = input;
        int w = 0;
        for (int l = 1; l < layers.length; l++) {
            int in = layers[l - 1];
            double[] out = new double[layers[l]];
            for (int j = 0; j < out.length; j++) {
                double s = 0;
                for (int i = 0; i < in; i++) {
                    s += weights[w++] * a[i];
                }
                s += weights[w++];
                out[j] = l < layers.length - 1 ? Math.tanh(s) : s;
            }
            a = out;
        }
        if (skip > 0) {
            int from = input.length - skip;
            for (int j = 0; j < a.length; j++) {
                for (int k = 0; k < skip; k++) {
                    a[j] += weights[w++] * input[from + k];
                }
            }
        }
        return a;
    }

    /**
     * The same network for a wider input and output layout: old input i feeds new input {@code inputMap[i]}, old output
     * j is new output {@code outputMap[j]}. New inputs weigh zero, so they change nothing; new outputs have zero weights
     * and the bias {@code newBias} (a score that never beats a real action). Hidden layers stay as they are.
     */
    public Network migrate(int[] inputMap, int inputs, int[] outputMap, int outputs, double newBias) {
        if (skip > 0) {
            throw new IllegalArgumentException("a network with direct inputs is never migrated: " + this);
        }
        if (inputMap.length != layers[0] || outputMap.length != layers[layers.length - 1]) {
            throw new IllegalArgumentException("maps of " + inputMap.length + " / " + outputMap.length + " for " + this);
        }
        int[] to = layers.clone();
        to[0] = inputs;
        to[to.length - 1] = outputs;
        double[] out = new double[count(to)];
        int r = 0;
        int w = 0;
        for (int l = 1; l < layers.length; l++) {
            boolean first = l == 1;
            boolean last = l == layers.length - 1;
            int in = layers[l - 1];
            int newIn = to[l - 1];
            int start = w;
            boolean[] filled = new boolean[to[l]];
            for (int j = 0; j < layers[l]; j++) {
                int nj = last ? outputMap[j] : j;
                filled[nj] = true;
                int row = start + nj * (newIn + 1);
                for (int i = 0; i < in; i++) {
                    out[row + (first ? inputMap[i] : i)] = weights[r++];
                }
                out[row + newIn] = weights[r++];
            }
            if (last) {
                for (int nj = 0; nj < to[l]; nj++) {
                    if (!filled[nj]) {
                        out[start + nj * (newIn + 1) + newIn] = newBias;
                    }
                }
            }
            w = start + to[l] * (newIn + 1);
        }
        return new Network(to, out);
    }

    @Override
    public String toString() {
        return "Network" + Arrays.toString(layers) + (skip > 0 ? " + " + skip + " direct" : "") + " (" + weights.length + " weights)";
    }
}
