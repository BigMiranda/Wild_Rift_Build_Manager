package com.ornnplanner.seed;

import com.ornnplanner.engine.Model.ItemDef;
import com.ornnplanner.engine.Model.PassiveText;
import com.ornnplanner.engine.Model.RefScope;
import com.ornnplanner.engine.Model.StatLine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runes and summoner spells transcribed from the game ({@code seed/runas_<patch>.yml}, {@code seed/feiticos_<patch>.yml}),
 * kept in memory: they are reference data, not edited in the app. A chosen rune becomes an {@link ItemDef} the engine
 * treats as held from the start (no cost, no inventory slot), with its option (enemies nearby, stacks...) applied.
 */
@Component
public class RuneCatalog {

    /** Rune option chosen per build (enemies nearby, stacks...). */
    public static class Option {
        public String name;
        public int min;
        public int max;
        public int defaultValue;
    }

    public static class Rune {
        public String name;
        /** Tree of the rune; null for keystones (any keystone goes with any tree). */
        public String tree;
        /** 0 = keystone, 1..3 = row of its tree. */
        public int row;
        public String tags;
        public String text;
        public String marker;
        public String icon;
        public String iconGrey;
        public Option option;
        /** Some of its effects only count in some situations (the build can switch them off). */
        public boolean hasConditional;
        /** Its effects enter the stat calculation. */
        public boolean modeled;
        transient List<Map<String, Object>> statusRaw = new ArrayList<>();
        transient List<Map<String, Object>> effectsRaw = new ArrayList<>();
    }

    public static class Tree {
        public String name;
        public String summary;
        public String icon;
        public String iconGrey;
    }

    public static class Spell {
        public String name;
        public String icon;
        public String maps;
        public Integer cooldown;
        public String text;
    }

    public static class CatalogDto {
        public String patch;
        public List<Tree> trees = new ArrayList<>();
        public List<Rune> keystones = new ArrayList<>();
        public List<Rune> runes = new ArrayList<>();
        public List<Spell> spells = new ArrayList<>();
    }

    private final CatalogDto dto = new CatalogDto();
    private final Map<String, Rune> byName = new LinkedHashMap<>();
    private final Map<String, String> statCodes = new HashMap<>();

    @SuppressWarnings("unchecked")
    public RuneCatalog(@Value("${planner.seed.runes:classpath:seed/runas_7_3.yml}") Resource runesFile,
                       @Value("${planner.seed.spells:classpath:seed/feiticos_7_3.yml}") Resource spellsFile,
                       @Value("${planner.seed.prices}") Resource pricesFile) {
        Map<String, Map<String, Object>> prices = read(pricesFile);
        prices.forEach((code, v) -> {
            if (v.get("loja") != null) {
                statCodes.put(String.valueOf(v.get("loja")), code);
            }
        });

        Map<String, Object> root = read(runesFile);
        dto.patch = String.valueOf(root.get("patch"));
        ((Map<String, Map<String, Object>>) root.get("arvores")).forEach((name, v) -> {
            Tree t = new Tree();
            t.name = name;
            t.summary = str(v.get("resumo"));
            t.icon = str(v.get("icone"));
            t.iconGrey = grey(t.icon);
            dto.trees.add(t);
        });
        ((Map<String, Map<String, Object>>) root.get("fundamentais")).forEach((name, v) ->
                dto.keystones.add(rune(name, null, 0, v)));
        ((Map<String, Map<String, Object>>) root.get("runas")).forEach((name, v) ->
                dto.runes.add(rune(name, str(v.get("arvore")), ((Number) v.get("linha")).intValue(), v)));

        Map<String, Object> spells = read(spellsFile);
        ((Map<String, Map<String, Object>>) spells.get("feiticos")).forEach((name, v) -> {
            Spell s = new Spell();
            s.name = name;
            s.icon = str(v.get("icone"));
            s.maps = str(v.get("mapas"));
            s.cooldown = v.get("recarga") == null ? null : ((Number) v.get("recarga")).intValue();
            s.text = str(v.get("texto"));
            dto.spells.add(s);
        });
    }

