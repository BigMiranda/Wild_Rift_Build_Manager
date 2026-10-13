package com.ornnplanner.arena;

import com.ornnplanner.arena.ai.Policy;
import com.ornnplanner.combat.CombatCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The arena as a training environment: an {@link Episode} is one fight with one champion to control, and
 * {@link #play} runs it with a given policy for that champion (and the episode's policies for the others), without
 * frames. Episodes share their inputs between threads; each play works on copies.
 */
public final class Gym {

    /** One fight: the inputs (read only), the timeline, the champion a policy controls, and the others' policies. */
    public static final class Episode {
        public final List<Arena.Input> inputs;
        public final List<ArenaModel.Action> actions;
        public final double duration;
        public final int me;
        /** Policy of each fighter (null = keep its own); the one at {@link #me} is replaced on play. */
        public final Policy[] policies;
        /** Short label of the opponents' brains, for reports (script, tactical, ai v3...). */
        public final String opponent;

        public Episode(List<Arena.Input> inputs, List<ArenaModel.Action> actions, double duration, int me,
                       Policy[] policies, String opponent) {
            this.inputs = inputs;
            this.actions = actions;
            this.duration = duration;
            this.me = me;
            this.policies = policies;
            this.opponent = opponent;
        }

        public int team() {
            return inputs.get(me).f.team;
        }
    }

    private final CombatCatalog catalog;
    private final Map<String, Object> map;

    public Gym(CombatCatalog catalog, Map<String, Object> map) {
        this.catalog = catalog;
        this.map = map;
    }

    /** A policy against a reference on the same episodes. */
    public static final class Report {
        public int episodes;
        /** Share of episodes where the policy did better than the reference on the objective (ties count half). */
        public double better;
        /** Share of episodes the policy's side won (draws count half), and the reference's. */
        public double winRate;
        public double referenceWinRate;
        public double meanFitness;
        public double referenceMeanFitness;
    }

    public Report compare(List<Episode> episodes, Policy policy, Policy reference, String objective) {
        Report r = new Report();
        r.episodes = episodes.size();
        for (Episode e : episodes) {
            ArenaModel.Result a = play(e, policy);
            ArenaModel.Result b = play(e, reference);
            double fa = ArenaScore.fitness(a, objective, e.me, e.team());
            double fb = ArenaScore.fitness(b, objective, e.me, e.team());
            r.better += fa > fb + 1e-9 ? 1 : Math.abs(fa - fb) <= 1e-9 ? 0.5 : 0;
            r.winRate += won(a, e.team());
            r.referenceWinRate += won(b, e.team());
            r.meanFitness += fa;
            r.referenceMeanFitness += fb;
        }
        int n = Math.max(1, episodes.size());
        r.better /= n;
        r.winRate /= n;
        r.referenceWinRate /= n;
        r.meanFitness /= n;
        r.referenceMeanFitness /= n;
        return r;
    }

    private static double won(ArenaModel.Result r, int team) {
        return r.winner == team ? 1 : r.winner < 0 ? 0.5 : 0;
    }

    public ArenaModel.Result play(Episode e, Policy mine) {
        List<Arena.Input> in = new ArrayList<>();
        for (int i = 0; i < e.inputs.size(); i++) {
            Arena.Input c = e.inputs.get(i).copy();
            Policy p = i == e.me ? mine : e.policies[i];
            if (p != null) {
                c.policy = p;
            }
            in.add(c);
        }
        return new Arena(catalog, map).withoutFrames().run(in, e.actions, e.duration);
    }
}
