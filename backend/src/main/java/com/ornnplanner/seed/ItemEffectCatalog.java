package com.ornnplanner.seed;

import com.ornnplanner.engine.Model.ComponentRef;
import com.ornnplanner.engine.Model.ItemDef;
import com.ornnplanner.engine.Model.StatLine;
import com.ornnplanner.seed.EffectSpec.Effect;
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

/**
 * Item passives / actives modeled as stats ({@code seed/efeitos_itens_<patch>.yml}): ultimate haste, shields and heals
 * as temporary health, stasis, combat bonuses... Kept in memory; a build switches each effect on or off (default: on,
 * unless the file says {@code padrao: false}), picks its option and stack rate, by "item|effect". The chosen lines are
 * added to the item for the calculation, with their own switch ({@link StatLine#switched}).
 */
@Component
public class ItemEffectCatalog {

    private final Map<String, List<Effect>> byItem = new LinkedHashMap<>();
    private final EffectSpec spec;

    @SuppressWarnings("unchecked")
    public ItemEffectCatalog(@Value("${planner.seed.itemEffects:classpath:seed/efeitos_itens_7_3.yml}") Resource file,
                             @Value("${planner.seed.prices}") Resource pricesFile) {
        Map<String, String> statCodes = new HashMap<>();
        Map<String, Map<String, Object>> prices = read(pricesFile);
        prices.forEach((code, v) -> {
            if (v.get("loja") != null) {
                statCodes.put(String.valueOf(v.get("loja")), code);
            }
        });
        spec = new EffectSpec(statCodes);
        Map<String, Object> root = read(file);
        ((Map<String, Map<String, Map<String, Object>>>) root.get("itens")).forEach((item, effects) -> {
            List<Effect> list = new ArrayList<>();
            effects.forEach((name, e) -> {
                Map<String, Object> raw = new LinkedHashMap<>(e);
                raw.putIfAbsent("padrao", true);   // item effects are on unless the file says otherwise
                list.add(spec.parse(name, raw));
            });
            byItem.put(item, list);
        });
    }

    /** Modeled effects of every item, by item name. */
    public Map<String, List<Effect>> all() {
        return byItem;
    }

    public List<Effect> effects(String item) {
        return byItem.getOrDefault(item, List.of());
    }

    /** Key of an effect in a build's choices. */
    public static String key(String item, String effect) {
        return item + "|" + effect;
    }

    /**
     * The item with the lines of its effects that count in this build, or the item itself when it has none. Effects
     * switched off are left out; the conditional lines of the others count whatever the purchase's conditional choice.
     */
    public ItemDef apply(ItemDef item, Map<String, Integer> options, Map<String, Boolean> conditional,
                         Map<String, List<double[]>> rates) {
        List<Effect> effects = effects(item.name);
        if (effects.isEmpty()) {
            return item;
        }
        List<StatLine> extra = new ArrayList<>();
        for (Effect f : effects) {
            String k = key(item.name, f.name);
            boolean on = conditional != null && conditional.get(k) != null ? conditional.get(k) : f.defaultOn;
            List<double[]> periods = EffectSpec.periods(f, rates, k);
            if (periods == null) {
                continue;
            }
            int opt = EffectSpec.option(f, options, k);
            for (StatLine l : spec.lines(f, level -> 1, opt, periods, f.passive != null ? f.passive : f.name)) {
                if (l.conditional && !on) {
                    continue;
                }
                l.switched = l.conditional;
                extra.add(l);
            }
        }
        ItemDef copy = copy(item);
        copy.stats.addAll(extra);
        return copy;
    }

    private static ItemDef copy(ItemDef i) {
        ItemDef c = new ItemDef();
        c.id = i.id;
        c.name = i.name;
        c.cost = i.cost;
        c.category = i.category;
        c.image = i.image;
        c.sourcePatch = i.sourcePatch;
        c.edited = i.edited;
        c.section = i.section;
        c.tabs = i.tabs;
        c.active = i.active;
        c.marker = i.marker;
        c.summary = i.summary;
        c.group = i.group;
        c.capture = i.capture;
        c.passives = i.passives;
        c.stats = new ArrayList<>(i.stats);
        c.components = new ArrayList<ComponentRef>(i.components);
        c.exclusiveGroups = i.exclusiveGroups;
        return c;
    }

    private static <T> T read(Resource r) {
        try (InputStream in = r.getInputStream()) {
            return new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + r, e);
        }
    }
}
