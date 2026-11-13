package com.ornnplanner.arena.atlas;

import com.ornnplanner.arena.fight.CombatCatalog;
import com.ornnplanner.engine.Model.ItemDef;
import com.ornnplanner.engine.Model.ItemEdit;
import com.ornnplanner.engine.Model.StatLine;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.seed.RuneCatalog;
import com.ornnplanner.service.PlannerService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Rodada 16: the conditions a side can have, read from the shop, the combat catalog and the runes (no name in code).
 * <ul>
 *   <li>status: each stat the finished items and boots give flat, at two levels: the median one item gives, and twice it;</li>
 *   <li>passiva: each finished item or boots with a passive, an active or a fight effect, held without its flat stats;</li>
 *   <li>fundamental / runa / feitico: a keystone, a rune or a summoner spell the side's base page does not have,
 *       swapped in (a rune takes its row of the primary tree, or the secondary slot).</li>
 * </ul>
 * Capabilities the arena does not model yet (invisibility, revealing...) stay out until they are configured.
 */
@Component
public class AtlasCatalog {

    static final long STAT_ID = -5_000_000L;
    private static final String FLASH = "Flash";

    private final PlannerService planner;
    private final CombatCatalog combat;
    private final RuneCatalog runes;
    private Map<String, String> statNames;

    public AtlasCatalog(PlannerService planner, CombatCatalog combat, RuneCatalog runes) {
        this.planner = planner;
        this.combat = combat;
        this.runes = runes;
    }

    /** The conditions of a side whose base build is {@code base}, from the sources asked. */
    public List<AtlasModel.Condition> conditions(BuildRepository.Build base, List<String> sources) {
        List<AtlasModel.Condition> out = new ArrayList<>();
        if (sources.contains("status")) {
            out.addAll(stats());
        }
        if (sources.contains("passiva")) {
            out.addAll(passives());
        }
        BuildRepository.RunePage page = base.runePage == null ? new BuildRepository.RunePage() : base.runePage;
        RuneCatalog.CatalogDto rc = runes.catalog();
        if (sources.contains("fundamental")) {
            for (RuneCatalog.Rune k : rc.keystones) {
                if (!k.name.equals(page.keystone) && k.modeled) {
                    AtlasModel.Condition c = new AtlasModel.Condition();
                    c.key = "fundamental:" + k.name;
                    c.source = "fundamental";
                    c.name = k.name;
                    c.rune = k.name;
                    c.exclusive.add("fundamental");
                    out.add(c);
                }
            }
        }
        if (sources.contains("runa")) {
            List<String> held = new ArrayList<>(page.primaryRunes == null ? List.of() : page.primaryRunes);
            if (page.secondaryRune != null) {
                held.add(page.secondaryRune);
            }
            for (RuneCatalog.Rune r : rc.runes) {
                if (held.contains(r.name) || !r.modeled) {
                    continue;
                }
                AtlasModel.Condition c = new AtlasModel.Condition();
                c.key = "runa:" + r.name;
                c.source = "runa";
                c.name = r.name;
                c.rune = r.name;
                c.tree = r.tree;
                c.row = r.row;
                // in the primary tree it takes its row; from another tree, the one secondary slot
                c.exclusive.add(r.tree != null && r.tree.equals(page.primary) ? "linha:" + r.tree + ":" + r.row : "secundaria");
                out.add(c);
            }
        }
        if (sources.contains("feitico")) {
            for (RuneCatalog.Spell s : rc.spells) {
                if (FLASH.equals(s.name) || base.spells != null && base.spells.contains(s.name)) {
                    continue;
                }
                AtlasModel.Condition c = new AtlasModel.Condition();
                c.key = "feitico:" + s.name;
                c.source = "feitico";
                c.name = s.name;
                c.spell = s.name;
                c.exclusive.add("feitico");
                out.add(c);
            }
        }
        return out;
    }

    /** Each stat the finished items and boots give flat: the median one item gives (level 1) and twice it (level 2). */
    List<AtlasModel.Condition> stats() {
        Map<String, List<Double>> values = new TreeMap<>();
        Map<String, Double> prices = planner.referenceData().statPrices;
        for (ItemDef it : shop()) {
            for (StatLine l : it.stats) {
                if (plain(l) && l.value != null && l.value > 0 && prices.getOrDefault(l.type, 0.0) > 0) {
                    values.computeIfAbsent(l.type, k -> new ArrayList<>()).add(l.value);
                }
            }
        }
        List<AtlasModel.Condition> out = new ArrayList<>();
        int idx = 0;
        for (Map.Entry<String, List<Double>> e : values.entrySet()) {
            List<Double> v = new ArrayList<>(e.getValue());
            Collections.sort(v);
            double median = v.get(v.size() / 2);
            for (int level = 1; level <= 2; level++) {
                AtlasModel.Condition c = new AtlasModel.Condition();
                c.key = "status:" + e.getKey() + ":" + level;
                c.source = "status";
                c.stat = e.getKey();
                c.level = level;
                c.amount = Math.round(median * level * 10) / 10.0;
                c.name = statName(e.getKey()) + " +" + fmt(c.amount);
                c.exclusive.add("status:" + e.getKey());
                c.itemId = STAT_ID - idx * 10L - level;
                out.add(c);
            }
            idx++;
        }
        return out;
    }

