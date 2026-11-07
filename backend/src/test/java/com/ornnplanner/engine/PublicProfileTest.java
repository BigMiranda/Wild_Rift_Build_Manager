package com.ornnplanner.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ornnplanner.publico.PublicApiFilter;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.service.PlannerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The public version (Rodada 11, part 1): every admin and write endpoint answers 404 as if it did not exist, the
 * reading and the light calculations answer, other sites are refused, an IP past its calculations per minute is told to
 * try later, and the database is opened read-only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"planner.public.compute-per-minute=20"})
@ActiveProfiles("publico")
class PublicProfileTest {

    static long buildId;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        Path db = PublicTestSupport.prepare("publico", ctx -> {
            PlannerService planner = ctx.getBean(PlannerService.class);
            BuildRepository builds = ctx.getBean(BuildRepository.class);
            long folder = builds.findFolders().get(0).id;
            buildId = builds.insertBuild(PublicTestSupport.build(planner, folder, "Pública",
                    "Coração Congelado", "Presságio de Randuin"));
        });
        r.add("planner.db-path", db::toString);
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void everyEndpointOutsideThePublicListAnswers404() {
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (String[] e : PublicTestSupport.endpoints()) {
            if (PublicTestSupport.publicEndpoint(e[0], e[1])) {
                continue;
            }
            checked++;
            String body = "GET".equals(e[0]) || "DELETE".equals(e[0]) ? null : "{}";
            HttpResponse<String> r = PublicTestSupport.call(port, e[0], PublicTestSupport.concrete(e[1]), body,
                    "X-Forwarded-For", "192.0.2.1");
            if (r.statusCode() != 404) {
                wrong.add(e[0] + " " + e[1] + " → " + r.statusCode());
            }
        }
        assertTrue(checked > 100, "the scan found the admin and write endpoints: " + checked);
        assertTrue(wrong.isEmpty(), "answered in the public version: " + wrong);
    }

    @Test
    void theAdminEndpointsAreNotEvenThere() {
        for (String[] e : PublicTestSupport.endpoints()) {
            if (e[1].startsWith("/api/admin")) {
                HttpResponse<String> r = PublicTestSupport.call(port, e[0], PublicTestSupport.concrete(e[1]),
                        "GET".equals(e[0]) ? null : "{}", "X-Forwarded-For", "192.0.2.2");
                assertEquals(404, r.statusCode(), e[0] + " " + e[1]);
                assertTrue(r.body().contains(PublicApiFilter.NOT_HERE), r.body());
            }
        }
    }

    @Test
    void theReadingAndThePlannerAnswer() throws Exception {
        String ip = "192.0.2.3";
        HttpResponse<String> info = PublicTestSupport.call(port, "GET", "/api/public/info", null, "X-Forwarded-For", ip);
        assertEquals(200, info.statusCode());
        assertTrue(json.readTree(info.body()).path("public").asBoolean());
        assertEquals(200, PublicTestSupport.call(port, "GET", "/api/meta", null, "X-Forwarded-For", ip).statusCode());
        HttpResponse<String> build = PublicTestSupport.call(port, "GET", "/api/builds/" + buildId, null, "X-Forwarded-For", ip);
        assertEquals(200, build.statusCode());
        HttpResponse<String> calc = PublicTestSupport.call(port, "POST", "/api/calculate", build.body(), "X-Forwarded-For", ip);
        assertEquals(200, calc.statusCode(), calc.body());
        JsonNode steps = json.readTree(calc.body()).path("steps");
        assertEquals(2, steps.size());
    }

    @Test
    void anotherSiteIsRefused() {
        HttpResponse<String> r = PublicTestSupport.call(port, "GET", "/api/meta", null,
                "Origin", "https://outro-site.example", "X-Forwarded-For", "192.0.2.4");
        assertEquals(403, r.statusCode());
    }

    @Test
    void pastItsCalculationsPerMinuteAnIpIsToldToTryLater() {
        String ip = "198.51.100.7";
        int last = 0;
        for (int i = 0; i < 21; i++) {
            last = PublicTestSupport.call(port, "POST", "/api/calculate", "{}", "X-Forwarded-For", ip).statusCode();
            if (i < 20) {
                assertFalse(last == 429, "request " + (i + 1) + " is within the limit");
            }
        }
        assertEquals(429, last);
        // another IP is not affected
        assertEquals(200, PublicTestSupport.call(port, "GET", "/api/meta", null, "X-Forwarded-For", "198.51.100.8").statusCode());
    }

    @Test
    void theDatabaseIsReadOnly() {
        assertThrows(Exception.class, () -> jdbc.update("INSERT INTO folder (name, created_at) VALUES ('x', 'y')"));
    }
}
