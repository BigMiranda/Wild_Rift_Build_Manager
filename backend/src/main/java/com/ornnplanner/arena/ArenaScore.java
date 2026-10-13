package com.ornnplanner.arena;

/** How good a fight went for one champion and its side: the objectives of the search and of training. */
public final class ArenaScore {

    public static final java.util.List<String> OBJECTIVES = java.util.List.of("win_fast", "damage", "survive", "team");

    private ArenaScore() {
    }

    /** Higher is better. win_fast | damage | survive | team. */
    public static double score(ArenaModel.Result r, String objective, int me, int team) {
        ArenaModel.FighterStats f = r.fighters.get(me);
        double teamDealt = 0;
        double teamHp = 0;
        double enemyHp = 0;
        for (ArenaModel.FighterStats x : r.fighters) {
            if (x.team == team) {
                teamDealt += x.dealt;
                teamHp += Math.max(0, x.health);
            } else {
                enemyHp += Math.max(0, x.health);
            }
        }
        switch (objective == null ? "" : objective) {
            case "damage":
                return f.dealt;
            case "survive":
                return (f.deathTime != null ? f.deathTime : r.duration + 1000) + f.health / 100;
            case "team":
                return teamHp - enemyHp;
            default: // win_fast: winning first (sooner is better), else the damage dealt and the enemy health left
                return r.winner == team ? 100000 - r.duration * 100 : teamDealt - enemyHp;
        }
    }

    /** Share of health left on the side minus the enemies' (-1 .. 1): a signal even when nobody wins. */
    public static double healthEdge(ArenaModel.Result r, int team) {
        double mine = 0;
        double mineMax = 0;
        double theirs = 0;
        double theirsMax = 0;
        for (ArenaModel.FighterStats x : r.fighters) {
            if (x.team == team) {
                mine += Math.max(0, x.health);
                mineMax += x.maxHealth;
            } else {
                theirs += Math.max(0, x.health);
                theirsMax += x.maxHealth;
            }
        }
        return mine / Math.max(1, mineMax) - theirs / Math.max(1, theirsMax);
    }

    /** Training reward: the objective, plus a small health edge term so close fights still tell versions apart. */
    public static double fitness(ArenaModel.Result r, String objective, int me, int team) {
        return score(r, objective, me, team) + 500 * healthEdge(r, team);
    }
}
