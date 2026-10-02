package com.ornnplanner.seed;

import com.ornnplanner.engine.GoldPricing.StatDef;
import com.ornnplanner.engine.Model.ItemDef;
import com.ornnplanner.engine.Model.RefScope;
import com.ornnplanner.engine.Model.StatLine;
import com.ornnplanner.engine.Stats;
import com.ornnplanner.repo.CatalogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Imports the item catalog and stat definitions from the changchiyou/wildrift-gold-efficiency YAML files
 * (MIT License, Copyright (c) 2024 changchiyou) into SQLite.
 *
 * <p>Items are upserted by name so ids referenced by saved builds stay stable. Items corrected by hand in the
 * admin screen ({@code edited = 1}) are skipped unless {@code overwriteEdited} is set. Recipes are not part of the
 * source data and are never touched. Derived fields of the YAML (amount, formula, first_base...) are ignored:
 * this application recomputes them.</p>
 */
@Component
public class CatalogImporter {

    private static final Logger log = LoggerFactory.getLogger(CatalogImporter.class);

    /** Stats relevant to a tank/support Ornn (gold prices shown by default). */
    static final Set<String> RELEVANT_STATS = Set.of(
            Stats.MAX_HEALTH, Stats.ARMOR, Stats.MAGIC_RESIST, Stats.ABILITY_HASTE, Stats.PCT_HEALTH_REGEN,
            Stats.ABILITY_POWER, Stats.ATTACK_DAMAGE);

    public static class ImportReport {
        public String patch;
        public int inserted;
        public int updated;
        public int skippedEdited;
        public int statDefs;
    }

    private final CatalogRepository catalog;
    private final Resource itemsFile;
    private final Resource statsFile;
    private final String patch;

    public CatalogImporter(CatalogRepository catalog,
                           @Value("${planner.seed.items}") Resource itemsFile,
                           @Value("${planner.seed.stats}") Resource statsFile,
                           @Value("${planner.seed.patch}") String patch) {
        this.catalog = catalog;
        this.itemsFile = itemsFile;
        this.statsFile = statsFile;
        this.patch = patch;
    }

    @Transactional
    public ImportReport importAll(boolean overwriteEdited) {
        ImportReport report = new ImportReport();
        report.patch = patch;
        report.statDefs = importStats();

        List<Map<String, Object>> rows = readYaml(itemsFile);
        for (Map<String, Object> row : rows) {
            ItemDef item = toItem(row);
            Optional<Long> existing = catalog.findItemIdByName(item.name);
            if (existing.isEmpty()) {
                catalog.insertItem(item);
                report.inserted++;
            } else if (!overwriteEdited && catalog.isEdited(existing.get())) {
                report.skippedEdited++;
            } else {
                catalog.updateItem(existing.get(), item);
                report.updated++;
            }
        }
        log.info("Catalog import (patch {}): {} inserted, {} updated, {} skipped (edited), {} stat definitions",
                patch, report.inserted, report.updated, report.skippedEdited, report.statDefs);
        return report;
    }

    private int importStats() {
        Map<String, Map<String, Object>> stats = readYaml(statsFile);
        int seq = 0;
        for (Map.Entry<String, Map<String, Object>> e : stats.entrySet()) {
            Map<String, Object> v = e.getValue();
            StatDef d = new StatDef();
            d.name = e.getKey();
            d.seq = seq++;
            d.category = str(v.get("type"));
            d.baseType = str(v.get("base_type"));
            d.baseItem = str(v.get("base_item"));
            d.fixedPrice = num(v.get("price"));
            d.alias = str(v.get("alias"));
            d.relevant = RELEVANT_STATS.contains(d.name);
            catalog.upsertStatDef(d);
        }
        return seq;
    }

    @SuppressWarnings("unchecked")
    private ItemDef toItem(Map<String, Object> row) {
        ItemDef item = new ItemDef();
        item.name = str(row.get("name"));
        item.cost = num(row.get("cost")).intValue();
        item.category = str(row.get("category"));
        item.image = str(row.get("image"));
        item.sourcePatch = patch;
        List<Map<String, Object>> stats = (List<Map<String, Object>>) row.get("stats");
        if (stats != null) {
            for (Map<String, Object> s : stats) {
                StatLine l = new StatLine();
                l.type = str(s.get("type"));
                l.value = num(s.get("value"));
                l.passive = str(s.get("passive"));
                l.ratio = num(s.get("ratio"));
                l.ref = num(s.get("ref"));
                l.refType = str(s.get("ref_type"));
                // Default follows the planner's rule: ratio x (unit base + everything bonus). Editable per line.
                l.refScope = RefScope.TOTAL;
                item.stats.add(l);
            }
        }
        return item;
    }

    private static <T> T readYaml(Resource r) {
        try (InputStream in = r.getInputStream()) {
            return new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + r, e);
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Double num(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        return Double.parseDouble(o.toString());
    }
}
