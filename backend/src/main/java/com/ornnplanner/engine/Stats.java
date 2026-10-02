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
