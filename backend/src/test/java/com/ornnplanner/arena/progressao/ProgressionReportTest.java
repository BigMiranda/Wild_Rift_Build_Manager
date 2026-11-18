package com.ornnplanner.arena.progressao;

import com.ornnplanner.engine.Model;
import com.ornnplanner.repo.BuildRepository;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rodada 19: the natural progression on a <b>copy</b> of a database (opt-in):
 * {@code ./mvnw test -Dtest=ProgressionReportTest -Dprogressao.report=data/ornn-planner.db}. Checks that the three most
 * likely continuations of Ornn and Rumble are builds the campaigns know, that the clock agrees with the planner's
 * timeline, and writes the leave-one-out numbers to target/progressao-report.txt.
 */
@EnabledIfSystemProperty(named = "progressao.report", matches = ".+")
@SpringBootTest(properties = {"server.port=0"})
class ProgressionReportTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        Path copy = Files.createTempDirectory("progressao-report").resolve("copy.db");
        Files.copy(Paths.get(System.getProperty("progressao.report")), copy);
        r.add("planner.db-path", copy::toString);
    }

    @Autowired
    ProgressionService progression;
    @Autowired
    PlannerService planner;

    @Test
    void report() throws IOException {
        StringBuilder sb = new StringBuilder();
        ProgressionModel m = progression.model();
        for (String[] pair : new String[][] {{"ORNN", "RUMBLE"}, {"RUMBLE", "ORNN"}}) {
            ProgressionService.Answer a = progression.answer(pair[0], List.of(), List.of(), List.of(pair[1]), null, 3);
            sb.append(a.champion).append(" (").append(a.known).append(", arquétipo ").append(a.archetype).append(")\n");
            List<Set<Long>> campaign = new ArrayList<>();
            for (ProgressionModel.Trajectory t : m.known()) {
                if (a.champion.equalsIgnoreCase(t.champion) && "descoberta".equals(t.source)) {
                    campaign.add(new HashSet<>(t.items));
                }
            }
            assertTrue(!campaign.isEmpty(), "the campaign has builds of " + a.champion);
            for (ProgressionService.ContinuationView c : a.continuations) {
                Set<Long> mine = new HashSet<>();
                StringBuilder line = new StringBuilder();
                for (ProgressionService.Step s : c.steps) {
                    mine.add(s.itemId);
                    line.append(String.format("%s %.1f' ", s.name, s.minute));
                }
                int best = 0;
                for (Set<Long> k : campaign) {
                    Set<Long> shared = new HashSet<>(k);
                    shared.retainAll(mine);
                    best = Math.max(best, shared.size());
                }
                sb.append(String.format("  p=%.5f  em comum com a campanha: %d/6  %s%n", c.probability, best, line));
                assertTrue(best >= 4, a.champion + ": a likely continuation far from every campaign build: " + line);
            }
        }
        ProgressionModel.Check all = progression.validate(List.of());
        ProgressionModel.Check two = progression.validate(List.of("ORNN", "RUMBLE"));
        sb.append(String.format("Deixe uma fora, todos: %d casos, top-1 %.1f%%, top-3 %.1f%% (linha de base %.1f%%), depois da 2ª compra %.1f%%%n",
                all.cases, 100.0 * all.top1 / all.cases, 100.0 * all.top3 / all.cases, 100.0 * all.baselineTop3 / all.cases,
                100.0 * all.lateTop3 / Math.max(1, all.lateCases)));
        sb.append(String.format("Deixe uma fora, Ornn e Rumble: %d casos, top-1 %.1f%%, top-3 %.1f%% (linha de base %.1f%%), depois da 2ª compra %.1f%%%n",
                two.cases, 100.0 * two.top1 / two.cases, 100.0 * two.top3 / two.cases, 100.0 * two.baselineTop3 / two.cases,
                100.0 * two.lateTop3 / Math.max(1, two.lateCases)));
        for (Map.Entry<String, int[]> e : two.byChampion.entrySet()) {
            sb.append(String.format("  %s: %d casos, top-3 %.1f%%, linha de base %.1f%%%n", e.getKey(), e.getValue()[0],
                    100.0 * e.getValue()[1] / e.getValue()[0], 100.0 * e.getValue()[2] / e.getValue()[0]));
        }
        assertTrue(two.top3 >= two.baselineTop3, "the chain should not lose to the plain frequency");
        Files.writeString(Paths.get("target/progressao-report.txt"), sb.toString(), StandardCharsets.UTF_8);
        System.out.println(sb);
    }

    /** The clock (starting gold + pace x minute over the cumulative cost) agrees with the planner's timeline. */
    @Test
    void theClockFollowsThePlanner() {
        ProgressionModel m = progression.model();
        ProgressionModel.Trajectory t = m.known().stream().filter(x -> "Ornn".equals(x.champion) && x.items.size() >= 5)
                .findFirst().orElseThrow();
        BuildRepository.Build b = new BuildRepository.Build();
        b.unitCode = "ORNN";
        b.name = "relógio";
        b.goldPerMin = 700;
        b.xpPerMin = 700;
        b.assumeHalfItems = true;
        b.assumeSmallItems = true;
        for (Long id : t.items) {
            b.steps.add(new BuildRepository.Step(id, true));
        }
        Model.TimelineResult r = planner.calculate(b);
        List<Double> planned = new ArrayList<>();
        for (Model.TimelineStep st : r.steps) {
            if (!st.implied && !st.sold && t.items.contains(st.itemId)) {
                planned.add(st.minute);
            }
        }
        List<Double> clock = m.minutes(t.items, 700);
        assertEquals(clock.size(), planned.size());
        for (int i = 0; i < clock.size(); i++) {
            assertEquals(planned.get(i), clock.get(i), 0.75, "item " + i + " of " + t.items);
        }
    }
}
