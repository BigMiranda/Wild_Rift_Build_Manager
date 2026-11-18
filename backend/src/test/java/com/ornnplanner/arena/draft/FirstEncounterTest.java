package com.ornnplanner.arena.draft;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rodada 20, Parte 4: the starting options of the shop and the style advice from the measured levels. */
class FirstEncounterTest {

    /** The shop's starts: the rule of FirstEncounterService.starts on the seed (cost within 500, basic or support prep). */
    @Test
    @SuppressWarnings("unchecked")
    void aboutTwelveStartsFitTheStartingGold() throws Exception {
        Map<String, Object> shop;
        try (InputStream in = new ClassPathResource("seed/loja_7_3f.yml").getInputStream()) {
            shop = (Map<String, Object>) ((Map<String, Object>) new Yaml().load(in)).get("itens");
        }
        List<String> all = new ArrayList<>();
        List<String> support = new ArrayList<>();
        shop.forEach((name, v) -> {
            Map<String, Object> it = (Map<String, Object>) v;
            int cost = ((Number) it.get("custo")).intValue();
            Object sec = it.get("secao");
            if (cost <= 500 && cost > 0 && "basico".equals(sec)) {
                all.add(name);
            }
            if (cost <= 500 && cost > 0 && "preparacao".equals(sec)) {
                support.add(name);
            }
        });
        assertEquals(10, all.size(), all.toString());
        assertEquals(2, support.size(), support.toString());
        assertEquals(12, all.size() + support.size(), "near 12 starts, as the prompt says");
    }

    private static FirstEncounterService.Row row(String style, double... allIn) {
        FirstEncounterService.Row r = new FirstEncounterService.Row();
        r.itemId = 1;
        r.name = "x";
        r.style = style;
        double sum = 0;
        for (int i = 0; i < allIn.length; i++) {
            FirstEncounterService.Level l = new FirstEncounterService.Level();
            l.level = i + 1;
            l.allIn = allIn[i];
            r.levels.add(l);
            sum += allIn[i];
        }
        r.mean = sum / allIn.length;
        return r;
    }

    private static String advice(double... allIn) {
        FirstEncounterService.Result r = new FirstEncounterService.Result();
        r.rivals.add("Rival");
        r.rows.add(row("padrao", allIn));
        r.best = r.rows.get(0);
        FirstEncounterService.advise(r, List.of("padrao"));
        return r.advice.key;
    }

    @Test
    void theStyleAdviceFollowsTheLevels() {
        assertEquals("pressioneAntes2", advice(62, 44, 40));
        assertEquals("farmeAte3", advice(48, 38, 57));
        assertEquals("ganhaTodos", advice(60, 58, 70));
        assertEquals("perdeTodos", advice(40, 35, 44));
        assertEquals("equilibrado", advice(51, 49, 52));
        FirstEncounterService.Result r = new FirstEncounterService.Result();
        r.rivals.add("Rival");
        r.rows.add(row("cauteloso", 51, 49, 52));
        r.best = r.rows.get(0);
        FirstEncounterService.advise(r, List.of("cauteloso"));
        assertTrue("cauteloso".equals(r.advice.vars.get("style")));
    }
}
