package com.ornnplanner.arena;

import com.ornnplanner.arena.fight.CombatCatalog;
import com.ornnplanner.engine.Model.ItemDef;
import com.ornnplanner.engine.Model.StatLine;
import com.ornnplanner.repo.CatalogRepository;
import com.ornnplanner.seed.ChampionCatalog;
import com.ornnplanner.seed.EffectSpec.Effect;
import com.ornnplanner.seed.ItemEffectCatalog;
import org.springframework.stereotype.Component;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the "visão sob efeitos" panel can apply to a build: the items and the champions' passives and abilities that
 * change how a defender is hit (resistance reduction, % and flat penetration, shield cut, Grievous Wounds), each effect
 * with its default value and, when it grows with a stat (Ocean Trident: ability power; Serpent's Fang: bonus attack
 * damage; Nilah: critical rate), the formula the screen uses to compute it from that stat.
 */
@Component
public class EffectSources {

    /** A source of effects: an item or a champion's passive / ability. */
    public static class Source {
        /** item | campeao */
        public String group;
        public String name;
        public Long itemId;
        public String champion;
        public List<Option> effects = new ArrayList<>();
    }

    /**
     * One effect of a source. value = min(max, base + per x the stat) when it scales ({@code scale} names the stat);
     * otherwise {@code value}. Shares are fractions; flat penetration in points.
     */
    public static class Option {
        public String kind;
        public double value;
        public Double base;
        public Double per;
        public Double max;
        public String scale;
        /** The value in words, from the seed ("40% + 0,05% por PdH, até 60%", "10/20/30% por rank"). */
        public String text;
    }

    /** Shop stat (code or pt-BR shop name) -> kind of applied effect. */
    private static final Map<String, String> PEN = Map.of(
            "% Armor Penetration", SeenBy.ARMOR_PEN_PCT, "% Penetração de Armadura", SeenBy.ARMOR_PEN_PCT,
            "Armor Penetration", SeenBy.ARMOR_PEN, "Penetração de Armadura", SeenBy.ARMOR_PEN,
            "% Magic Penetration", SeenBy.MAGIC_PEN_PCT, "% Penetração Mágica", SeenBy.MAGIC_PEN_PCT,
            "Magic Penetration", SeenBy.MAGIC_PEN, "Penetração Mágica", SeenBy.MAGIC_PEN);

    /** The stat a "ref" of a champion line scales with, in words. */
    private static final Map<String, String> REF = Map.of("% Taxa de Crítico", "Taxa de Crítico (%)",
            "% Critical Rate", "Taxa de Crítico (%)");

    private final CombatCatalog combat;
    private final CatalogRepository shop;
    private final ItemEffectCatalog itemEffects;
    private final ChampionCatalog champions;
    private final ArenaService arena;

    public EffectSources(CombatCatalog combat, CatalogRepository shop, ItemEffectCatalog itemEffects,
                         ChampionCatalog champions, ArenaService arena) {
        this.combat = combat;
        this.shop = shop;
        this.itemEffects = itemEffects;
        this.champions = champions;
        this.arena = arena;
    }

