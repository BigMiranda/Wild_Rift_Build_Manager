package com.ornnplanner.engine;

import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.ArenaService;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.service.PlannerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Relatórios › Campeão: passiva e habilidades (Rodada 8, parte 2b): the abilities read with the build's stats are the
 * Arena's — the damage of each ability against the ragdoll, ±1%, for the same build at the same minute.
 */
@SpringBootTest(properties = {"server.port=0"})
class ChampionAbilitiesTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        String db = Files.createTempDirectory("champion-abilities").resolve("t.db").toString();
        r.add("planner.db-path", () -> db);
    }

    @Autowired
    ArenaService arena;
    @Autowired
    PlannerService planner;

    private final Map<String, Long> ids = new LinkedHashMap<>();

    @BeforeEach
    void names() {
        planner.itemViews().forEach(i -> ids.put(i.name, i.id));
    }

    /** Ornn with tank items and no runes (no damage amplifier between the sheet and the hit). */
    private BuildRepository.Build ornn() {
        BuildRepository.Build b = new BuildRepository.Build();
        b.name = "teste";
        b.unitCode = "ORNN";
        b.goldPerMin = 700;
        b.xpPerMin = 700;
        b.steps = new ArrayList<>();
        for (String i : List.of("Coração Congelado", "Presságio de Randuin", "Armadura de Espinhos")) {
            b.steps.add(new BuildRepository.Step(ids.get(i), true));
        }
        b.spells = new ArrayList<>();
        return b;
    }

    private static ArenaModel.Dummy target() {
        ArenaModel.Dummy d = new ArenaModel.Dummy();
        d.health = 200000;
        d.armor = 80;
        d.magicResist = 60;
        return d;
    }

    private static Map<String, Object> slot(List<Map<String, Object>> list, String slot) {
        return list.stream().filter(m -> slot.equals(m.get("slot"))).findFirst().orElseThrow();
    }

    @Test
    void theReadingIsTheArenasDamageAgainstTheRagdoll() {
        BuildRepository.Build b = ornn();
        double minute = 18;
        List<Map<String, Object>> read = arena.abilityValues(b, minute, target());
        Map<String, Object> q = slot(read, "1");
        Map<String, Object> e = slot(read, "3");
        assertEquals("fisico", q.get("damageType"));
        double qAfter = ((Number) q.get("afterResist")).doubleValue();
        double eAfter = ((Number) e.get("afterResist")).doubleValue();
        // the terms add up to the damage
        double sum = 0;
        for (Object t : (List<?>) q.get("terms")) {
            sum += ((Number) ((Map<?, ?>) t).get("value")).doubleValue();
        }
        assertEquals(((Number) q.get("damage")).doubleValue(), sum, 1e-6);
        // the cooldown is the sheet's with the build's haste
        double cd = ((Number) q.get("cooldownBase")).doubleValue() / (1 + ((Number) q.get("haste")).doubleValue() / 100);
        assertEquals(cd, ((Number) q.get("cooldown")).doubleValue(), 1e-9);

        // the same build at the same minute in a fight on the same ragdoll: what each hit of Q and E dealt
        ArenaModel.Scenario sc = new ArenaModel.Scenario();
        ArenaModel.Slot me = new ArenaModel.Slot();
        me.team = 0;
        me.build = b;
        me.minute = minute;
        me.brain = "tactical";
        ArenaModel.Slot dummy = new ArenaModel.Slot();
        dummy.team = 1;
        dummy.dummy = target();
        // face to face (the lane is long: from the default places Ornn walks the whole fight)
        me.x = 5000.0;
        dummy.x = 5400.0;
        sc.fighters = List.of(me, dummy);
        sc.duration = 20;
        ArenaModel.Result r = arena.runQuiet(sc);
        ArenaModel.FighterStats f = r.fighters.get(0);
        System.out.println("damage by source: " + f.damageBySource + " | events: " + f.events + " | dealt " + f.dealt
                + " | end " + f.endX + " | notes " + f.notes + " | took " + r.duration + " | other " + r.fighters.get(1).endX);
        int checked = 0;
        for (Map<String, Object> m : List.of(q, e)) {
            String name = String.valueOf(m.get("name"));
            String slot = String.valueOf(m.get("slot"));
            Double dealt = f.damageBySource.entrySet().stream().filter(x -> x.getKey().contains(name))
                    .map(Map.Entry::getValue).reduce(Double::sum).orElse(null);
            Double hits = f.events.get("habilidade_acertou:" + slot);
            if (dealt == null || hits == null || hits < 1) {
                continue;
            }
            double perHit = dealt / hits;
            double expected = ((Number) m.get("afterResist")).doubleValue();
            assertEquals(expected, perHit, expected * 0.01, name + ": reading " + expected + " × arena " + perHit);
            checked++;
        }
        assertTrue(checked > 0, "the fight used Q or E on the ragdoll");
        assertTrue(qAfter > 0 && eAfter > 0);
        assertNotNull(q.get("usesToKill"));
    }
}
