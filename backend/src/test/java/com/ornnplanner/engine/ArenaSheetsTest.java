package com.ornnplanner.engine;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel.Action;
import com.ornnplanner.arena.ArenaModel.Frame;
import com.ornnplanner.arena.ArenaModel.Result;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mechanics the game's text does not tell, as fields of the sheets: displacement immunity, Ornn's attack on Brittle. */
class ArenaSheetsTest {

    /** A champion whose 1 is a long, slow dash (2s) with the given immunity fields. */
    private static Map<String, Object> dasher(Map<String, Object> immunity) {
        Map<String, Object> dash = new LinkedHashMap<>(Map.of("nome", "Avanço longo", "forma", "avanco", "recarga", List.of(30),
                "alcance", 1000, "velocidade", 500, "raio", 50, "sem_dano", true));
        dash.putAll(immunity);
        return Map.of("raio", 80, "alcance", 175, "habilidades", Map.of("1", dash));
    }

    /** An enemy with a targeted 3s stun (1) and a long pull (2), cast at once. */
    private static Map<String, Object> controller() {
        return Map.of("raio", 80, "alcance", 175, "habilidades", Map.of(
                "1", Map.of("nome", "Atordoar", "forma", "alvo", "alcance", 3000, "recarga", List.of(60),
                        "dano", Map.of("tipo", "magico", "base", 1), "efeitos", Map.of("atordoa", 3.0)),
                "2", Map.of("nome", "Puxar", "forma", "alvo", "alcance", 3000, "recarga", List.of(60),
                        "dano", Map.of("tipo", "magico", "base", 1), "efeitos", Map.of("puxa", 300))));
    }

