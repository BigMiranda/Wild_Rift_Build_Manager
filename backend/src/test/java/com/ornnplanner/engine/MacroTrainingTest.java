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

    @Autowired
    com.ornnplanner.arena.MacroTrainingService service;
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

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

    /** Parte 4: a macro saved with zero weights plays the script's match; one of another schema is refused. */
    @Test
    void aSavedMacroPlaysAndAnotherSchemaIsRefused() {
        com.ornnplanner.arena.MacroPolicyRepository repo = service.repository();
        com.ornnplanner.arena.MacroPolicyRepository.Record r = new com.ornnplanner.arena.MacroPolicyRepository.Record();
        r.obsSchema = com.ornnplanner.arena.rift.MacroObservation.SCHEMA;
        r.actions = com.ornnplanner.arena.rift.MacroActions.SIZE;
        r.rules = MacroTraining.RULES;
        r.drafts = List.of(List.copyOf(ids));
        r.weights = com.ornnplanner.arena.MacroPolicyRepository.Weights.of(MacroTraining.zeros(), com.ornnplanner.arena.rift.NeuralMacro.BASE_BONUS);
        long id = repo.insert(r).id;
        MatchModel.Request req = request(5);
        MatchModel.Result plain = matches.run(req);
        req.macroBlue = "macro:" + id;
        MatchModel.Result saved = matches.run(req);
        assertEquals(plain.winner, saved.winner);
        assertEquals(plain.seconds, saved.seconds);
        assertEquals(plain.events.size(), saved.events.size());
        assertEquals(List.of("Macro treinada v" + repo.find(id).get().version, "Roteiro"), saved.macros);
        assertEquals(List.copyOf(ids), repo.find(id).get().drafts.get(0));
        jdbc.update("UPDATE macro_policy SET obs_schema = 0 WHERE id = ?", id);
        org.springframework.web.server.ResponseStatusException e = org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class, () -> matches.run(req));
        assertTrue(e.getReason().contains("outro esquema"), e.getReason());
        assertTrue(repo.delete(id));
    }

    private MatchModel.Request request(long seed) {
        MatchModel.Request req = new MatchModel.Request();
        req.seed = seed;
        req.limit = 12;
        for (int side = 0; side < 2; side++) {
            for (long id : ids) {
                MatchModel.Slot s = new MatchModel.Slot();
                s.buildId = id;
                s.team = side;
                req.slots.add(s);
            }
        }
        return req;
    }

    /** Parte 4: "Treinar a macro" end to end, tiny: the job runs, validates and saves a version. */
    @Test
    void theServiceTrainsValidatesAndSaves() throws InterruptedException {
        com.ornnplanner.arena.MacroTrainingService.TrainRequest req = new com.ornnplanner.arena.MacroTrainingService.TrainRequest();
        req.drafts = List.of(List.copyOf(ids));
        req.generations = 1;
        req.population = 2;
        req.episodes = 1;
        req.validationSeeds = 1;
        req.limit = 6;
        com.ornnplanner.arena.MacroTrainingService.Job job = service.start(req);
        long until = System.currentTimeMillis() + 300_000;
        while (("rodando".equals(job.status) || "validando".equals(job.status)) && System.currentTimeMillis() < until) {
            Thread.sleep(200);
        }
        assertEquals("pronto", job.status, job.message);
        assertEquals(1, job.progress.size());
        assertEquals(2, job.result.games);
        assertTrue(service.repository().list().stream().anyMatch(x -> x.id == job.result.id));
        service.repository().delete(job.result.id);
    }
}
