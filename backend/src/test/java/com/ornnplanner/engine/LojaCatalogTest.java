package com.ornnplanner.engine;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Consistency of the catalog transcribed from the shop screenshots (seed/loja_7_3.yml). */
class LojaCatalogTest {

    @SuppressWarnings("unchecked")
    private static <T> T load(String path) throws Exception {
        try (InputStream in = LojaCatalogTest.class.getResourceAsStream(path)) {
            return (T) new Yaml().load(in);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void recipesStatsAndEffectsAreConsistent() throws Exception {
        Map<String, Object> root = load("/seed/loja_7_3.yml");
        Map<String, Map<String, Object>> itens = (Map<String, Map<String, Object>>) root.get("itens");
        Map<String, Map<String, Object>> prices = load("/seed/precos_status.yml");
        Set<String> shopStats = new HashSet<>();
        prices.values().forEach(p -> shopStats.add((String) p.get("loja")));

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : itens.entrySet()) {
            String name = e.getKey();
            Map<String, Object> item = e.getValue();
            int cost = ((Number) item.get("custo")).intValue();
            int components = 0;
            for (Object c : (List<Object>) item.getOrDefault("receita", List.of())) {
                String cname = c instanceof Map ? (String) ((Map<String, Object>) c).get("item") : (String) c;
                int qty = c instanceof Map ? ((Number) ((Map<String, Object>) c).get("qtd")).intValue() : 1;
                if (!itens.containsKey(cname)) {
                    problems.add(name + ": componente inexistente " + cname);
                    continue;
                }
                components += qty * ((Number) itens.get(cname).get("custo")).intValue();
            }
            if (components >= cost && components > 0) {
                problems.add(name + ": componentes custam " + components + " >= " + cost);
            }
            List<Map<String, Object>> stats = new ArrayList<>((List<Map<String, Object>>) item.getOrDefault("status", List.of()));
            List<Map<String, Object>> effects = new ArrayList<>((List<Map<String, Object>>) item.getOrDefault("efeitos", List.of()));
            Map<String, Object> evo = (Map<String, Object>) item.get("evolucao");
            if (evo != null) {
                stats.addAll((List<Map<String, Object>>) evo.getOrDefault("status", List.of()));
                effects.addAll((List<Map<String, Object>>) evo.getOrDefault("efeitos", List.of()));
            }
            for (Map<String, Object> s : stats) {
                if (!shopStats.contains(s.get("tipo"))) {
                    problems.add(name + ": status sem preço " + s.get("tipo"));
                }
            }
            for (Map<String, Object> f : effects) {
                if (!shopStats.contains(f.get("tipo")) || (f.get("ref") != null && !shopStats.contains(f.get("ref")))) {
                    problems.add(name + ": efeito com status desconhecido " + f);
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        assertEquals(171, itens.size());
    }
}
