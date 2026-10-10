package com.ornnplanner.combat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Combat effects of the items and the summoner spells used in a fight ({@code seed/combate_<patch>.yml}). */
@Component
public class CombatCatalog {

    private final Map<String, List<Map<String, Object>>> items = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> spells = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    public CombatCatalog(@Value("${planner.seed.combat:classpath:seed/combate_7_3.yml}") Resource file) {
        try (InputStream in = file.getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            items.putAll((Map<String, List<Map<String, Object>>>) root.get("itens"));
            spells.putAll((Map<String, Map<String, Object>>) root.get("feiticos"));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + file, e);
        }
    }

    public List<Map<String, Object>> effects(String item) {
        return items.getOrDefault(item, List.of());
    }

    public Map<String, Object> spell(String name) {
        return spells.get(name);
    }

    public Map<String, List<Map<String, Object>>> items() {
        return items;
    }
}
