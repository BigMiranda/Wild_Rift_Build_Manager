package com.ornnplanner.arena.fight;

/**
 * What one source (item, rune, ability, spell) of a fighter did in the fight: damage dealt, damage added by resistance
 * reduction and by amplification, damage prevented, enemy shields and heals cut, own shields absorbed / lost, heals
 * done / lost.
 */
public class Ledger {
    public int fighter;
    public String source;
    public double damage;
    public double extraShred;
    public double extraAmp;
    public double prevented;
    public double shieldCut;
    public double healCut;
    public double shieldAbsorbed;
    public double shieldLost;
    public double healed;
    public double healLost;
}
