package com.ornnplanner.engine;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel.Result;
import com.ornnplanner.arena.SeenBy;
import com.ornnplanner.arena.ai.ActionSpace;
import com.ornnplanner.arena.ai.Policy;
import com.ornnplanner.arena.fight.ItemEffect;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Visto por": the defender's stats in practice for an attacker, by the fight's own rules. */
class SeenByTest {

    private static Arena.Input defender() {
        Arena.Input d = ArenaScenarios.champion("Darius", 1, 1250, 0, 3000, 100, 120);
        d.autoAbilities = false;
        d.policy = (o, m) -> Policy.Decision.of(ActionSpace.IDLE);
        return d;
    }

    private static Arena.Input attacker() {
        Arena.Input a = ArenaScenarios.champion("Ornn", 0, 1000, 0, 400000, 180, 90);
        a.f.total.put("% Armor Penetration", 20.0);
        a.f.total.put("Armor Penetration", 10.0);
        a.autoAbilities = false;
        a.policy = (o, m) -> Policy.Decision.of(m.valid(ActionSpace.attack(0)) ? ActionSpace.attack(0) : ActionSpace.IDLE);
        return a;
    }

    /** 🎯 The physical effective health matches the raw damage of the attacks that killed the defender in a fight. */
    @Test
    void physicalEffectiveHealthMatchesAFightOfAttacks() {
        SeenBy s = SeenBy.of(ArenaScenarios.CATALOG, ArenaScenarios.MAP, defender(), attacker(), List.of());
        Result r = new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).run(List.of(attacker(), defender()), List.of(), 120);
        assertTrue(r.fighters.get(1).deathTime != null, "the attacks kill");
        double attacks = r.fighters.get(0).damageBySource.get("Ataque") / (180 * s.physicalThrough);
        double raw = Math.round(attacks) * 180;   // the raw damage of the attacks that landed
        System.out.printf("effective health (physical attacks) %.1f; %d attacks of 180 = %.0f raw%n", s.effectiveHpAttacks,
                Math.round(attacks), raw);
        assertTrue(raw >= s.effectiveHpAttacks - 1e-6 && raw - 180 < s.effectiveHpAttacks,
                "the killing attack is the one that passes the effective health");
        // armor 120 after 20% and 10 flat: 120 * 0.8 - 10 = 86
        assertEquals(86, s.effectiveArmor, 1e-6);
    }

    @Test
    void anAllyRagdollAtItsMostShredsAndCutsHealing() {
        List<ItemEffect> ally = List.of(
                new ItemEffect("Cutelo Negro", Map.of("tipo", "reduz_resistencia", "resistencia", "armadura", "por_acerto", 0.06,
                        "max", 0.30, "gatilho", "fisico", "duracao", 6)),
                new ItemEffect("Lembrete Mortal", Map.of("tipo", "feridas", "pct", 0.5, "gatilho", "fisico")));
        SeenBy plain = SeenBy.of(ArenaScenarios.CATALOG, ArenaScenarios.MAP, defender(), attacker(), List.of());
        SeenBy with = SeenBy.of(ArenaScenarios.CATALOG, ArenaScenarios.MAP, defender(), attacker(), ally);
        assertEquals(0.30, with.armorShred, 1e-9);
        assertEquals(120 * 0.7 * 0.8 - 10, with.effectiveArmor, 1e-6);
        assertTrue(with.effectiveHpPhysical < plain.effectiveHpPhysical);
        assertEquals(0.5, with.healingKept, 1e-9);
        assertEquals(plain.effectiveHpMagic, with.effectiveHpMagic, 1e-6, "the magic side does not change");
    }

    /** Each value says from where to where and why: the shred of the ally's item and the attacker's penetration items. */
    @Test
    void theReasonsNameTheItems() {
        Arena.Input a = attacker();
        a.f.statSources.put("% Armor Penetration", new java.util.LinkedHashMap<>(Map.of("Lembranças do Lorde Dominik", 20.0)));
        a.f.statSources.put("Armor Penetration", new java.util.LinkedHashMap<>(Map.of("Lâmina Fantasma de Youmuu", 10.0)));
        List<ItemEffect> ally = List.of(new ItemEffect("Cutelo Negro", Map.of("tipo", "reduz_resistencia", "resistencia", "armadura",
                "por_acerto", 0.06, "max", 0.30, "gatilho", "fisico", "duracao", 6)),
                new ItemEffect("Lembrete Mortal", Map.of("tipo", "feridas", "pct", 0.5, "gatilho", "fisico")));
        SeenBy s = SeenBy.of(ArenaScenarios.CATALOG, ArenaScenarios.MAP, defender(), a, ally);
        assertEquals(List.of("Cutelo Negro (aliado) −30%", "Lembranças do Lorde Dominik 20% de penetração",
                "Lâmina Fantasma de Youmuu 10 de penetração"), s.armorReasons);
        assertTrue(s.magicReasons.isEmpty());
        assertEquals("Lembrete Mortal (aliado)", s.healCutBy);
    }
}
