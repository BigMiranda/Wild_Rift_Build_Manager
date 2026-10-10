package com.ornnplanner.service;

import com.ornnplanner.engine.GoldPricing;
import com.ornnplanner.engine.GoldPricing.PriceTable;
import com.ornnplanner.engine.GoldPricing.StatDef;
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
import java.util.List;
import java.util.Map;

@Service
public class PlannerService {

    public static class ItemView extends ItemDef {
        public double staticPct;
        public String staticFormula;
    }

    private final CatalogRepository catalog;
    private final ReferenceRepository reference;
    private final com.ornnplanner.seed.RuneCatalog runes;
    private final com.ornnplanner.seed.ChampionCatalog champions;

    public PlannerService(CatalogRepository catalog, ReferenceRepository reference,
                          com.ornnplanner.seed.RuneCatalog runes, com.ornnplanner.seed.ChampionCatalog champions) {
        this.catalog = catalog;
        this.reference = reference;
        this.runes = runes;
        this.champions = champions;
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
        in.assumeHalfItems = build.assumeHalfItems;
        in.assumeSmallItems = build.assumeSmallItems;
        in.matchEnd = build.matchEnd;
        if (build.steps != null) {
            for (int p = 0; p < build.steps.size(); p++) {
                com.ornnplanner.repo.BuildRepository.Step s = build.steps.get(p);
                if (s.moment()) {
                    in.moments.add(new com.ornnplanner.engine.Model.Moment(p, s.atMinute, s.afterMinutes));
                } else if (s.itemId != null) {
                    in.itemIds.add(s.itemId);
                    in.conditional.add(s.includeConditional);
                    in.positions.add(p);
                }
            }
        }
        if (build.runePage != null) {
            long id = -1;
            for (String name : build.runePage.chosen()) {
                Integer opt = build.runePage.options == null ? null : build.runePage.options.get(name);
                List<double[]> periods = new java.util.ArrayList<>();
                if (build.runePage.rates != null && build.runePage.rates.get(name) != null) {
                    for (com.ornnplanner.repo.BuildRepository.RatePeriod p : build.runePage.rates.get(name)) {
                        periods.add(new double[] {p.start, p.perMinute});
                    }
                }
                if (build.runePage.gold != null && build.runePage.gold.get(name) != null) {
                    for (com.ornnplanner.repo.BuildRepository.GoldEvent g : build.runePage.gold.get(name)) {
                        in.extraGold.add(new double[] {g.minute, g.gold});
                    }
                }
                runes.toItem(name, opt, id--, periods).ifPresent(item -> {
                    in.runes.add(item);
                    in.runeConditional.add(build.runePage.conditional == null
                            || !Boolean.FALSE.equals(build.runePage.conditional.get(name)));
                });
            }
        }
        addChampionEffects(build, in);
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

    /**
     * The champion's modeled passive / abilities, held from the start like the runes (ids after the runes'), with the
     * build's skill order, options, conditional choices and stack rates.
     */
    private void addChampionEffects(Build build, EngineInput in) {
        com.ornnplanner.repo.BuildRepository.ChampionSetup setup = build.championSetup != null ? build.championSetup
                : new com.ornnplanner.repo.BuildRepository.ChampionSetup();
        Map<String, List<double[]>> rates = new java.util.LinkedHashMap<>();
        if (setup.rates != null) {
            setup.rates.forEach((name, list) -> {
                List<double[]> periods = new ArrayList<>();
                if (list != null) {
                    for (com.ornnplanner.repo.BuildRepository.RatePeriod p : list) {
                        periods.add(new double[] {p.start, p.perMinute});
                    }
                }
                rates.put(name, periods);
            });
        }
        long firstId = -1 - in.runes.size();
        for (com.ornnplanner.seed.ChampionCatalog.EffectItem e : champions.toItems(in.unit.code, setup.skillOrder,
                setup.skillLevels, setup.options, setup.conditional, rates, firstId)) {
            in.runes.add(e.item);
            in.runeConditional.add(e.conditional);
        }
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

    /** Whole catalog with the static (reference site) efficiency of each item. */
    public List<ItemView> itemViews() {
        ReferenceData ref = referenceData();
        List<ItemView> views = new ArrayList<>();
        for (ItemDef i : ref.items.values()) {
            ItemView v = new ItemView();
            v.id = i.id;
            v.name = i.name;
            v.cost = i.cost;
            v.category = i.category;
            v.image = i.image;
            v.sourcePatch = i.sourcePatch;
            v.edited = i.edited;
            v.section = i.section;
            v.tabs = i.tabs;
            v.active = i.active;
            v.marker = i.marker;
            v.summary = i.summary;
            v.group = i.group;
            v.capture = i.capture;
            v.passives = i.passives;
            v.stats = i.stats;
            v.components = i.components;
            v.exclusiveGroups = i.exclusiveGroups;
            GoldPricing.StaticResult st = GoldPricing.staticEfficiency(i, ref.statPrices, ref.baseItemNames);
            v.staticPct = st.pct;
            v.staticFormula = st.formula;
            views.add(v);
        }
        return views;
    }
}
