package com.ornnplanner.engine;

import com.ornnplanner.engine.GoldPricing.PriceTable;
import com.ornnplanner.engine.GoldPricing.StatDef;
import com.ornnplanner.engine.Model.ItemDef;
import com.ornnplanner.engine.Model.StatLine;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The static efficiency port must reproduce the "amount" column published by the reference site for every item
 * of the bundled patch (that column was produced by the original data.py).
 */
class GoldPricingReferenceTest {

    @Test
    @SuppressWarnings("unchecked")
    void staticEfficiencyMatchesReferenceSiteForEveryItem() throws Exception {
        List<Map<String, Object>> rows;
        Map<String, Map<String, Object>> statsYaml;
        try (InputStream items = getClass().getResourceAsStream("/referencia/items_7_3.yml");
             InputStream stats = getClass().getResourceAsStream("/referencia/stats_7_3.yml")) {
            rows = new Yaml().load(items);
            statsYaml = new Yaml().load(stats);
        }

        List<StatDef> defs = new ArrayList<>();
        int seq = 0;
        for (Map.Entry<String, Map<String, Object>> e : statsYaml.entrySet()) {
            StatDef d = new StatDef();
            d.name = e.getKey();
            d.seq = seq++;
            d.baseType = (String) e.getValue().get("base_type");
            d.baseItem = (String) e.getValue().get("base_item");
            Object price = e.getValue().get("price");
            d.fixedPrice = price == null ? null : ((Number) price).doubleValue();
            d.alias = (String) e.getValue().get("alias");
            defs.add(d);
        }

        List<ItemDef> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            ItemDef i = new ItemDef();
            i.name = (String) row.get("name");
            i.cost = ((Number) row.get("cost")).intValue();
            for (Map<String, Object> s : (List<Map<String, Object>>) row.get("stats")) {
                StatLine l = new StatLine();
                l.type = (String) s.get("type");
                l.value = s.get("value") == null ? null : ((Number) s.get("value")).doubleValue();
                l.ratio = s.get("ratio") == null ? null : ((Number) s.get("ratio")).doubleValue();
                l.ref = s.get("ref") == null ? null : ((Number) s.get("ref")).doubleValue();
                l.refType = (String) s.get("ref_type");
                l.passive = (String) s.get("passive");
                i.stats.add(l);
            }
            items.add(i);
        }

        PriceTable prices = GoldPricing.computePrices(defs, items);
        assertTrue(prices.problems.isEmpty(), prices.problems.toString());
        assertEquals(3.33, prices.prices.get("Max Health").price, 1e-9);
        assertEquals(25.0, prices.prices.get("Armor").price, 1e-9);
        assertEquals(25.0, prices.prices.get("Magic Resistance").price, 1e-9);
        assertEquals(60.0, prices.prices.get("Ability Haste").price, 1e-9);

        Set<String> baseItems = GoldPricing.baseItemNames(defs);
        int checked = 0;
        for (int k = 0; k < items.size(); k++) {
            String amount = (String) rows.get(k).get("amount");
            double expected = Double.parseDouble(amount.replace("%", ""));
            double actual = GoldPricing.staticEfficiency(items.get(k), prices.asMap(), baseItems).pct;
            assertEquals(expected, actual, 0.011, items.get(k).name);
            checked++;
        }
        assertTrue(checked > 200);
    }
}
