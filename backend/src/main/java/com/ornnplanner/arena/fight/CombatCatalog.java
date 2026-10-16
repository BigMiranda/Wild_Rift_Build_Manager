package com.ornnplanner.arena.fight;

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
    private final Map<String, Map<String, Object>> activations = new LinkedHashMap<>();
    private final Map<String, List<Map<String, Object>>> runes = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> triggers = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    public CombatCatalog(@Value("${planner.seed.combat:classpath:seed/combate_7_3b.yml}") Resource file) {
        try (InputStream in = file.getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            items.putAll((Map<String, List<Map<String, Object>>>) root.get("itens"));
            spells.putAll((Map<String, Map<String, Object>>) root.get("feiticos"));
            activations.putAll((Map<String, Map<String, Object>>) root.getOrDefault("ativacao", Map.of()));
            runes.putAll((Map<String, List<Map<String, Object>>>) root.getOrDefault("runas", Map.of()));
            triggers.putAll((Map<String, Map<String, Object>>) root.getOrDefault("gatilhos", Map.of()));
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

    public Map<String, Map<String, Object>> activations() {
        return activations;
    }

    /** Fight effects of a rune (the arena's procs: Electrocute, Grasp, Brutal...). */
    public List<Map<String, Object>> runeEffects(String rune) {
        return runes.getOrDefault(rune, List.of());
    }

    /**
     * When a conditional effect of the planner counts in the arena ("Item|Effect", rune or champion effect name);
     * null = no trigger written (it follows the build's switch for the whole fight).
     */
    public Map<String, Object> trigger(String key) {
        return triggers.get(key);
    }

    public Map<String, List<Map<String, Object>>> items() {
        return items;
    }
}
