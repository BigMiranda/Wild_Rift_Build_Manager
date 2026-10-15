package com.ornnplanner.arena.ai;

import com.ornnplanner.arena.ArenaModel.Condition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * The facts of an {@link Observation} by name, for conditions written as data (combos' start and abort, guidelines):
 * {@code alvo.controle_restante > 0.3}, {@code acerto:1 >= 0.6}, {@code pronto:R == 1}. Booleans are 1 / 0, times in
 * seconds, shares in 0..1, distances in map units. Generic: nothing names a champion; what differs comes from the sheets
 * through the predictions.
 */
public final class Facts {

    private Facts() {
    }

    private static final Map<String, java.util.function.Function<Observation, Double>> PLAIN = new LinkedHashMap<>();
    /** Facts of an ability slot: name:slot. */
    private static final Map<String, BiFunction<Observation, Integer, Double>> PER_SLOT = new LinkedHashMap<>();

    static {
        // the current target (enemy slot 0)
        PLAIN.put("alvo.presente", o -> b(o.enemy(0).present));
        PLAIN.put("alvo.distancia", o -> o.enemy(0).present ? o.enemy(0).distance : 1e9);
        PLAIN.put("alvo.vida_pct", o -> o.enemy(0).hp / Math.max(1, o.enemy(0).maxHp));
        PLAIN.put("alvo.escudo_pct", o -> o.enemy(0).shield / Math.max(1, o.enemy(0).maxHp));
        PLAIN.put("alvo.controlado", o -> b(o.enemy(0).crowdControlled));
        PLAIN.put("alvo.controle_restante", o -> o.enemy(0).controlLeft);
        PLAIN.put("alvo.no_ar_restante", o -> o.enemy(0).airLeft);
        PLAIN.put("alvo.lentidao_restante", o -> o.enemy(0).slowLeft);
        PLAIN.put("alvo.lentidao_pct", o -> o.enemy(0).slowPct);
        PLAIN.put("alvo.marca_restante", o -> o.enemy(0).markLeft);
        PLAIN.put("alvo.marcas_consumidas", o -> (double) o.enemy(0).marksConsumed);
        PLAIN.put("alvo.recurso_pct", o -> o.enemy(0).resourcePct);
        PLAIN.put("alvo.superaquecido_restante", o -> o.enemy(0).overheatLeft);
        PLAIN.put("alvo.avanco_pronto", o -> b(o.enemy(0).dashReady));
        PLAIN.put("alvo.tem_avanco_pronto", o -> b(o.enemy(0).dashReady));
        PLAIN.put("alvo.flash_pronto", o -> b(o.enemy(0).flashReady));
        PLAIN.put("alvo.purificar_pronto", o -> b(o.enemy(0).cleanseReady));
        PLAIN.put("alvo.alcance_ataque", o -> o.enemy(0).attackRange);
        PLAIN.put("alvo.me_alcanca", o -> b(o.enemy(0).reachesMe));
        PLAIN.put("alvo.no_alcance_ataque", o -> b(o.enemy(0).inAttackReach));
        // the deciding champion
        PLAIN.put("proprio.vida_pct", o -> o.self().hp / Math.max(1, o.self().maxHp));
        PLAIN.put("proprio.escudo_pct", o -> o.self().shield / Math.max(1, o.self().maxHp));
        PLAIN.put("proprio.recurso_pct", o -> o.self().resourceMax > 0 ? o.self().resource / o.self().resourceMax : 0);
        PLAIN.put("proprio.zona_recurso", o -> b(o.self().resourceZone));
        PLAIN.put("proprio.superaquecido_restante", o -> o.self().overheatLeft);
        PLAIN.put("proprio.controle_restante", o -> o.self().controlLeft);
        PLAIN.put("proprio.vida_perdida_2s", o -> o.self().recentLoss);
        PLAIN.put("proprio.ataque_pronto", o -> b(o.self().attackReady));
        PLAIN.put("proprio.em_avanco_imune", o -> b(o.self().immuneDash));
        PLAIN.put("proprio.reconjuracao_restante", o -> o.self().recastLeft);
        PLAIN.put("proprio.ameaca", o -> Math.min(1e9, o.self().threatTime));
        PLAIN.put("proprio.parede", o -> o.self().wallDistance);
        PLAIN.put("ataque.consome_marca", o -> b(o.self().attackConsumesMark));
        PLAIN.put("ataque.aplica_marca", o -> b(o.self().attackAppliesMark));
        PLAIN.put("tempo", Observation::time);
        PER_SLOT.put("pronto", (o, k) -> b(o.self().ready[k]));
        PER_SLOT.put("aprendida", (o, k) -> b(o.self().learned[k]));
        PER_SLOT.put("recarga", (o, k) -> o.self().cooldownLeft[k]);
        PER_SLOT.put("reconjuracao_aberta", (o, k) -> b(o.self().recastOpen[k]));
        PER_SLOT.put("no_alcance", (o, k) -> b(o.enemy(0).inAbilityRange[k]));
        PER_SLOT.put("acerto", (o, k) -> o.self().hitChance[k]);
        PER_SLOT.put("acerto_corpo", (o, k) -> o.self().bodyHit[k]);
        PER_SLOT.put("reconjuracao_acertaria", (o, k) -> o.self().recastHit[k]);
        PER_SLOT.put("tempo_ate_efeito", (o, k) -> o.self().timeToEffect[k]);
        PER_SLOT.put("encadeia", (o, k) -> b(o.self().chainsControl[k]));
        PER_SLOT.put("consome_marca", (o, k) -> b(o.self().consumesMark[k]));
        PER_SLOT.put("aplica_marca", (o, k) -> b(o.self().appliesMark[k]));
        PER_SLOT.put("imobiliza", (o, k) -> b(o.self().immobilizes[k]));
        PER_SLOT.put("de_mira", (o, k) -> b(o.self().aimed[k]));
    }

    /** Every fact name (per-slot ones with ":S"), for the screen's editor. */
    public static List<String> names() {
        List<String> out = new java.util.ArrayList<>(PLAIN.keySet());
        for (String k : PER_SLOT.keySet()) {
            out.add(k + ":S");
        }
        return out;
    }

    public static boolean known(String fact) {
        if (fact == null) {
            return false;
        }
        int i = fact.indexOf(':');
        return i < 0 ? PLAIN.containsKey(fact)
                : PER_SLOT.containsKey(fact.substring(0, i)) && ActionSpace.slotIndex(fact.substring(i + 1)) >= 0;
    }

    /** The fact's value now (null for an unknown name). Reads the predictions too. */
    public static Double value(Observation o, String fact) {
        if (fact == null) {
            return null;
        }
        o.predicted();
        int i = fact.indexOf(':');
        if (i < 0) {
            java.util.function.Function<Observation, Double> f = PLAIN.get(fact);
            return f == null ? null : f.apply(o);
        }
        BiFunction<Observation, Integer, Double> f = PER_SLOT.get(fact.substring(0, i));
        int k = ActionSpace.slotIndex(fact.substring(i + 1));
        return f == null || k < 0 ? null : f.apply(o, k);
    }

    /** The condition holds now (an unknown fact never holds). */
    public static boolean holds(Observation o, Condition c) {
        Double v = value(o, c.fact);
        if (v == null) {
            return false;
        }
        switch (c.op == null ? ">" : c.op) {
            case "<":
                return v < c.value - 1e-9;
            case "<=":
                return v <= c.value + 1e-9;
            case ">=":
                return v >= c.value - 1e-9;
            case "==":
                return Math.abs(v - c.value) <= 1e-9;
            case "!=":
                return Math.abs(v - c.value) > 1e-9;
            default:
                return v > c.value + 1e-9;
        }
    }

    /** All hold (an empty list holds). */
    public static boolean all(Observation o, List<Condition> cs) {
        if (cs != null) {
            for (Condition c : cs) {
                if (!holds(o, c)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The first that holds, or null. */
    public static Condition any(Observation o, List<Condition> cs) {
        if (cs != null) {
            for (Condition c : cs) {
                if (holds(o, c)) {
                    return c;
                }
            }
        }
        return null;
    }

    private static double b(boolean v) {
        return v ? 1 : 0;
    }
}
