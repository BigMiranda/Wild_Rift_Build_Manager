package com.ornnplanner.engine;

import com.ornnplanner.arena.QuickCompareService;
import com.ornnplanner.arena.QuickCompareService.Card;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.service.PlannerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The planner's quick item comparison on the seed's catalog (a new database, builds not saved). */
@SpringBootTest(properties = {"server.port=0"})
class QuickCompareTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        String db = Files.createTempDirectory("quick-compare").resolve("t.db").toString();
        r.add("planner.db-path", () -> db);
    }

    @Autowired
    QuickCompareService quick;
    @Autowired
    PlannerService planner;

    private final Map<String, Long> ids = new LinkedHashMap<>();

    @BeforeEach
    void names() {
        planner.itemViews().forEach(i -> ids.put(i.name, i.id));
    }

    private BuildRepository.Build build(double gold, String... items) {
        BuildRepository.Build b = new BuildRepository.Build();
        b.name = "teste";
        b.unitCode = "ORNN";
        b.goldPerMin = gold;
        b.xpPerMin = 700;
        b.steps = new ArrayList<>();
        for (String i : items) {
            b.steps.add(new BuildRepository.Step(ids.get(i), true));
        }
        return b;
    }

    private QuickCompareService.Request ask(BuildRepository.Build b, Integer slot, String... candidates) {
        QuickCompareService.Request r = new QuickCompareService.Request();
        r.build = b;
        r.slot = slot;
        for (String c : candidates) {
            r.candidates.add(ids.get(c));
        }
        return r;
    }

    /** The item in the slot is the first card and changes nothing. */
    @Test
    void theCurrentItemIsTheReferenceWithDeltaZero() {
        QuickCompareService.Result r = quick.compare(ask(build(700, "Coração Congelado", "Presságio de Randuin", "Armadura de Espinhos"), 1,
                "Manto da Meia-noite"));
        Card ref = r.cards.get(0);
        assertEquals("Presságio de Randuin", ref.name);
        assertTrue(ref.reference);
        assertEquals(0, ref.nextDelay, 1e-9);
        assertEquals(0, ref.lastDelay, 1e-9);
        assertFalse(r.cards.get(1).reference);
        assertTrue(ref.effectiveMixed > ref.effectiveMagic * 0 && ref.held);
    }

    /** A dearer candidate delays what comes after it by the gold's account. */
    @Test
    void aDearerCandidateDelaysTheNextItemsByTheGold() {
        double gold = 700;
        QuickCompareService.Result r = quick.compare(ask(build(gold, "Presságio de Randuin", "Armadura de Espinhos"), 0,
                "Desespero Eterno"));
        Card current = r.cards.get(0);
        Card dear = r.cards.get(1);
        assertTrue(dear.cost > current.cost);
        assertEquals((double) (dear.cost - current.cost) / gold, dear.lastDelay, 1e-6);
        assertEquals(dear.nextDelay, dear.lastDelay, 1e-9);
    }

    /** Upgraded boots with the gold before 10:00 wait, and say so. */
    @Test
    void upgradedBootsBeforeTenWait() {
        QuickCompareService.Result r = quick.compare(ask(build(1500, "Botas Galvanizadas de Aço", "Coração Congelado"), 0, "Mobilização Blindada"));
        Card boots = r.cards.get(1);
        assertEquals("Mobilização Blindada", boots.name);
        assertEquals(10.0, boots.readyAt, 1e-9);
        assertEquals(10.0, boots.waitedUntil, 1e-9);
        assertNull(r.cards.get(0).waitedUntil);
    }

    /** No suggestion breaks an exclusive group with what the build holds. */
    @Test
    void theSuggestionRespectsExclusiveGroups() {
        Map<Long, com.ornnplanner.engine.Model.ItemDef> items = planner.referenceData().items;
        com.ornnplanner.engine.Model.ItemDef blade = items.get(ids.get("Ruptor Divino"));
        assertTrue(blade.exclusiveGroups.contains("Lâmina Arcana"));
        QuickCompareService.Request q = ask(build(700, "Ruptor Divino", "Colhedor de Essência"), 1);
        // the slot holds a blade: another blade in it clashes with the Sunderer of the first purchase
        q.criterion = "ouro";
        QuickCompareService.Result r = quick.suggest(q);
        assertFalse(r.cards.isEmpty());
        List<String> names = new ArrayList<>();
        for (Card c : r.cards) {
            names.add(c.name);
            assertFalse(items.get(c.itemId).exclusiveGroups.contains("Lâmina Arcana"), c.name);
            assertTrue(c.violations.isEmpty(), c.name);
        }
        System.out.println("suggested: " + names + " in " + Math.round(r.ms) + " ms");
    }
}
