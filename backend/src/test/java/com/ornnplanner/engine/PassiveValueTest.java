package com.ornnplanner.engine;

import com.ornnplanner.arena.GoldSearch;
import com.ornnplanner.arena.PassiveValueService;
import com.ornnplanner.arena.PassiveValueService.PassiveValue;
import com.ornnplanner.arena.PassiveValueService.Reading;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The gold value of the passives: the estimate by the numbers of the moment and the measure in a fight. */
@SpringBootTest(properties = {"server.port=0"})
class PassiveValueTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        String db = Files.createTempDirectory("passive-value").resolve("t.db").toString();
        r.add("planner.db-path", () -> db);
    }

    @Autowired
    PassiveValueService values;
    @Autowired
    PlannerService planner;

    private final Map<String, Long> ids = new LinkedHashMap<>();

    @BeforeEach
    void names() {
        planner.itemViews().forEach(i -> ids.put(i.name, i.id));
    }

    private BuildRepository.Build build(String name, String... items) {
        BuildRepository.Build b = new BuildRepository.Build();
        b.name = name;
        b.unitCode = "ORNN";
        b.goldPerMin = 1000;
        b.xpPerMin = 700;
        b.steps = new ArrayList<>();
        for (String i : items) {
            b.steps.add(new BuildRepository.Step(ids.get(i), true));
        }
        return b;
    }

    private Map<String, PassiveValue> estimate(BuildRepository.Build b, String item, double minute) {
        PassiveValueService.EstimateRequest q = new PassiveValueService.EstimateRequest();
        q.build = b;
        q.minutes = List.of(minute);
        Map<String, PassiveValue> out = new LinkedHashMap<>();
        for (PassiveValueService.ItemValues iv : values.estimate(q).get(0).items) {
            if (iv.itemName.equals(item)) {
                iv.passives.forEach(p -> out.put(p.name, p));
            }
        }
        return out;
    }

    private Reading measure(BuildRepository.Build b, String item, String passive, double minute, String test) {
        PassiveValueService.MeasureRequest q = new PassiveValueService.MeasureRequest();
        q.build = b;
        q.scenario.kind = "teste";
        q.scenario.key = test;
        PassiveValueService.Job j = new PassiveValueService.Job();
        j.minute = minute;
        j.itemId = ids.get(item);
        j.passive = passive;
        q.jobs.add(j);
        return values.measure(q).get(0);
    }

    /** The search finds the gold on a known curve, says when the range ends and needs no fight for a tie. */
    @Test
    void theGoldSearchFindsTheValueOnASyntheticCurve() {
        GoldSearch.Found line = GoldSearch.find(g -> 100 + 0.02 * g, 100, 140, 6000, 0.1, 6);
        assertEquals(2000, line.gold, 10);
        // a concave curve: 100 + 8 sqrt(g) reaches 200 at g = 12.5^2 = 156.25
        GoldSearch.Found curve = GoldSearch.find(g -> 100 + 8 * Math.sqrt(g), 100, 200, 6000, 0.5, 12);
        assertEquals(156.25, curve.gold, 3);
        GoldSearch.Found capped = GoldSearch.find(g -> 100 + 0.001 * g, 100, 140, 6000, 0.1, 6);
        assertTrue(capped.capped);
        assertEquals(6000, capped.gold, 1e-9);
        GoldSearch.Found tie = GoldSearch.find(g -> { throw new AssertionError("no fight for a tie"); }, 100, 100.01, 6000, 0.1, 6);
        assertEquals(0, tie.gold, 1e-9);
    }

    /** Damage by the trade, a shred through penetration; Grievous Wounds stay at the tier's value. */
    @Test
    void theEstimateUsesTheNumbersOfTheMoment() {
        Map<String, PassiveValue> thorns = estimate(build("a", "Armadura de Espinhos"), "Armadura de Espinhos", 20);
        PassiveValue reflect = thorns.get("Espinhos");
        assertEquals(PassiveValueService.ESTIMATED, reflect.source);
        assertTrue(reflect.gold > 0);
        assertTrue(reflect.detail.contains("de dano"), reflect.detail);
        PassiveValue wounds = thorns.get("Entrelaçar");
        assertEquals(PassiveValueService.TIER, wounds.source);
        assertNull(wounds.gold);
        assertTrue(wounds.measurable);
        PassiveValue shred = estimate(build("b", "Cutelo Negro"), "Cutelo Negro", 20).values().stream()
                .filter(p -> PassiveValueService.ESTIMATED.equals(p.source)).findFirst().orElseThrow();
        assertTrue(shred.detail.contains("penetração"), shred.detail);
    }

    /** A passive that only gives stats stays out of both modes: the stats' account already is its measure. */
    @Test
    void aPassiveOfStatsOnlyIsNeitherEstimatedNorMeasured() {
        Map<String, PassiveValue> ps = estimate(build("c", "Capuz da Morte de Rabadon"), "Capuz da Morte de Rabadon", 20);
        PassiveValue stats = ps.values().stream().filter(p -> PassiveValueService.STATUS.equals(p.source)).findFirst().orElseThrow();
        assertNull(stats.gold);
        assertFalse(stats.measurable);
    }

    /** An item without the passive named: both fights are the same one, the passive is worth 0. */
    @Test
    void aMeasureOfNoPassiveIsZero() {
        Reading r = measure(build("d", "Cota de Malha"), "Cota de Malha", "Nenhuma", 12, "critico");
        assertEquals(0, r.gold, 1e-9);
        assertEquals(0, r.lossPct, 1e-9);
        assertEquals(r.scoreOn, r.scoreOff, 1e-9);
    }

    /** The passive off changes the fight; the same builds give the same reading (the cache keys differ by the name). */
    @Test
    void measuringIsDeterministicAndReadsThePassiveOff() {
        Reading a = measure(build("e", "Desespero Eterno", "Armadura de Espinhos"), "Desespero Eterno", "Angústia", 15, "critico");
        Reading b = measure(build("f", "Desespero Eterno", "Armadura de Espinhos"), "Desespero Eterno", "Angústia", 15, "critico");
        assertNotNull(a);
        assertTrue(a.lossPct != 0, "the passive heals in the fight");
        assertEquals(a.scoreOn, b.scoreOn, 1e-9);
        assertEquals(a.scoreOff, b.scoreOff, 1e-9);
        assertEquals(a.gold, b.gold, 1e-9);
        assertTrue(Math.abs(a.gold) > 0);
    }
}