    /** Every source, items first then champions, each group in name order. */
    public List<Source> list() {
        Collator pt = Collator.getInstance(new Locale("pt", "BR"));
        Map<String, Source> items = new LinkedHashMap<>();
        Map<String, Long> ids = new LinkedHashMap<>();
        for (ItemDef i : shop.findAllItems().values()) {
            ids.putIfAbsent(i.name, i.id);
            for (StatLine l : i.stats) {
                String kind = PEN.get(l.type);
                if (kind != null && l.ratio == null && l.value != null && l.value > 0) {
                    flat(item(items, ids, i.name), kind, l.value, l.valueMax);
                }
            }
        }
        itemEffects.all().forEach((name, list) -> {
            for (Effect e : list) {
                for (Map<String, Object> l : e.rawLines()) {
                    String kind = PEN.get(String.valueOf(l.get("tipo")));
                    if (kind != null && l.get("ratio") == null && l.get("valor") != null) {
                        flat(item(items, ids, name), kind, last(l.get("valor")),
                                l.get("valor_nv15") == null ? null : last(l.get("valor_nv15")));
                    }
                }
            }
        });
        combat.items().forEach((name, list) -> {
            for (Map<String, Object> e : list) {
                Option o = combatOption(e);
                if (o != null) {
                    item(items, ids, name).effects.add(o);
                }
            }
        });
        List<Source> out = new ArrayList<>(items.values());
        out.sort((a, b) -> pt.compare(a.name, b.name));

        Map<String, Source> champs = new LinkedHashMap<>();
        for (Map<String, Object> c : champions.summaries()) {
            String champ = String.valueOf(c.get("nome"));
            for (Effect e : champions.effects(String.valueOf(c.get("code")))) {
                for (Map<String, Object> l : e.rawLines()) {
                    String kind = PEN.get(String.valueOf(l.get("tipo")));
                    if (kind == null || Boolean.TRUE.equals(l.get("condicional"))) {
                        continue;
                    }
                    Option o = l.get("ratio") != null ? ratioOption(kind, l) : rankOption(kind, l.get("valor"), 1);
                    if (o != null) {
                        champion(champs, champ, e.name, e.ability).effects.add(o);
                    }
                }
            }
        }
        kitShreds(champs);
        List<Source> cs = new ArrayList<>(champs.values());
        cs.sort((a, b) -> pt.compare(a.name, b.name));
        out.addAll(cs);
        return out;
    }

    private static Source item(Map<String, Source> items, Map<String, Long> ids, String name) {
        return items.computeIfAbsent(name, n -> {
            Source s = new Source();
            s.group = "item";
            s.name = n;
            s.itemId = ids.get(n);
            return s;
        });
    }

    private static Source champion(Map<String, Source> champs, String champ, String ability, String slot) {
        return champs.computeIfAbsent(champ + "|" + ability, k -> {
            Source s = new Source();
            s.group = "campeao";
            s.champion = champ;
            s.name = champ + ": " + ability + (slot == null ? "" : " (" + slot + ")");
            return s;
        });
    }

    /** A flat value of a shop / passive line (penetration), at level 1 (the level 15 one in the text). */
    private static void flat(Source s, String kind, double v, Double v15) {
        boolean share = kind.endsWith("_pct");
        Option o = new Option();
        o.kind = kind;
        o.value = share ? v / 100 : v;
        o.text = share ? num(v) + "%" : v15 != null && v15 > v ? num(v) + "–" + num(v15) + " pelo nível" : num(v);
        if (s.effects.stream().noneMatch(x -> x.kind.equals(o.kind) && x.value == o.value)) {
            s.effects.add(o);
        }
    }

    /** An item's combat effect that changes how a defender is hit, at its most (null = none). */
    public static Option combatOption(Map<String, Object> e) {
        String tipo = String.valueOf(e.get("tipo"));
        Option o = new Option();
        switch (tipo) {
            case "reduz_resistencia":
                o.kind = "armadura".equals(e.get("resistencia")) ? SeenBy.ARMOR_SHRED : SeenBy.MAGIC_SHRED;
                o.value = Arena.num(e.get("max"), 0.3);
                o.text = pct(Arena.num(e.get("por_acerto"), o.value)) + " por acerto, até " + pct(o.value);
                return o;
            case "feridas":
                if (Boolean.TRUE.equals(e.get("ao_ser_atingido"))) {
                    return null;
                }
                o.kind = SeenBy.WOUNDS;
                o.value = Arena.num(e.get("pct"), 0.4);
                o.text = pct(o.value);
                return o;
            case "corta_escudo":
                o.kind = SeenBy.SHIELD_CUT;
                o.base = Arena.num(e.get("base"), 0);
                o.max = Arena.num(e.get("max"), 0.6);
                o.value = o.base;
                if (e.get("dda_bonus") != null) {
                    o.per = Arena.num(e.get("dda_bonus"), 0);
                    o.scale = "DdA adicional";
                } else if (e.get("pdh") != null) {
                    o.per = Arena.num(e.get("pdh"), 0);
                    o.scale = "PdH";
                }
                o.text = pct(o.base) + (o.per == null ? "" : " + " + pct(o.per) + " por " + o.scale) + ", até " + pct(o.max);
                return o;
            default:
                return null;
        }
    }

