package com.ornnplanner.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ornnplanner.arena.discovery.CampaignService;
import com.ornnplanner.arena.discovery.DiscoveryModel;
import com.ornnplanner.publico.PublicExporter;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.repo.DiscoveryRepository;
import com.ornnplanner.service.PlannerService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rodada 11.b, part 1: a discovery that knows a public and a private build. Exported, no name nor id of the private
 * build (nor of its folder) is left anywhere in the file; the public and meta known builds stay; the private base
 * becomes "build base (privada)"; guides and combos that named it are swept too.
 */
class PublicDiscoveryPrivacyTest {

    private static final String SECRET = "Segredo Zirconio";
    private static final String SECRET_FOLDER = "Pasta Escondida Quartzo";
    private static final String OTHER = "Outra Oculta Basalto";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void noPrivateBuildLeavesThroughTheDiscoveryGuidesOrCombos() throws Exception {
        long[] ids = new long[3];
        Path[] out = new Path[1];
        PublicExporter.Report[] rep = new PublicExporter.Report[1];
        PublicTestSupport.prepare("privacidade", ctx -> {
            PlannerService planner = ctx.getBean(PlannerService.class);
            BuildRepository builds = ctx.getBean(BuildRepository.class);
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
            long open = builds.insertFolder("Aberta");
            builds.markFolderPublic(open, true);
            long closed = builds.insertFolder(SECRET_FOLDER);
            // the private one first: it is the champion's base of a campaign (its first saved build by name)
            ids[1] = builds.insertBuild(PublicTestSupport.build(planner, closed, "A " + SECRET, "Armadura de Espinhos"));
            ids[0] = builds.insertBuild(PublicTestSupport.build(planner, open, "Ornn publica", "Coração Congelado", "Presságio de Randuin"));
            ids[2] = builds.insertBuild(PublicTestSupport.build(planner, closed, OTHER, "Coração Congelado"));
            String patch = ctx.getBean(com.ornnplanner.seed.ChampionCatalog.class).patch();

            CampaignService.ChampionResult res = new CampaignService.ChampionResult();
            res.saved.add(Map.of("buildId", ids[0], "name", "Ornn publica", "score", 70.0));
            res.saved.add(Map.of("buildId", ids[1], "name", "A " + SECRET, "score", 60.0));
            DiscoveryModel.Result d = new DiscoveryModel.Result();
            d.champion = "Ornn";
            d.known.add(known("Ornn publica", "salva", itemsOf(builds, ids[0])));
            d.known.add(known("A " + SECRET, "salva", itemsOf(builds, ids[1])));
            d.known.add(known("Meta do site (site X, 2026)", "meta", List.of()));
            DiscoveryModel.KitView found = known(null, "busca", itemsOf(builds, ids[0]));
            found.closestKnown = "A " + SECRET;
            d.standard = found;
            d.ranked.add(found);
            d.niches.put("durabilidade", found);
            DiscoveryModel.Finding f = new DiscoveryModel.Finding();
            f.kit = found;
            f.against = "A " + SECRET;
            f.text = "troca X por Y: 72 contra 60 de A " + SECRET + "; ganha em Bruiser +3";
            d.discoveries.add(f);
            d.notes.add("Base: A " + SECRET + " · descoberta");
            res.discovery = d;
            res.matchups.add(Map.of("rival", "Darius", "build", OTHER, "mean", 12.0));
            res.matchups.add(Map.of("rival", "Ornn", "build", "Ornn publica", "mean", 1.0));

            DiscoveryRepository.DiscoveryRow row = new DiscoveryRepository.DiscoveryRow();
            row.patch = patch;
            row.unitCode = "ORNN";
            row.champion = "Ornn";
            row.configKey = "ORNN|teste";
            row.config = "{\"discovery\":{\"buildId\":" + ids[1] + ",\"build\":{\"name\":\"A " + SECRET + "\"}}}";
            row.status = "feito";
            row.step = "fim";
            row.campaign = "Campanha de " + OTHER;
            try {
                row.result = JSON.writeValueAsString(res);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            ctx.getBean(DiscoveryRepository.class).insertDiscovery(row);

            // a public guide suggested from the discovery, and a combo found on the private build
            String now = Instant.now().toString();
            jdbc.update("INSERT INTO build_guide (unit_code, name, tree, created_at, updated_at, publico) VALUES (?,?,?,?,?,1)",
                    "ORNN", "Guia da descoberta", "{\"note\":\"melhor que A " + SECRET + "\",\"buildId\":" + ids[1] + ",\"then\":{\"buildId\":" + ids[0] + "}}", now, now);
            jdbc.update("INSERT INTO arena_combo (champion, name, origin, active, build, build_id, data, created_at, updated_at) VALUES (?,?,?,1,?,?,?,?,?)",
                    "Ornn", "Combo de " + OTHER, "descoberto", OTHER, ids[2], "{\"from\":\"" + OTHER + "\"}", now, now);
            try {
                out[0] = Files.createTempDirectory("publica-privacidade").resolve("publico.db");
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            rep[0] = ctx.getBean(PublicExporter.class).export(out[0]);
        });

        // the whole file, byte by byte (and the WAL, if any)
        String bytes = new String(Files.readAllBytes(out[0]), StandardCharsets.ISO_8859_1)
                + new String(Files.readAllBytes(out[0]), StandardCharsets.UTF_8);
        for (String name : List.of(SECRET, OTHER, SECRET_FOLDER, "Zirconio", "Basalto", "Quartzo")) {
            assertFalse(bytes.contains(name), name + " is in the exported file");
        }
        assertTrue(rep[0].excluded.stream().anyMatch(e -> e.startsWith("das Descobertas")), rep[0].excluded.toString());

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + out[0]); Statement st = c.createStatement()) {
            // no table holds a private build id where a build id goes
            for (String[] q : new String[][] {{"build_discovery", "config"}, {"build_discovery", "result"}, {"build_guide", "tree"},
                    {"arena_combo", "data"}}) {
                try (ResultSet rs = st.executeQuery("SELECT " + q[1] + " FROM " + q[0])) {
                    while (rs.next()) {
                        List<Long> found = new ArrayList<>();
                        collectIds(JSON.readTree(rs.getString(1)), found);
                        assertFalse(found.contains(ids[1]) || found.contains(ids[2]), q[0] + "." + q[1] + ": " + rs.getString(1));
                    }
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM arena_combo WHERE build_id IS NOT NULL")) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
            JsonNode r;
            try (ResultSet rs = st.executeQuery("SELECT result FROM build_discovery")) {
                assertTrue(rs.next());
                r = JSON.readTree(rs.getString(1));
            }
            assertEquals(1, r.path("saved").size(), "only the public saved build stays");
            assertEquals(ids[0], r.path("saved").get(0).path("buildId").asLong());
            JsonNode known = r.path("discovery").path("known");
            assertEquals(2, known.size(), "the public and the meta known builds stay: " + known);
            assertEquals("Ornn publica", known.get(0).path("label").asText());
            assertEquals("meta", known.get(1).path("origin").asText());
            assertEquals("build base (privada)", r.path("discovery").path("standard").path("closestKnown").asText());
            assertEquals("build base (privada)", r.path("discovery").path("discoveries").get(0).path("against").asText());
            assertTrue(r.path("discovery").path("discoveries").get(0).path("text").asText().contains("60 de build base (privada)"));
            assertEquals("build (privada)", r.path("matchups").get(0).path("build").asText());
            assertEquals("Darius", r.path("matchups").get(0).path("rival").asText());
            assertEquals("Ornn publica", r.path("matchups").get(1).path("build").asText());
            try (ResultSet rs = st.executeQuery("SELECT config FROM build_discovery")) {
                rs.next();
                JsonNode cfg = JSON.readTree(rs.getString(1));
                assertTrue(cfg.path("discovery").path("build").isNull());
                assertTrue(cfg.path("discovery").path("buildId").isNull());
            }
            try (ResultSet rs = st.executeQuery("SELECT tree FROM build_guide")) {
                rs.next();
                JsonNode tree = JSON.readTree(rs.getString(1));
                assertNotNull(tree);
                assertEquals(ids[0], tree.path("then").path("buildId").asLong(), "a public build id stays");
            }
        }
    }

    private static DiscoveryModel.KitView known(String label, String origin, List<Long> items) {
        DiscoveryModel.KitView k = new DiscoveryModel.KitView();
        k.label = label;
        k.origin = origin;
        k.items = new ArrayList<>(items);
        k.score = 60;
        return k;
    }

    private static List<Long> itemsOf(BuildRepository builds, long id) {
        List<Long> out = new ArrayList<>();
        builds.findBuild(id).orElseThrow().steps.forEach(s -> out.add(s.itemId));
        return out;
    }

    private static void collectIds(JsonNode n, List<Long> out) {
        if (n == null) {
            return;
        }
        n.fields().forEachRemaining(e -> {
            if (e.getKey().toLowerCase().contains("buildid") || e.getKey().equals("build_id")) {
                if (e.getValue().canConvertToLong()) {
                    out.add(e.getValue().asLong());
                }
            }
            collectIds(e.getValue(), out);
        });
        if (n.isArray()) {
            n.forEach(x -> collectIds(x, out));
        }
    }
}
