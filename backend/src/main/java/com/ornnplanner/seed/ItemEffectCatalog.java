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
    /** Stacks gathered while held (Rodada 13), by "item|passive": seconds per stack, most stacks, thresholds by stat. */
    private final Map<String, Hold> holds = new LinkedHashMap<>();

    /** A passive whose stacks come with the time the item is held ({@code acumulo_por_posse}). */
    public static final class Hold {
        public double every;
        public double max;
        /** Stat code -> stacks from which the line counts whole (e.g. Mejai's movement speed from 10 stacks). */
        public Map<String, Double> from = new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public ItemEffectCatalog(@Value("${planner.seed.itemEffects:classpath:seed/efeitos_itens_7_3d.yml}") Resource file,
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
        Object raw = root.get("acumulo_por_posse");
        if (raw instanceof Map) {
            ((Map<String, Map<String, Object>>) raw).forEach((key, v) -> {
                Hold h = new Hold();
                h.every = ((Number) v.get("a_cada_s")).doubleValue();
                h.max = ((Number) v.get("max")).doubleValue();
                if (v.get("inteiro_a_partir") instanceof Map) {
                    ((Map<String, Object>) v.get("inteiro_a_partir")).forEach((stat, n) ->
                            h.from.put(spec.stat(stat), ((Number) n).doubleValue()));
                }
                holds.put(key, h);
            });
        }
        if (root.get("evolucao_por_tempo") instanceof Map) {
            ((Map<String, Object>) root.get("evolucao_por_tempo")).forEach((item, v) -> evolutions.put(item, ((Number) v).doubleValue()));
        }
        if (root.get("ouro_por_posse") instanceof Map) {
            ((Map<String, Map<String, Object>>) root.get("ouro_por_posse")).forEach((item, v) -> income.put(item,
                    new double[] {((Number) v.get("ouro")).doubleValue(), ((Number) v.get("a_cada_s")).doubleValue()}));
        }
        if (root.get("herda_acumulos") instanceof Map) {
            ((Map<String, List<String>>) root.get("herda_acumulos")).forEach((item, v) -> keeps.put(item, new ArrayList<>(v)));
        }
    }

    public Map<String, Double> evolutions() {
        return evolutions;
    }

    /**
     * The items with their timing (Rodada 13): an evolved form reached with time knows its base item and the minutes, an
     * item that keeps a component's stacks knows which. Changes the map's entries to copies; the rest stay as they are.
     */
    public void timing(Map<Long, ItemDef> items) {
        if (evolutions.isEmpty() && keeps.isEmpty() && income.isEmpty()) {
            return;
        }
        Map<String, ItemDef> byName = new HashMap<>();
        items.values().forEach(i -> byName.putIfAbsent(i.name, i));
        items.replaceAll((id, i) -> {
            Double after = evolutions.get(i.name);
            List<String> kept = keeps.get(i.name);
            ItemDef base = after == null || i.group == null || i.group.equals(i.name) ? null : byName.get(i.group);
            double[] gold = income.get(i.name);
            if (base == null && kept == null && gold == null) {
                return i;
            }
            ItemDef c = i.withStats(i.stats);
            if (gold != null) {
                c.goldEach = gold[0];
                c.goldEvery = gold[1];
            }
            if (base != null) {
                c.evolveAfter = after;
                c.evolvedFrom = base;
            }
            if (kept != null) {
                c.stacksFrom = kept;
            }
            return c;
        });
    }

    /** Evolved forms reached with time, by name: minutes after the purchase (Rodada 13). */
    private final Map<String, Double> evolutions = new LinkedHashMap<>();
    /** Items that keep the stacks of the components they consume, by name (Rodada 13). */
    private final Map<String, List<String>> keeps = new LinkedHashMap<>();
    /** Gold earned while held, by name: {gold, seconds} (Rodada 13). */
    private final Map<String, double[]> income = new LinkedHashMap<>();

    /** The passives gathered while held, by "item|passive". */
    public Map<String, Hold> holds() {
        return holds;
    }

    /** The item with its lines gathered while held marked (a copy), or the item itself when it has none. */
    ItemDef held(ItemDef item) {
        boolean any = false;
        for (StatLine l : item.stats) {
            any |= l.passive != null && holds.containsKey(key(item.name, l.passive));
        }
        if (!any) {
            return item;
        }
        ItemDef copy = copy(item);
        copy.stats = new ArrayList<>();
        for (StatLine l : item.stats) {
            Hold h = l.passive == null ? null : holds.get(key(item.name, l.passive));
            if (h == null) {
                copy.stats.add(l);
                continue;
            }
            StatLine c = l.copy();
            c.heldEvery = h.every;
            c.heldMax = h.max;
            c.heldFrom = h.from.get(l.type);
            copy.stats.add(c);
        }
        return copy;
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
        item = held(item);
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
        c.evolveAfter = i.evolveAfter;
        c.evolvedFrom = i.evolvedFrom;
        c.stacksFrom = i.stacksFrom;
        c.goldEach = i.goldEach;
        c.goldEvery = i.goldEvery;
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
