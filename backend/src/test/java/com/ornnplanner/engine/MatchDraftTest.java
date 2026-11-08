package com.ornnplanner.engine;

import com.ornnplanner.arena.MatchService;
import com.ornnplanner.arena.rift.MacroPolicy;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A match's draft played many times (the macro's training, proposta-macro-treinada.md, Parte 1): the same seed gives the
 * same match on a fresh draft or a used one, one after another or on many threads at once, and one macro per side
 * changes nothing when both sides have the script.
 */
@SpringBootTest(properties = {"server.port=0"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MatchDraftTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        String db = Files.createTempDirectory("match-draft").resolve("t.db").toString();
        r.add("planner.db-path", () -> db);
    }

    @Autowired
    MatchService matches;
    @Autowired
    BuildRepository builds;
    @Autowired
    PlannerService planner;

    private MatchModel.Request request;

    @BeforeAll
    void builds() {
        Map<String, Long> ids = new LinkedHashMap<>();
        planner.itemViews().forEach(i -> ids.put(i.name, i.id));
        request = new MatchModel.Request();
        request.limit = 20;
        String[][] team = {{"RUMBLE", "Cutelo Negro"}, {"ORNN", "Coração de Aço"}, {"RENEKTON", "Cutelo Negro"},
            {"DARIUS", "Cutelo Negro"}, {"ORNN", "Égide de Fogo Solar"}};
        List<Long> saved = new ArrayList<>();
        long folder = builds.insertFolder("Partida");
        for (String[] t : team) {
            BuildRepository.Build b = new BuildRepository.Build();
            b.folderId = folder;
            b.name = t[0] + " " + t[1];
            b.unitCode = t[0];
            b.goldPerMin = 700;
            b.xpPerMin = 700;
            b.steps = new ArrayList<>(List.of(new BuildRepository.Step(ids.get(t[1]), true),
                    new BuildRepository.Step(ids.get("Coração de Aço"), true)));
            saved.add(builds.insertBuild(b));
        }
        for (int side = 0; side < 2; side++) {
            for (long id : saved) {
                MatchModel.Slot s = new MatchModel.Slot();
                s.buildId = id;
                s.team = side;
                request.slots.add(s);
            }
        }
    }

    /** What a match came to: winner, length, fights, and every seat's gold, experience and deaths. */
    private static String print(MatchModel.Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.winner).append('@').append(r.seconds).append(" fights ").append(r.fights.size()).append(" events ").append(r.events.size());
        for (MatchModel.Player p : r.players) {
            sb.append(" | ").append(p.goldEarned).append('/').append(p.xp).append('/').append(p.deaths).append('/').append(p.purchases.size());
        }
        return sb.toString();
    }

    private String play(MatchService.Draft d, long seed) {
        MacroPolicy script = matches.scripted();
        return print(matches.run(d, seed, 20, script, script));
    }

    @Test
    void theSameSeedGivesTheSameMatchOnAnyDraftAndThread() throws Exception {
        long[] seeds = {1, 2, 3};
        // each seed on its own fresh draft: the reference
        List<String> fresh = new ArrayList<>();
        long t0 = System.nanoTime();
        for (long seed : seeds) {
            fresh.add(play(matches.draft(request), seed));
        }
        double cold = (System.nanoTime() - t0) / 1e9 / seeds.length;
        // one draft, used again and again
        MatchService.Draft shared = matches.draft(request);
        play(shared, 99);   // warms its fighters up in another order
        long t1 = System.nanoTime();
        for (int i = 0; i < seeds.length; i++) {
            assertEquals(fresh.get(i), play(shared, seeds[i]), "seed " + seeds[i] + " on a used draft");
        }
        double warm = (System.nanoTime() - t1) / 1e9 / seeds.length;
        // a new draft played on many threads at once, every seed twice
        MatchService.Draft parallel = matches.draft(request);
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
        try {
            List<Future<String>> out = new ArrayList<>();
            for (int k = 0; k < 2; k++) {
                for (long seed : seeds) {
                    out.add(pool.submit(() -> play(parallel, seed)));
                }
            }
            for (int i = 0; i < out.size(); i++) {
                assertEquals(fresh.get(i % seeds.length), out.get(i).get(), "seed " + seeds[i % seeds.length] + " on " + "many threads");
            }
        } finally {
            pool.shutdown();
        }
        System.out.printf("match draft: %.2f s per match on a fresh draft, %.2f s on a warm one, %d fighters made%n", cold, warm, shared.fightersMade());
        assertTrue(warm <= cold * 1.5, "a used draft is not slower: " + warm + " vs " + cold);
    }

    @Test
    void oneScriptPerSideIsThePlainMatch() {
        MatchService.Draft d = matches.draft(request);
        request.seed = 4;
        String plain = print(matches.run(request));
        String perSide = print(matches.run(d, 4, 20, matches.scripted(), matches.scripted()));
        assertEquals(plain, perSide);
    }
}
