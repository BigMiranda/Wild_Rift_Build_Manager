package com.ornnplanner.engine;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Every number of the match rules says where it came from: an estimate is marked, never silent. */
class RiftDataTest {

    /** A block with numbers needs a source of its own or one in a block around it. */
    @SuppressWarnings("unchecked")
    private static void blocks(String path, Map<String, Object> m, boolean sourced, List<String> missing) {
        boolean here = sourced || m.containsKey("fonte");
        boolean numbers = false;
        for (Object v : m.values()) {
            numbers |= v instanceof Number;
        }
        if (numbers && !here) {
            missing.add(path);
        }
        m.forEach((k, v) -> {
            if (v instanceof Map) {
                blocks(path + "." + k, (Map<String, Object>) v, here, missing);
            }
        });
    }

    @Test
    void everyBlockOfTheMatchRulesHasASource() throws Exception {
        try (InputStream in = new ClassPathResource("seed/objetivos_7_3d.yml").getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            List<String> missing = new ArrayList<>();
            for (String section : List.of("tropas", "torres", "fonte_base", "selva", "plantas", "objetivos", "ouro", "xp")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) root.get(section);
                m.forEach((k, v) -> {
                    if (v instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> b = (Map<String, Object>) v;
                        blocks(section + "." + k, b, false, missing);
                    }
                });
            }
            assertEquals(List.of(), missing);
        }
    }
}
