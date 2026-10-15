package com.ornnplanner.arena.fight;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A champion in the arena (built by {@link FighterBuilder} from a build at a minute); not changed by a run. */
public class Fighter {
    public int index;
    public int team;
    public String name;
    public String champion;
    public int level;
    public double minute;
    public Map<String, Double> total = new LinkedHashMap<>();
    public Map<String, Double> base = new LinkedHashMap<>();
    /** Held items (names) and their ids. */
    public Map<String, Long> items = new LinkedHashMap<>();
    public List<ItemEffect> effects = new ArrayList<>();
    public List<AbilityParser.Ability> abilities = new ArrayList<>();
    /** Ranks of 1, 2, 3, R at the fighter's level. */
    public int[] ranks = new int[4];
    /** Base attack speed at the level (attacks / s), before the bonus %. */
    public double baseAttackSpeed;
    public List<String> spells = new ArrayList<>();
    /** Where the effective stats come from (item / rune / champion effect name -> amount). */
    public Map<String, Double> shieldSources = new LinkedHashMap<>();
    /** Shields that absorb only magic / only physical damage. */
    public Map<String, Double> magicShieldSources = new LinkedHashMap<>();
    public Map<String, Double> physicalShieldSources = new LinkedHashMap<>();
    /** Statuses of the build that only count after some time or hits in the fight. */
    public List<Delay> delays = new ArrayList<>();
    public Map<String, Double> healSources = new LinkedHashMap<>();
    public Map<String, Double> allyShieldSources = new LinkedHashMap<>();
    public Map<String, Double> allyHealSources = new LinkedHashMap<>();
    public Map<String, Double> vampSources = new LinkedHashMap<>();

    public double stat(String s) {
        return total.getOrDefault(s, 0.0);
    }

    public double bonus(String s) {
        return stat(s) - base.getOrDefault(s, 0.0);
    }
}