    /** Each finished item or boots with a passive, an active or a fight effect. */
    List<AtlasModel.Condition> passives() {
        List<AtlasModel.Condition> out = new ArrayList<>();
        for (ItemDef it : shop()) {
            boolean passive = it.stats.stream().anyMatch(l -> l.passive != null || l.ratio != null || l.conditional || l.perMinute != null);
            if (!passive && !it.active && combat.effects(it.name).isEmpty()) {
                continue;
            }
            AtlasModel.Condition c = new AtlasModel.Condition();
            c.key = "passiva:" + it.id;
            c.source = "passiva";
            c.name = it.name;
            c.itemId = it.id;
            c.exclusive.add("item:" + (it.group == null ? it.name : it.group));
            for (String g : it.exclusiveGroups == null ? List.<String>of() : it.exclusiveGroups) {
                c.exclusive.add("grupo:" + g);
            }
            if (it.boots()) {
                c.exclusive.add("botas");
            }
            out.add(c);
        }
        return out;
    }

    /** What can be bought: the upgraded items and the boots (an evolved form comes from its base item, with time). */
    public List<ItemDef> buyable() {
        List<ItemDef> out = new ArrayList<>();
        for (ItemDef it : shop()) {
            if (!"evolucao".equals(it.section)) {
                out.add(it);
            }
        }
        return out;
    }

    /** The finished items (evolved forms included) and the boots of the shop. */
    List<ItemDef> shop() {
        List<ItemDef> out = new ArrayList<>();
        for (ItemDef it : planner.referenceData().items.values()) {
            if (it.id > 0 && (it.finished() || it.boots())) {
                out.add(it);
            }
        }
        return out;
    }

    /** A flat stat line of the item itself (not a passive's, a ratio, a conditional or a per-minute one). */
    static boolean plain(StatLine l) {
        return l.passive == null && l.ratio == null && !l.conditional && l.perMinute == null;
    }

    /** The synthetic item of a stat condition: only that stat. */
    static ItemDef statItem(AtlasModel.Condition c) {
        ItemDef d = new ItemDef();
        d.id = c.itemId;
        d.name = "Condição: " + c.name;
        d.group = d.name;
        d.section = "aprimorado";
        d.stats.add(StatLine.flat(c.stat, c.amount));
        return d;
    }

    /** An item held for its passive only: its own flat stats taken back. */
    ItemEdit passiveOnly(long itemId) {
        ItemDef it = planner.referenceData().items.get(itemId);
        ItemEdit e = new ItemEdit();
        if (it != null) {
            for (StatLine l : it.stats) {
                if (plain(l) && l.value != null && l.value != 0) {
                    e.extra.merge(l.type, -l.value, Double::sum);
                }
            }
        }
        return e;
    }

    /** An item's flat stats (what the guide spreads on the stat conditions). */
    public Map<String, Double> flatStats(long itemId) {
        Map<String, Double> out = new LinkedHashMap<>();
        ItemDef it = planner.referenceData().items.get(itemId);
        if (it != null) {
            for (StatLine l : it.stats) {
                if (plain(l) && l.value != null && l.value != 0) {
                    out.merge(l.type, l.value, Double::sum);
                }
            }
        }
        return out;
    }

    /** The gold of a stat amount, by the price of a point (null when the stat has no price). */
    public Double statGold(String stat, Double amount) {
        Double p = stat == null ? null : planner.referenceData().statPrices.get(stat);
        return p == null || amount == null ? null : p * amount;
    }

    public RuneCatalog.Rune rune(String name) {
        return runes.find(name).orElse(null);
    }

    public ItemDef item(long id) {
        return planner.referenceData().items.get(id);
    }

    @SuppressWarnings("unchecked")
    String statName(String code) {
        if (statNames == null) {
            Map<String, String> m = new LinkedHashMap<>();
            try (InputStream in = new ClassPathResource("seed/precos_status.yml").getInputStream()) {
                Map<String, Object> raw = new Yaml().load(in);
                raw.forEach((k, v) -> {
                    if (v instanceof Map && ((Map<String, Object>) v).get("loja") != null) {
                        m.put(k, String.valueOf(((Map<String, Object>) v).get("loja")));
                    }
                });
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read precos_status.yml", e);
            }
            statNames = m;
        }
        return statNames.getOrDefault(code, code);
    }

    private static String fmt(double v) {
        return Math.abs(v - Math.rint(v)) < 1e-9 ? String.valueOf((long) Math.rint(v)) : String.valueOf(v);
    }
}
