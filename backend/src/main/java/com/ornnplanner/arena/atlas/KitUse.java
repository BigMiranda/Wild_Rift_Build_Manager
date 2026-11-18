package com.ornnplanner.arena.atlas;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rodada 19, "aproveita o kit?": for each stat, how much a champion's kit turns it into effect (0..1), from its
 * abilities' ratios ({@code AbilityParser}), how much it depends on basic attacks (its class's prior from the seed
 * blended with its profile's share of attack damage), its damage types and its resource. No champion or item is named:
 * the rules are the seed's ({@code aproveita_kit} in atlas_&lt;patch&gt;.yml). Pure.
 */
public final class KitUse {

    /** What the kit is read from. */
    public static class Kit {
        /** Main classes (Atirador, Tanque...), the first one leads. */
        public List<String> classes = List.of();
        /** Basic attacks' share of its damage per second at the reference stats (the profile). */
        public double attackShare;
        public double physical;
        public double magic;
        /** Stats its abilities' damage formulas read (Attack Damage, Ability Power, Max Health...). */
        public Set<String> ratios = Set.of();
        /** Its abilities speak of critical strikes. */
        public boolean critText;
        public boolean mana;
    }

    /** The seed's rules. */
    public static class Rules {
        /** Each class's dependence on basic attacks (0..1). */
        public Map<String, Double> classAttack = new LinkedHashMap<>();
        /** The class prior's weight against the profile's share. */
        public double classWeight = 0.6;
        /** Critical strike without a word of it in the kit, and attack speed: their share of the attack dependence. */
        public double critWithoutText = 0.6;
        public double attackSpeed = 0.8;
        /** Below this a condition is out of the archetype. */
        public double threshold = 0.4;
        /** Points an out-of-archetype choice must win by. */
        public double margin = 3;
        /** stat -> how it is converted: ataque, critico, vel_ataque, razao, fisico, magico, mana (absent = 1). */
        public Map<String, String> stats = new LinkedHashMap<>();
    }

    private KitUse() {
    }

    /** The basic attack dependence: the class prior blended with the profile's share. */
    public static double attack(Kit k, Rules r) {
        double prior = k.classes.isEmpty() ? 0.5 : r.classAttack.getOrDefault(k.classes.get(0), 0.5);
        return clamp(r.classWeight * prior + (1 - r.classWeight) * k.attackShare);
    }

    /** How much the kit turns a stat into effect (0..1); stats with no rule (health, resistances...) are 1. */
    public static double stat(Kit k, Rules r, String stat) {
        String how = r.stats.get(stat);
        if (how == null) {
            return 1;
        }
        double dep = attack(k, r);
        switch (how) {
            case "ataque":
                return dep;
            case "critico":
                return clamp(dep * (k.critText ? 1 : r.critWithoutText));
            case "vel_ataque":
                return clamp(dep * r.attackSpeed);
            case "razao":
                // a stat its abilities read is whole; otherwise only what the basic attacks make of it
                return k.ratios.contains(stat) ? 1 : "Attack Damage".equals(stat) ? dep : 0;
            case "fisico":
                return clamp(k.physical);
            case "magico":
                return clamp(k.magic);
            case "mana":
                return k.mana ? 1 : 0;
            default:
                return 1;
        }
    }

    /** An item's (or a passive's) kit use: its flat stats' kit use weighted by their gold (1 when it has none priced). */
    public static double weighted(Kit k, Rules r, Map<String, Double> statGold) {
        double gold = 0;
        double sum = 0;
        for (Map.Entry<String, Double> e : statGold.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) {
                continue;
            }
            gold += e.getValue();
            sum += e.getValue() * stat(k, r, e.getKey());
        }
        return gold <= 0 ? 1 : sum / gold;
    }

    /**
     * The out-of-archetype rule: the best choice by value stays when it is in the kit, or when it beats the best choice
     * in the kit by the margin; else the best in the kit wins. {@code scores[i]} is what each choice makes of the
     * objective, {@code values[i]} what ranks them (the gain per gold), {@code inKit[i]} whether it is in the kit.
     * Returns the index chosen (-1 = none).
     */
    public static int choose(double[] values, double[] scores, boolean[] inKit, double margin) {
        int best = -1;
        int bestIn = -1;
        for (int i = 0; i < values.length; i++) {
            if (best < 0 || values[i] > values[best] + 1e-12) {
                best = i;
            }
            if (inKit[i] && (bestIn < 0 || values[i] > values[bestIn] + 1e-12)) {
                bestIn = i;
            }
        }
        if (best < 0 || inKit[best] || bestIn < 0) {
            return best;
        }
        return scores[best] >= scores[bestIn] + margin ? best : bestIn;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
