package com.ornnplanner.engine;

import com.ornnplanner.arena.ArenaEvents;
import com.ornnplanner.arena.ArenaModel.FighterStats;
import com.ornnplanner.arena.ArenaModel.Result;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The structured events of a fight: marks, crowd control, ability hits and recasts, counted without changing it. */
class ArenaEventsTest {

    private static Result run(String name) {
        return ArenaScenarios.all().stream().filter(s -> s.name.equals(name)).findFirst().orElseThrow().run(false);
    }

    private static double ev(FighterStats f, String key) {
        return f.events.getOrDefault(key, 0.0);
    }

    @Test
    void ornnBreaksHisBrittleWithAttacksAndHoldsTheTarget() {
        Result r = run("duel Ornn x Darius");
        FighterStats ornn = r.fighters.get(0);
        FighterStats darius = r.fighters.get(1);
        assertTrue(ev(ornn, "marca_aplicada:Frágil") >= 1, ornn.events.toString());
        assertTrue(ev(ornn, "marca_consumida:Frágil:ataque") >= 1, ornn.events.toString());
        assertTrue(ev(ornn, "controle_causado") > 0.5);
        // the time Darius spent controlled is the control Ornn caused (he is the only one controlling him)
        assertEquals(ev(ornn, "controle_causado"), ev(darius, "tempo_controlado"), 1e-6);
        // the autopilot never recasts: the elemental's way back is lost
        assertEquals(1, ev(ornn, "reconjuracao_perdida:R:não tentou"), 1e-9);
        assertTrue(ev(ornn, "habilidade_acertou:R") >= 1);
        assertTrue(ornn.eventSummary.startsWith("Frágil: "), ornn.eventSummary);
    }

    @Test
    void theRecastBreaksTheBrittleOfTheWayOut() {
        Result r = run("orders + autopilot");
        FighterStats ornn = r.fighters.get(0);
        assertEquals(1, ev(ornn, "reconjuracao_acertou:R"), 1e-9, ornn.events.toString());
        assertTrue(ev(ornn, "marca_consumida:Frágil:R") >= 1, ornn.events.toString());
    }

    @Test
    void aBrittleThatDoesNotImmobilizeOverwritesTheOneOn() {
        // Ornn's W ends with a Brittle while the one of his R is still on, without breaking it (the game does so too)
        FighterStats ornn = run("duel Ornn x Rumble").fighters.get(0);
        assertTrue(ev(ornn, "marca_sobrescrita:Frágil") >= 1, ornn.events.toString());
        double applied = ev(ornn, "marca_aplicada:Frágil");
        double settled = ev(ornn, "marca_consumida:Frágil:ataque") + ev(ornn, "marca_consumida:Frágil:3")
                + ev(ornn, "marca_consumida:Frágil:R") + ev(ornn, "marca_consumida:Frágil:2")
                + ev(ornn, "marca_sobrescrita:Frágil") + ev(ornn, "marca_expirou:Frágil");
        assertTrue(settled <= applied, ornn.events.toString());
    }

    @Test
    void marksOfTheOtherSheetsAndChainedControl() {
        Result r = run("3v3");
        FighterStats vayne = r.fighters.get(2);
        FighterStats ksante = r.fighters.get(5);
        assertTrue(ev(vayne, "marca_consumida:Dardos de Prata:ataque") >= 1, vayne.events.toString());
        assertTrue(ev(ksante, "marca_consumida:Instinto Destemido:ataque") >= 1, ksante.events.toString());
        assertTrue(ev(ksante, "cc_encadeado") >= 1, ksante.events.toString());
        // a bare dash that cannot hit (K'Sante's Footwork) is not a cast that misses
        assertEquals(0, ev(ksante, "habilidade_errou:3"), 1e-9);
        // Darius' bleed
        assertTrue(ev(r.fighters.get(1), "marca_aplicada:Hemorragia") >= 5);
    }

    @Test
    void summaryInWords() {
        Map<String, Double> e = new LinkedHashMap<>();
        e.put("marca_aplicada:Frágil", 5.0);
        e.put("marca_consumida:Frágil:ataque", 1.0);
        e.put("marca_consumida:Frágil:R", 1.0);
        e.put("marca_sobrescrita:Frágil", 2.0);
        e.put("marca_expirou:Frágil", 1.0);
        e.put("controle_causado", 3.1);
        e.put("cc_encadeado", 2.0);
        e.put("runa_ativou:Aperto dos Mortos-Vivos", 2.0);
        assertEquals("Frágil: 5 aplicados, 2 consumidos (ataque 1, R 1), 2 sobrescritos, 1 expirou · controle: 3,1 s, "
                + "2 encadeados · Aperto dos Mortos-Vivos ×2", ArenaEvents.summary(e));
    }
}
