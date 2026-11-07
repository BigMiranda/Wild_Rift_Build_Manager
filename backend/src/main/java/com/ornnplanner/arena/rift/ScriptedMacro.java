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

    /**
     * Playing in two tempos: when hurt or with gold for the next item, every way out is priced against staying
     * ({@link TwoTempo}); null = stay and go on with the role.
     */
    private Intent twoTempos(Match m, Match.Seat me) {
        @SuppressWarnings("unchecked")
        Map<String, Object> w = (Map<String, Object>) params.get("dois_tempos");
        boolean hurt = me.hpFraction() < RiftRules.value(w.get("pensar_abaixo"), 0.8);
        boolean rich = m.canBuy(me) && me.goldInHand(m) >= num("voltar_ouro", 1100);
        if (!hurt && !rich) {
            return null;
        }
        TwoTempo.Choice c = m.tempo.choose(m.situation(me));
        m.decided(me, c);
        switch (c.option) {
            case HONEYFRUIT:
                double[] f = m.nearestFruit(me);
                return f == null ? null : new Intent(Goal.RETREAT, f[0], f[1], "frutomel");
            case RECALL_WALK:
                return new Intent(Goal.RECALL, 0, 0, null);
            case RECALL_TELEPORT:
                return new Intent(Goal.RECALL, 0, 0, "teleporte");
            case SHOP_HERE:
                return new Intent(Goal.SHOP, me.x, me.y, null);
            default:
                return null;
        }
    }

    @Override
    public Intent decide(Match m, Match.Seat me) {
        if (m.tempo != null && !m.atBase(me)) {
            Intent two = twoTempos(m, me);
            if (two != null) {
                return two;
            }
        } else if (me.hpFraction() < num("voltar_vida", 0.3) || m.canBuy(me) && me.goldInHand(m) >= num("voltar_ouro", 1100) && !m.atBase(me)) {
            return new Intent(Goal.RECALL, 0, 0, null);
        }
        double[] objective = m.objectiveUp();
        double group = num("agrupar_aos", 14) * 60;
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
