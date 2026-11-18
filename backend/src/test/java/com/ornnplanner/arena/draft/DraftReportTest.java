package com.ornnplanner.arena.draft;

import com.ornnplanner.engine.Model;
import com.ornnplanner.service.PlannerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rodada 20, Partes 4 a 6, on a <b>copy</b> of a database (opt-in):
 * {@code ./mvnw test -Dtest=DraftReportTest -Ddraft.report=data/ornn-planner.db}. Ornn (Barão, azul) against Darius:
 * the first encounter (about 12 starts each side, in seconds), the 10 likely builds of Darius after a purchase, and the
 * tips for Ornn; numbers and times to target/draft-report.txt.
 */
@EnabledIfSystemProperty(named = "draft.report", matches = ".+")
@SpringBootTest(properties = {"server.port=0"})
class DraftReportTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        Path copy = Files.createTempDirectory("draft-report").resolve("copy.db");
        Files.copy(Paths.get(System.getProperty("draft.report")), copy);
        r.add("planner.db-path", copy::toString);
    }

    @Autowired
    FirstEncounterService encounter;
    @Autowired
    LiveBuildsService builds;
    @Autowired
    TipsService tips;
    @Autowired
    PlannerService planner;

    private long id(String name) {
        for (Model.ItemDef d : planner.referenceData().items.values()) {
            if (name.equals(d.name)) {
                return d.id;
            }
        }
        throw new IllegalArgumentException(name);
    }

    @Test
    void report() throws IOException {
        StringBuilder sb = new StringBuilder();
        LiveGameModel.Game g = new LiveGameModel.Game();
        g.blue.players.get(0).champion = "Ornn";
        g.red.players.get(0).champion = "Darius";

        FirstEncounterService.Result e = encounter.run(g, LiveGameModel.BLUE, 0);
        sb.append(String.format(Locale.ROOT, "encontro: %d inícios do Ornn x %d do Darius, %d lutas, %.1f s (%.1f s preparando)%n",
                e.allyStarts.size(), e.enemyStarts.size(), e.fights, e.seconds, e.prepareSeconds));
        sb.append(String.format(Locale.ROOT, "  melhor: %s (%s) %.1f; conselho %s %s%n", e.best.name, e.best.style, e.best.mean, e.advice.key, e.advice.vars));
        assertTrue(e.allyStarts.size() >= 10 && e.allyStarts.size() <= 12, "near 12 starts");
        assertTrue(e.seconds < 30, "the encounter of a lane fits in seconds: " + e.seconds);

        g.red.players.get(0).items.add(id("Cutelo Negro"));
        g.clock.seconds = 480;
        LiveBuildsService.Result b = builds.run(g, LiveGameModel.RED, 0, true);
        sb.append(String.format(Locale.ROOT, "builds do Darius: %d, %.1f s, duelo agora %.1f%n", b.builds.size(), b.seconds, b.duelNow));
        for (LiveBuildsService.Build x : b.builds) {
            sb.append(String.format(Locale.ROOT, "  %.4f%% imp %.1f: ", 100 * x.probability, x.importance));
            x.steps.forEach(s -> sb.append(s.name).append(String.format(Locale.ROOT, " %.1f' | ", s.minute)));
            sb.append('\n');
        }
        assertTrue(b.builds.size() <= 10 && !b.builds.isEmpty());
        for (int i = 1; i < b.builds.size(); i++) {
            assertTrue(b.builds.get(i - 1).importance >= b.builds.get(i).importance, "sorted by importance");
        }

        TipsService.Result t = tips.run(g, LiveGameModel.BLUE, 0, List.of());
        sb.append(String.format(Locale.ROOT, "dicas do Ornn: %d, %.1f s%n", t.tips.size(), t.seconds));
        t.tips.forEach(x -> sb.append("  ").append(x.enemy).append(' ').append(x.kind).append(' ').append(x.key).append(' ')
                .append(x.source).append(' ').append(x.vars).append('\n'));
        assertFalse(t.tips.isEmpty());
        assertTrue(t.tips.stream().allMatch(x -> x.source != null));
        Files.write(Paths.get("target/draft-report.txt"), sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
