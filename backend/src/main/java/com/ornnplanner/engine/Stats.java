package com.ornnplanner.engine;

import java.util.List;

/**
 * Stat names. Item stats use exactly the names of the reference data set
 * (changchiyou/wildrift-gold-efficiency, _data/stats_&lt;patch&gt;.yml) so that imported
 * data and derived gold prices line up without any mapping layer.
 */
public final class Stats {
    private Stats() {
    }

    public static final String MAX_HEALTH = "Max Health";
    public static final String ARMOR = "Armor";
    public static final String MAGIC_RESIST = "Magic Resistance";
    public static final String ABILITY_HASTE = "Ability Haste";
    public static final String ABILITY_POWER = "Ability Power";
    public static final String ATTACK_DAMAGE = "Attack Damage";
    public static final String MAX_MANA = "Max Mana";
    public static final String MOVE_SPEED = "Move Speed";
    /** Item stat: % of base health regen. */
    public static final String PCT_HEALTH_REGEN = "% Health Regen";
    /** Item stat: % of base mana regen. */
    public static final String PCT_MANA_REGEN = "% Mana Regeneration";

    /** Unit stat (base/growth only): health regen per 5 seconds. Items modify it through {@link #PCT_HEALTH_REGEN}. */
    public static final String HEALTH_REGEN = "Health Regen";
    /** Unit stat (base/growth only): mana regen per 5 seconds. Items modify it through {@link #PCT_MANA_REGEN}. */
    public static final String MANA_REGEN = "Mana Regen";

    /** % of crowd control time cut. Sources stack multiplicatively (see {@link #combine}). */
    public static final String TENACITY = "% Tenacity";

    /**
     * Cooldown reduction in % (Rodada 17): what a refund of the cooldown left does to an ability's cooldown on
     * average (Ímpeto Gradual, Transcendência, Arcanista do Axioma, Navori, Ornn's Investida on a pillar). Kinds of
     * Ability Haste's cooldown cut, like Ultimate / Basic Ability Haste: of the basic abilities, of the ultimate, of
     * one ability. Applied after the haste: cooldown = base / (1 + haste / 100) x (1 - reduction / 100).
     */
    public static final String BASIC_CDR = "% Basic Cooldown Reduction";
    public static final String ULT_CDR = "% Ultimate Cooldown Reduction";
    /** Of one ability only: {@code "% Ability <slot> Cooldown Reduction"}, slot 1, 2 or 3. */
    public static String abilityCdr(String slot) {
        return "% Ability " + slot + " Cooldown Reduction";
    }

    public static final List<String> CDR_STATS = List.of(BASIC_CDR, ULT_CDR, abilityCdr("1"), abilityCdr("2"), abilityCdr("3"));

    /** Stats whose sources each cut what is left (see {@link #combine}). */
    private static final java.util.Set<String> MULTIPLICATIVE = java.util.stream.Stream.concat(
            java.util.stream.Stream.of(TENACITY), CDR_STATS.stream()).collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** Whether a stat's sources each cut what is left instead of adding up. */
    public static boolean multiplicative(String stat) {
        return MULTIPLICATIVE.contains(stat);
    }

    /**
     * Two amounts of one stat together. Every stat adds up, except Tenacity and the cooldown reductions: each source
     * cuts what is left, so 10% + 30% = 37%, + 20% = 50%, + 20% = 60% (Tenacity measured in the game's shop).
     */
    public static double combine(String stat, double a, double b) {
        if (MULTIPLICATIVE.contains(stat)) {
            return 100 - (100 - a) * (100 - b) / 100;
        }
        return a + b;
    }

    /** The inverse of {@link #combine}: a total without one of its parts. */
    public static double remove(String stat, double total, double part) {
        if (MULTIPLICATIVE.contains(stat)) {
            return part >= 100 ? total : 100 - (100 - total) / ((100 - part) / 100);
        }
        return total - part;
    }

    /**
     * Share of an ability's base cooldown it actually waits (0..1), from a stats map: the haste (Ability Haste plus
     * Ultimate / Basic Ability Haste), then the % reductions (basic or ultimate, and the ability's own).
     */
    public static double cooldownFactor(java.util.function.ToDoubleFunction<String> stat, String slot) {
        boolean ult = "R".equals(slot);
        double haste = stat.applyAsDouble(ABILITY_HASTE) + stat.applyAsDouble(ult ? "Ultimate Haste" : "Basic Ability Haste");
        double cut = ult ? stat.applyAsDouble(ULT_CDR)
                : combine(BASIC_CDR, stat.applyAsDouble(BASIC_CDR), stat.applyAsDouble(abilityCdr(slot)));
        return 1 / (1 + Math.max(0, haste) / 100) * (1 - Math.max(0, Math.min(90, cut)) / 100);
    }

    /** {@code m.merge} with {@link #combine}. */
    public static void merge(java.util.Map<String, Double> m, String stat, double v) {
        m.merge(stat, v, (a, b) -> combine(stat, a, b));
    }

    /** Marker used by the reference data for "this item has conditional stats"; carries no value. */
    public static final String UNSTABLE_MARKER = "Unstable Passives' Stats";

    /** Stats Ornn's Living Forge multiplies (bonus part only). */
    public static final List<String> LIVING_FORGE_STATS = List.of(MAX_HEALTH, ARMOR, MAGIC_RESIST);

    /** Stats a unit has a base value + per-level growth for. */
    public static final List<String> UNIT_STATS = List.of(
            MAX_HEALTH, MAX_MANA, HEALTH_REGEN, MANA_REGEN, ARMOR, MAGIC_RESIST,
            ATTACK_DAMAGE, ABILITY_POWER, ABILITY_HASTE, MOVE_SPEED);

    /** Stats shown by default in the timeline (the ones relevant to a tank/support Ornn). */
    public static final List<String> DISPLAY_STATS = List.of(
            MAX_HEALTH, ARMOR, MAGIC_RESIST, ABILITY_HASTE, HEALTH_REGEN, ABILITY_POWER, ATTACK_DAMAGE,
            MAX_MANA, MANA_REGEN, MOVE_SPEED);
}
