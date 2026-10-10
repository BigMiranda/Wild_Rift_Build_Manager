package com.ornnplanner.seed;

import com.ornnplanner.engine.GoldPricing.StatDef;
import com.ornnplanner.engine.Model.ComponentRef;
import com.ornnplanner.engine.Model.ItemDef;
import com.ornnplanner.engine.Model.PassiveText;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Imports the catalog transcribed from the Wild Rift shop ({@code seed/loja_<patch>.yml}) and the gold price bases
 * ({@code seed/precos_status.yml}) into SQLite.
 *
 * <p>Shop stat names (pt-BR) are mapped to the planner's internal stat codes through the {@code loja} field of the
 * price file. Evolutions (Fimbulwinter, Muramana...) become items of their own, in section {@code evolucao}, grouped
 * under their base item. Items are upserted by name so ids referenced by saved builds stay stable; items corrected
 * in the admin screen ({@code edited = 1}) are skipped unless {@code overwriteEdited} is set.</p>
 */
@Component
public class CatalogImporter {

    private static final Logger log = LoggerFactory.getLogger(CatalogImporter.class);

    /** Stats relevant to a tank/support Ornn (gold prices highlighted in the admin screen). */
    static final Set<String> RELEVANT_STATS = Set.of(
            Stats.MAX_HEALTH, Stats.ARMOR, Stats.MAGIC_RESIST, Stats.ABILITY_HASTE, Stats.PCT_HEALTH_REGEN,
            Stats.ABILITY_POWER, Stats.ATTACK_DAMAGE);

    public static class ImportReport {
        public String patch;
        public int inserted;
        public int updated;
        public int skippedEdited;
        public int statDefs;
        public List<String> warnings = new ArrayList<>();
    }

    private final CatalogRepository catalog;
    private final Resource catalogFile;
    private final Resource pricesFile;

    public CatalogImporter(CatalogRepository catalog,
                           @Value("${planner.seed.catalog}") Resource catalogFile,
                           @Value("${planner.seed.prices}") Resource pricesFile) {
        this.catalog = catalog;
        this.catalogFile = catalogFile;
        this.pricesFile = pricesFile;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public ImportReport importAll(boolean overwriteEdited) {
        ImportReport report = new ImportReport();
        Map<String, String> statCodes = importPrices(report);

        Map<String, Object> root = readYaml(catalogFile);
        report.patch = String.valueOf(root.get("patch"));
        Map<String, Map<String, Object>> itens = (Map<String, Map<String, Object>>) root.get("itens");

        // Pass 1: items (and their evolutions).
        Map<String, List<String>> recipes = new LinkedHashMap<>();
        Set<String> skipped = new HashSet<>();
        for (Map.Entry<String, Map<String, Object>> e : itens.entrySet()) {
            String name = e.getKey();
            Map<String, Object> row = e.getValue();
            ItemDef item = toItem(name, row, row, name, report.patch, statCodes, report.warnings);
            List<String> recipe = recipeNames(row);
            upsert(item, overwriteEdited, report, skipped);
            recipes.put(name, recipe);
            Map<String, Object> evo = (Map<String, Object>) row.get("evolucao");
            if (evo != null) {
                String evoName = String.valueOf(evo.get("nome"));
                ItemDef evolved = toItem(evoName, row, evo, name, report.patch, statCodes, report.warnings);
                evolved.section = "evolucao";
                evolved.summary = "Forma evoluída de " + name + " (" + str(row.get("resumo")) + ")";
                evolved.marker = null;
                evolved.capture = str(row.get("captura"));
                upsert(evolved, overwriteEdited, report, skipped);
                recipes.put(evoName, recipe); // reached by buying the base item's recipe
            }
        }

        // Pass 2: recipes, by name.
        for (Map.Entry<String, List<String>> e : recipes.entrySet()) {
            if (skipped.contains(e.getKey())) {
                continue;
            }
            long id = catalog.findItemIdByName(e.getKey()).orElseThrow();
            Map<Long, Integer> qty = new LinkedHashMap<>();
            for (String component : e.getValue()) {
                Optional<Long> cid = catalog.findItemIdByName(component);
                if (cid.isEmpty()) {
                    report.warnings.add(e.getKey() + ": componente desconhecido '" + component + "'");
                    continue;
                }
                qty.merge(cid.get(), 1, Integer::sum);
            }
            List<ComponentRef> refs = new ArrayList<>();
            qty.forEach((cid, q) -> refs.add(new ComponentRef(cid, q)));
            catalog.replaceComponents(id, refs);
        }
        log.info("Catalog import (patch {}): {} inserted, {} updated, {} skipped (edited), {} stat definitions, {} warnings",
                report.patch, report.inserted, report.updated, report.skippedEdited, report.statDefs, report.warnings.size());
        report.warnings.forEach(w -> log.warn("  {}", w));
        return report;
    }

    private void upsert(ItemDef item, boolean overwriteEdited, ImportReport report, Set<String> skipped) {
        Optional<Long> existing = catalog.findItemIdByName(item.name);
        if (existing.isEmpty()) {
            catalog.insertItem(item);
            report.inserted++;
        } else if (!overwriteEdited && catalog.isEdited(existing.get())) {
            report.skippedEdited++;
            skipped.add(item.name);
        } else {
            catalog.updateItem(existing.get(), item);
            report.updated++;
        }
    }

    /** Stat definitions from the price file; returns shop stat name (pt-BR) -> internal stat code. */
    private Map<String, String> importPrices(ImportReport report) {
        Map<String, Map<String, Object>> stats = readYaml(pricesFile);
        Map<String, String> codes = new HashMap<>();
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
            d.factor = num(v.get("factor"));
            d.relevant = RELEVANT_STATS.contains(d.name);
            catalog.upsertStatDef(d);
            if (v.get("loja") != null) {
                codes.put(str(v.get("loja")), d.name);
            }
        }
        report.statDefs = seq;
        return codes;
    }

