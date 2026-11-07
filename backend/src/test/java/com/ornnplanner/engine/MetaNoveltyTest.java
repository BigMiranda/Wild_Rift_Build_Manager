package com.ornnplanner.engine;

import com.ornnplanner.api.MetaBuildController;
import com.ornnplanner.arena.discovery.DiscoveryModel;
import com.ornnplanner.arena.discovery.DiscoveryService;
import com.ornnplanner.repo.DiscoveryRepository;
import com.ornnplanner.service.PlannerService;
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

/** Rodada 9, part 5: the meta builds (the new tables, the text paste) and the novelty against the known builds. */
@SpringBootTest(properties = {"server.port=0"})
class MetaNoveltyTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        String db = Files.createTempDirectory("meta").resolve("t.db").toString();
        r.add("planner.db-path", () -> db);
    }

    @Autowired
    MetaBuildController meta;
    @Autowired
    DiscoveryRepository repo;
    @Autowired
    PlannerService planner;

    private long id(String name) {
        return planner.itemViews().stream().filter(i -> i.name.equals(name)).findFirst().orElseThrow().id;
    }

    @Test
    void aPastedTextBecomesAMetaBuild() {
        MetaBuildController.Paste p = new MetaBuildController.Paste();
        p.unitCode = "ORNN";
        p.name = "Site X";
        p.source = "site X, outubro/2026";
        p.text = "1. coracao de aco, Armadura de Espinhos → Passos de Mercúrio / Aperto dos Mortos-Vivos; Item Que Não Existe";
        MetaBuildController.Parsed out = meta.parse(p);
        assertEquals(List.of(id("Coração de Aço"), id("Armadura de Espinhos")), out.build.items);
        assertEquals(id("Passos de Mercúrio"), out.build.boots);
        assertEquals("Aperto dos Mortos-Vivos", out.build.keystone);
        assertEquals(List.of("Item Que Não Existe"), out.unknown);
        DiscoveryRepository.MetaBuild saved = meta.create(out.build);
        assertNotNull(saved.id);
        assertEquals(1, repo.metaBuilds("ORNN").size());
        assertEquals(0, repo.metaBuilds("DARIUS").size());
    }

    @Test
    void noveltyIsTheDistanceToTheClosestKnownBuild() {
        DiscoveryModel.KitView a = new DiscoveryModel.KitView();
        a.items = List.of(1L, 2L, 3L, 4L, 5L);
        a.boots = 9L;
        a.keystone = "K1";
        DiscoveryModel.KitView same = new DiscoveryModel.KitView();
        same.items = List.of(1L, 2L, 3L, 4L, 5L);
        same.boots = 9L;
        same.keystone = "K1";
        assertEquals(0, DiscoveryService.novelty(a, same, 0.15), 1e-9);
        DiscoveryModel.KitView two = new DiscoveryModel.KitView();
        two.items = List.of(1L, 2L, 3L, 4L, 7L, 8L);
        two.boots = 9L;
        two.keystone = "K2";
        // shared 5 of 8 (1, 2, 3, 4, 9) -> 1 - 5/8, plus the keystone step
        assertEquals(1 - 5.0 / 8 + 0.15, DiscoveryService.novelty(a, two, 0.15), 1e-9);
        assertTrue(DiscoveryService.novelty(a, two, 0.9) <= 1);
    }
}