    /** A champion line by rank ([10, 20, 30]): the highest rank by default; {@code times} for stacks. */
    private static Option rankOption(String kind, Object valor, int times) {
        if (valor == null) {
            return null;
        }
        boolean share = kind.endsWith("_pct") || kind.equals(SeenBy.ARMOR_SHRED) || kind.equals(SeenBy.MAGIC_SHRED);
        double scale = kind.endsWith("_pct") ? 100 : 1;
        List<Double> ranks = new ArrayList<>();
        if (valor instanceof List) {
            for (Object v : (List<?>) valor) {
                ranks.add(Arena.num(v, 0) * times / scale);
            }
        } else {
            ranks.add(Arena.num(valor, 0) * times / scale);
        }
        Option o = new Option();
        o.kind = kind;
        o.value = ranks.get(ranks.size() - 1);
        StringBuilder b = new StringBuilder();
        for (double v : ranks) {
            b.append(b.length() == 0 ? "" : "/").append(share ? num(v * 100) : num(v));
        }
        o.text = b + (share ? "%" : "") + (ranks.size() > 1 ? " por rank" : "");
        return o;
    }

    /** A champion line that converts a stat (Nilah: 28% of the critical rate as armor penetration). */
    private static Option ratioOption(String kind, Map<String, Object> l) {
        String ref = String.valueOf(l.get("ref"));
        String scale = REF.get(ref);
        if (scale == null) {
            return null;
        }
        double ratio = Arena.num(l.get("ratio"), 0);
        Option o = new Option();
        o.kind = kind;
        o.base = 0.0;
        o.per = kind.endsWith("_pct") ? ratio / 100 : ratio;
        o.max = kind.endsWith("_pct") ? ratio : null;
        o.value = 0;
        o.text = num(ratio * 100) + "% da " + scale.replace(" (%)", "");
        return o;
    }

    /** Resistance reduction of the champions' abilities in the arena kits (reduz_arm / reduz_rm), at the top rank. */
    @SuppressWarnings("unchecked")
    private void kitShreds(Map<String, Source> champs) {
        Map<String, Object> kits = (Map<String, Object>) arena.kits().get("campeoes");
        if (kits == null) {
            return;
        }
        kits.forEach((champ, k) -> {
            Map<String, Object> abilities = (Map<String, Object>) ((Map<String, Object>) k).get("habilidades");
            if (abilities == null) {
                return;
            }
            abilities.forEach((slot, a) -> {
                Map<String, Object> ab = (Map<String, Object>) a;
                for (String where : List.of("efeitos", "efeitos_fortalecido")) {
                    Map<String, Object> ef = (Map<String, Object>) ab.get(where);
                    if (ef == null) {
                        continue;
                    }
                    for (String res : List.of("reduz_arm", "reduz_rm")) {
                        Map<String, Object> spec = (Map<String, Object>) ef.get(res);
                        if (spec == null) {
                            continue;
                        }
                        int stacks = (int) Arena.num(spec.get("acumula"), 1);
                        Option o = rankOption(res.equals("reduz_arm") ? SeenBy.ARMOR_SHRED : SeenBy.MAGIC_SHRED,
                                spec.get("pct"), stacks);
                        if (stacks > 1) {
                            o.text += " (" + stacks + " acúmulos)";
                        }
                        if (where.equals("efeitos_fortalecido")) {
                            o.text += " (fortalecida)";
                        }
                        String name = ab.get("nome") == null ? slot : String.valueOf(ab.get("nome"));
                        champion(champs, champ, name, slot).effects.add(o);
                    }
                }
            });
        });
    }

    private static double last(Object v) {
        return v instanceof List ? Arena.num(((List<?>) v).get(((List<?>) v).size() - 1), 0) : Arena.num(v, 0);
    }

    private static String pct(double v) {
        return num(v * 100) + "%";
    }

    /** A number in pt-BR with up to two decimals ("0,05", "22,5", "30"). */
    private static String num(double v) {
        double r = Math.round(v * 100) / 100.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r).replace('.', ',');
    }
}
