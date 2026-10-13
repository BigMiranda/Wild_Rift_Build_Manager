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

    public Network(int[] layers, double[] weights) {
        if (weights.length != count(layers)) {
            throw new IllegalArgumentException("expected " + count(layers) + " weights, got " + weights.length);
        }
        this.layers = layers.clone();
        this.weights = weights.clone();
    }

    /** A network with every weight at zero (all scores 0). */
    public static Network zeros(int[] layers) {
        return new Network(layers, new double[count(layers)]);
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
        return a;
    }

    @Override
    public String toString() {
        return "Network" + Arrays.toString(layers) + " (" + weights.length + " weights)";
    }
}
