package com.ornnplanner.engine;

import com.ornnplanner.arena.fight.BuildArchetypes;
import com.ornnplanner.arena.fight.ChampionProfiles;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Official classes, the profile computed from the sheets and the archetypes of builds (the seed's catalog, a new database). */
@SpringBootTest(properties = {"server.port=0"})
class ClassificationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        String db = Files.createTempDirectory("classification").resolve("t.db").toString();
        r.add("planner.db-path", () -> db);
    }

    private static final Set<String> CLASSES = Set.of("Controlador", "Lutador", "Mago", "Atirador", "Assassino", "Tanque", "Especialista");
    private static final Set<String> ROLES = Set.of("Tanque", "Lutador", "Assassino", "Mago", "Atirador", "Suporte");
    private static final Set<String> LANES = Set.of("Barão", "Selva", "Meio", "Dragão", "Suporte");

    @Autowired
    ChampionProfiles profiles;
    @Autowired
    BuildArchetypes archetypes;
    @Autowired
    PlannerService planner;

    private final Map<String, Long> ids = new LinkedHashMap<>();

    @BeforeEach
    void names() {
        planner.itemViews().forEach(i -> ids.put(i.name, i.id));
    }

    private BuildRepository.Build build(String unit, String... items) {
        BuildRepository.Build b = new BuildRepository.Build();
        b.name = "teste";
        b.unitCode = unit;
        b.goldPerMin = 3000;
        b.xpPerMin = 800;
        b.steps = new ArrayList<>();
        for (String i : items) {
            assertNotNull(ids.get(i), i);
            b.steps.add(new BuildRepository.Step(ids.get(i), true));
        }
        return b;
    }

    /** Every champion has a class, a role, a lane and a source, all from the lists of the game. */
    @Test
    void everyChampionHasItsClassesRolesAndLanes() {
        List<ChampionProfiles.Profile> all = profiles.all();
        assertEquals(142, all.size());
        for (ChampionProfiles.Profile p : all) {
            assertFalse(p.classes.isEmpty(), p.name);
            assertTrue(CLASSES.containsAll(p.mainClasses), p.name + ": " + p.mainClasses);
            assertTrue(!p.roles.isEmpty() && ROLES.containsAll(p.roles), p.name + ": " + p.roles);
            assertTrue(!p.lanes.isEmpty() && LANES.containsAll(p.lanes), p.name + ": " + p.lanes);
            assertNotNull(p.source, p.name);
            assertTrue(p.attackRange > 0, p.name);
        }
    }

    /** The profile reads the sheet: a tank's health, a marksman's range, a mage's magic damage, the ability ranges of a kit. */
    @Test
    void theProfileReadsTheSheets() {
        ChampionProfiles.Profile ornn = profiles.of("Ornn");
        assertTrue(ornn.mainClasses.contains("Tanque"));
        assertTrue(ornn.control.getOrDefault("arremesso", 0) > 0, "knocks up: " + ornn.control);
        assertNotNull(ornn.abilityRange, "it has a sheet in the arena");
        ChampionProfiles.Profile mage = profiles.of("Syndra");
        assertTrue(mage.magic > 0.5, "magic damage: " + mage.magic);
        assertTrue(mage.attackRange > 400);
        assertTrue(profiles.of("Vayne").physical > 0.5);
        assertTrue(ornn.healthLv15 > profiles.of("Vayne").healthLv15);
        assertEquals("Tanque", ornn.suggested);
        assertEquals("Atirador", profiles.of("Caitlyn").suggested);
        assertEquals("Mago", profiles.of("Syndra").suggested);
        assertEquals(List.of("Mago"), profiles.classesOf("Norra"), "a Wild Rift champion, from its WR page");
    }

    /** By thresholds of stats and kinds of effects. */
    @Test
    void archetypesComeFromTheStatsAndEffects() {
        assertEquals("tanque", archetypes.of(build("ORNN", "Coração de Aço", "Armadura de Espinhos", "Presságio de Randuin"), 25.0).key);
        assertEquals("critico", archetypes.of(build("VAYNE", "Gume do Infinito", "Canhão Fumegante", "Lembranças do Lorde Dominik"), 25.0).key);
        assertEquals("mago_continuo", archetypes.of(build("RUMBLE", "Tormento de Liandry", "Cajado do Vazio"), 25.0).key);
        BuildArchetypes.Archetype tank = archetypes.of(build("ORNN", "Armadura de Espinhos", "Presságio de Randuin"), 25.0);
        assertNotNull(tank.detail, "a tank says what it stacks");
        assertTrue(tank.shares.get("defesa") >= 0.6, "shares: " + tank.shares);
    }
}
