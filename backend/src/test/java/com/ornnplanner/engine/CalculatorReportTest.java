package com.ornnplanner.engine;

import com.ornnplanner.arena.calc.CalculatorService;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.service.PlannerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The calculator's measured examples on a <b>copy</b> of a database (report style, opt-in):
 * {@code ./mvnw test -Dtest=CalculatorReportTest -Dcalc.report=data/ornn-planner.db}. Writes target/calc-report.txt.
 */
@EnabledIfSystemProperty(named = "calc.report", matches = ".+")
@SpringBootTest(properties = {"server.port=0"})
class CalculatorReportTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        Path copy = Files.createTempDirectory("calc-report").resolve("copy.db");
        Files.copy(Paths.get(System.getProperty("calc.report")), copy);
        r.add("planner.db-path", copy::toString);
    }

    @Autowired
    CalculatorService calc;
    @Autowired
    BuildRepository builds;
    @Autowired
    PlannerService planner;

    private long build(String name, String unit) {
        return builds.findBuildSummaries().stream().filter(b -> unit.equals(b.unitCode) && (name == null || name.equals(b.name))).findFirst().orElseThrow().id;
    }

    private static String text(CalculatorService.Answer a) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%.1f s, %d measures, truncated %s%n", a.seconds, a.evaluations, a.truncated));
        if (a.best != null) {
            sb.append("best ").append(a.best.names).append(String.format(" %.1f %s%n", a.best.score, java.util.Arrays.toString(a.best.curve)));
        }
        a.alternatives.forEach(l -> sb.append("alt  ").append(l.names).append(String.format(" %.1f%n", l.score)));
        a.importance.forEach(m -> sb.append("  ").append(m).append('\n'));
        sb.append("boots ").append(a.boots).append(' ').append(a.bootsFrom).append('\n');
        a.rows.forEach(r -> sb.append(String.format("row %s wins %.0f%% worst %.2f vs %s mix %.2f%n", r.ours.names, r.winRate * 100, r.worst, r.worstAnswer == null ? null : r.worstAnswer.names, r.mix)));
        return sb.toString();
    }

    @Test
    void report() throws IOException {
        StringBuilder sb = new StringBuilder("6.1 Big Carry v1 x Basico\n");
        CalculatorService.Request first = new CalculatorService.Request();
        first.buildId = build("Big Carry v1", "ORNN");
        first.tanksOnly = true;
        first.length = 3;
        first.beam = 2;
        first.budget = 120;
        first.minutes = new ArrayList<>(List.of(8.0, 12.0, 16.0));
        sb.append(text(calc.compute(first, null)));
        sb.append("6.2 Ornn x Darius k=4 d=2\n");
        CalculatorService.Request tree = new CalculatorService.Request();
        tree.buildId = first.buildId;
        tree.opponentBuildId = build(null, "DARIUS");
        tree.tree = true;
        tree.k = 4;
        tree.depth = 2;
        tree.length = 2;
        tree.beam = 4;
        tree.budget = 3000;
        tree.minutes = new ArrayList<>(List.of(10.0, 14.0, 18.0));
        CalculatorService.Answer a = calc.compute(tree, null);
        sb.append(text(a));
        assertEquals(a.best.order, calc.compute(tree, null).best.order);
        System.out.println(sb);
        Files.write(Paths.get("target/calc-report.txt"), sb.toString().getBytes("UTF-8"));
    }
}
