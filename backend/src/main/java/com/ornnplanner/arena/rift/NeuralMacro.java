package com.ornnplanner.arena.rift;

import com.ornnplanner.arena.ai.Network;

/**
 * A learned macro (proposta-macro-treinada.md, Parte 2): the network scores every {@link MacroActions action} from the
 * {@link MacroObservation}, and the best valid one is taken. Residual over the {@link ScriptedMacro}: the script's own
 * choice gets {@link #BASE_BONUS} added, so with all weights at zero it plays exactly like the script, and training only
 * has to learn when to do something else.
 *
 * <p>Asked at every coarse step, like the script. Holds no state: one instance plays any number of matches at once; what
 * it must remember lives in the {@link Match.Seat}.
 */
public final class NeuralMacro implements MacroPolicy {

    public static final double BASE_BONUS = 0.5;

    /** Obs -> 16 -> one score per action. */
    public static final int[] LAYERS = {MacroObservation.SIZE, 16, MacroActions.SIZE};

    private final Network net;
    private final ScriptedMacro script;
    private final double bonus;

    public NeuralMacro(Network net, ScriptedMacro script) {
        this(net, script, BASE_BONUS);
    }

    public NeuralMacro(Network net, ScriptedMacro script, double bonus) {
        this.net = net;
        this.script = script;
        this.bonus = bonus;
    }

    public Network network() {
        return net;
    }

    @Override
    public Intent decide(Match m, Match.Seat me) {
        ScriptedMacro.Suggestion s = script.suggest(m, me);
        int suggested = MacroActions.of(s.intent, me);
        double[] scores = net.forward(MacroObservation.of(m, me, suggested));
        scores[suggested] += bonus;
        int best = suggested;
        for (int a = 0; a < scores.length; a++) {
            if (a != suggested && (scores[a] > scores[best] || scores[a] == scores[best] && a < best) && MacroActions.valid(a, m, me)) {
                best = a;
            }
        }
        if (best == suggested) {
            // the script's own choice: its log, exactly as the script tells it
            if (s.choice != null) {
                m.decided(me, s.choice);
            }
            me.macroAction = -1;
            return s.intent;
        }
        if (s.choice != null) {
            me.lastChoice = null;   // the script's way out was not taken: tell the next one again
        }
        if (me.macroAction != best) {
            me.macroAction = best;
            m.note(me.team, m.name(me) + " (macro treinada): " + MacroActions.LABELS[best] + " em vez de " + MacroActions.LABELS[suggested]);
        }
        return MacroActions.intent(best, m, me);
    }
}
