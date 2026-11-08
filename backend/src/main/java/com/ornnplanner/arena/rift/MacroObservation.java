package com.ornnplanner.arena.rift;

/**
 * What a learned macro sees (proposta-macro-treinada.md, Parte 2): the champion, the clock and the objectives, its lane,
 * who is around and the score, every number scaled to about [-1, 1], plus the script's choice as one-hot. Versioned:
 * saved weights record {@link #SCHEMA} and are refused under another one.
 */
public final class MacroObservation {

    private MacroObservation() {
    }

    public static final int SCHEMA = 2;

    public static final int SIZE = 51;

    private static final String[] ROLES = {"barao", "selva", "meio", "dupla", "suporte"};

    private static double clip(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** The vector for a seat; {@code suggested} is the action the script would take. */
    public static double[] of(Match m, Match.Seat me, int suggested) {
        double[] o = new double[SIZE];
        int k = 0;
        // the champion
        for (String r : ROLES) {
            o[k++] = r.equals(me.role) ? 1 : 0;
        }
        o[k++] = me.hp;
        o[k++] = clip(me.goldInHand(m) / 1500, 0, 3);
        o[k++] = me.level / 18.0;
        int steps = m.world.steps(me.index);
        o[k++] = steps == 0 ? 1 : me.steps / (double) steps;
        o[k++] = m.teleportReady(me) ? 1 : 0;
        o[k++] = m.atBase(me) ? 1 : 0;
        // where it is
        double[] base = me.team == 0 ? m.map.fountainBlue : m.map.fountainRed;
        o[k++] = Math.hypot(base[0] - me.x, base[1] - me.y) / 12000;
        String lane = Match.laneOf(me.role);
        double[] spot = m.laneSpot(me.team, lane);
        o[k++] = clip(Math.hypot(spot[0] - me.x, spot[1] - me.y) / 6000, 0, 2);
        o[k++] = Math.hypot(m.map.dragonPit[0] - me.x, m.map.dragonPit[1] - me.y) / 12000;
        o[k++] = Math.hypot(m.map.baronPit[0] - me.x, m.map.baronPit[1] - me.y) / 12000;
        // the clock and the objectives
        o[k++] = m.seconds() / 1200;
        double dragon = m.dragonIn();
        o[k++] = dragon == 0 ? 1 : 0;
        o[k++] = dragon < 0 ? 1 : clip(dragon / 300, 0, 1);
        double baron = m.baronIn();
        o[k++] = baron == 0 ? 1 : 0;
        o[k++] = baron < 0 ? 1 : clip(baron / 300, 0, 1);
        // its lane: the front towers and waves of both sides
        Match.Tower mine = m.nextTower(me.team, lane);
        Match.Tower theirs = m.nextTower(1 - me.team, lane);
        o[k++] = mine == null ? 0 : mine.hp / mine.maxHp;
        o[k++] = mine == null ? 0 : mine.plates / 5.0;
        o[k++] = theirs == null ? 0 : theirs.hp / theirs.maxHp;
        double length = m.laneLength(lane);
        Match.Wave ours = m.frontOf(me.team, lane);
        Match.Wave enemy = m.frontOf(1 - me.team, lane);
        o[k++] = ours == null ? 0 : ours.along / length;
        o[k++] = enemy == null ? 1 : (length - enemy.along) / length;
        // who is around
        int foes = 0;
        int friends = 0;
        double nearest = Double.MAX_VALUE;
        double nearestHp = 1;
        double foeStrength = 0;
        double friendStrength = m.strength(me);
        double all = 0;
        int alive = 0;
        for (Match.Seat s : m.seats) {
            if (s.dead) {
                continue;
            }
            all += m.strength(s);
            alive++;
            if (s == me) {
                continue;
            }
            double d = Math.hypot(s.x - me.x, s.y - me.y);
            if (s.team != me.team) {
                if (d <= 2000) {
                    foes++;
                }
                if (d <= 1500) {
                    foeStrength += m.strength(s);
                }
                if (d < nearest) {
                    nearest = d;
                    nearestHp = s.hp;
                }
            } else {
                if (d <= 2000) {
                    friends++;
                }
                if (d <= 1500) {
                    friendStrength += m.strength(s);
                }
            }
        }
        o[k++] = foes / 5.0;
        o[k++] = friends / 4.0;
        o[k++] = nearest == Double.MAX_VALUE ? 1 : clip(nearest / 3000, 0, 1);
        o[k++] = nearestHp;
        o[k++] = clip(foeStrength / Math.max(1, friendStrength), 0, 4) / 4;
        o[k++] = clip(m.strength(me) / Math.max(1, all / Math.max(1, alive)), 0, 3) / 3;
        // the score
        double gold = 0;
        int kills = 0;
        int towers = 0;
        for (Match.Seat s : m.seats) {
            double sign = s.team == me.team ? 1 : -1;
            gold += sign * s.gold;
            kills += (int) sign * s.kills;
        }
        for (Match.Tower tw : m.towers) {
            if (tw.hp > 0) {
                towers += tw.team == me.team ? 1 : -1;
            }
        }
        o[k++] = clip(gold / 5000, -2, 2);
        o[k++] = clip(kills / 10.0, -2, 2);
        o[k++] = m.drakes(me.team) / 4.0;
        o[k++] = m.drakes(1 - me.team) / 4.0;
        o[k++] = m.baronBuff(me.team) ? 1 : 0;
        o[k++] = m.baronBuff(1 - me.team) ? 1 : 0;
        o[k++] = towers / 9.0;
        // what the script would do
        o[k + suggested] = 1;
        k += MacroActions.SIZE;
        if (k != SIZE) {
            throw new IllegalStateException("macro observation has " + k + " numbers, SIZE says " + SIZE);
        }
        return o;
    }
}
