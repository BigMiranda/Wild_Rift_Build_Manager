package com.ornnplanner.engine;

import com.ornnplanner.engine.Model.ItemDef;

/**
 * The shop's rules on <i>when</i> an item can be bought ({@code seed/regras_loja.yml}), as one check shared by
 * everything that assembles builds: the planner's timeline, the item candidates of the Effectiveness reports and the
 * shop screen (through {@code /api/meta}). No item is named: a rule reads the item's shop section and tabs.
 */
public final class ShopRuleCheck {

    /** No time rule (hypothetical inventories, older tests). */
    public static final ShopRuleCheck NONE = new ShopRuleCheck(0);

    /** Game minute from which upgraded boots (tier 3, shop section "aprimorado") can be bought; 0 = any time. */
    public final double upgradedBootsMinute;

    public ShopRuleCheck(double upgradedBootsMinute) {
        this.upgradedBootsMinute = Math.max(0, upgradedBootsMinute);
    }

    /** Upgraded boots: boots in the shop's upgraded section. */
    public static boolean upgradedBoots(ItemDef item) {
        return item != null && item.boots() && "aprimorado".equals(item.section);
    }

    /** Earliest game minute the shop sells {@code item} (0 = from the start). */
    public double earliestMinute(ItemDef item) {
        return upgradedBoots(item) ? upgradedBootsMinute : 0;
    }

    /** Whether {@code item} can be bought at {@code minute}. */
    public boolean allowedAt(ItemDef item, double minute) {
        return minute + 1e-9 >= earliestMinute(item);
    }

    /** The rule that holds {@code item} back, in the shop's words (pt-BR), or null when there is none. */
    public String rule(ItemDef item) {
        double m = earliestMinute(item);
        return m > 0 ? "botas aprimoradas só a partir dos " + mmss(m) : null;
    }

    static String mmss(double minute) {
        long sec = Math.round(minute * 60);
        return (sec / 60) + ":" + String.format("%02d", sec % 60);
    }
}
