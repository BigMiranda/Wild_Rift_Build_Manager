package com.ornnplanner.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ornnplanner.arena.discovery.DiscoveryModel;
import com.ornnplanner.arena.discovery.DiscoveryService;
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

/**
 * The build discovery on a <b>copy</b> of a database (report style, opt-in):
 * {@code ./mvnw test -Dtest=DiscoveryReportTest -Ddiscovery.report=data/ornn-planner.db -Ddiscovery.builds="Big Carry v1"
 * -Ddiscovery.budget=rapido}. Writes target/discovery-&lt;build&gt;-&lt;budget&gt;.json and prints a summary.
 */
@EnabledIfSystemProperty(named = "discovery.report", matches = ".+")
@SpringBootTest(properties = {"server.port=0"})
class DiscoveryReportTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        Path copy = Files.createTempDirectory("discovery-report").resolve("copy.db");
        Files.copy(Paths.get(System.getProperty("discovery.report")), copy);
        r.add("planner.db-path", copy::toString);
    }

    @Autowired
    DiscoveryService discovery;
    @Autowired
    BuildRepository builds;

    @Test
    void report() throws IOException {
        String budget = System.getProperty("discovery.budget", DiscoveryModel.QUICK);
        // kits measured at the same time (Funnel.parallel): 1 = one at a time, to compare with the parallel run
        if (System.getProperty("discovery.parallel") != null) {
            com.ornnplanner.arena.discovery.Funnel.parallel = Integer.parseInt(System.getProperty("discovery.parallel"));
        }
        ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        for (String name : System.getProperty("discovery.builds", "Big Carry v1").split(",")) {
            BuildRepository.Build b = builds.findBuildSummaries().stream().filter(x -> x.name.equals(name.trim())).findFirst().orElseThrow();
            DiscoveryModel.Request r = new DiscoveryModel.Request();
            r.buildId = b.id;
            r.budget = budget;
            DiscoveryModel.Estimate est = discovery.estimate(r);
            DiscoveryModel.Result res = discovery.run(r, null);
            Files.writeString(Paths.get("target/discovery-" + name.trim().replace(' ', '_') + "-" + budget
                    + System.getProperty("discovery.suffix", "") + ".json"), json.writeValueAsString(res));
            System.out.printf("DISCOVERY %s (%s, %s, quality %s): %.0f s (estimated %.0f s); %d quick, %d full, %d confirm, %d order; surrogate r = %s%n",
                    name, res.champion, res.championClass, res.quality, res.seconds, est.seconds, res.quickRuns, res.fullRuns,
                    res.confirmRuns, res.orderMeasures, res.surrogateCorrelation);
            for (DiscoveryModel.KitView k : res.ranked.subList(0, Math.min(5, res.ranked.size()))) {
                System.out.printf("DISCOVERY   %.1f (mean %.1f, P20 %.1f, conf %s) %s + %s [%s] %s%n", k.score, k.mean, k.p20, k.confirmed, k.names,
                        k.bootsName, k.niche, k.origin);
            }
            for (DiscoveryModel.KitView k : res.known) {
                System.out.printf("DISCOVERY   conhecida %s + %s%n", k.names, k.bootsName);
            }
            res.niches.forEach((n, k) -> System.out.printf("DISCOVERY   nicho %s: %.1f %s%n", n, k.score, k.names));
            res.synergies.stream().limit(5).forEach(s -> System.out.printf("DISCOVERY   sinergia %+.1f %s%n", s.value, s.explain));
            res.discoveries.forEach(f -> System.out.printf("DISCOVERY   descoberta %s%n", f.text));
            res.notes.forEach(n -> System.out.printf("DISCOVERY   nota %s%n", n));
        }
    }
}
