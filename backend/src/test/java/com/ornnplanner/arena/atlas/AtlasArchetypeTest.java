package com.ornnplanner.arena.atlas;

import com.ornnplanner.arena.fight.BuildArchetypes;
import com.ornnplanner.service.PlannerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The archetype asked for in the Atlas's guide: a set of shop items' archetype and its distance to the one asked. */
@SpringBootTest(properties = {"server.port=0"})
class AtlasArchetypeTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        String db = Files.createTempDirectory("atlas-arch").resolve("t.db").toString();
        r.add("planner.db-path", () -> db);
    }

    @Autowired
    AtlasService atlas;
    @Autowired
    BuildArchetypes archetypes;
    @Autowired
    PlannerService planner;

    private List<Long> items(String... names) {
        List<Long> out = new ArrayList<>();
        for (String n : names) {
            out.add(planner.itemViews().stream().filter(i -> n.equals(i.name)).findFirst().orElseThrow().id);
        }
        return out;
    }

    @Test
    void aTankBuildIsATankAndFarFromACriticalOne() {
        List<Long> tank = items("Coração Congelado", "Armadura de Warmog", "Presságio de Randuin", "Manto da Aurora");
        assertEquals(0, atlas.archetypeDistance(tank, "tanque"), 1e-9);
        assertTrue(atlas.archetypeDistance(tank, "critico") > 0.5, "a tank is far from crit");
        // a fighter's rule comes after the tank's: a pure tank build is also far from a fighter's
        assertTrue(atlas.archetypeDistance(tank, "lutador") > 0);
    }

    @Test
    void criticalItemsReachTheCriticalArchetype() {
        List<Long> crit = items("Gume do Infinito", "Dançarina Fantasma", "Furacão de Runaan");
        assertEquals(0, atlas.archetypeDistance(crit, "critico"), 1e-9);
        assertTrue(atlas.archetypeDistance(crit, "tanque") > 0.5);
    }

    @Test
    void steeringTakesThePenaltyPerUnitOfDistance() {
        List<Long> crit = items("Gume do Infinito", "Dançarina Fantasma");
        double d = atlas.archetypeDistance(crit, "tanque");
        assertEquals(60 - atlas.archetypePenalty() * d, atlas.steer(60, crit, "tanque"), 1e-9);
        assertEquals(60, atlas.steer(60, crit, null), 1e-9);
        assertEquals(60, atlas.steer(60, crit, "critico"), 1e-9);
        assertEquals(9, archetypes.names().size());
    }
}
