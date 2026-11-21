package com.ornnplanner.engine;

import com.ornnplanner.arena.ArenaService;
import com.ornnplanner.arena.AutoBrain;
import com.ornnplanner.arena.BrainDefaults;
import com.ornnplanner.arena.league.AiCampaignService;
import com.ornnplanner.arena.league.LeagueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rodada 21, parts 4 and 5: the mass training campaign's order and table, and the brains of the processes. */
@SpringBootTest(properties = {"server.port=0"})
class AiCampaignTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        String db = Files.createTempDirectory("ai-campaign").resolve("t.db").toString();
        r.add("planner.db-path", () -> db);
    }

    @Autowired
    AiCampaignService campaign;
    @Autowired
    LeagueService league;

    @Test
    void classesFirstThenEveryChampionWithASheet() {
        List<String> order = campaign.order(new AiCampaignService.Request());
        int classes = league.bankSettings().classes.size();
        for (int i = 0; i < classes; i++) {
            assertTrue(order.get(i).startsWith(AutoBrain.CLASS_PREFIX), order.get(i));
        }
        assertEquals(classes + league.withSheet().size(), order.size());
        // with no builds campaign: by presence in the meta, then A/B sheets before C/D
        List<String> champions = order.subList(classes, order.size());
        for (int i = 1; i < champions.size(); i++) {
            String a = champions.get(i - 1);
            String b = champions.get(i);
            assertTrue(league.presence(a) >= league.presence(b), a + " antes de " + b);
            if (league.presence(a) == league.presence(b)) {
                assertTrue(rank(a) <= rank(b), "A/B antes de C/D: " + a + " antes de " + b);
            }
        }
        // with no league AI yet, every owner needs training, and the table says untrained
        assertEquals("sem IA da liga", campaign.needs(order.get(0)));
        List<AiCampaignService.ChampionAi> table = campaign.table();
        assertEquals(league.withSheet().size(), table.size());
        assertTrue(table.stream().allMatch(c -> "sem_treino".equals(c.status)));
        assertEquals(league.withSheet().size(), campaign.estimate(List.of(), "rapido").championsAll);
    }

    private int rank(String champion) {
        String q = league.quality(champion);
        return "A".equals(q) || "B".equals(q) ? 0 : 1;
    }

    @Test
    void everyPlayerProcessIsAutomaticAndTheRagdollsStand() {
        for (String p : List.of("arena", "testar_na_arena", "partida", "campanha", "descoberta", "calculadora", "atlas",
                "draft_tabelas", "primeiro_encontro", "builds_provaveis", "modelos_do_mapa")) {
            assertEquals(AutoBrain.AUTO, BrainDefaults.of(p), p);
        }
        assertEquals(ArenaService.STAND, BrainDefaults.of("efetividade"));
    }
}
