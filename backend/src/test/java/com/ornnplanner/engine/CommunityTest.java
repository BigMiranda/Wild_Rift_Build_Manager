package com.ornnplanner.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ornnplanner.publico.CommunityService;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.service.PlannerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The builds the public version's visitors share (Rodada 11, part 4): nothing without consent, "apagar o que enviei"
 * erases, an invalid build is refused, a duplicate is one more vote, the sends are limited, and the light election
 * measures the voted builds and hands the candidates only to the administrator's token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "planner.community.per-anon-per-hour=6", "planner.community.per-ip-per-hour=100",
        "planner.community.min-votes=2", "planner.community.interval-minutes=0",
        "planner.community.admin-token=segredo-de-teste"})
@ActiveProfiles("publico")
class CommunityTest {

    static Path communityDb;
    static ObjectNode build;
    static ObjectNode other;
    static ObjectNode invalid;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        ObjectMapper json = new ObjectMapper();
        Path db = PublicTestSupport.prepare("comunidade", ctx -> {
            PlannerService planner = ctx.getBean(PlannerService.class);
            build = json.valueToTree(PublicTestSupport.build(planner, 1, "Minha", "Coração Congelado", "Presságio de Randuin"));
            other = json.valueToTree(PublicTestSupport.build(planner, 1, "Outra", "Armadura de Espinhos"));
            invalid = json.valueToTree(PublicTestSupport.build(planner, 1, "Duas iguais", "Coração Congelado", "Coração Congelado"));
        });
        try {
            communityDb = Files.createTempDirectory("comunidade").resolve("comunidade.db");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        r.add("planner.db-path", db::toString);
        r.add("planner.community.db-path", communityDb::toString);
    }

    @LocalServerPort
    int port;
    @Autowired
    CommunityService community;

    private final ObjectMapper json = new ObjectMapper();

    private HttpResponse<String> send(String anon, boolean consent, JsonNode b, String ip) throws Exception {
        ObjectNode s = json.createObjectNode();
        s.put("anonId", anon);
        s.put("consent", consent);
        s.set("build", b);
        return PublicTestSupport.call(port, "POST", "/api/community/builds", json.writeValueAsString(s), "X-Forwarded-For", ip);
    }

    private int votes() {
        return community.stats().get("votes") instanceof Number ? ((Number) community.stats().get("votes")).intValue() : -1;
    }

    @Test
    void withoutConsentNothingIsKept() throws Exception {
        long before = ((Number) community.stats().get("builds")).longValue();
        HttpResponse<String> r = send(UUID.randomUUID().toString(), false, build, "198.51.100.30");
        assertEquals(403, r.statusCode(), r.body());
        assertTrue(r.body().contains(CommunityService.NO_CONSENT));
        assertEquals(before, ((Number) community.stats().get("builds")).longValue());
    }

    @Test
    void anInvalidBuildIsRefused() throws Exception {
        HttpResponse<String> r = send(UUID.randomUUID().toString(), true, invalid, "198.51.100.31");
        assertEquals(400, r.statusCode(), r.body());
        ObjectNode unknown = build.deepCopy();
        unknown.put("unitCode", "NAO_EXISTE");
        assertEquals(400, send(UUID.randomUUID().toString(), true, unknown, "198.51.100.31").statusCode());
        assertEquals(400, send("nao-e-um-uuid", true, build, "198.51.100.31").statusCode());
    }