    /**
     * Builds an item from the shop record. {@code shop} holds the shop-level fields (cost, tabs, section...),
     * {@code data} the stats/passives/efeitos (the record itself, or its evolution).
     */
    @SuppressWarnings("unchecked")
    private ItemDef toItem(String name, Map<String, Object> shop, Map<String, Object> data, String group, String patch,
                           Map<String, String> codes, List<String> warnings) {
        ItemDef item = new ItemDef();
        item.name = name;
        item.group = group;
        item.cost = num(shop.get("custo")).intValue();
        item.section = str(shop.get("secao"));
        item.tabs = new ArrayList<>((List<String>) shop.getOrDefault("abas", List.of()));
        item.category = String.join(", ", item.tabs);
        item.active = Boolean.TRUE.equals(shop.get("ativavel"));
        item.marker = str(shop.get("marcador"));
        item.summary = str(shop.get("resumo"));
        item.capture = str(shop.get("captura"));
        item.sourcePatch = patch;
        item.exclusiveGroups = new ArrayList<>((List<String>) data.getOrDefault("exclusivo", List.of()));

        for (Map<String, Object> s : (List<Map<String, Object>>) data.getOrDefault("status", List.of())) {
            item.stats.add(StatLine.flat(code(str(s.get("tipo")), codes, name, warnings), num(s.get("valor"))));
        }
        for (Map<String, Object> p : (List<Map<String, Object>>) data.getOrDefault("passivas", List.of())) {
            item.passives.add(new PassiveText(str(p.get("nome")), str(p.get("texto"))));
        }
        for (Map<String, Object> e : (List<Map<String, Object>>) data.getOrDefault("efeitos", List.of())) {
            StatLine l = new StatLine();
            l.type = code(str(e.get("tipo")), codes, name, warnings);
            l.passive = str(e.get("passiva"));
            l.conditional = Boolean.TRUE.equals(e.get("condicional"));
            if (e.get("ratio") != null) {
                l.ratio = num(e.get("ratio"));
                l.refType = code(str(e.get("ref")), codes, name, warnings);
                l.refScope = scope(str(e.get("escopo")));
            } else {
                l.value = num(e.get("valor"));
                l.valueMax = num(e.get("valor_nv15"));
            }
            item.stats.add(l);
        }
        return item;
    }

    @SuppressWarnings("unchecked")
    private static List<String> recipeNames(Map<String, Object> row) {
        List<String> names = new ArrayList<>();
        for (Object c : (List<Object>) row.getOrDefault("receita", List.of())) {
            if (c instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) c;
                int q = num(m.get("qtd")).intValue();
                for (int i = 0; i < q; i++) {
                    names.add(str(m.get("item")));
                }
            } else {
                names.add(String.valueOf(c));
            }
        }
        return names;
    }

    private static String code(String shopName, Map<String, String> codes, String item, List<String> warnings) {
        String c = codes.get(shopName);
        if (c == null) {
            warnings.add(item + ": status '" + shopName + "' sem preço definido (mantido com o nome da loja)");
            return shopName;
        }
        return c;
    }

    private static RefScope scope(String s) {
        if (s == null) {
            return RefScope.TOTAL;
        }
        switch (s) {
            case "bonus":
                return RefScope.BONUS;
            case "base":
                return RefScope.BASE;
            default:
                return RefScope.TOTAL;
        }
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
