package com.ornnplanner.arena;

import com.ornnplanner.arena.ArenaModel.FighterStats;
import com.ornnplanner.arena.ArenaModel.Result;
import com.ornnplanner.arena.fight.CombatCatalog;
import com.ornnplanner.arena.fight.ItemEffect;
import com.ornnplanner.engine.Stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * How one champion sees another ("visto por"): the defender's stats in practice for that attacker. Everything comes
 * from the fight's own rules: the stats both have as a fight starts (a zero-second run of the arena), the share of a
 * hit that gets through ({@link Arena#through}, the function every hit uses) and the attacker's (and an ally
 * ragdoll's) item effects at their most: resistance reduction, penetration, lethality, shield cut, Grievous Wounds,
 * the defender's damage reduction against attacks.
 */
public final class SeenBy {

    public double health;
    public double armor;
    public double magicResist;
    /** Armor and magic resist that count for this attacker (after reduction, % and flat penetration). */
    public double effectiveArmor;
    public double effectiveMagicResist;
    /** Share of physical / magic damage that gets through (after resistances). */
    public double physicalThrough;
    public double magicThrough;
    /** Reduction of the defender against attacks (items), 0..1. */
    public double attackReduction;
    /** Resistance reduction applied at its most (attacker's and ally's items). */
    public double armorShred;
    public double magicShred;
    /** Shields as the fight starts, and after the attacker's shield cut. */
    public double shields;
    public double shieldsAfterCut;
    public double shieldCut;
    /** Share of the defender's healing that lands (Grievous Wounds of the attacker or the ally). */
    public double healingKept;
    /** The attacker's damage split (physical share), by its attack damage and ability power. */
    public double physicalShare;
    /** Effective health: against physical damage, physical attacks only, magic damage, and the attacker's mix. */
    public double effectiveHpPhysical;
    public double effectiveHpAttacks;
    public double effectiveHpMagic;
    public double effectiveHpMixed;
    /** Why the resistances drop, in words ("Cutelo Negro −30%", "Lembranças do Lorde Dominik 35% de penetração"). */
    public List<String> armorReasons = new ArrayList<>();
    public List<String> magicReasons = new ArrayList<>();
    /** The items behind the shield cut and the Grievous Wounds (null = none). */
    public String shieldCutBy;
    public String healCutBy;
    public Double minute;

    private SeenBy() {
    }

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }

    /** The defender seen by the attacker, with an ally's item effects (null = none) applied at their most. */
    public static SeenBy of(CombatCatalog catalog, Map<String, Object> map, Arena.Input defender, Arena.Input attacker,
                            List<ItemEffect> allyEffects) {
        Arena.Input d = defender.copy();
        Arena.Input a = attacker.copy();
        d.f = defender.f;
        a.f = attacker.f;
        d.x = 1000;
        a.x = 1200;
        int dTeam = d.f.team;
        int aTeam = a.f.team;
        d.f.team = 0;
        a.f.team = 1;
        Result r;
        try {
            r = new Arena(catalog, map).withoutFrames().run(List.of(d, a), List.of(), 0);
        } finally {
            d.f.team = dTeam;
            a.f.team = aTeam;
        }
        FighterStats ds = r.fighters.get(0);
        FighterStats as = r.fighters.get(1);
        SeenBy s = new SeenBy();
        s.health = ds.startStats.getOrDefault(Stats.MAX_HEALTH, 0.0);
        s.armor = ds.startStats.getOrDefault(Stats.ARMOR, 0.0);
        s.magicResist = ds.startStats.getOrDefault(Stats.MAGIC_RESIST, 0.0);
        List<ItemEffect> hitting = new ArrayList<>(attacker.f.effects);
        int own = hitting.size();
        if (allyEffects != null) {
            hitting.addAll(allyEffects);
        }
        double cut = 0;
        double gw = 0;
        String armorBy = null;
        String magicBy = null;
        for (int i = 0; i < hitting.size(); i++) {
            ItemEffect e = hitting.get(i);
            String who = e.item + (i >= own ? " (aliado)" : "");
            switch (e.kind()) {
                case "reduz_resistencia":
                    double max = Arena.num(e.p.get("max"), 0.3);
                    if ("armadura".equals(e.p.get("resistencia"))) {
                        if (max > s.armorShred) {
                            s.armorShred = max;
                            armorBy = who;
                        }
                    } else if (max > s.magicShred) {
                        s.magicShred = max;
                        magicBy = who;
                    }
                    break;
                case "corta_escudo":
                    double c = Math.min(Arena.num(e.p.get("max"), 0.6), Arena.num(e.p.get("base"), 0)
                            + Arena.num(e.p.get("dda_bonus"), 0) * Math.max(0, as.startStats.getOrDefault(Stats.ATTACK_DAMAGE, 0.0)
                            - attacker.f.base.getOrDefault(Stats.ATTACK_DAMAGE, 0.0))
                            + Arena.num(e.p.get("pdh"), 0) * as.startStats.getOrDefault(Stats.ABILITY_POWER, 0.0));
                    if (c > cut) {
                        cut = c;
                        s.shieldCutBy = who;
                    }
                    break;
                case "feridas":
                    if (Arena.num(e.p.get("pct"), 0.4) > gw) {
                        gw = Arena.num(e.p.get("pct"), 0.4);
                        s.healCutBy = who;
                    }
                    break;
                default:
                    break;
            }
        }
        if (armorBy != null) {
            s.armorReasons.add(armorBy + " −" + pct(s.armorShred));
        }
        if (magicBy != null) {
            s.magicReasons.add(magicBy + " −" + pct(s.magicShred));
        }
        reasons(s.armorReasons, attacker.f.statSources, "% Armor Penetration", "Armor Penetration");
        reasons(s.magicReasons, attacker.f.statSources, "% Magic Penetration", "Magic Penetration");
        for (ItemEffect e : defender.f.effects) {
            if ("reduz_dano".equals(e.kind()) && "ataque".equals(String.valueOf(e.p.get("contra")))) {
                s.attackReduction = Math.min(0.9, s.attackReduction + Arena.num(e.p.get("pct"), 0));
            }
        }
        double pctArmorPen = as.startStats.getOrDefault("% Armor Penetration", 0.0) / 100;
        double flatArmorPen = as.startStats.getOrDefault("Armor Penetration", 0.0);
        double pctMagicPen = as.startStats.getOrDefault("% Magic Penetration", 0.0) / 100;
        double flatMagicPen = as.startStats.getOrDefault("Magic Penetration", 0.0);
        s.physicalThrough = Arena.through(s.armor, s.armorShred, pctArmorPen, flatArmorPen);
        s.magicThrough = Arena.through(s.magicResist, s.magicShred, pctMagicPen, flatMagicPen);
        s.effectiveArmor = resist(s.physicalThrough);
        s.effectiveMagicResist = resist(s.magicThrough);
        double any = ds.startShields.getOrDefault("any", 0.0);
        double phys = ds.startShields.getOrDefault("fisico", 0.0);
        double mag = ds.startShields.getOrDefault("magico", 0.0);
        s.shields = any + phys + mag;
        s.shieldCut = cut;
        s.shieldsAfterCut = s.shields * (1 - cut);
        s.healingKept = 1 - gw;
        double ad = Math.max(0, as.startStats.getOrDefault(Stats.ATTACK_DAMAGE, 0.0));
        double ap = Math.max(0, as.startStats.getOrDefault(Stats.ABILITY_POWER, 0.0));
        s.physicalShare = ad + ap <= 0 ? 1 : ad / (ad + ap);
        double k = 1 - cut;
        s.effectiveHpPhysical = (s.health + (any + phys) * k) / Math.max(1e-9, s.physicalThrough);
        s.effectiveHpAttacks = (s.health + (any + phys) * k) / Math.max(1e-9, s.physicalThrough * (1 - s.attackReduction));
        s.effectiveHpMagic = (s.health + (any + mag) * k) / Math.max(1e-9, s.magicThrough);
        double mix = s.physicalShare * s.physicalThrough + (1 - s.physicalShare) * s.magicThrough;
        s.effectiveHpMixed = (s.health + s.shields * k) / Math.max(1e-9, mix);
        return s;
    }

    /** The attacker's penetration by source, in words: "% de penetração" and flat "de penetração". */
    private static void reasons(List<String> out, Map<String, Map<String, Double>> sources, String pctStat, String flatStat) {
        sources.getOrDefault(pctStat, Map.of()).forEach((src, v) -> out.add(src + " " + Math.round(v) + "% de penetração"));
        sources.getOrDefault(flatStat, Map.of()).forEach((src, v) -> out.add(src + " " + Math.round(v) + " de penetração"));
    }

    /** The resistance that lets the given share through (100 / (100 + r)), negative ones too. */
    private static double resist(double through) {
        return through <= 1 ? 100 / Math.max(1e-9, through) - 100 : 100 - 100 / Math.max(1e-9, 2 - through);
    }
}