    @SuppressWarnings("unchecked")
    private Rune rune(String name, String tree, int row, Map<String, Object> v) {
        Rune r = new Rune();
        r.name = name;
        r.tree = tree;
        r.row = row;
        r.tags = str(v.get("tags"));
        r.text = str(v.get("texto"));
        r.marker = str(v.get("marcador"));
        r.icon = slug(name) + ".png";
        r.iconGrey = grey(r.icon);
        Map<String, Object> o = (Map<String, Object>) v.get("opcao");
        if (o != null) {
            r.option = new Option();
            r.option.name = str(o.get("nome"));
            r.option.min = ((Number) o.get("min")).intValue();
            r.option.max = ((Number) o.get("max")).intValue();
            r.option.defaultValue = ((Number) o.get("padrao")).intValue();
        }
        r.statusRaw = (List<Map<String, Object>>) v.getOrDefault("status", List.of());
        r.effectsRaw = (List<Map<String, Object>>) v.getOrDefault("efeitos", List.of());
        r.modeled = !r.statusRaw.isEmpty() || !r.effectsRaw.isEmpty();
        r.hasConditional = r.effectsRaw.stream().anyMatch(e -> Boolean.TRUE.equals(e.get("condicional")));
        byName.put(name, r);
        return r;
    }

    public CatalogDto catalog() {
        return dto;
    }

    public Optional<Rune> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /**
     * The rune as an engine item: its stats and effects with the option applied ({@code valor + valor_por_opcao x opcao},
     * {@code ratio + ratio_por_opcao x opcao}; lines with {@code opcao_min} only count from that option on).
     */
    public Optional<ItemDef> toItem(String name, Integer option, long id) {
        Rune r = byName.get(name);
        if (r == null) {
            return Optional.empty();
        }
        int opt = option != null ? option : r.option != null ? r.option.defaultValue : 0;
        if (r.option != null) {
            opt = Math.max(r.option.min, Math.min(r.option.max, opt));
        }
        ItemDef item = new ItemDef();
        item.id = id;
        item.name = r.name;
        item.section = "runa";
        item.category = "Runa";
        item.passives.add(new PassiveText(r.name, r.text));
        for (Map<String, Object> s : r.statusRaw) {
            item.stats.add(StatLine.flat(code(str(s.get("tipo"))), num(s.get("valor"))));
        }
        for (Map<String, Object> e : r.effectsRaw) {
            if (e.get("opcao_min") != null && opt < ((Number) e.get("opcao_min")).intValue()) {
                continue;
            }
            StatLine l = new StatLine();
            l.type = code(str(e.get("tipo")));
            l.passive = e.get("passiva") != null ? str(e.get("passiva")) : r.name;
            l.conditional = Boolean.TRUE.equals(e.get("condicional"));
            if (e.get("nivel_min") != null) {
                l.minLevel = ((Number) e.get("nivel_min")).intValue();
            }
            if (e.get("ratio") != null) {
                l.ratio = num(e.get("ratio")) + orZero(e.get("ratio_por_opcao")) * opt;
                l.refType = code(str(e.get("ref")));
                l.refScope = "bonus".equals(e.get("escopo")) ? RefScope.BONUS
                        : "base".equals(e.get("escopo")) ? RefScope.BASE : RefScope.TOTAL;
            } else {
                double base = num(e.get("valor"));
                double per = orZero(e.get("valor_por_opcao"));
                l.value = base + per * opt;
                if (e.get("valor_nv15") != null) {
                    l.valueMax = num(e.get("valor_nv15")) + per * opt;
                }
            }
            item.stats.add(l);
        }
        return Optional.of(item);
    }

    private String code(String shopName) {
        return statCodes.getOrDefault(shopName, shopName);
    }

    static String slug(String name) {
        String s = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "").toLowerCase();
        return s.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private static String grey(String icon) {
        return icon == null ? null : icon.replace(".png", "-cinza.png");
    }

    private static double orZero(Object o) {
        return o == null ? 0 : num(o);
    }

    private static double num(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : Double.parseDouble(String.valueOf(o));
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static <T> T read(Resource r) {
        try (InputStream in = r.getInputStream()) {
            return new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + r, e);
        }
    }
}
