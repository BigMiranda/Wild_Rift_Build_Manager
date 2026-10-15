package com.ornnplanner.arena.ai;

import java.util.List;

/**
 * A learned policy: the network scores every action from the observation, and the best valid one is chosen.
 *
 * <p>Residual over the tactical autopilot: the action the {@link TacticalPolicy} would pick gets {@link #BASE_BONUS}
 * added to its score. With all weights at zero it plays exactly like the tactical autopilot, and training only has to
 * learn when to do something else; a version can never start below that baseline.
 *
 * <p>Decides every {@link #INTERVAL} seconds (the arena keeps the last action meanwhile, while it stays valid).
 */
public final class NeuralPolicy implements Policy {

    public static final double BASE_BONUS = 0.5;
    public static final double INTERVAL = 0.1;

    private final Network net;
    private final Policy base;
    private final double bonus;

    /** @param priority ability order for the tactical base; read at each decision, not copied. */
    public NeuralPolicy(Network net, List<String> priority) {
        this(net, priority, BASE_BONUS);
    }

    /** Residual over the tactical autopilot with predictions (what training uses since observation schema 3). */
    public NeuralPolicy(Network net, List<String> priority, double bonus) {
        this(net, bonus, new TacticalPolicy(priority));
    }

    /** Residual over a given base (a saved version keeps the base it was trained on: {@link TacticalPolicy#legacy}). */
    public NeuralPolicy(Network net, double bonus, Policy base) {
        this.net = net;
        this.base = base;
        this.bonus = bonus;
    }

    public Policy base() {
        return base;
    }

    public Network network() {
        return net;
    }

    @Override
    public double interval() {
        return INTERVAL;
    }

    @Override
    public Decision decide(Observation obs, ActionMask mask) {
        double[] scores = net.forward(obs.vector());
        int suggested = base.decide(obs, mask).action;
        scores[suggested] += bonus;
        // best first; the mask is only asked until a valid one is found
        Integer[] order = new Integer[scores.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (x, y) -> scores[y] != scores[x] ? Double.compare(scores[y], scores[x]) : Integer.compare(x, y));
        for (int a : order) {
            if (mask.valid(a)) {
                return new Decision(a, scores);
            }
        }
        return new Decision(ActionSpace.IDLE, scores);
    }
}