    private static Result fight(Map<String, Object> dasherKit, String enemySlot) {
        Arena.Input a = ArenaScenarios.champion("Ornn", 0, 1000, 0, 400000, 1, 90);
        Arena.Input b = ArenaScenarios.champion("Ornn", 1, 4000, 0, 400000, 1, 90);
        a.kit = dasherKit;
        b.kit = controller();
        a.autoAbilities = false;
        b.autoAbilities = false;
        List<Action> orders = new ArrayList<>(List.of(ArenaScenarios.order(0, 0, "ability", "1", 1),
                ArenaScenarios.order(1, 0.5, "ability", enemySlot, 0), ArenaScenarios.order(0, 0, "stop", null, null),
                ArenaScenarios.order(1, 0, "stop", null, null)));
        return new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).run(List.of(a, b), orders, 6);
    }

    private static String state(Result r, double t) {
        for (Frame f : r.frames) {
            if (Math.abs(f.t - t) < 0.051) {
                return f.units.get(0).state;
            }
        }
        return null;
    }

    private static double x(Result r, double t) {
        for (Frame f : r.frames) {
            if (Math.abs(f.t - t) < 0.051) {
                return f.units.get(0).x;
            }
        }
        return Double.NaN;
    }

    @Test
    void aStunDuringAnImmuneDashLandsAtItsEnd() {
        Result r = fight(dasher(Map.of("imune_deslocamento", true)), "1");   // continua_depois is the default
        assertEquals("dash", state(r, 1.0), "the stun does not stop the dash");
        assertEquals(2000, x(r, 2.3), 1, "the dash went its whole way");
        // the stun (3s from 0.5) lands at the end of the dash (2.0) with what is left: until 3.5
        assertEquals("stun", state(r, 3.0));
        assertTrue(!"stun".equals(state(r, 3.7)), state(r, 3.7));
        assertEquals(1, r.fighters.get(0).events.getOrDefault("atordoamento_adiado", 0.0), 1e-9);
    }

    @Test
    void interrompeStopsTheDashAsWithoutImmunity() {
        Result r = fight(dasher(Map.of("imune_deslocamento", true, "cc_durante_avanco", "interrompe")), "1");
        assertEquals("stun", state(r, 1.0));
        assertTrue(x(r, 3.0) < 1400, "stopped about where it was stunned: " + x(r, 3.0));
        Result plain = fight(dasher(Map.of()), "1");
        assertEquals(x(plain, 3.0), x(r, 3.0), 1e-6);
    }

    @Test
    void aPullDuringAnImmuneDashIsVoid() {
        Result immune = fight(dasher(Map.of("imune_deslocamento", true)), "2");
        assertEquals(1, immune.fighters.get(0).events.getOrDefault("deslocamento_anulado:puxao", 0.0), 1e-9);
        assertEquals(2000, x(immune, 2.3), 1);
        Result plain = fight(dasher(Map.of()), "2");
        assertTrue(plain.fighters.get(0).events.getOrDefault("deslocamento_anulado:puxao", 0.0) == 0);
        assertTrue(Math.abs(x(plain, 2.3) - 2000) > 100, "pulled out of the dash (to the puller): " + x(plain, 2.3));
    }

    /** The real sheet: Ornn's headbutt (the R's recast) is immune to displacement while it lasts. */
    @Test
    void ornnsHeadbuttVoidsAPullTimedOnIt() {
        int voided = 0;
        int tries = 0;
        for (double t = 1.3; t <= 3.0; t += 0.02) {
            Arena.Input ornn = ArenaScenarios.champion("Ornn", 0, 1000, 0, 400000, 100, 90);
            Arena.Input darius = ArenaScenarios.champion("Darius", 1, 1350, 0, 400000, 100, 90);
            ornn.autoAbilities = false;
            darius.autoAbilities = false;
            List<Action> orders = new ArrayList<>(List.of(ArenaScenarios.order(0, 0, "ability", "R", 1),
                    ArenaScenarios.order(0, 1.3, "recast", "R", null), ArenaScenarios.order(1, t, "ability", "3", 0),
                    ArenaScenarios.order(0, 0, "stop", null, null), ArenaScenarios.order(1, 0, "stop", null, null)));
            Result r = new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).withoutFrames().run(List.of(ornn, darius), orders, 6);
            tries++;
            voided += r.fighters.get(0).events.getOrDefault("deslocamento_anulado:puxao", 0.0) > 0 ? 1 : 0;
        }
        assertTrue(voided > 0 && voided < tries, "some pulls land in the headbutt, most do not: " + voided + "/" + tries);
    }

    @Test
    void ornnsAttackOnBrittleCanBeAKnockUp() {
        // W (Brittle on the last hit) then attacks: by default each attack pushes; with arremessa it knocks up
        Result push = brittle(Map.of("efeito", "empurra", "empurra", 150));
        Result up = brittle(Map.of("efeito", "arremessa", "duracao", 0.4));
        assertTrue(push.fighters.get(0).events.getOrDefault("cc_aplicado:empurrao", 0.0) >= 1, push.fighters.get(0).events.toString());
        assertEquals(0, up.fighters.get(0).events.getOrDefault("cc_aplicado:empurrao", 0.0), 1e-9);
        assertTrue(up.fighters.get(0).events.getOrDefault("cc_aplicado:arremesso", 0.0) >= 1, up.fighters.get(0).events.toString());
    }

    @SuppressWarnings("unchecked")
    private static Result brittle(Map<String, Object> attacks) {
        Arena.Input ornn = ArenaScenarios.champion("Ornn", 0, 1000, 0, 400000, 100, 90);
        Arena.Input dummy = ArenaScenarios.champion("Darius", 1, 1300, 0, 400000, 1, 90);
        Map<String, Object> kit = new LinkedHashMap<>(ornn.kit);
        Map<String, Object> fr = new LinkedHashMap<>((Map<String, Object>) kit.get("fragil"));
        fr.put("ataques", attacks);
        kit.put("fragil", fr);
        ornn.kit = kit;
        ornn.autoAbilities = false;
        dummy.autoAbilities = false;
        List<Action> orders = new ArrayList<>(List.of(ArenaScenarios.order(0, 0, "ability", "2", 1),
                ArenaScenarios.order(1, 0, "stop", null, null)));
        return new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).run(List.of(ornn, dummy), orders, 4);
    }
}
