package com.ornnplanner.arena.ai;

import com.ornnplanner.arena.ArenaModel.Condition;
import com.ornnplanner.arena.ArenaModel.Guideline;

import java.util.ArrayList;
import java.util.List;

/**
 * A learned policy with the user's guidelines on top (the help level "com dicas do usuário"): the guidelines whose
 * conditions hold forbid actions (masked), then force one (chosen when valid), then add or take points from the
 * scores (prefer / avoid). The decision is tagged with the guideline followed, so training can reward it. Stateless.
 */
public final class GuidedPolicy implements Policy {

    private final NeuralPolicy inner;
    private final List<Guideline> rules;

    public GuidedPolicy(NeuralPolicy inner, List<Guideline> rules) {
        this.inner = inner;
        this.rules = rules == null ? List.of() : rules;
    }

    public NeuralPolicy inner() {
        return inner;
    }

    @Override
    public double interval() {
        return inner.interval();
    }

    @Override
    public Decision decide(Observation obs, ActionMask mask) {
        if (rules.isEmpty()) {
            return inner.decide(obs, mask);
        }
        boolean[] banned = new boolean[ActionSpace.SIZE];
        double[] extra = new double[ActionSpace.SIZE];
        List<Guideline> on = new ArrayList<>();
        for (Guideline g : rules) {
            if (Facts.all(obs, g.when)) {
                on.add(g);
            }
        }
        for (Guideline g : on) {
            int a = action(obs, g.action);
            if (a >= 0 && "proibir".equals(g.effect)) {
                banned[a] = true;
            }
        }
        ActionMask allowed = a -> !banned[a] && mask.valid(a);
        for (Guideline g : on) {
            int a = action(obs, g.action);
            if (a >= 0 && "obrigar".equals(g.effect) && allowed.valid(a)) {
                return new Decision(a, null, g.id);
            }
        }
        for (Guideline g : on) {
            int a = action(obs, g.action);
            if (a < 0) {
                continue;
            }
            if ("preferir".equals(g.effect)) {
                extra[a] += g.strength;
            } else if ("evitar".equals(g.effect)) {
                extra[a] -= g.strength;
            }
        }
        Decision d = inner.decide(obs, allowed, extra);
        String tag = null;
        for (Guideline g : on) {
            int a = action(obs, g.action);
            boolean followed = "preferir".equals(g.effect) ? d.action == a
                    : ("evitar".equals(g.effect) || "proibir".equals(g.effect)) && a >= 0 && d.action != a && mask.valid(a);
            if (followed) {
                tag = g.id;
                break;
            }
        }
        return new Decision(d.action, d.scores, tag);
    }

    /** The action index of a guideline's action name on the current target (-1 = unknown or not available). */
    public static int action(Observation obs, String name) {
        if (name == null) {
            return -1;
        }
        int i = name.indexOf(':');
        String kind = i < 0 ? name : name.substring(0, i);
        String arg = i < 0 ? null : name.substring(i + 1);
        switch (kind) {
            case "habilidade":
                return ActionSpace.slotIndex(arg) < 0 ? -1 : ActionSpace.cast(ActionSpace.slotIndex(arg), 0);
            case "reconjurar":
                return ActionSpace.slotIndex(arg) < 0 ? -1 : ActionSpace.recast(ActionSpace.slotIndex(arg));
            case "atacar":
                return ActionSpace.attack(0);
            case "kite":
                return ActionSpace.kite(0);
            case "recuar":
                return ActionSpace.RETREAT;
            case "desviar":
                return ActionSpace.DODGE;
            case "flash_fuga":
                return ActionSpace.FLASH_AWAY;
            case "parado":
                return ActionSpace.IDLE;
            case "combo": {
                try {
                    int c = Integer.parseInt(arg) - 1;
                    return c >= 0 && c < ActionSpace.COMBOS ? ActionSpace.combo(c) : -1;
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
            case "ativo": {
                String[] names = obs.self().activeName;
                for (int k = 0; k < names.length; k++) {
                    if (arg != null && arg.equals(names[k])) {
                        return ActionSpace.active(k);
                    }
                }
                return -1;
            }
            default:
                return -1;
        }
    }

    /**
     * The one preset, generic: do not overwrite a mark. For each ability: avoid it when it applies a mark, does not
     * consume one, and the side's mark on the target has more than 0.5s left.
     */
    public static List<Guideline> dontOverwriteMarks() {
        List<Guideline> out = new ArrayList<>();
        for (String s : ActionSpace.SLOTS) {
            Guideline g = new Guideline();
            g.id = "nao_sobrescrever_marca:" + s;
            g.effect = "evitar";
            g.action = "habilidade:" + s;
            g.strength = 1;
            g.when = new ArrayList<>(List.of(new Condition("aplica_marca:" + s, "==", 1), new Condition("consome_marca:" + s, "==", 0),
                    new Condition("alvo.marca_restante", ">", 0.5)));
            g.note = "não sobrescrever marca";
            out.add(g);
        }
        return out;
    }
}
