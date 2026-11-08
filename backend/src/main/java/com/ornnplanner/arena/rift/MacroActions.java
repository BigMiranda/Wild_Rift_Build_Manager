package com.ornnplanner.arena.rift;

import com.ornnplanner.arena.rift.MacroPolicy.Goal;
import com.ornnplanner.arena.rift.MacroPolicy.Intent;

/**
 * What a learned macro may do (proposta-macro-treinada.md, Parte 2): each action becomes the same {@link Intent} the
 * script would make for it, so the script's choice always has an action and that action gives back its intent. Only
 * appended at the end; saved weights record how many actions they score.
 */
public final class MacroActions {

    private MacroActions() {
    }

    public static final int OWN_LANE = 0;
    public static final int BARON_LANE = 1;
    public static final int MID_LANE = 2;
    public static final int DRAGON_LANE = 3;
    public static final int JUNGLE = 4;
    public static final int OBJECTIVE = 5;
    public static final int PUSH = 6;
    public static final int HONEYFRUIT = 7;
    public static final int RECALL_WALK = 8;
    public static final int RECALL_TELEPORT = 9;
    public static final int SHOP_HERE = 10;
    public static final int HOLD_TOWER = 11;
    public static final int DEFEND = 12;

    public static final int SIZE = 13;

    /** For the log and the screen (pt-BR, as the match's log). */
    public static final String[] LABELS = {"ficar na própria rota", "ir à rota do Barão", "ir ao meio", "ir à rota do Dragão",
        "fazer a selva", "ir ao objetivo", "agrupar e empurrar", "recuar para o Frutomel", "voltar à base a pé",
        "voltar à base de Teleporte", "comprar onde está", "esperar sob a torre", "defender a base"};

    private static final String[] LANES = {null, "barao", "meio", "dragao"};

    /** The action of one of the script's intents. */
    public static int of(Intent i, Match.Seat me) {
        switch (i.goal) {
            case RECALL:
                return "teleporte".equals(i.what) ? RECALL_TELEPORT : RECALL_WALK;
            case RETREAT:
                return HONEYFRUIT;
            case SHOP:
                return SHOP_HERE;
            case OBJECTIVE:
                return OBJECTIVE;
            case PUSH:
                return PUSH;
            case JUNGLE:
                return JUNGLE;
            case DEFEND:
                return DEFEND;
            default:
                return OWN_LANE;
        }
    }

    /** May the seat take this action now? */
    public static boolean valid(int a, Match m, Match.Seat me) {
        switch (a) {
            case JUNGLE:
                return m.nextCamp(me) != null;
            case OBJECTIVE:
                return m.objectiveUp() != null;
            case HONEYFRUIT:
                return !m.atBase(me) && m.nearestFruit(me) != null;
            case RECALL_WALK:
                return !m.atBase(me);
            case RECALL_TELEPORT:
                return !m.atBase(me) && m.teleportReady(me);
            case SHOP_HERE:
                return m.canBuy(me) && m.shopsHere(me);
            case HOLD_TOWER:
                return m.nextTower(me.team, Match.laneOf(me.role)) != null;
            case DEFEND:
                return m.threat(me.team) != null;
            default:
                return true;
        }
    }

    /** The intent of an action, the same one the script makes when it picks it. */
    public static Intent intent(int a, Match m, Match.Seat me) {
        switch (a) {
            case BARON_LANE:
            case MID_LANE:
            case DRAGON_LANE: {
                double[] spot = m.laneSpot(me.team, LANES[a]);
                return new Intent(Goal.LANE, spot[0], spot[1], LANES[a]);
            }
            case JUNGLE: {
                double[] camp = m.nextCamp(me);
                return new Intent(Goal.JUNGLE, camp[0], camp[1], String.valueOf((int) camp[2]));
            }
            case OBJECTIVE: {
                double[] o = m.objectiveUp();
                return new Intent(Goal.OBJECTIVE, o[0], o[1], o[2] > 0 ? "barao" : "dragao");
            }
            case PUSH: {
                double[] rally = m.rally(me.team);
                return new Intent(Goal.PUSH, rally[0], rally[1], m.pushLane(me.team));
            }
            case HONEYFRUIT: {
                double[] f = m.nearestFruit(me);
                return new Intent(Goal.RETREAT, f[0], f[1], "frutomel");
            }
            case RECALL_WALK:
                return new Intent(Goal.RECALL, 0, 0, null);
            case RECALL_TELEPORT:
                return new Intent(Goal.RECALL, 0, 0, "teleporte");
            case SHOP_HERE:
                return new Intent(Goal.SHOP, me.x, me.y, null);
            case DEFEND: {
                Object[] th = m.threat(me.team);
                return new Intent(Goal.DEFEND, (double) th[0], (double) th[1], (String) th[2]);
            }
            case HOLD_TOWER: {
                String lane = Match.laneOf(me.role);
                Match.Tower tw = m.nextTower(me.team, lane);
                return new Intent(Goal.LANE, tw.x, tw.y, lane);
            }
            default: {
                String lane = Match.laneOf(me.role);
                double[] spot = m.laneSpot(me.team, lane);
                return new Intent(Goal.LANE, spot[0], spot[1], lane);
            }
        }
    }
}
