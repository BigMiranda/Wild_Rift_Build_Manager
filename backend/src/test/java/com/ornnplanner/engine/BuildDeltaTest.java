package com.ornnplanner.engine;

import com.ornnplanner.arena.BuildDeltaService;
import com.ornnplanner.arena.BuildDeltaService.Change;
import com.ornnplanner.arena.BuildDeltaService.Delta;
import com.ornnplanner.arena.BuildDeltaService.Source;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Swap and recalculate the whole build" (Rodada 8, parte 0) on the seed's catalog, in a new database. */
@SpringBootTest(properties = {"server.port=0"})
class BuildDeltaTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        String db = Files.createTempDirectory("build-delta").resolve("t.db").toString();
        r.add("planner.db-path", () -> db);
    }

    @Autowired
    BuildDeltaService deltas;
    @Autowired
    QuickCompareService quick;
    @Autowired
    PlannerService planner;

    private final Map<String, Long> ids = new LinkedHashMap<>();

    @BeforeEach
    void names() {
        planner.itemViews().forEach(i -> ids.put(i.name, i.id));
    }

    private BuildRepository.Build build(String... items) {
        BuildRepository.Build b = new BuildRepository.Build();
        b.name = "teste";
        b.unitCode = "ORNN";
        b.goldPerMin = 700;
        b.xpPerMin = 700;
        b.steps = new ArrayList<>();
        for (String i : items) {
            b.steps.add(new BuildRepository.Step(ids.get(i), true));
        }
        return b;
    }

    private Card card(QuickCompareService.Result r, String name) {
        return r.cards.stream().filter(c -> c.name.equals(name)).findFirst().orElseThrow();
    }

    /** Ornn's Living Forge reads every item's stats: swapping one changes the gold of the forge, outside the item. */
    @Test
    void swappingAnItemThatFeedsTheForgeChangesTheOthers() {
        BuildRepository.Build b = build("Coração Congelado", "Presságio de Randuin", "Armadura de Espinhos");
        Delta d = deltas.delta(b, Change.item(1, ids.get("Manto da Meia-noite"), null), 25.0);
        Source forge = d.sources.stream().filter(s -> "forge".equals(s.kind)).findFirst().orElse(null);
        assertNotNull(forge, "the forge's gold changed");
        assertTrue(Math.abs(forge.delta) > 1, "forge Δ " + forge.delta);
        assertTrue(Math.abs(d.othersDelta) > 1, "effect on the other items and runes " + d.othersDelta);
        Source own = d.sources.get(0);
        assertTrue(own.own);
        assertEquals("Manto da Meia-noite", own.name);
    }

    /** The whole build's change is the sum of its sources, and own + others. */
    @Test
    void theWholeDeltaIsTheSumOfTheSources() {
        BuildRepository.Build b = build("Coração Congelado", "Presságio de Randuin", "Armadura de Espinhos");
        Delta d = deltas.delta(b, Change.item(0, ids.get("Couraça do Defunto"), null), 22.0);
        double sum = d.sources.stream().mapToDouble(s -> s.delta).sum();
        assertEquals(d.goldDelta, sum, 1e-6);
        assertEquals(d.goldDelta, d.ownDelta + d.othersDelta, 1e-6);
        assertEquals(d.goldAfter - d.goldBefore, d.goldDelta, 1e-6);
        for (Map.Entry<String, Double> e : d.statsDelta.entrySet()) {
            assertEquals(d.statsAfter.get(e.getKey()) - d.statsBefore.get(e.getKey()), e.getValue(), 1e-9, e.getKey());
        }
    }

    /** An evolved candidate holds the evolved form's stats; its card names the base form, and the base the evolved. */
    @Test
    void anEvolvedCandidateAddsTheEvolvedStats() {
        BuildRepository.Build b = build("Coração Congelado", "Armadura de Espinhos");
        QuickCompareService.Request q = new QuickCompareService.Request();
        q.build = b;
        q.slot = 1;
        q.candidates = List.of(ids.get("Aproximação Invernal"), ids.get("Fimbulwinter"));
        QuickCompareService.Result r = quick.compare(q);
        Card base = card(r, "Aproximação Invernal");
        Card evolved = card(r, "Fimbulwinter");
        assertEquals("base", base.form);
        assertEquals("evoluido", evolved.form);
        assertEquals(ids.get("Fimbulwinter"), base.otherFormId);
        assertNull(evolved.evolvesAt, "the engine does not estimate the evolution: evolved from the purchase");
        Model.ItemDef def = planner.referenceData().items.get(ids.get("Fimbulwinter"));
        int checked = 0;
        for (Model.StatLine l : def.stats) {
            if (l.ratio == null && l.passive == null && !l.conditional && l.valueMax == null && l.value != null) {
                // the card adds the passives on top (Fimbulwinter turns mana into health)
                assertTrue(evolved.statsAtMinute.getOrDefault(l.type, 0.0) >= l.value - 1e-6, l.type);
                checked++;
            }
        }
        assertTrue(checked > 0);
        assertTrue(!base.statsAtMinute.equals(evolved.statsAtMinute), "the two forms add different stats");
    }

    /**
     * The same swap, the same numbers: the quick comparison's card, the delta asked alone (the effectiveness candidates
     * and the rune comparison ask it so) and the calculator's reading of an order (the build's order with the swap).
     */
    @Test
    void everyComparatorGivesTheSameNumberForTheSameSwap() {
        BuildRepository.Build b = build("Coração Congelado", "Presságio de Randuin", "Armadura de Espinhos");
        long candidate = ids.get("Manto da Meia-noite");
        QuickCompareService.Request q = new QuickCompareService.Request();
        q.build = b;
        q.slot = 1;
        q.candidates = List.of(candidate);
        q.minute = 24.0;
        Card c = card(quick.compare(q), "Manto da Meia-noite");

        QuickCompareService.DeltaRequest alone = new QuickCompareService.DeltaRequest();
        alone.build = b;
        alone.change = Change.item(1, candidate, null);
        alone.minute = 24.0;
        Delta asked = quick.delta(alone);

        List<Long> order = new ArrayList<>();
        b.steps.forEach(s -> order.add(s.itemId));
        order.set(1, candidate);
        Delta byOrder = deltas.delta(b, Change.order(order), 24.0);

        for (Delta d : List.of(asked, byOrder)) {
            assertEquals(c.delta.goldDelta, d.goldDelta, 1e-6);
            assertEquals(c.delta.statsDelta, d.statsDelta);
            assertEquals(c.delta.effectiveMixedAfter, d.effectiveMixedAfter, 1e-6);
        }
        assertEquals(c.delta.ownDelta, asked.ownDelta, 1e-6);
    }
}
