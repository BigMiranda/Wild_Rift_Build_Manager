package com.ornnplanner.engine;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel.Result;
import com.ornnplanner.arena.EffectSources;
import com.ornnplanner.arena.SeenBy;
import com.ornnplanner.arena.SeenBy.Applied;
import com.ornnplanner.arena.ai.ActionSpace;
import com.ornnplanner.arena.ai.Policy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Visão sob efeitos": the defender's stats in practice under chosen effects, by the fight's own rules. */
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

    private static final List<Applied> PEN = List.of(new Applied("Último Sussurro", SeenBy.ARMOR_PEN_PCT, 0.20),
            new Applied("Punhal Serrilhado", SeenBy.ARMOR_PEN, 10));

    private static SeenBy under(List<Applied> fx) {
        return SeenBy.under(ArenaScenarios.CATALOG, ArenaScenarios.MAP, defender(), attacker(), fx);
    }

    /**
     * 🎯 Under the penetration of an attacker, the physical effective health matches the raw damage of that attacker's
     * attacks that killed the defender in a fight.
     */
    @Test
    void physicalEffectiveHealthMatchesAFightOfAttacks() {
        SeenBy s = under(PEN);
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
    void aShredAndWoundsAtTheirValues() {
        SeenBy plain = under(List.of());
        SeenBy with = under(List.of(new Applied("Cutelo Negro", SeenBy.ARMOR_SHRED, 0.30),
                new Applied("Lembrete Mortal", SeenBy.WOUNDS, 0.5)));
        assertEquals(120, plain.effectiveArmor, 1e-6, "nothing applied: the armor as it is");
        assertEquals(0.30, with.armorShred, 1e-9);
        assertEquals(120 * 0.7, with.effectiveArmor, 1e-6);
        assertTrue(with.effectiveHpPhysical < plain.effectiveHpPhysical);
        assertEquals(0.5, with.healingKept, 1e-9);
        assertEquals(plain.effectiveHpMagic, with.effectiveHpMagic, 1e-6, "the magic side does not change");
    }

    /** Each value says from where to where and why: the shred's source and each penetration source. */
    @Test
    void theReasonsNameTheSources() {
        SeenBy s = under(List.of(new Applied("Cutelo Negro", SeenBy.ARMOR_SHRED, 0.30),
                new Applied("Lembranças do Lorde Dominik", SeenBy.ARMOR_PEN_PCT, 0.35),
                new Applied("Ambessa: Execução Pública (R)", SeenBy.ARMOR_PEN_PCT, 0.30),
                new Applied("Lâmina Fantasma de Youmuu", SeenBy.ARMOR_PEN, 10),
                new Applied("Lembrete Mortal", SeenBy.WOUNDS, 0.5)));
        assertEquals(List.of("Cutelo Negro −30%", "Lembranças do Lorde Dominik 35% de penetração",
                "Ambessa: Execução Pública (R) 30% de penetração", "Lâmina Fantasma de Youmuu 10 de penetração"), s.armorReasons);
        assertTrue(s.magicReasons.isEmpty());
        assertEquals("Lembrete Mortal", s.healCutBy);
        // % penetrations add up, as the stat does in a fight: 120 * 0.7 * (1 - 0.65) - 10
        assertEquals(120 * 0.7 * 0.35 - 10, s.effectiveArmor, 1e-6);
    }

    /** The Ocean Trident's and the Serpent's Fang's shield cut: a base, a share per stat point and a cap, as in the file. */
    @Test
    void shieldCutsScaleWithTheirStat() {
        EffectSources.Option trident = EffectSources.combatOption(ArenaScenarios.CATALOG.effects("Tridente da Oceânide").get(0));
        assertEquals(SeenBy.SHIELD_CUT, trident.kind);
        assertEquals("PdH", trident.scale);
        assertEquals(0.40, trident.value, 1e-9, "by default, the base");
        assertEquals(0.60, Math.min(trident.max, trident.base + trident.per * 400), 1e-9, "400 AP reaches the cap");
        assertEquals(0.45, Math.min(trident.max, trident.base + trident.per * 100), 1e-9);
        EffectSources.Option fang = EffectSources.combatOption(ArenaScenarios.CATALOG.effects("Presa da Serpente").get(0));
        assertEquals("DdA adicional", fang.scale);
        assertEquals(0.50, Math.min(fang.max, fang.base + fang.per * 100), 1e-9);
        assertEquals("40% + 0,1% por DdA adicional, até 60%", fang.text);
        // the cut applies to the shields as the fight starts
        SeenBy s = under(List.of(new Applied("Tridente da Oceânide", SeenBy.SHIELD_CUT, 0.45)));
        assertEquals(0.45, s.shieldCut, 1e-9);
        assertEquals(s.shields * 0.55, s.shieldsAfterCut, 1e-6);
        assertEquals("Tridente da Oceânide", s.shieldCutBy);
    }

    @Test
    void itemsOnlyGiveTheEffectsThatChangeHowADefenderIsHit() {
        assertEquals(null, EffectSources.combatOption(Map.of("tipo", "amplifica", "pct", 0.05)));
        assertEquals(null, EffectSources.combatOption(Map.of("tipo", "feridas", "pct", 0.4, "ao_ser_atingido", true)));
        EffectSources.Option cleaver = EffectSources.combatOption(ArenaScenarios.CATALOG.effects("Cutelo Negro").get(0));
        assertEquals(SeenBy.ARMOR_SHRED, cleaver.kind);
        assertEquals(0.30, cleaver.value, 1e-9);
        assertEquals("6% por acerto, até 30%", cleaver.text);
    }
}
