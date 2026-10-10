package com.ornnplanner.seed;

import com.ornnplanner.engine.Model.StatGrowth;
import com.ornnplanner.engine.Model.UnitProfile;
import com.ornnplanner.engine.Stats;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Champions transcribed from the game ({@code seed/campeoes_<patch>.yml}): passive, abilities and the stats measured
 * in the training mode at levels 1 and 15. Kept in memory as reference data; each champion is also seeded as a
 * planner unit whose base stats grow linearly between the two measured levels.
 */
@Component
public class ChampionCatalog {

    /** Stats-block key -> unit stat. */
    private static final Map<String, String> STATUS = new LinkedHashMap<>();

    static {
        STATUS.put("Vida", Stats.MAX_HEALTH);
        STATUS.put("Mana", Stats.MAX_MANA);
        STATUS.put("RegVida", Stats.HEALTH_REGEN);
        STATUS.put("RegMana", Stats.MANA_REGEN);
        STATUS.put("Armadura", Stats.ARMOR);
        STATUS.put("RM", Stats.MAGIC_RESIST);
        STATUS.put("DdA", Stats.ATTACK_DAMAGE);
        STATUS.put("PdH", Stats.ABILITY_POWER);
        STATUS.put("AH", Stats.ABILITY_HASTE);
    }

    private final String patch;
    /** code -> champion entry as in the seed file, plus "code" and "nome". */
    private final Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    public ChampionCatalog(@Value("${planner.seed.champions:classpath:seed/campeoes_7_3.yml}") Resource file) {
        Map<String, Object> root;
        try (InputStream in = file.getInputStream()) {
            root = new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + file, e);
        }
        patch = String.valueOf(root.get("patch"));
        ((Map<String, Map<String, Object>>) root.get("campeoes")).forEach((name, v) -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("code", code(name));
            c.put("nome", name);
            c.putAll(v);
            byCode.put(code(name), c);
        });
    }

    /** Unit code of a champion name: "Dr. Mundo" -> DR_MUNDO, "Cho'Gath" -> CHOGATH. */
    public static String code(String name) {
        String plain = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return plain.replace("'", "").replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_|_$", "").toUpperCase();
    }

    public String patch() {
        return patch;
    }

    public Optional<Map<String, Object>> find(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    /** Every champion with a measured stats block as a unit: value(L) = nv1 + (nv15 - nv1) / 14 * (L - 1). */
    @SuppressWarnings("unchecked")
    public Map<String, UnitProfile> units() {
        Map<String, UnitProfile> units = new LinkedHashMap<>();
        byCode.forEach((code, c) -> {
            Map<String, Object> status = (Map<String, Object>) c.get("status");
            if (status == null) {
                return;
            }
            Map<String, Object> l1 = (Map<String, Object>) status.get("nv1");
            Map<String, Object> l15 = (Map<String, Object>) status.get("nv15");
            UnitProfile u = new UnitProfile();
            u.code = code;
            u.name = (String) c.get("nome");
            u.livingForge = false;
            STATUS.forEach((key, stat) -> {
                if (l1.get(key) != null && l15.get(key) != null) {
                    double a = num(l1.get(key));
                    double b = num(l15.get(key));
                    u.stats.put(stat, new StatGrowth(a, (b - a) / 14));
                }
            });
            if (status.get("VdM") != null) {
                u.stats.put(Stats.MOVE_SPEED, new StatGrowth(num(status.get("VdM")), 0));
            }
            units.put(code, u);
        });
        return units;
    }

    private static double num(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : Double.parseDouble(String.valueOf(o));
    }
}
