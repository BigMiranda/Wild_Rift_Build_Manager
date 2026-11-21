package com.ornnplanner.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ornnplanner.arena.rift.RiftRules;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rodada 21, part 6: the objectives by {@link ObjectiveRules} against the fixture the frontend's mirror
 * ({@code frontend/src/objectives.js}, {@code frontend/test/objectives.test.mjs}) is checked against too: the same rules
 * (the seed's), the same cases, the same parts. {@code -Dparity.write=true} writes the fixture again (after a rule change).
 */
class ObjectivesParityTest {

    static final File FIXTURE = new File("../frontend/test/objetivos-paridade.json");
    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    static Map<String, Object> rules() {
        return RiftRules.load(new ClassPathResource("seed/objetivos_7_3d.yml")).map("objetivos");
    }

    static Map<String, Double> stats(double ad, double ap, double armor, double mr, double hp) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("Attack Damage", ad);
        m.put("Ability Power", ap);
        m.put("Armor", armor);
        m.put("Magic Resistance", mr);
        m.put("Max Health", hp);
        return m;
    }

    static ObjectiveRules.Objectives of(int oceano, int montanha, int infernal, String soul, boolean elder, boolean baron) {
        ObjectiveRules.Objectives o = new ObjectiveRules.Objectives();
        o.dragons.put("oceano", oceano);
        o.dragons.put("montanha", montanha);
        o.dragons.put("infernal", infernal);
        o.soul = soul;
        o.elder = elder;
        o.baron = baron;
        return o;
    }

    /** The cases: one of each drake, two and three of them, every soul (and a soul without the three), Elder, Baron. */
    static List<Map<String, Object>> cases() {
        List<Object[]> raw = new ArrayList<>();
        raw.add(new Object[] {of(1, 0, 0, null, false, false), 8.0, stats(90, 0, 60, 40, 1500), stats(70, 0, 45, 32, 1300)});
        raw.add(new Object[] {of(0, 1, 0, null, false, false), 12.0, stats(120, 30, 180, 90, 3200), stats(85, 0, 80, 50, 2100)});
        raw.add(new Object[] {of(0, 0, 1, null, false, false), 14.5, stats(210, 140, 95, 60, 2400), stats(90, 0, 70, 45, 1900)});
        raw.add(new Object[] {of(1, 1, 1, "oceano", false, false), 20.0, stats(260, 90, 140, 110, 3900), stats(100, 0, 90, 55, 2300)});
        raw.add(new Object[] {of(1, 1, 1, "infernal", true, true), 24.0, stats(310, 400, 120, 80, 3100), stats(110, 0, 95, 60, 2400)});
        raw.add(new Object[] {of(1, 1, 1, "montanha", false, true), 22.0, stats(150, 60, 260, 190, 5200), stats(95, 0, 100, 60, 2500)});
        raw.add(new Object[] {of(1, 0, 1, "oceano", true, false), 18.0, stats(200, 100, 110, 70, 2800), stats(100, 0, 85, 52, 2200)});
        raw.add(new Object[] {of(5, 0, 0, null, false, false), 10.0, stats(100, 0, 70, 40, 1800), stats(80, 0, 55, 35, 1500)});
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : raw) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("objectives", r[0]);
            c.put("minute", r[1]);
            c.put("total", r[2]);
            c.put("base", r[3]);
            out.add(c);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    @Test
    void javaMatchesTheFixtureTheFrontendReads() throws Exception {
        Map<String, Object> rules = rules();
        List<Map<String, Object>> cases = cases();
        for (Map<String, Object> c : cases) {
            List<ObjectiveRules.Part> parts = ObjectiveRules.parts((ObjectiveRules.Objectives) c.get("objectives"), rules,
                    (Double) c.get("minute"), (Map<String, Double>) c.get("total"), (Map<String, Double>) c.get("base"));
            c.put("expected", JSON.convertValue(parts, List.class));
        }
        Map<String, Object> fixture = new LinkedHashMap<>();
        fixture.put("_", "Rodada 21: gerado por ObjectivesParityTest (-Dparity.write=true); lido por frontend/test/objectives.test.mjs");
        Map<String, Object> sub = new LinkedHashMap<>();
        sub.put("dragao", rules.get("dragao"));
        sub.put("anciao", rules.get("anciao"));
        sub.put("mao_do_barao", rules.get("mao_do_barao"));
        fixture.put("rules", sub);
        fixture.put("cases", JSON.convertValue(cases, List.class));
        if (Boolean.getBoolean("parity.write") || !FIXTURE.exists()) {
            JSON.writeValue(FIXTURE, fixture);
        }
        Map<String, Object> saved = JSON.readValue(FIXTURE, Map.class);
        assertEquals(JSON.convertValue(sub, Map.class), saved.get("rules"), "as regras do seed mudaram: gere a fixture de novo");
        List<Map<String, Object>> savedCases = (List<Map<String, Object>>) saved.get("cases");
        List<Map<String, Object>> now = JSON.convertValue(cases, List.class);
        assertEquals(now.size(), savedCases.size());
        for (int i = 0; i < now.size(); i++) {
            assertEquals(JSON.writeValueAsString(now.get(i).get("expected")), JSON.writeValueAsString(savedCases.get(i).get("expected")), "caso " + i);
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void theRulesAreTheFightsOnes() {
        Map<String, Object> rules = rules();
        Map<String, Double> total = stats(210, 140, 95, 60, 2400);
        Map<String, Object> infernal = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) rules.get("dragao")).get("elementos")).get("infernal");
        assertEquals(RiftRules.dragonDelta(infernal, 1, k -> total.getOrDefault(k, 0.0)),
                ObjectiveRules.dragonDelta(infernal, 1, k -> total.getOrDefault(k, 0.0)));
        // the Ocean soul of the user's print: 518 of heal at that moment
        List<ObjectiveRules.Part> p = ObjectiveRules.parts(of(1, 1, 1, "oceano", false, false), rules, 20, total, stats(90, 0, 70, 45, 1900));
        ObjectiveRules.Part soul = p.stream().filter(x -> "soul:oceano".equals(x.source)).findFirst().orElseThrow();
        assertEquals(250 + 0.48 * 120 + 0.25 * 140 + 0.10 * 500, soul.fight.value, 1e-9);
        // the Ocean drake and the Elder now have numbers
        assertTrue(p.stream().anyMatch(x -> "oceano".equals(x.source) && x.fight != null && x.fight.value > 0));
        List<ObjectiveRules.Part> elder = ObjectiveRules.parts(of(0, 0, 0, null, true, false), rules, 20, total, total);
        assertEquals(1500, elder.get(0).fight.value, 1e-9);
    }
}
