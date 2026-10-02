package com.ornnplanner.service;

import com.ornnplanner.engine.GoldPricing;
import com.ornnplanner.engine.GoldPricing.PriceTable;
import com.ornnplanner.engine.GoldPricing.StatDef;
import com.ornnplanner.engine.Model.ComponentRef;
import com.ornnplanner.engine.Model.EngineInput;
import com.ornnplanner.engine.Model.ItemDef;
import com.ornnplanner.engine.Model.ReferenceData;
import com.ornnplanner.engine.Model.StatGrowth;
import com.ornnplanner.engine.Model.TimelineResult;
import com.ornnplanner.engine.Model.UnitProfile;
import com.ornnplanner.engine.Stats;
import com.ornnplanner.engine.TimelineEngine;
import com.ornnplanner.repo.BuildRepository.Build;
import com.ornnplanner.repo.BuildRepository.RagdollStat;
import com.ornnplanner.repo.CatalogRepository;
import com.ornnplanner.repo.ReferenceRepository;
import com.ornnplanner.seed.ReferenceSeeder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PlannerService {

    /** Final-item categories shown by default (tank / support focus). */
    public static final Set<String> DEFAULT_CATEGORIES = Set.of(
            "DEFENSE ITEMS", "SUPPORT ITEMS", "DEFENSE ITEMS, SUPPORT ITEMS", "SUPPORT ITEMS, ACTIVE ITEMS",
            "DEFENSE ITEMS, ACTIVE ITEMS", "BOOTS");

    /** Stats that make a basic / mid tier item a likely tank-support component when no recipe is registered. */
    private static final Set<String> COMPONENT_HINT_STATS = Set.of(
            Stats.MAX_HEALTH, Stats.ARMOR, Stats.MAGIC_RESIST, Stats.ABILITY_HASTE, Stats.PCT_HEALTH_REGEN,
            Stats.PCT_MANA_REGEN, "% Heal and shield strength");

    public static class ItemView extends ItemDef {
        public boolean relevant;
        public String relevanceReason;
        public double staticPct;
        public String staticFormula;
    }

    private final CatalogRepository catalog;
    private final ReferenceRepository reference;

    public PlannerService(CatalogRepository catalog, ReferenceRepository reference) {
        this.catalog = catalog;
        this.reference = reference;
    }

    public ReferenceData referenceData() {
        ReferenceData r = new ReferenceData();
        r.items = catalog.findAllItems();
        r.forgeTiers = reference.findForgeTiers();
        r.xpTable = reference.findXpTable();
        List<StatDef> defs = catalog.findStatDefs();
        r.statPrices = GoldPricing.computePrices(defs, r.items.values()).asMap();
        r.baseItemNames = GoldPricing.baseItemNames(defs);
        return r;
    }

    public PriceTable priceTable() {
        return GoldPricing.computePrices(catalog.findStatDefs(), catalog.findAllItems().values());
    }

    public TimelineResult calculate(Build build) {
        ReferenceData ref = referenceData();
        EngineInput in = new EngineInput();
        in.unit = resolveUnit(build);
        in.goldPerMin = build.goldPerMin;
        in.xpPerMin = build.xpPerMin;
        in.itemIds = build.itemIds == null ? new ArrayList<>() : build.itemIds;
        TimelineResult result = new TimelineEngine(ref).run(in);
        if (ReferenceSeeder.RAGDOLL.equals(in.unit.code)) {
            boolean anyFilled = build.ragdollStats != null && build.ragdollStats.values().stream()
                    .anyMatch(s -> s != null && (s.base != null || s.growth != null));
            if (!anyFilled) {
                result.warnings.add(0, "Boneco de pano sem status definidos: todos os campos em branco contam como 0.");
            }
        }
        return result;
    }

    private UnitProfile resolveUnit(Build build) {
        String code = build.unitCode == null ? ReferenceSeeder.ORNN : build.unitCode;
        UnitProfile unit = reference.findUnit(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unidade desconhecida: " + code));
        if (ReferenceSeeder.RAGDOLL.equals(code)) {
            unit.stats.clear();
            for (String stat : Stats.UNIT_STATS) {
                RagdollStat s = build.ragdollStats == null ? null : build.ragdollStats.get(stat);
                double base = s == null || s.base == null ? 0 : s.base;
                double growth = s == null || s.growth == null ? 0 : s.growth;
                unit.stats.put(stat, new StatGrowth(base, growth));
            }
        }
        return unit;
    }

    /** Catalog with the default tank/support filter flag and static efficiency. */
    public List<ItemView> itemViews() {
        ReferenceData ref = referenceData();
        Map<Long, ItemDef> items = ref.items;

        // Components (recursively) of every default-category final item.
        Map<Long, String> componentOf = new HashMap<>();
        boolean recipesIncomplete = false;
        for (ItemDef i : items.values()) {
            if (!DEFAULT_CATEGORIES.contains(i.category)) {
                continue;
            }
            if (i.components.isEmpty() && !isComponentCategory(i.category) && i.cost > 1000) {
                recipesIncomplete = true;
            }
            collectComponents(i, i.name, items, componentOf, new HashSet<>());
        }

        List<ItemView> views = new ArrayList<>();
        for (ItemDef i : items.values()) {
            ItemView v = new ItemView();
            v.id = i.id;
            v.name = i.name;
            v.cost = i.cost;
            v.category = i.category;
            v.image = i.image;
            v.sourcePatch = i.sourcePatch;
            v.edited = i.edited;
            v.stats = i.stats;
            v.components = i.components;
            GoldPricing.StaticResult st = GoldPricing.staticEfficiency(i, ref.statPrices, ref.baseItemNames);
            v.staticPct = st.pct;
            v.staticFormula = st.formula;
            if (DEFAULT_CATEGORIES.contains(i.category)) {
                v.relevant = true;
                v.relevanceReason = "categoria";
            } else if (isComponentCategory(i.category) && componentOf.containsKey(i.id)) {
                v.relevant = true;
                v.relevanceReason = "componente de " + componentOf.get(i.id);
            } else if (isComponentCategory(i.category) && recipesIncomplete
                    && i.stats.stream().anyMatch(s -> COMPONENT_HINT_STATS.contains(s.type))) {
                v.relevant = true;
                v.relevanceReason = "provável componente (receitas não cadastradas)";
            }
            views.add(v);
        }
        return views;
    }

    private static boolean isComponentCategory(String category) {
        return category != null && (category.startsWith("BASIC ITEMS") || category.startsWith("MID TIER ITEMS"));
    }

    private static void collectComponents(ItemDef item, String finalName, Map<Long, ItemDef> items,
                                          Map<Long, String> out, Set<Long> seen) {
        for (ComponentRef c : item.components) {
            if (!seen.add(c.itemId)) {
                continue;
            }
            out.putIfAbsent(c.itemId, finalName);
            ItemDef comp = items.get(c.itemId);
            if (comp != null) {
                collectComponents(comp, finalName, items, out, seen);
            }
        }
    }
}
