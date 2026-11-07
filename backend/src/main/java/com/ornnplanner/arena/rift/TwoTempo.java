package com.ornnplanner.arena.rift;

import java.util.EnumMap;
import java.util.Map;

/**
 * Playing in two tempos (Rodada 10, Parte 2): after a trade, is it worth staying and trading on, or stepping out to
 * recover (a Honeyfruit, the base on foot or by Teleport, the shop where it stands) and coming back? Every option is
 * priced in gold over the same horizon: the lane's gold and experience it keeps or loses while away (with the experience
 * the enemy denies), the plates at risk, the health it gets back, the item it buys, and the risk of dying if it stays,
 * from the forecast of the trade. Pure: the match fills a {@link Situation}, the weights are {@code macro_<patch>.yml}
 * ({@code dois_tempos}).
 */
public final class TwoTempo {

    public enum Option { STAY, HONEYFRUIT, RECALL_WALK, RECALL_TELEPORT, SHOP_HERE }

    /** What the champion sees now. Health in shares of the maximum; speeds in units per second; gold in gold. */
    public static final class Situation {
        public double hp = 1;
        public double maxHp = 1000;
        /** Share of the maximum health it loses per second if it stays and the trade goes on (the forecast). */
        public double dangerPerS;
        /** Share it regains per second on its own, out of the trade. */
        public double regenPerS;
        /** Gold and experience per second the lane gives it while it stands there. */
        public double laneGoldPerS;
        public double laneXpPerS;
        /** Plates on its front tower and how many plates per second the enemy takes while it is away. */
        public int plates;
        public double platesPerS;
        /** Gold the enemy takes off it when it dies (kill and the lane lost while it waits to come back). */
        public double deathCost = 600;
        /** Distance to the nearest Honeyfruit up (-1: none) and what its fruits heal (share of the maximum). */
        public double fruitDistance = -1;
        public double fruitHeal;
        public double baseDistance = 6000;
        public double moveSpeed = 350;
        public double recallSeconds = 8;
        public boolean teleportReady;
        public double teleportSeconds = 3.5;
        /** It may buy where it stands (a field of its sheet, or an ally that sells to it). */
        public boolean shopsHere;
        /** Gold of the items the gold in hand pays now, and what they are worth (the quick comparison's gain). */
        public double itemGold;
        public double itemGain;
    }

    /** The pick and what each option is worth. */
    public static final class Choice {
        public Option option;
        public final Map<Option, Double> values = new EnumMap<>(Option.class);
        /** Of the pick: seconds away from the lane, health it gets back (share), minions it lets go. */
        public double away;
        public double hpGain;
        public double minionsLost;
    }

    private final Map<String, Object> w;

    public TwoTempo(Map<String, Object> weights) {
        this.w = weights == null ? Map.of() : weights;
    }

    private double num(String key, double def) {
        return RiftRules.value(w.get(key), def);
    }

    /** Share of a death over {@code seconds} at this health: none while the health outlasts the trade, all when it runs out. */
    double deathChance(double hp, double danger, double seconds) {
        double loss = danger * seconds;
        if (loss <= 1e-9) {
            return 0;
        }
        return Math.max(0, Math.min(1, (loss - hp * num("folga_vida", 0.8)) / loss));
    }

    public Choice choose(Situation s) {
        double horizon = num("horizonte_s", 40);
        double xpGold = num("ouro_por_xp", 0.6);
        double income = s.laneGoldPerS + s.laneXpPerS * xpGold * (1 + num("negacao_xp", 0.5));
        double plateGold = num("ouro_por_placa", 150) * num("peso_placas", 1);
        double hpGold = num("ouro_por_vida", 0.1) * s.maxHp;   // what a full bar is worth, beyond the death risk
        double itemWeight = num("peso_item", 0.5);
        Choice c = new Choice();
        Map<Option, double[]> detail = new EnumMap<>(Option.class);   // {away, hpGain}

        // stay and trade on
        double stayDeath = deathChance(s.hp, Math.max(0, s.dangerPerS - s.regenPerS), horizon);
        double stay = income * horizon * (1 - stayDeath) - stayDeath * s.deathCost
                - Math.max(0, s.dangerPerS - s.regenPerS) * horizon * hpGold * (1 - stayDeath);
        c.values.put(Option.STAY, stay);
        detail.put(Option.STAY, new double[] {0, 0});

        // a way out: away for some seconds, back with health (and maybe items); the rest of the horizon in lane
        java.util.function.BiFunction<Double, Double, Double> out = (away, hpAfter) -> {
            double left = Math.max(0, horizon - away);
            double death = deathChance(hpAfter, Math.max(0, s.dangerPerS - s.regenPerS), left);
            double plates = Math.min(s.plates, s.platesPerS * away) * plateGold;
            return income * left * (1 - death) - death * s.deathCost - plates
                    - Math.max(0, s.dangerPerS - s.regenPerS) * left * hpGold * (1 - death) + (hpAfter - s.hp) * hpGold;
        };
        if (s.fruitDistance >= 0 && s.fruitHeal > 0) {
            double away = 2 * s.fruitDistance / s.moveSpeed + num("comer_s", 1);
            double hpAfter = Math.min(1, s.hp + s.fruitHeal + s.regenPerS * away);
            c.values.put(Option.HONEYFRUIT, out.apply(away, hpAfter));
            detail.put(Option.HONEYFRUIT, new double[] {away, hpAfter - s.hp});
        }
        double walkBack = s.baseDistance / s.moveSpeed;
        double buy = s.itemGain * itemWeight;
        {
            double away = s.recallSeconds + walkBack + num("na_base_s", 2);
            c.values.put(Option.RECALL_WALK, out.apply(away, 1.0) + buy);
            detail.put(Option.RECALL_WALK, new double[] {away, 1 - s.hp});
        }
        if (s.teleportReady) {
            double away = s.recallSeconds + s.teleportSeconds + num("na_base_s", 2);
            c.values.put(Option.RECALL_TELEPORT, out.apply(away, 1.0) + buy - num("custo_teleporte", 120));
            detail.put(Option.RECALL_TELEPORT, new double[] {away, 1 - s.hp});
        }
        if (s.shopsHere && s.itemGold > 0) {
            c.values.put(Option.SHOP_HERE, stay + buy);
            detail.put(Option.SHOP_HERE, new double[] {0, 0});
        }
        // staying wins ties and small edges: no going back and forth for a few gold
        Option best = Option.STAY;
        double margin = num("margem", 40);
        for (Map.Entry<Option, Double> e : c.values.entrySet()) {
            if (e.getKey() != Option.STAY && e.getValue() > c.values.get(best) + (best == Option.STAY ? margin : 0)) {
                best = e.getKey();
            }
        }
        c.option = best;
        c.away = detail.get(best)[0];
        c.hpGain = detail.get(best)[1];
        double minionsPerS = s.laneGoldPerS / Math.max(1, num("ouro_medio_tropa", 50));
        c.minionsLost = minionsPerS * c.away;
        return c;
    }
}
