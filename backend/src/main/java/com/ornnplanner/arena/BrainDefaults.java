package com.ornnplanner.arena;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rodada 21, part 5: the brain of each process in one place ({@code ia_padrao_<patch>.yml}): the automatic brain for
 * everything that stands for a player, the effectiveness ragdolls' tactical_stand on purpose. Static helpers read the
 * loaded file (the defaults below until Spring loads it, as in the pure tests).
 */
@Component
public class BrainDefaults {

    public static final List<String> BRAINS = List.of(AutoBrain.AUTO, "tactical", ArenaService.STAND, "script");
    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
    private static volatile Map<String, String> loaded = DEFAULTS;

    static {
        for (String p : List.of("arena", "testar_na_arena", "partida", "campanha", "descoberta", "calculadora", "atlas", "draft_tabelas",
                "primeiro_encontro", "builds_provaveis", "modelos_do_mapa")) {
            DEFAULTS.put(p, AutoBrain.AUTO);
        }
        DEFAULTS.put("efetividade", ArenaService.STAND);
    }

    @SuppressWarnings("unchecked")
    public BrainDefaults(@Value("${planner.seed.brains:classpath:seed/ia_padrao_7_3f.yml}") Resource file) {
        Map<String, String> m = new LinkedHashMap<>(DEFAULTS);
        try (InputStream in = file.getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            ((Map<String, Object>) root.getOrDefault("processos", Map.of())).forEach((k, v) -> {
                String b = String.valueOf(v);
                if (!BRAINS.contains(b)) {
                    throw new IllegalStateException("ia_padrao: cérebro desconhecido para " + k + ": " + b);
                }
                m.put(k, b);
            });
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + file, e);
        }
        loaded = m;
    }

    /** The brain of a process (auto when the file does not name it). */
    public static String of(String process) {
        return loaded.getOrDefault(process, AutoBrain.AUTO);
    }

    /** Every process and its brain (the screens read it). */
    public static Map<String, String> all() {
        return new LinkedHashMap<>(loaded);
    }
}
