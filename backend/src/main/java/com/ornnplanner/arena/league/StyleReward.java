package com.ornnplanner.arena.league;

import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.ArenaScore;
import com.ornnplanner.arena.ArenaTraining;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rodada 21: the reward of each requested style ({@code recompensas} of {@code treino_liga_<patch>.yml}): a weighted sum
 * of terms measured in the fight. The terms are generic (no champion, no style hard-wired here): which ones count, and
 * how much, is the seed's.
 */
public final class StyleReward {

    private StyleReward() {
    }

    /** Every term of the fight for the champion at {@code me} (see the seed's header for their meaning). */
    public static Map<String, Double> terms(ArenaModel.Result r, int me, int team, double limit) {
        ArenaModel.FighterStats f = r.fighters.get(me);
        double lim = Math.max(1, limit);
        boolean won = r.winner == team;
        double myEnd = f.deathTime != null ? f.deathTime : r.duration;
        Map<String, Double> t = new LinkedHashMap<>();
        t.put("vitoria", won ? 1.0 : 0.0);
        t.put("limpa", ArenaTraining.cleanWin(r, me, team) ? 1.0 : 0.0);
        t.put("rapidez", won ? Math.max(0, 1 - r.duration / lim) : 0.0);
        t.put("dano", f.dealt / Math.max(1, f.maxHealth));
        t.put("vida", Math.max(0, f.health) / Math.max(1, f.maxHealth));
        t.put("viveu", f.deathTime == null ? 1.0 : 0.0);
        t.put("tempo_vivo", Math.min(1, myEnd / lim));
        boolean arrived = f.escapedAt != null && f.deathTime == null;
        t.put("chegou", arrived ? 1.0 : 0.0);
        t.put("chegou_cedo", arrived ? Math.max(0, 1 - f.escapedAt / lim) : 0.0);
        double held = 0;
        double control = 0;
        for (ArenaModel.FighterStats e : r.fighters) {
            if (e.team == team) {
                continue;
            }
            double theirEnd = Math.min(e.deathTime != null ? e.deathTime : r.duration, e.escapedAt != null ? e.escapedAt : r.duration);
            held += Math.min(myEnd, theirEnd);
            for (double[] s : e.controlSpans) {
                control += Math.max(0, Math.min(s[1], r.duration) - s[0]);
            }
        }
        t.put("presos", held / lim);
        t.put("controle", control / lim);
        t.put("ouro", f.goldEarned / 100);
        t.put("vantagem", ArenaScore.healthEdge(r, team));
        return t;
    }

    /** The style's reward: its weights over the terms (an unknown style reads as {@code padrao}). */
    public static double score(ArenaModel.Result r, int me, int team, double limit, Map<String, Double> weights) {
        Map<String, Double> t = terms(r, me, team, limit);
        double s = 0;
        for (Map.Entry<String, Double> w : weights.entrySet()) {
            Double v = t.get(w.getKey());
            if (v == null) {
                throw new IllegalStateException("Termo de recompensa desconhecido: " + w.getKey());
            }
            s += w.getValue() * v;
        }
        return s;
    }
}
