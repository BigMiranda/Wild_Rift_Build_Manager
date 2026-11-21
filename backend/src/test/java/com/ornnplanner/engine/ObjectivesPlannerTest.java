package com.ornnplanner.engine;

import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.service.PlannerService;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rodada 21, part 6: the planner's payload with objectives: one source "Objetivos" in the totals, the gold and the relevance. */
@SpringBootTest(properties = {"server.port=0"})
class ObjectivesPlannerTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        String db = Files.createTempDirectory("objectives").resolve("t.db").toString();
        r.add("planner.db-path", () -> db);
    }

    @Autowired
    PlannerService planner;

    private BuildRepository.Build build(ArenaModel.ObjectiveBuffs o) {
        Map<String, Long> ids = new LinkedHashMap<>();
        planner.itemViews().forEach(i -> ids.put(i.name, i.id));
        BuildRepository.Build b = new BuildRepository.Build();
        b.name = "teste";
        b.unitCode = "ORNN";
        b.goldPerMin = 900;
        b.xpPerMin = 700;
        b.steps = new ArrayList<>(List.of(new BuildRepository.Step(ids.get("Coração de Aço"), true),
                new BuildRepository.Step(ids.get("Armadura de Espinhos"), true)));
        b.objectives = o;
        return b;
    }

    static Model.MinutePoint last(Model.TimelineResult r) {
        return r.series.get(r.series.size() - 1);
    }

    @Test
    void theObjectivesAreOneSourceOfTheSheet() {
        ArenaModel.ObjectiveBuffs o = new ArenaModel.ObjectiveBuffs();
        o.dragons.put("montanha", 1);
        o.dragons.put("infernal", 1);
        o.dragons.put("oceano", 1);
        o.soul = "oceano";
        o.elder = true;
        o.baron = true;
        Model.MinutePoint plain = last(planner.calculate(build(null)));
        Model.MinutePoint with = last(planner.calculate(build(o)));
        assertNull(plain.objectiveParts);
        assertNotNull(with.objectiveParts);
        Model.ItemContribution c = with.contributions.stream().filter(x -> x.objective).findFirst().orElseThrow();
        assertEquals(ObjectiveRules.NAME, c.itemName);
        assertTrue(c.rune);
        assertTrue(c.flatGold > 0);
        // the totals are the plain ones plus the objectives', by the rule of the fights
        for (Map.Entry<String, Double> e : with.objectiveDelta.entrySet()) {
            assertEquals(plain.total.getOrDefault(e.getKey(), 0.0) + e.getValue(), with.total.get(e.getKey()), 1e-6, e.getKey());
        }
        assertTrue(with.objectiveDelta.get("Armor") > 4);
        assertTrue(with.objectiveDelta.get("Heal") > 250);
        // the fight effects: the Elder, the Ocean drake and soul
        assertTrue(with.objectiveParts.stream().anyMatch(p -> "elder".equals(p.source) && p.fight.value > 0));
        assertTrue(with.objectiveParts.stream().anyMatch(p -> "oceano".equals(p.source) && p.fight.value > 0));
        // the relevance sees the source
        Model.Relevance rel = planner.relevance(build(o), with.minute, List.of());
        assertTrue(rel.contributions.stream().anyMatch(x -> x.objective));
    }
}
