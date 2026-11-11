package com.ornnplanner.engine;

import com.ornnplanner.arena.calc.CalculatorService;
import com.ornnplanner.repo.BuildRepository;
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
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The order the Discovery asks the calculator for one kit, on a <b>copy</b> (opt-in, Rodada 13): why an order ends cut.
 * {@code ./mvnw test -Dtest=OrderCutReportTest -Dorder.report=data/ornn-planner.db -Dorder.build="Big Carry v1"
 * -Dorder.items=6,17,19,23,29 -Dorder.boots=175 -Dorder.budget=2000}
 */
@EnabledIfSystemProperty(named = "order.report", matches = ".+")
@SpringBootTest(properties = {"server.port=0"})
class OrderCutReportTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        Path copy = Files.createTempDirectory("order-report").resolve("copy.db");
        Files.copy(Paths.get(System.getProperty("order.report")), copy);
        r.add("planner.db-path", copy::toString);
    }

    @Autowired
    CalculatorService calc;
    @Autowired
    BuildRepository builds;

    @Test
    void report() {
        String name = System.getProperty("order.build", "Big Carry v1");
        BuildRepository.Build b = builds.findBuild(builds.findBuildSummaries().stream().filter(x -> x.name.equals(name)).findFirst()
                .orElseThrow().id).orElseThrow();
        List<Long> items = Arrays.stream(System.getProperty("order.items").split(",")).map(Long::valueOf).collect(Collectors.toList());
        long boots = Long.parseLong(System.getProperty("order.boots"));
        CalculatorService.Request cr = new CalculatorService.Request();
        cr.build = b;
        cr.candidates = new ArrayList<>(items);
        cr.required = new ArrayList<>(items);
        cr.length = items.size();
        cr.budget = Integer.parseInt(System.getProperty("order.budget", "2000"));
        cr.beam = 3;
        cr.boots = new ArrayList<>(List.of(boots, 168L));
        if (Boolean.getBoolean("order.requireBoots")) {
            cr.required.add(boots);
        }
        CalculatorService.Answer a = calc.compute(cr, null);
        System.out.printf("ORDER evaluations %d truncated %s best %s alternatives %d%n", a.evaluations, a.truncated,
                a.best == null ? null : a.best.order, a.alternatives.size());
        a.alternatives.forEach(x -> System.out.println("ORDER   alt " + x.order));
    }
}
