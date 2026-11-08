package com.ornnplanner.arena.rift;

import java.util.Map;

/**
 * The macro by role: it goes back to the base with low health or gold for the next item, works its lane or the jungle
 * route, turns to the objectives by their times, and groups after a while. It names no champion; the numbers are in
 * {@code macro_<patch>.yml}.
 */
public final class ScriptedMacro implements MacroPolicy {

    private final Map<String, Object> params;

    public ScriptedMacro(Map<String, Object> params) {
        this.params = params;
    }

    private double num(String key, double def) {
        return RiftRules.value(params.get(key), def);
    }

    /** What the script would do, and the two-tempo choice it weighed on the way (null: it did not weigh one). */
    public static final class Suggestion {
        public final Intent intent;
        public final TwoTempo.Choice choice;

        Suggestion(Intent intent, TwoTempo.Choice choice) {
            this.intent = intent;
            this.choice = choice;
        }
    }

    /**
     * Playing in two tempos: when hurt or with gold for the next item, every way out is priced against staying
     * ({@link TwoTempo}); a null intent = stay and go on with the role.
     */
    private Suggestion twoTempos(Match m, Match.Seat me) {
        @SuppressWarnings("unchecked")
        Map<String, Object> w = (Map<String, Object>) params.get("dois_tempos");
        boolean hurt = me.hpFraction() < RiftRules.value(w.get("pensar_abaixo"), 0.8);
        boolean rich = m.canBuy(me) && me.goldInHand(m) >= num("voltar_ouro", 1100);
        if (!hurt && !rich) {
            return null;
        }
        TwoTempo.Choice c = m.tempo.choose(m.situation(me));
        switch (c.option) {
            case HONEYFRUIT:
                double[] f = m.nearestFruit(me);
                return new Suggestion(f == null ? null : new Intent(Goal.RETREAT, f[0], f[1], "frutomel"), c);
            case RECALL_WALK:
                return new Suggestion(new Intent(Goal.RECALL, 0, 0, null), c);
            case RECALL_TELEPORT:
                return new Suggestion(new Intent(Goal.RECALL, 0, 0, "teleporte"), c);
            case SHOP_HERE:
                return new Suggestion(new Intent(Goal.SHOP, me.x, me.y, null), c);
            default:
                return new Suggestion(null, c);
        }
    }

    @Override
    public Intent decide(Match m, Match.Seat me) {
        Suggestion s = suggest(m, me);
        if (s.choice != null) {
            m.decided(me, s.choice);
        }
        return s.intent;
    }

    /** What the script would do now, with no trace in the match: the learned macro's base. */
    public Suggestion suggest(Match m, Match.Seat me) {
        TwoTempo.Choice weighed = null;
        // the lane is free (its enemy laners dead, at base or going back): press it, the wave and the tower, before buying
        if (m.laneFree(me) && !m.atBase(me) && me.hpFraction() >= num("pressionar_vida", 0.35)) {
            return new Suggestion(role(m, me), null);
        }
        if (m.tempo != null && !m.atBase(me)) {
            Suggestion two = twoTempos(m, me);
            if (two != null) {
                weighed = two.choice;
                if (two.intent != null) {
                    return two;
                }
            }
        } else if (me.hpFraction() < num("voltar_vida", 0.3) || m.canBuy(me) && me.goldInHand(m) >= num("voltar_ouro", 1100) && !m.atBase(me)) {
            return new Suggestion(new Intent(Goal.RECALL, 0, 0, null), null);
        }
        return new Suggestion(role(m, me), weighed);
    }

    /** The role's plan, with no way out: objectives, grouping, the jungle route, the lane. */
    private Intent role(Match m, Match.Seat me) {
        double[] objective = m.objectiveUp();
        double group = num("agrupar_aos", 14) * 60;
        // after grouping, an edge is pushed before any objective: the Baron buff, or enough enemies dead
        boolean edge = m.baronBuff(me.team) || m.dead(1 - me.team) >= num("empurrar_com_mortos", 3);
        if (m.seconds() >= group && edge) {
            double[] rally = m.rally(me.team);
            return new Intent(Goal.PUSH, rally[0], rally[1], m.pushLane(me.team));
        }
        // without an edge, a structure under pressure is defended first (the base and the Nexus at any time)
        Object[] threat = m.threat(me.team);
        if (threat != null) {
            return new Intent(Goal.DEFEND, (double) threat[0], (double) threat[1], (String) threat[2]);
        }
        boolean roams = "selva".equals(me.role) || "meio".equals(me.role) || m.seconds() >= group;
        if (objective != null && roams && m.seconds() >= num("objetivo_depois_de", 5) * 60) {
            return new Intent(Goal.OBJECTIVE, objective[0], objective[1], objective[2] > 0 ? "barao" : "dragao");
        }
        if (m.seconds() >= group) {
            double[] rally = m.rally(me.team);
            return new Intent(Goal.PUSH, rally[0], rally[1], m.pushLane(me.team));
        }
        if ("selva".equals(me.role)) {
            double[] camp = m.nextCamp(me);
            if (camp != null) {
                return new Intent(Goal.JUNGLE, camp[0], camp[1], String.valueOf((int) camp[2]));
            }
        }
        String lane = Match.laneOf(me.role);
        double[] spot = m.laneSpot(me.team, lane);
        return new Intent(Goal.LANE, spot[0], spot[1], lane);
    }
}
