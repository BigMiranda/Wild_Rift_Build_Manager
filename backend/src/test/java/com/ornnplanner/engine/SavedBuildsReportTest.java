package com.ornnplanner.engine;

import com.ornnplanner.engine.Model.MinutePoint;
import com.ornnplanner.engine.Model.TimelineResult;
import com.ornnplanner.engine.Model.TimelineStep;
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
import java.util.Locale;

/**
 * Runs the planner on every build of a <b>copy</b> of a real database and writes one line per purchase plus the stats
 * at key minutes, to compare patches or rule changes by diffing two runs:
 * {@code ./mvnw test -Dtest=SavedBuildsReportTest -Dplanner.builds.copy=data/ornn-planner.db
 * -Dplanner.builds.patch=7_3d -Dplanner.builds.out=target/builds-7_3d.txt}. The copy is migrated and, when the
 * seed's patch differs, re-imported; the original file is never opened for writing.
 */
@EnabledIfSystemProperty(named = "planner.builds.copy", matches = ".+")
@SpringBootTest(properties = {"server.port=0"})
class SavedBuildsReportTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        Path dir = Files.createTempDirectory("builds-report");
        Path copy = dir.resolve("copy.db");
        Files.copy(Paths.get(System.getProperty("planner.builds.copy")), copy);
        r.add("planner.db-path", copy::toString);
        String p = System.getProperty("planner.builds.patch");
        if (p != null && !p.isEmpty()) {
            for (String[] kv : new String[][]{{"catalog", "loja"}, {"itemEffects", "efeitos_itens"},
                    {"combat", "combate"}, {"runes", "runas"}, {"spells", "feiticos"},
                    {"champions", "campeoes"}, {"kits", "kits"}}) {
                r.add("planner.seed." + kv[0], () -> "classpath:seed/" + kv[1] + "_" + p + ".yml");
            }
        }
    }

    @Autowired
    BuildRepository builds;
    @Autowired
    PlannerService planner;

    @Test
    void writesEveryBuildTimeline() throws IOException {
        StringBuilder out = new StringBuilder();
        for (BuildRepository.Build summary : builds.findBuildSummaries()) {
            BuildRepository.Build b = builds.findBuild(summary.id).orElseThrow();
            TimelineResult r = planner.calculate(b);
            out.append(String.format(Locale.ROOT, "## %d %s (%s)%n", b.id, b.name, r.unitName));
            for (TimelineStep s : r.steps) {
                out.append(String.format(Locale.ROOT, "  %s%-34s %6.2f min  pago %5d%s%n", s.implied ? "  · " : "",
                        (s.sold ? "vende " : "") + s.itemName, s.minute, s.paidCost,
                        ""));
            }
            for (TimelineStep s : r.ignored) {
                out.append("  ignorada: ").append(s.itemName).append('\n');
            }
            for (double m : new double[]{10, 15, 20, 25}) {
                MinutePoint at = null;
                for (MinutePoint p : r.series) {
                    if (p.minute <= m + 1e-9) {
                        at = p;
                    }
                }
                if (at != null && at.total != null) {
                    out.append(String.format(Locale.ROOT, "  aos %2.0f: nv %d, Vida %.0f, Arm %.0f, RM %.0f, DdA %.0f, PdH %.0f%n",
                            m, at.level, at.total.getOrDefault("Vida Máxima", 0.0), at.total.getOrDefault("Armadura", 0.0),
                            at.total.getOrDefault("Resistência Mágica", 0.0), at.total.getOrDefault("Dano de Ataque", 0.0),
                            at.total.getOrDefault("Poder de Habilidade", 0.0)));
                }
            }
        }
        Path file = Paths.get(System.getProperty("planner.builds.out", "target/builds-report.txt"));
        Files.write(file, out.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("builds report -> " + file.toAbsolutePath());
    }
}
