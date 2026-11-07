package com.ornnplanner.engine;

import com.ornnplanner.arena.ArenaService;
import com.ornnplanner.arena.EffectModel;
import com.ornnplanner.arena.OffenseModel;
import com.ornnplanner.arena.OffenseTestService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offensive effectiveness (Rodada 9, part 1b): the build attacks target ragdolls, on the seed's catalog. */
@SpringBootTest(properties = {"server.port=0"})
class OffenseTestsTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        String db = Files.createTempDirectory("offense").resolve("t.db").toString();
        r.add("planner.db-path", () -> db);
    }

    @Autowired
    OffenseTestService offense;
    @Autowired
    PlannerService planner;
    @Autowired
    ArenaService arena;

    private final Map<String, Long> ids = new LinkedHashMap<>();

    @BeforeEach
    void names() {
        planner.itemViews().forEach(i -> ids.put(i.name, i.id));
    }

    private BuildRepository.Build build(String unit, String... items) {
        BuildRepository.Build b = new BuildRepository.Build();
        b.name = "teste " + unit;
        b.unitCode = unit;
        b.goldPerMin = 800;
        b.xpPerMin = 700;
        b.steps = new ArrayList<>();
        for (String i : items) {
            b.steps.add(new BuildRepository.Step(ids.get(i), true));
        }
        return b;
    }

    private OffenseModel.Outcome run(BuildRepository.Build b, String target, EffectModel.Variant v) {
        OffenseModel.Request req = new OffenseModel.Request();
        req.build = b;
        req.minute = 20.0;
        OffenseModel.Job j = new OffenseModel.Job();
        j.targetKey = target;
        j.variant = v;
        req.jobs.add(j);
        OffenseModel.Outcome o = offense.evaluate(req).get(0);
        assertNotNull(o.index, target + ": " + o.error);
        return o;
    }

    private static EffectModel.Variant variant(String kind, long item) {
        EffectModel.Variant v = new EffectModel.Variant();
        v.kind = kind;
        v.itemId = item;
        return v;
    }

    /** Percent armor penetration is worth more against much resistance than against a fragile target (same gold in AD). */
    @Test
    void penetrationMattersMoreAgainstResistance() {
        BuildRepository.Build b = build("DARIUS", "Serrespada Quimiopunk", "Rancor de Serylda");
        long serylda = ids.get("Rancor de Serylda");
        EffectModel.Variant ad = variant("ouro", serylda);
        ad.mix = offense.mixes().get(0);
        assertEquals(Map.of("Attack Damage", 1.0), ad.mix);
        double wall = run(b, "muralha", null).index / run(b, "muralha", ad).index;
        double fragile = run(b, "fragil", null).index / run(b, "fragil", ad).index;
        assertTrue(wall > fragile, "the item over its gold in AD: against resistance " + wall + ", fragile " + fragile);
        assertTrue(wall > 1, "against resistance the penetration beats the AD: " + wall);
    }

    /** Grievous Wounds raise the index against much healing and not against a fragile target. */
    @Test
    void grievousWoundsMatterAgainstHealing() {
        BuildRepository.Build b = build("DARIUS", "Serrespada Quimiopunk");
        long saw = ids.get("Serrespada Quimiopunk");
        double heal = run(b, "cura_alta", null).index / run(b, "cura_alta", variant("so_status", saw)).index;
        double fragile = run(b, "fragil", null).index / run(b, "fragil", variant("so_status", saw)).index;
        assertTrue(heal > 1.01, "against healing: " + heal);
        assertTrue(heal > fragile, heal + " vs fragile " + fragile);
        OffenseModel.Outcome o = run(b, "cura_alta", variant("so_status", saw));
        assertTrue(o.build.lostToHeal > 0, "the target heals");
    }

    /** Against the dodging targets, aimed abilities miss more than attacks with a target. */
    @Test
    void aimedAbilitiesMissMoreThanTargetedAttacks() {
        BuildRepository.Build b = build("ORNN", "Égide de Fogo Solar");
        OffenseModel.Request req = new OffenseModel.Request();
        req.build = b;
        req.minute = 12.0;
        req.readMode = EffectModel.STARTS;
        req.starts = 4;
        for (String k : List.of("esquiva_avancos", "esquiva_velocidade")) {
            OffenseModel.Job j = new OffenseModel.Job();
            j.targetKey = k;
            req.jobs.add(j);
        }
        double cast = 0;
        double hit = 0;
        double attacks = 0;
        double landed = 0;
        for (OffenseModel.Outcome o : offense.evaluate(req)) {
            cast += o.build.aimedCast;
            hit += o.build.aimedHit;
            attacks += o.build.attacks;
            landed += o.build.attacksHit;
        }
        assertTrue(cast > 0 && attacks > 0, "casts " + cast + ", attacks " + attacks);
        assertTrue(hit / cast < landed / attacks, "aimed " + hit + "/" + cast + " vs attacks " + landed + "/" + attacks);
    }

    /** The same fight twice: the same numbers, dashes drawn by the seed included. */
    @Test
    void itIsDeterministic() {
        OffenseModel.Target t = offense.presets().get("esquiva_avancos");
        BuildRepository.Build b = build("VAYNE", "Gume do Infinito");
        OffenseModel.Request req = new OffenseModel.Request();
        req.build = b;
        req.minute = 15.0;
        OffenseModel.Job j = new OffenseModel.Job();
        j.target = t;
        req.jobs.add(j);
        OffenseModel.Outcome a = offense.evaluate(req).get(0);
        j.target = t.copy();
        j.target.name = t.name + " ";   // not the cached fight
        OffenseModel.Outcome c = offense.evaluate(req).get(0);
        assertEquals(a.build.timeToKill, c.build.timeToKill, 1e-12);
        assertEquals(a.build.raw, c.build.raw, 1e-9);
        assertEquals(a.index, c.index, 1e-9);
    }
    /** The calibration table of docs/rodada-9.md: three champions on every target (opt-in: -Doffense.report=true). */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "offense.report", matches = "true")
    void calibrationReport() {
        Map<String, BuildRepository.Build> cases = new LinkedHashMap<>();
        cases.put("Vayne 15:00", build("VAYNE", "Gume do Infinito", "Lembranças do Lorde Dominik"));
        cases.put("Darius 15:00", build("DARIUS", "Serrespada Quimiopunk", "Rancor de Serylda"));
        cases.put("Ornn 15:00", build("ORNN", "Égide de Fogo Solar", "Armadura de Espinhos"));
        for (Map.Entry<String, BuildRepository.Build> c : cases.entrySet()) {
            OffenseModel.Request req = new OffenseModel.Request();
            req.build = c.getValue();
            req.minute = 15.0;
            offense.presets().keySet().forEach(k -> {
                OffenseModel.Job j = new OffenseModel.Job();
                j.targetKey = k;
                req.jobs.add(j);
            });
            long t0 = System.nanoTime();
            List<OffenseModel.Outcome> out = offense.evaluate(req);
            System.out.printf("REPORT %s (%.1f s)%n", c.getKey(), (System.nanoTime() - t0) / 1e9);
            for (OffenseModel.Outcome o : out) {
                System.out.printf("REPORT | %s | %s | %.0f/s | %.0f%% / %.0f%% / %.0f%% | %.0f/%.0f, %.0f/%.0f | %s | %.0f%s |%n", o.targetName,
                        o.build.killed ? String.format("%.1f s", o.build.timeToKill) : String.format("> %.0f s (%.0f%%)", o.build.timeToKill, o.build.healthLeftPct),
                        o.build.effectiveDps, o.build.lostToResistPct, o.build.lostToShieldPct, o.build.lostToHealPct,
                        o.build.aimedHit, o.build.aimedCast, o.build.attacksHit, o.build.attacks,
                        o.reference.killed ? String.format("%.1f s", o.reference.timeToKill) : String.format("> %.0f s", o.reference.timeToKill),
                        o.index, o.byRate ? " (taxa)" : "");
            }
        }
    }
}
