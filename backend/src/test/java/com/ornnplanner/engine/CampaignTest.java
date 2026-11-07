package com.ornnplanner.engine;

import com.ornnplanner.arena.discovery.CampaignService;
import com.ornnplanner.arena.discovery.DiscoveryModel;
import com.ornnplanner.repo.DiscoveryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The campaigns' queue (Rodada 9, parts 6 and 8), with a tiny budget, in a new database. */
@SpringBootTest(properties = {"server.port=0"})
class CampaignTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        String db = Files.createTempDirectory("campaign").resolve("t.db").toString();
        r.add("planner.db-path", () -> db);
    }

    @Autowired
    CampaignService campaigns;
    @Autowired
    DiscoveryRepository repo;

    static CampaignService.Request tiny(List<String> units) {
        CampaignService.Request r = new CampaignService.Request();
        r.name = "teste";
        r.units = units;
        r.discovery.runes = false;
        r.discovery.order = false;
        DiscoveryModel.Budget b = new DiscoveryModel.Budget();
        b.shortlist = 8;
        b.jokers = 1;
        b.search.population = 3;
        b.search.generations = 1;
        b.search.children = 4;
        b.search.fullPerGeneration = 1;
        b.search.confirm = 1;
        b.search.synergyPairs = 0;
        b.orderBudget = 0;
        r.discovery.custom = b;
        return r;
    }

    @Test
    void aCampaignRunsItsChampionsAndSkipsTheLowConfidenceOnes() throws InterruptedException {
        CampaignService.Request r = tiny(List.of("ORNN", "AHRI"));
        CampaignService.Estimate e = campaigns.estimate(r);
        assertEquals(1, e.champions, "Ahri has no sheet: skipped");
        assertEquals(1, e.lowConfidence);
        List<DiscoveryRepository.DiscoveryRow> rows = campaigns.enqueue(r);
        assertEquals("pulado", rows.stream().filter(x -> "AHRI".equals(x.unitCode)).findFirst().orElseThrow().status);
        long ornn = rows.stream().filter(x -> "ORNN".equals(x.unitCode)).findFirst().orElseThrow().id;
        long t0 = System.currentTimeMillis();
        while (!"feito".equals(repo.discovery(ornn).status) && System.currentTimeMillis() - t0 < 600_000) {
            assertTrue(!"erro".equals(repo.discovery(ornn).status), repo.discovery(ornn).error);
            Thread.sleep(500);
        }
        DiscoveryRepository.DiscoveryRow done = repo.discovery(ornn);
        assertEquals("feito", done.status);
        assertEquals("fim", done.step);
        CampaignService.ChampionResult res = campaigns.latest("ORNN");
        assertNotNull(res);
        assertTrue(res.syntheticBase, "no saved build: the champion alone is the base");
        assertNotNull(res.discovery.standard);
        // the same champion and configuration again: no new row, the done one stands
        List<DiscoveryRepository.DiscoveryRow> again = campaigns.enqueue(r);
        assertEquals(ornn, again.stream().filter(x -> "ORNN".equals(x.unitCode)).findFirst().orElseThrow().id);
        assertEquals(1, campaigns.estimate(r).alreadyDone);
    }
}
