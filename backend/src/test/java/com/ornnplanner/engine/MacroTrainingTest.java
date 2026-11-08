package com.ornnplanner.engine;

import com.ornnplanner.arena.MacroTraining;
import com.ornnplanner.arena.MatchService;
import com.ornnplanner.arena.ai.Network;
import com.ornnplanner.arena.ai.Trainer;
import com.ornnplanner.arena.rift.MatchModel;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.service.PlannerService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The macro's training (proposta-macro-treinada.md, Parte 3), small: the script against itself is an even match, the
 * fitness reads the winner, and the same seed trains the same weights on one thread or many.
 */
@SpringBootTest(properties = {"server.port=0"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MacroTrainingTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        String db = Files.createTempDirectory("macro-training").resolve("t.db").toString();
        r.add("planner.db-path", () -> db);
    }

    @Autowired
    MatchService matches;
    @Autowired
    BuildRepository builds;
    @Autowired
    PlannerService planner;

    private MatchService.Draft draft;
    private final List<Long> ids = new ArrayList<>();

    @BeforeAll
    void draft() {
        Map<String, Long> ids = new LinkedHashMap<>();
        planner.itemViews().forEach(i -> ids.put(i.name, i.id));
        long folder = builds.insertFolder("Treino");
        MatchModel.Request req = new MatchModel.Request();
        for (String unit : List.of("RUMBLE", "ORNN", "RENEKTON", "DARIUS", "ORNN")) {
            BuildRepository.Build b = new BuildRepository.Build();
            b.folderId = folder;
            b.name = unit;
            b.unitCode = unit;
            b.goldPerMin = 700;
            b.xpPerMin = 700;
            b.steps = new ArrayList<>(List.of(new BuildRepository.Step(ids.get("Cutelo Negro"), true),
                    new BuildRepository.Step(ids.get("Coração de Aço"), true)));
            long id = builds.insertBuild(b);
            this.ids.add(id);
            for (int side = 0; side < 2; side++) {
                MatchModel.Slot s = new MatchModel.Slot();
                s.buildId = id;
                s.team = side;
                req.slots.add(s);
            }
        }
        draft = matches.draft(req);
    }

    private MacroTraining training(int threads) {
        MacroTraining.Config c = new MacroTraining.Config();
        c.population = 4;
        c.generations = 2;
        c.episodesPerGeneration = 1;
        c.seedsPerDraft = 4;
        c.validationSeeds = 2;
        c.limitMinutes = 12;
        c.threads = threads;
        return new MacroTraining(matches, List.of(draft), c, List.of());
    }

    /** The script against itself, both sides of every seed: exactly even, and the fitness reads the winner. */
    @Test
    void theScriptAgainstItselfIsEven() {
        MacroTraining t = training(4);
        MacroTraining.Validation v = t.validate(MacroTraining.zeros());
        assertEquals(4, v.games);
        assertEquals(0.5, v.winrate, 1e-9);
        assertEquals(0, v.deviations, 1e-9);
        assertEquals(v.wins, v.losses);
        MatchModel.Result r = matches.run(draft, 7, 20, t.script(), t.script());
        if (r.winner >= 0) {
            assertTrue(t.fitness(r, r.winner) > 0.4);
            assertTrue(t.fitness(r, 1 - r.winner) < -0.4);
        }
        assertEquals(-t.fitness(r, 0), t.fitness(r, 1), 1e-9);
    }

    /** Two generations: the same weights on one thread and on many, and they moved off zero. */
    @Test
    void theSameSeedTrainsTheSameWeightsOnAnyThreads() {
        Trainer.Listener quiet = g -> { };
        MacroTraining.Listener listener = new MacroTraining.Listener() {
            @Override
            public void generation(Trainer.Generation g) {
                quiet.generation(g);
            }
        };
        Network one = training(1).train(MacroTraining.zeros(), listener);
        Network many = training(8).train(MacroTraining.zeros(), listener);
        assertArrayEquals(one.weights(), many.weights());
        assertTrue(Arrays.stream(one.weights()).anyMatch(w -> w != 0), "the weights moved");
    }
}
