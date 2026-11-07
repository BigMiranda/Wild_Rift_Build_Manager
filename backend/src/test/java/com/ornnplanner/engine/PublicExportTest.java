package com.ornnplanner.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.ArenaService;
import com.ornnplanner.arena.guide.GuideService;
import com.ornnplanner.publico.PublicExporter;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.service.PlannerService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The export of the public database (Rodada 11, part 2): only what is marked goes out, and the public version starts on
 * the exported database, opens the planner and runs the quick comparison and the basic effectiveness.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("publico")
class PublicExportTest {

    static long publicBuild;
    static long privateBuild;
    static PublicExporter.Report report;
    static Path exported;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        PublicTestSupport.prepare("exportacao", ctx -> {
            PlannerService planner = ctx.getBean(PlannerService.class);
            BuildRepository builds = ctx.getBean(BuildRepository.class);
            long open = builds.insertFolder("Guias abertas");
            builds.markFolderPublic(open, true);
            long closed = builds.findFolders().stream().filter(f -> f.name.equals("Geral")).findFirst().orElseThrow().id;
            publicBuild = builds.insertBuild(PublicTestSupport.build(planner, open, "Ornn tanque",
                    "Coração Congelado", "Presságio de Randuin"));
            privateBuild = builds.insertBuild(PublicTestSupport.build(planner, closed, "Secreta",
                    "Armadura de Espinhos"));
            ArenaService arena = ctx.getBean(ArenaService.class);
            arena.save(scenario("Pública contra o boneco", publicBuild));
            arena.save(scenario("Com a secreta", privateBuild));
            GuideService guides = ctx.getBean(GuideService.class);
            guides.save(guide("Guia aberta", true));
            guides.save(guide("Guia fechada", false));
            try {
                exported = Files.createTempDirectory("publica").resolve("ornn-planner-publico.db");
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            report = ctx.getBean(PublicExporter.class).export(exported);
        });
        r.add("planner.db-path", exported::toString);
    }

    private static ArenaModel.Scenario scenario(String name, long buildId) {
        ArenaModel.Scenario sc = new ArenaModel.Scenario();
        sc.name = name;
        sc.duration = 20;
        ArenaModel.Slot me = new ArenaModel.Slot();
        me.team = 0;
        me.buildId = buildId;
        ArenaModel.Slot dummy = new ArenaModel.Slot();
        dummy.team = 1;
        dummy.dummy = new ArenaModel.Dummy();
        sc.fighters = new ArrayList<>(List.of(me, dummy));
        return sc;
    }

    private static GuideService.View guide(String name, boolean publico) {
        GuideService.View v = new GuideService.View();
        v.unitCode = "ORNN";
        v.name = name;
        v.publico = publico;
        return v;
    }

    @LocalServerPort
    int port;

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = PublicTestSupport.call(port, "GET", path, null, "X-Forwarded-For", "203.0.113.20");
        assertEquals(200, r.statusCode(), path + ": " + r.body());
        return json.readTree(r.body());
    }

    private JsonNode post(String path, Object body) throws Exception {
        HttpResponse<String> r = PublicTestSupport.call(port, "POST", path, json.writeValueAsString(body), "X-Forwarded-For", "203.0.113.21");
        assertEquals(200, r.statusCode(), path + ": " + r.body());
        return json.readTree(r.body());
    }

    @Test
    void onlyWhatIsMarkedGoesOut() throws Exception {
        assertEquals(List.of("Guias abertas"), report.folders);
        assertEquals(List.of("Ornn tanque"), report.builds);
        assertEquals(List.of("Pública contra o boneco"), report.scenarios);
        assertEquals(1, report.counts.get("build"));
        assertEquals(1, report.counts.get("folder"));
        assertEquals(1, report.counts.get("build_guide"));
        assertEquals(1, report.counts.get("arena_scenario"));
        assertFalse(report.counts.containsKey("arena_dummy"), "tables the public version never reads are dropped");
        assertTrue(report.excluded.stream().anyMatch(e -> e.matches("[1-9][0-9]* build[(]s[)] de pastas não marcadas.*")),
                report.excluded.toString());
        assertTrue(report.sizeBytes > 0 && report.sizeBytes == Files.size(exported));
        assertTrue(Files.exists(exported.resolveSibling("ornn-planner-publico-relatorio.md")));

        JsonNode builds = get("/api/builds");
        assertEquals(1, builds.size());
        assertEquals("Ornn tanque", builds.get(0).path("name").asText());
        assertEquals(404, PublicTestSupport.call(port, "GET", "/api/builds/" + privateBuild, null,
                "X-Forwarded-For", "203.0.113.20").statusCode());
        assertEquals(1, get("/api/folders").size());
        assertEquals(1, get("/api/arena/scenarios").size());
        JsonNode guides = get("/api/guides?unit=ORNN");
        assertEquals(1, guides.size());
        assertEquals("Guia aberta", guides.get(0).path("name").asText());
        assertEquals(report.patch, get("/api/public/info").path("export").path("patch").asText());
    }

    /** With nothing public of a kind (no scenario, say), every row of it goes: "NOT IN ()" keeps nothing. */
    @Test
    void anEmptySetOfPublicIdsDeletesEverything() throws Exception {
        assertEquals("()", PublicExporter.in(java.util.Set.of()));
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:");
             java.sql.Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t (id INTEGER)");
            st.execute("INSERT INTO t VALUES (1), (2)");
            st.execute("DELETE FROM t WHERE id NOT IN " + PublicExporter.in(java.util.Set.of()));
            try (java.sql.ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t")) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @Test
    void thePublicVersionOpensThePlannerAndRunsTheComparisonAndTheBasicEffectiveness() throws Exception {
        JsonNode build = get("/api/builds/" + publicBuild);
        JsonNode timeline = post("/api/calculate", build);
        assertEquals(2, timeline.path("steps").size());
        double minute = timeline.path("steps").get(1).path("minute").asDouble();

        long candidate = 0;
        for (JsonNode item : get("/api/items")) {
            if (item.path("name").asText().equals("Armadura de Espinhos")) {
                candidate = item.path("id").asLong();
            }
        }
        ObjectNode ask = json.createObjectNode();
        ask.set("build", build);
        ask.put("slot", 1);
        ask.putArray("candidates").add(candidate);
        JsonNode compared = post("/api/quick/compare", ask);
        assertTrue(compared.toString().contains("Armadura de Espinhos") || compared.toString().contains(String.valueOf(candidate)));

        JsonNode basic = get("/api/effect/reports/basico");
        ObjectNode req = json.createObjectNode();
        req.put("buildId", publicBuild);
        req.put("minute", minute);
        ArrayNode jobs = req.putArray("jobs");
        for (int i = 0; i < Math.min(2, basic.path("tests").size()); i++) {
            String key = basic.path("tests").get(i).path("key").asText();
            jobs.addObject().put("tag", key).put("testKey", key);
        }
        JsonNode effect = post("/api/effect/evaluate", req);
        assertEquals(jobs.size(), effect.path("outcomes").size());
        for (JsonNode o : effect.path("outcomes")) {
            assertTrue(o.path("error").isMissingNode() || o.path("error").isNull(), o.toString());
            assertTrue(o.path("index").isNumber(), o.toString());
        }

        JsonNode pre = get("/api/public/effect");
        assertEquals(1, pre.size());
        assertEquals(publicBuild, pre.get(0).path("buildId").asLong());
        assertNotNull(pre.get(0).path("index").numberValue());
    }
}
