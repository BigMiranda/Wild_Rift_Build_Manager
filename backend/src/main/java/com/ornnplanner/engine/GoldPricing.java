package com.ornnplanner.engine;

import com.ornnplanner.engine.Model.ItemDef;
import com.ornnplanner.engine.Model.StatLine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Gold-per-stat pricing and static gold efficiency, ported from
 * changchiyou/wildrift-gold-efficiency ({@code data.py}, MIT License, Copyright (c) 2024 changchiyou).
 *
 * <ul>
 *   <li>"exclude": price fixed manually in the stats file.</li>
 *   <li>"first": item that grants only that stat, price = cost / stat amount.</li>
 *   <li>"second": item with two stats, subtract the already known price of the others first.</li>
 *   <li>alias: same price as another stat.</li>
 * </ul>
 * Prices are rounded to 2 decimals exactly like the reference implementation.
 */
public final class GoldPricing {
    private GoldPricing() {
    }

    public static class StatDef {
        public String name;
        public int seq;
        public String category;
        /** first | second | exclude | null */
        public String baseType;
        public String baseItem;
        public Double fixedPrice;
        public String alias;
        public boolean relevant;
    }

    public static class StatPrice {
        public String stat;
        public double price;
        public String method;
        public String formula;
    }

    public static class PriceTable {
        public Map<String, StatPrice> prices = new LinkedHashMap<>();
        public List<String> problems = new ArrayList<>();

        public Map<String, Double> asMap() {
            Map<String, Double> m = new LinkedHashMap<>();
            prices.forEach((k, v) -> m.put(k, v.price));
            return m;
        }
    }

    public static PriceTable computePrices(List<StatDef> defs, Collection<ItemDef> items) {
        PriceTable table = new PriceTable();
        Map<String, ItemDef> byName = new LinkedHashMap<>();
        for (ItemDef i : items) {
            byName.put(i.name, i);
        }
        for (StatDef d : defs) {
            if ("exclude".equals(d.baseType) && d.fixedPrice != null) {
                put(table, d.name, d.fixedPrice, "exclude", "fixed");
            }
        }
        for (String pass : List.of("first", "second")) {
            for (StatDef d : defs) {
                if (!pass.equals(d.baseType)) {
                    continue;
                }
                ItemDef item = byName.get(d.baseItem);
                if (item == null) {
                    table.problems.add("Base item '" + d.baseItem + "' for stat '" + d.name + "' not found");
                    continue;
                }
                double target = sumOfType(item, d.name);
                if (target == 0) {
                    table.problems.add("Base item '" + d.baseItem + "' has no '" + d.name + "'");
                    continue;
                }
                double cost = item.cost;
                StringBuilder minus = new StringBuilder();
                if (pass.equals("second")) {
                    for (StatLine s : item.stats) {
                        if (s.value == null || s.type.equals(d.name)) {
                            continue;
                        }
                        StatPrice other = table.prices.get(s.type);
                        if (other == null) {
                            table.problems.add("Stat '" + s.type + "' has no price while deriving '" + d.name + "'");
                            continue;
                        }
                        cost -= s.value * other.price;
                        minus.append('-').append(fmt(s.value)).append('*').append(fmt(other.price));
                    }
                }
                double price = round2(cost / target);
                String formula = pass.equals("first")
                        ? item.cost + "/" + fmt(target)
                        : "(" + item.cost + minus + ")/" + fmt(target);
                put(table, d.name, price, pass + " (" + item.name + ")", formula);
            }
        }
        for (StatDef d : defs) {
            if (d.alias != null) {
                StatPrice target = table.prices.get(d.alias);
                if (target == null) {
                    table.problems.add("'" + d.name + "' aliases '" + d.alias + "' which has no price");
                } else {
                    put(table, d.name, target.price, "alias of " + d.alias, target.formula);
                }
            }
        }
        return table;
    }

    public static Set<String> baseItemNames(List<StatDef> defs) {
        Set<String> names = new HashSet<>();
        for (StatDef d : defs) {
            if (("first".equals(d.baseType) || "second".equals(d.baseType)) && d.baseItem != null) {
                names.add(d.baseItem);
            }
        }
        return names;
    }

    /** Result of the static (reference site) efficiency calculation. */
    public static class StaticResult {
        public double pct;
        public String formula;
    }

    /**
     * Static gold efficiency exactly as the reference site computes it: percentage passives are applied only
     * to the same item's own flat stats (plus the optional champion "ref" value), never to the rest of the build.
     */
    public static StaticResult staticEfficiency(ItemDef item, Map<String, Double> prices, Set<String> baseItems) {
        StaticResult r = new StaticResult();
        if (baseItems.contains(item.name)) {
            r.pct = 100.0;
            r.formula = "Base Item";
            return r;
        }
        double worth = 0;
        StringBuilder f = new StringBuilder();
        for (StatLine s : item.stats) {
            Double value = s.value;
            if (s.ratio != null) {
                double refBase = 0;
                if (s.refType != null) {
                    for (StatLine o : item.stats) {
                        // The reference skips passive lines (they carry a `ratio` key, even when null).
                        if (o.ratio == null && o.passive == null && o.value != null && o.type.equals(s.refType)) {
                            refBase += o.value;
                        }
                    }
                }
                if (refBase != 0 && s.ref != null) {
                    value = (s.ref + refBase) * s.ratio;
                } else if (refBase != 0) {
                    value = refBase * s.ratio;
                } else if (s.ref != null) {
                    value = s.ref * s.ratio;
                }
                if (value != null) {
                    value = round2(value);
                }
            }
            if (value != null && !s.marker()) {
                double price = prices.getOrDefault(s.type, 0.0);
                worth += value * price;
                if (f.length() > 0) {
                    f.append('+');
                }
                f.append(fmt(value)).append('*').append(fmt(price));
            }
        }
        r.pct = item.cost == 0 ? 0 : round2(worth / item.cost * 100);
        r.formula = "(" + (f.length() == 0 ? "0" : f) + ")/" + item.cost;
        return r;
    }

    static double sumOfType(ItemDef item, String type) {
        double sum = 0;
        for (StatLine s : item.stats) {
            if (s.value != null && s.type.equals(type)) {
                sum += s.value;
            }
        }
        return sum;
    }

    private static void put(PriceTable t, String stat, double price, String method, String formula) {
        StatPrice p = new StatPrice();
        p.stat = stat;
        p.price = price;
        p.method = method;
        p.formula = formula;
        t.prices.put(stat, p);
    }

    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public static String fmt(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e12) {
            return String.valueOf((long) v);
        }
        return String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
