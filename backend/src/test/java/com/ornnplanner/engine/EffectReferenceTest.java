package com.ornnplanner.engine;

import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.EffectModel;
import com.ornnplanner.arena.EffectTestService;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What an effectiveness index is measured against, on the seed's catalog (a new database, builds not saved). */
@SpringBootTest(properties = {"server.port=0"})
class EffectReferenceTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        String db = Files.createTempDirectory("effect-ref").resolve("t.db").toString();
        r.add("planner.db-path", () -> db);
    }

    @Autowired
    EffectTestService effect;
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
        b.goldPerMin = 1200;
        b.xpPerMin = 800;
        b.steps = new ArrayList<>();
        for (String i : items) {
            b.steps.add(new BuildRepository.Step(ids.get(i), true));
        }
        return b;
    }

    private EffectModel.Outcome run(BuildRepository.Build b, double minute, EffectModel.Test test, String key) {
        EffectModel.Request req = new EffectModel.Request();
        req.build = b;
        req.minute = minute;
        EffectModel.Job job = new EffectModel.Job();
        job.test = test;
        job.testKey = key;
        req.jobs = List.of(job);
        EffectModel.Outcome o = effect.evaluate(req).get(0);
        assertEquals(null, o.error);
        return o;
    }

    /** 🎯 The same build at two minutes of one level, against a fixed preset: the same reference; the build changes. */
    @Test
    void twoMinutesOfOneLevelHaveTheSameReference() {
        BuildRepository.Build b = build("Coração de Aço", "Égide de Fogo Solar", "Armadura de Espinhos");
        EffectModel.Outcome early = run(b, 19, null, "atirador_alimentado");
        EffectModel.Outcome late = run(b, 26, null, "atirador_alimentado");
        System.out.printf("reference %.2f s / %.2f s; build %.2f s / %.2f s%n", early.reference.timeToDeath, late.reference.timeToDeath,
                early.build.timeToDeath, late.build.timeToDeath);
        assertEquals(early.reference.timeToDeath, late.reference.timeToDeath, 1e-9);
        assertEquals(early.reference.maxHealth, late.reference.maxHealth, 1e-9);
        assertEquals(early.reference.taken, late.reference.taken, 1e-9);
    }

    /** The attacker of a scenario does not depend on the minute, unless the scenario says so. */
    @Test
    void attackersAreFixedUnlessTheScenarioGrows() {
        BuildRepository.Build b = build("Coração de Aço");
        EffectModel.Request at = new EffectModel.Request();
        at.build = b;
        EffectModel.Job job = new EffectModel.Job();
        job.test = effect.presets().get("critico");
        at.minute = 10.0;
        ArenaModel.Scenario early = effect.scenario(at, job);
        at.minute = 25.0;
        ArenaModel.Scenario late = effect.scenario(at, job);
        assertEquals(early.fighters.get(1).dummy.attackDamage, late.fighters.get(1).dummy.attackDamage, 1e-9);

        EffectModel.Job grows = new EffectModel.Job();
        grows.test = copy(effect.presets().get("critico"));
        grows.test.scalesWithMinute = true;
        at.minute = 10.0;
        ArenaModel.Scenario weak = effect.scenario(at, grows);
        at.minute = 25.0;
        ArenaModel.Scenario full = effect.scenario(at, grows);
        assertEquals(300, full.fighters.get(1).dummy.attackDamage, 1e-9, "the preset's stats at the full minute");
        assertEquals(120, weak.fighters.get(1).dummy.attackDamage, 1e-9, "10 / 25 of them at 10:00");
        assertNotEquals(early.fighters.get(1).dummy.attackDamage, weak.fighters.get(1).dummy.attackDamage);
    }

    /** With nowhere to run, more health is a little more time: never the walk to the wall (2.8 s against 20.8 s). */
    @Test
    void theBuildNeverRunsToTheWall() {
        BuildRepository.Build b = build();
        EffectModel.Outcome o = run(b, 20, null, "atirador_alimentado");
        System.out.printf("no items against the fed marksman: %.2f s with %.0f health%n", o.reference.timeToDeath, o.reference.maxHealth);
        assertTrue(o.reference.died);
        assertTrue(o.reference.timeToDeath < 6, "dead where the fight is, in " + o.reference.timeToDeath + " s");
    }

    /** 🎯 The mage presets kill a late game tank with no magic resist items in less than 60 s. */
    @Test
    void magesKillATankWithoutMagicResist() {
        BuildRepository.Build b = build("Botas Galvanizadas de Aço", "Coração de Aço", "Égide de Fogo Solar", "Armadura de Espinhos",
                "Presságio de Randuin", "Coração Congelado");
        b.goldPerMin = 3000;
        for (String mage : List.of("liandry", "cajado_vazio", "pdh_1000_cajado")) {
            EffectModel.Outcome o = run(b, 26, null, mage);
            System.out.printf("%s: %.1f s, %.0f health, net %.0f/s (reference %.1f s), index %.0f%n", mage, o.build.timeToDeath, o.build.maxHealth,
                    o.build.netRate, o.reference.timeToDeath, o.index);
            assertTrue(o.build.died && o.build.timeToDeath < 60, mage + ": " + o.build.timeToDeath + " s");
            assertTrue(o.index <= o.cap);
        }
    }

    /** Every index stops at the cap, and a fight to the death gives time to die over the reference's. */
    @Test
    void theIndexOfAFightToTheDeathIsTheTimeRatio() {
        EffectModel.Outcome o = run(build("Coração de Aço", "Égide de Fogo Solar"), 26, null, "atirador_alimentado");
        assertTrue(o.build.died && o.reference.died);
        assertEquals(100 * o.build.timeToDeath / o.reference.timeToDeath, o.index, 1e-9);
        assertEquals(1000, o.cap, 1e-9);
    }

    private static EffectModel.Test copy(EffectModel.Test t) {
        EffectModel.Test c = new EffectModel.Test();
        c.name = t.name;
        c.duration = t.duration;
        t.attackers.forEach(a -> c.attackers.add(a.copy()));
        return c;
    }
}
