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
 * A build seen under chosen effects ("visão sob efeitos"): the defender's stats in practice when hit by an item's or a
 * champion's effects, each at the value the screen sets (the Ocean Trident's shield cut grows with ability power, the
 * Serpent's Fang's with bonus attack damage). Everything comes from the fight's own rules: the stats the defender has
 * as a fight starts (a zero-second run of the arena) and the share of a hit that gets through ({@link Arena#through},
 * the function every hit uses); plus the defender's own damage reduction against attacks.
 */
public final class SeenBy {

    /** Kinds of applied effect. Shares are fractions (0.3 = 30%); flat penetration is in points. */
    public static final String ARMOR_SHRED = "reduz_armadura";
    public static final String MAGIC_SHRED = "reduz_rm";
    public static final String ARMOR_PEN_PCT = "pen_armadura_pct";
    public static final String ARMOR_PEN = "pen_armadura";
    public static final String MAGIC_PEN_PCT = "pen_magica_pct";
    public static final String MAGIC_PEN = "pen_magica";
    public static final String SHIELD_CUT = "corta_escudo";
    public static final String WOUNDS = "feridas";
    public static final List<String> KINDS = List.of(ARMOR_SHRED, MAGIC_SHRED, ARMOR_PEN_PCT, ARMOR_PEN, MAGIC_PEN_PCT,
            MAGIC_PEN, SHIELD_CUT, WOUNDS);

    /** One effect applied to the defender: where it comes from, its kind and its value. */
    public static class Applied {
        public String source;
        public String kind;
        public double value;

        public Applied() {
        }

        public Applied(String source, String kind, double value) {
            this.source = source;
            this.kind = kind;
            this.value = value;
        }
    }

    public double health;
    public double armor;
    public double magicResist;
    /** Armor and magic resist that count under the effects (after reduction, % and flat penetration). */
    public double effectiveArmor;
    public double effectiveMagicResist;
    /** Share of physical / magic damage that gets through (after resistances). */
    public double physicalThrough;
    public double magicThrough;
    /** Reduction of the defender against attacks (items), 0..1. */
    public double attackReduction;
    /** Resistance reduction applied (the strongest of each resistance). */
    public double armorShred;
    public double magicShred;
    /** Shields as the fight starts, and after the shield cut. */
    public double shields;
    public double shieldsAfterCut;
    public double shieldCut;
    /** Share of the defender's healing that lands (Grievous Wounds). */
    public double healingKept;
    /** The physical part of the damage the mixed effective health assumes (the rest is magic). */
    public double physicalShare;
    /** Effective health: against physical damage, physical attacks only, magic damage, and the mix. */
    public double effectiveHpPhysical;
    public double effectiveHpAttacks;
    public double effectiveHpMagic;
    public double effectiveHpMixed;
    /** Why the resistances drop, in words ("Cutelo Negro −30%", "Lembranças do Lorde Dominik 35% de penetração"). */
    public List<String> armorReasons = new ArrayList<>();
    public List<String> magicReasons = new ArrayList<>();
    /** The sources behind the shield cut and the Grievous Wounds (null = none). */
    public String shieldCutBy;
    public String healCutBy;
    public Double minute;

    private SeenBy() {
    }

    private static String pct(double v) {
        long tenths = Math.round(v * 1000);
        return tenths % 10 == 0 ? tenths / 10 + "%" : tenths / 10 + "," + tenths % 10 + "%";
    }

    /** What the defender brings as a fight starts, before any effect: it depends on the build and minute only. */
    public static class Base {
        public double health;
        public double armor;
        public double magicResist;
        /** Shields as the fight starts: against any damage, physical only, magic only. */
        public double shieldAny;
        public double shieldPhysical;
        public double shieldMagic;
        /** Reduction against attacks (the defender's items), 0..1. */
        public double attackReduction;
        /** The build's items at the minute (name -> id), for the counterfactual of each one. */
        public Map<String, Long> items = new java.util.LinkedHashMap<>();
    }

    /**
     * The defender's starting stats: a zero-second run of the arena. {@code opponent} only stands across the defender
     * (a ragdoll); its own stats count for nothing.
     */
    public static Base base(CombatCatalog catalog, Map<String, Object> map, Arena.Input defender, Arena.Input opponent) {
        Arena.Input d = defender.copy();
        Arena.Input o = opponent.copy();
        d.f = defender.f;
        o.f = opponent.f;
        d.x = 1000;
        o.x = 1200;
        int dTeam = d.f.team;
        int oTeam = o.f.team;
        d.f.team = 0;
        o.f.team = 1;
        Result r;
        try {
            r = new Arena(catalog, map).withoutFrames().run(List.of(d, o), List.of(), 0);
        } finally {
            d.f.team = dTeam;
            o.f.team = oTeam;
        }
        FighterStats ds = r.fighters.get(0);
        Base b = new Base();
        b.health = ds.startStats.getOrDefault(Stats.MAX_HEALTH, 0.0);
        b.armor = ds.startStats.getOrDefault(Stats.ARMOR, 0.0);
        b.magicResist = ds.startStats.getOrDefault(Stats.MAGIC_RESIST, 0.0);
        b.shieldAny = ds.startShields.getOrDefault("any", 0.0);
        b.shieldPhysical = ds.startShields.getOrDefault("fisico", 0.0);
        b.shieldMagic = ds.startShields.getOrDefault("magico", 0.0);
        for (ItemEffect e : defender.f.effects) {
            if ("reduz_dano".equals(e.kind()) && "ataque".equals(String.valueOf(e.p.get("contra")))) {
                b.attackReduction = Math.min(0.9, b.attackReduction + Arena.num(e.p.get("pct"), 0));
            }
        }
        b.items.putAll(defender.f.items);
        return b;
    }

    /** The defender under the effects (a zero-second run, then {@link #apply}). */
    public static SeenBy under(CombatCatalog catalog, Map<String, Object> map, Arena.Input defender, Arena.Input opponent,
                               List<Applied> effects) {
        return apply(base(catalog, map, defender, opponent), effects, 0.5);
    }

    /**
     * The defender's starting stats under the effects: pure arithmetic, by the fight's mitigation rule.
     * {@code physicalShare} is the physical part of the damage for the mixed effective health (the rest is magic).
     */
    public static SeenBy apply(Base b, List<Applied> effects, double physicalShare) {
        SeenBy s = new SeenBy();
        s.physicalShare = Math.max(0, Math.min(1, physicalShare));
        s.health = b.health;
        s.armor = b.armor;
        s.magicResist = b.magicResist;
        s.attackReduction = b.attackReduction;
        double pctArmorPen = 0;
        double flatArmorPen = 0;
        double pctMagicPen = 0;
        double flatMagicPen = 0;
        double cut = 0;
        double gw = 0;
        String armorBy = null;
        String magicBy = null;
        List<String> armorPen = new ArrayList<>();
        List<String> magicPen = new ArrayList<>();
        for (Applied e : effects == null ? List.<Applied>of() : effects) {
            double v = Math.max(0, e.value);
            if (v <= 0 || e.kind == null) {
                continue;
            }
            switch (e.kind) {
                case ARMOR_SHRED:
                    if (Math.min(1, v) > s.armorShred) {
                        s.armorShred = Math.min(1, v);
                        armorBy = e.source;
                    }
                    break;
                case MAGIC_SHRED:
                    if (Math.min(1, v) > s.magicShred) {
                        s.magicShred = Math.min(1, v);
                        magicBy = e.source;
                    }
                    break;
                case ARMOR_PEN_PCT:
                    pctArmorPen += v;
                    armorPen.add(e.source + " " + pct(v) + " de penetração");
                    break;
                case ARMOR_PEN:
                    flatArmorPen += v;
                    armorPen.add(e.source + " " + Math.round(v) + " de penetração");
                    break;
                case MAGIC_PEN_PCT:
                    pctMagicPen += v;
                    magicPen.add(e.source + " " + pct(v) + " de penetração");
                    break;
                case MAGIC_PEN:
                    flatMagicPen += v;
                    magicPen.add(e.source + " " + Math.round(v) + " de penetração");
                    break;
                case SHIELD_CUT:
                    if (Math.min(1, v) > cut) {
                        cut = Math.min(1, v);
                        s.shieldCutBy = e.source;
                    }
                    break;
                case WOUNDS:
                    if (Math.min(1, v) > gw) {
                        gw = Math.min(1, v);
                        s.healCutBy = e.source;
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
        s.armorReasons.addAll(armorPen);
        s.magicReasons.addAll(magicPen);
        s.physicalThrough = Arena.through(s.armor, s.armorShred, Math.min(1, pctArmorPen), flatArmorPen);
        s.magicThrough = Arena.through(s.magicResist, s.magicShred, Math.min(1, pctMagicPen), flatMagicPen);
        s.effectiveArmor = resist(s.physicalThrough);
        s.effectiveMagicResist = resist(s.magicThrough);
        double any = b.shieldAny;
        double phys = b.shieldPhysical;
        double mag = b.shieldMagic;
        s.shields = any + phys + mag;
        s.shieldCut = cut;
        s.shieldsAfterCut = s.shields * (1 - cut);
        s.healingKept = 1 - gw;
        double k = 1 - cut;
        s.effectiveHpPhysical = (s.health + (any + phys) * k) / Math.max(1e-9, s.physicalThrough);
        s.effectiveHpAttacks = (s.health + (any + phys) * k) / Math.max(1e-9, s.physicalThrough * (1 - s.attackReduction));
        s.effectiveHpMagic = (s.health + (any + mag) * k) / Math.max(1e-9, s.magicThrough);
        double mix = s.physicalShare * s.physicalThrough + (1 - s.physicalShare) * s.magicThrough;
        s.effectiveHpMixed = (s.health + s.shields * k) / Math.max(1e-9, mix);
        return s;
    }

    /** The resistance that lets the given share through (100 / (100 + r)), negative ones too. */
    private static double resist(double through) {
        return through <= 1 ? 100 / Math.max(1e-9, through) - 100 : 100 - 100 / Math.max(1e-9, 2 - through);
    }
}