    @Test
    void aDuplicateIsOneMoreVoteAndForgettingErasesWhatWasSent() throws Exception {
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        JsonNode first = json.readTree(send(a, true, other, "198.51.100.32").body());
        assertTrue(first.path("counted").asBoolean());
        assertEquals(1, first.path("votes").asInt());
        long builds = ((Number) community.stats().get("builds")).longValue();
        // the same build, renamed and from another visitor: the name does not count, it is one more vote
        ObjectNode renamed = other.deepCopy();
        renamed.put("name", "Outro nome");
        renamed.put("note", "nota pessoal");
        JsonNode second = json.readTree(send(b, true, renamed, "198.51.100.33").body());
        assertEquals(first.path("fingerprint").asText(), second.path("fingerprint").asText());
        assertEquals(2, second.path("votes").asInt());
        assertEquals(builds, ((Number) community.stats().get("builds")).longValue(), "no new row");
        // the same visitor again: no second vote
        JsonNode again = json.readTree(send(b, true, other, "198.51.100.33").body());
        assertFalse(again.path("counted").asBoolean());
        assertEquals(2, again.path("votes").asInt());
        // nothing personal is kept: no name, no note, no IP
        Path wal = communityDb.resolveSibling(communityDb.getFileName() + "-wal");
        String stored = Files.readString(communityDb, StandardCharsets.ISO_8859_1)
                + (Files.exists(wal) ? Files.readString(wal, StandardCharsets.ISO_8859_1) : "");
        assertTrue(stored.contains("ORNN"), "the check reads where the rows are");
        assertFalse(stored.contains("nota pessoal") || stored.contains("Outro nome") || stored.contains("198.51.100.33"));

        // "apagar o que enviei"
        HttpResponse<String> gone = PublicTestSupport.call(port, "DELETE", "/api/community/mine", null,
                "X-Anon-Id", b, "X-Forwarded-For", "198.51.100.33");
        assertEquals(200, gone.statusCode(), gone.body());
        assertEquals(1, json.readTree(gone.body()).path("removed").asInt());
        assertEquals(1, json.readTree(send(a, true, other, "198.51.100.32").body()).path("votes").asInt());
        PublicTestSupport.call(port, "DELETE", "/api/community/mine", null, "X-Anon-Id", a, "X-Forwarded-For", "198.51.100.32");
        assertEquals(builds - 1, ((Number) community.stats().get("builds")).longValue(), "a build nobody sent any more goes");
    }

    @Test
    void theSendsOfAVisitorAreLimited() throws Exception {
        String anon = UUID.randomUUID().toString();
        int last = 0;
        for (int i = 0; i < 7; i++) {
            last = send(anon, true, build, "198.51.100.34").statusCode();
            if (i < 6) {
                assertEquals(200, last, "send " + (i + 1));
            }
        }
        assertEquals(429, last);
    }

    @Test
    void theElectionMeasuresTheVotedBuildsAndOnlyTheTokenSeesTheCandidates() throws Exception {
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        send(a, true, build, "198.51.100.35");
        send(b, true, build, "198.51.100.36");
        assertTrue(community.elect() >= 1, "the voted build was measured");
        assertEquals(0, community.elect(), "cached: measured once per patch");
        assertEquals(404, PublicTestSupport.call(port, "GET", "/api/community/candidates", null, "X-Forwarded-For", "198.51.100.37").statusCode());
        assertEquals(404, PublicTestSupport.call(port, "GET", "/api/community/candidates", null,
                "X-Admin-Token", "errado", "X-Forwarded-For", "198.51.100.37").statusCode());
        HttpResponse<String> r = PublicTestSupport.call(port, "GET", "/api/community/candidates", null,
                "X-Admin-Token", "segredo-de-teste", "X-Forwarded-For", "198.51.100.37");
        assertEquals(200, r.statusCode(), r.body());
        JsonNode c = json.readTree(r.body()).path("candidates");
        assertEquals(1, c.size());
        assertTrue(c.get(0).path("votes").asInt() >= 2);
        assertTrue(c.get(0).path("score").isNumber());
        assertEquals("ORNN", c.get(0).path("unitCode").asText());
        // the approved guides come with the export: none yet
        assertEquals(0, json.readTree(PublicTestSupport.call(port, "GET", "/api/community/guides", null,
                "X-Forwarded-For", "198.51.100.37").body()).size());
        assertTrue(votes() >= 2);
    }
}
