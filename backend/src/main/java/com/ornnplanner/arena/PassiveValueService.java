package com.ornnplanner.arena;

import com.ornnplanner.arena.EffectModel.Measures;
import com.ornnplanner.arena.fight.CombatCatalog;
import com.ornnplanner.arena.fight.FighterBuilder;
import com.ornnplanner.engine.Model.ItemContribution;
import com.ornnplanner.engine.Model.ItemDef;
import com.ornnplanner.engine.Model.ItemEdit;
import com.ornnplanner.engine.Model.MinutePoint;
import com.ornnplanner.engine.Model.StatLine;
import com.ornnplanner.engine.Model.TimelineResult;
import com.ornnplanner.engine.Stats;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.seed.EffectSpec;
import com.ornnplanner.seed.ItemEffectCatalog;
import com.ornnplanner.service.PlannerService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleUnaryOperator;

/**
 * What the passives of a build are worth in gold at a minute, past the value by the item's tier. Estimated: the numbers
 * of the moment (the arena's formulas) through the equivalences of {@code equivalencias_passivas_<patch>.yml}. Measured:
 * the fight with one passive off against the fight with it on, in a test scenario, the basic report or a saved arena
 * scenario, and the gold in pure stats that gives the same gain (the stats of the effectiveness reading "same gold").
 * A passive that only gives stats is left out of both: the stats' gold already is its measure.
 */
@Service
public class PassiveValueService {

    public static final String ESTIMATED = "estimado";
    public static final String TIER = "tier";
    public static final String STATUS = "status";

    /** One passive of an item at a minute. {@code gold} null = it stays at the tier's value. */
    public static class PassiveValue {
        public String name;
        /** estimado | tier | status */
        public String source;
        public Double gold;
        /** The account, in words. */
        public String detail;
        /** It does something in a fight: the measured mode can read it. */
        public boolean measurable;
    }

    public static class ItemValues {
        public long itemId;
        public String itemName;
        public List<PassiveValue> passives = new ArrayList<>();
    }

    public static class Sample {
        public double minute;
        public List<ItemValues> items = new ArrayList<>();
    }

    public static class EstimateRequest {
        public Long buildId;
        public BuildRepository.Build build;
        public List<Double> minutes = new ArrayList<>();
    }

    /** Which fight measures: a test scenario (teste + key), the basic report (basico) or a saved scenario (arena + id). */
    public static class Scenario {
        public String kind = "basico";
        public String key;
        public Long id;
    }

    public static class Job {
        public double minute;
        public long itemId;
        public String passive;
    }

    public static class MeasureRequest {
        public Long buildId;
        public BuildRepository.Build build;
        public Scenario scenario = new Scenario();
        public List<Job> jobs = new ArrayList<>();
    }

    /** A passive measured: the gold that gives the same gain, and how much the fight loses without it. */
    public static class Reading {
        public double minute;
        public long itemId;
        public String passive;
        /** Negative: the passive makes the result worse. */
        public double gold;
        /** % of the result lost with the passive off (negative: the fight gets better without it). */
        public double lossPct;
        public double scoreOn;
        public double scoreOff;
        /** The gain is in damage dealt (no change in the time to die): the basket is attack damage or ability power. */
        public boolean damage;
        /** The search hit the top of the range: the value is at least this. */
        public boolean capped;
    }

    private final PlannerService planner;
    private final ArenaService arena;
    private final EffectTestService effect;
    private final CombatCatalog catalog;
    private final ItemEffectCatalog itemEffects;
    private final BuildRepository builds;
    private final Map<String, Object> trade;
    private final Map<String, Object> shield;
    private final Map<String, Object> search;
    private final double shredFactor;
    private final Map<String, List<Map<String, Object>>> lines = new ConcurrentHashMap<>();
    private final Map<String, Reading> readings = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public PassiveValueService(PlannerService planner, ArenaService arena, EffectTestService effect, CombatCatalog catalog,
                               ItemEffectCatalog itemEffects, BuildRepository builds,
                               @Value("${planner.seed.passiveEquivalences:classpath:seed/equivalencias_passivas_7_3d.yml}") Resource file) {
        this.planner = planner;
        this.arena = arena;
        this.effect = effect;
        this.catalog = catalog;
        this.itemEffects = itemEffects;
        this.builds = builds;
        try (InputStream in = file.getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            trade = (Map<String, Object>) root.get("troca");
            shield = (Map<String, Object>) root.get("escudo");
            search = (Map<String, Object>) root.get("busca");
            shredFactor = num(((Map<String, Object>) root.get("reducao")).get("fator"), 0.75);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + file, e);
        }
    }

    private BuildRepository.Build buildOf(BuildRepository.Build build, Long id) {
        if (build != null) {
            return build;
        }
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha uma build.");
        }
        return builds.findBuild(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Build " + id + " não existe."));
    }

    // ------------------------------------------------------------------ estimated

    /** The passives of the items held at each minute, estimated by the numbers of that moment. */
    public List<Sample> estimate(EstimateRequest req) {
        BuildRepository.Build b = buildOf(req.build, req.buildId);
        TimelineResult r = planner.calculate(b);
        if (r.series.isEmpty()) {
            return List.of();
        }
        Map<Long, ItemDef> items = planner.referenceData().items;
        Map<String, Double> prices = r.statPrices;
        String key = EffectTestService.hash(EffectTestService.write(b));
        List<Callable<Sample>> runs = new ArrayList<>();
        for (double minute : new TreeSet<>(req.minutes)) {
            runs.add(() -> sample(b, key, r, minute, items, prices));
        }
        return ArenaService.parallel(runs);
    }

    private Sample sample(BuildRepository.Build b, String key, TimelineResult r, double minute, Map<Long, ItemDef> items,
                          Map<String, Double> prices) {
        Sample s = new Sample();
        s.minute = minute;
        MinutePoint p = point(r, minute);
        if (lines.size() > 2000) {
            lines.clear();
        }
        List<Map<String, Object>> all = lines.computeIfAbsent(key + "@" + minute, k -> arena.effectValues(b, minute));
        Set<Long> seen = new LinkedHashSet<>();
        for (ItemContribution c : p.contributions) {
            ItemDef it = items.get(c.itemId);
            if (c.rune || it == null || !seen.add(c.itemId)) {
                continue;
            }
            s.items.add(values(it, c, all, prices));
        }
        return s;
    }

    private ItemValues values(ItemDef it, ItemContribution c, List<Map<String, Object>> all, Map<String, Double> prices) {
        ItemValues out = new ItemValues();
        out.itemId = it.id;
        out.itemName = it.name;
        Map<String, List<Map<String, Object>>> byPassive = new LinkedHashMap<>();
        for (Map<String, Object> line : all) {
            if (!it.name.equals(line.get("source"))) {
                continue;
            }
            String passive = passiveOf(it, line);
            if (passive != null) {
                byPassive.computeIfAbsent(passive, k -> new ArrayList<>()).add(line);
            }
        }
        Set<String> withStats = new LinkedHashSet<>();
        c.passiveParts.forEach(x -> withStats.add(x.passive));
        Set<String> names = new LinkedHashSet<>();
        it.passives.forEach(x -> {
            if (x.name != null) {
                names.add(x.name);
            }
        });
        names.addAll(withStats);
        for (String name : names) {
            PassiveValue v = new PassiveValue();
            v.name = name;
            List<Map<String, Object>> fx = byPassive.getOrDefault(name, List.of());
            v.measurable = fx.stream().anyMatch(PassiveValueService::actsInTheFight);
            double gold = 0;
            boolean any = false;
            List<String> account = new ArrayList<>();
            for (Map<String, Object> line : fx) {
                Estimate e = estimateLine(line, it, prices);
                if (e != null) {
                    gold += e.gold;
                    any = true;
                    account.add(e.detail);
                }
            }
            if (any) {
                v.source = ESTIMATED;
                v.gold = gold;
                v.detail = String.join(" · ", account);
            } else if (fx.isEmpty() && withStats.contains(name)) {
                v.source = STATUS;
            } else {
                v.source = withStats.contains(name) ? STATUS : TIER;
            }
            out.passives.add(v);
        }
        return out;
    }

    /** A trigger that only gives stats does not count: the stats' account already is its measure. */
    @SuppressWarnings("unchecked")
    private static boolean actsInTheFight(Map<String, Object> line) {
        if (!"gatilho".equals(line.get("kind"))) {
            return true;
        }
        Object delta = line.get("delta");
        return delta instanceof Map && ((Map<String, Object>) delta).keySet().stream().anyMatch(FIGHT_STATS::contains);
    }

    private static final Set<String> FIGHT_STATS = Set.of("Shield", "Magic Shield", "Physical Shield", "Heal", "Stasis");

    /** The passive a line of the arena's values belongs to. */
    @SuppressWarnings("unchecked")
    private String passiveOf(ItemDef it, Map<String, Object> line) {
        if ("gatilho".equals(line.get("kind"))) {
            String fx = String.valueOf(line.get("effect"));
            for (EffectSpec.Effect e : itemEffects.effects(it.name)) {
                if (e.name.equals(fx)) {
                    return e.passive != null ? e.passive : e.name;
                }
            }
            return fx;
        }
        Object spec = line.get("spec");
        return spec instanceof Map ? CombatCatalog.passiveOf(it, (Map<String, Object>) spec) : null;
    }

    /** An estimated value and its account. */
    static final class Estimate {
        final double gold;
        final String detail;

        Estimate(double gold, String detail) {
            this.gold = gold;
            this.detail = detail;
        }
    }

    @SuppressWarnings("unchecked")
    private Estimate estimateLine(Map<String, Object> line, ItemDef it, Map<String, Double> prices) {
        String kind = String.valueOf(line.get("kind"));
        Map<String, Object> spec = line.get("spec") instanceof Map ? (Map<String, Object>) line.get("spec") : Map.of();
        double value = num(line.get("value"), 0);
        double dur = num(trade.get("duracao"), 10);
        double attacks = num(trade.get("ataques"), 8);
        double abilities = num(trade.get("habilidades"), 3);
        double targetHp = num(trade.get("vida_alvo"), 3000);
        double hits;
        switch (kind) {
            case "ao_contato":
                hits = attacks;
                break;
            case "a_cada_ataque":
                hits = attacks / Math.max(1, num(spec.get("n"), 3));
                break;
            case "lamina":
                hits = Math.min(abilities, dur / 1.5);
                break;
            case "queimadura":
                hits = Math.min(abilities, dur / Math.max(1, num(spec.get("duracao"), 3)));
                value += num(spec.get("vida_max_alvo_por_s"), 0) * targetHp * num(spec.get("duracao"), 3);
                break;
            case "aura":
                hits = dur;
                break;
            case "golpe":
                hits = num(spec.get("recarga"), 10) <= 0 ? abilities : 1 + Math.floor(dur / num(spec.get("recarga"), 10));
                break;
            case "espinhos":
                hits = num(trade.get("ataques_recebidos"), 10);
                break;
            case "ativo":
                hits = 1 + Math.floor(dur / Math.max(1, num(spec.get("recarga"), 60)));
                break;
            case "reduz_resistencia": {
                boolean armor = "armadura".equals(spec.get("resistencia"));
                double price = prices.getOrDefault(armor ? "% Armor Penetration" : "% Magic Penetration", 0.0);
                double pct = 100 * num(spec.get("max"), 0.3);
                return price <= 0 ? null : new Estimate(pct * shredFactor * price, "penetração equivalente: " + round(pct) + "% × "
                        + shredFactor + " × " + round(price) + " de ouro por ponto");
            }
            case "gatilho":
                return shieldEstimate(line, prices);
            default:
                return null;
        }
        value += num(spec.get("vida_max_alvo"), 0) * targetHp + num(spec.get("vida_atual_alvo"), 0) * targetHp;
        double damage = value * hits;
        String type = String.valueOf(line.get("type"));
        boolean magic = "magico".equals(type) || "adaptativo".equals(type);
        double perPoint = magic ? abilities * num(trade.get("razao_pdh"), 0.7)
                : attacks + abilities * num(trade.get("razao_dda"), 1);
        double price = prices.getOrDefault(magic ? Stats.ABILITY_POWER : Stats.ATTACK_DAMAGE, 0.0);
        if (perPoint <= 0 || price <= 0 || damage <= 0) {
            return null;
        }
        double points = damage / perPoint;
        return new Estimate(points * price, round(value) + " de dano × " + round(hits) + " na troca de " + round(dur) + "s = "
                + round(damage) + " = " + round(points) + (magic ? " de PdH" : " de DdA") + " × " + round(price) + " de ouro");
    }

    /** A shield of a trigger: its gold as the stat, times how often it is there when it matters. */
    @SuppressWarnings("unchecked")
    private Estimate shieldEstimate(Map<String, Object> line, Map<String, Double> prices) {
        Map<String, Object> delta = line.get("delta") instanceof Map ? (Map<String, Object>) line.get("delta") : Map.of();
        Map<String, Object> spec = line.get("spec") instanceof Map ? (Map<String, Object>) line.get("spec") : Map.of();
        double gold = 0;
        double amount = 0;
        for (String stat : List.of("Shield", "Magic Shield", "Physical Shield")) {
            double v = num(delta.get(stat), 0);
            gold += v * prices.getOrDefault(stat, 0.0);
            amount += v;
        }
        if (amount <= 0) {
            return null;
        }
        double factor = availability(spec, shield);
        return new Estimate(gold * factor, round(amount) + " de escudo = " + round(gold) + " de ouro × disponibilidade " + round(factor));
    }

    /** Availability of a shield: trigger × cooldown × duration. */
    @SuppressWarnings("unchecked")
    static double availability(Map<String, Object> spec, Map<String, Object> shield) {
        Map<String, Object> by = (Map<String, Object>) shield.get("gatilho");
        double trigger = num(by.get(String.valueOf(spec.get("quando"))), num(by.get("padrao"), 0.7));
        double cooldown = num(spec.get("recarga"), 0);
        double window = num(shield.get("janela_recarga"), 30);
        double duration = num(spec.containsKey("duracao") ? spec.get("duracao") : spec.get("duracao_escudo"), 0);
        double full = num(shield.get("duracao_cheia"), 3);
        return trigger * (cooldown > window ? window / cooldown : 1) * (duration > 0 && duration < full ? duration / full : 1);
    }

    // ------------------------------------------------------------------ measured

    /** A batch of passives: each its fights, in parallel, cached by build, minute, scenario and passive. */
    public List<Reading> measure(MeasureRequest req) {
        BuildRepository.Build b = buildOf(req.build, req.buildId);
        Scenario sc = req.scenario == null ? new Scenario() : req.scenario;
        String key = EffectTestService.hash(EffectTestService.write(b)) + "|" + sc.kind + "|" + sc.key + "|" + sc.id;
        if (readings.size() > 20000) {
            readings.clear();
        }
        List<Callable<Reading>> runs = new ArrayList<>();
        for (Job j : req.jobs) {
            String k = key + "|" + j.minute + "|" + j.itemId + "|" + j.passive;
            runs.add(() -> {
                Reading r = readings.get(k);
                if (r == null) {
                    r = read(b, sc, j);
                    readings.put(k, r);
                }
                return r;
            });
        }
        return ArenaService.parallel(runs);
    }

    private Reading read(BuildRepository.Build b, Scenario sc, Job j) {
        Map<Long, ItemEdit> none = Map.of();
        ItemEdit offOnly = new ItemEdit();
        offOnly.off.add(j.passive);
        Map<Long, ItemEdit> off = Map.of(j.itemId, offOnly);
        double[] on = score(b, sc, j.minute, none);
        double[] without = score(b, sc, j.minute, off);
        double minChange = num(search.get("variacao_minima"), 0.005);
        boolean damage = Math.abs(without[0] - on[0]) < minChange * Math.max(1e-9, on[0]) && Math.abs(without[1] - on[1]) > minChange * on[1];
        int metric = damage ? 1 : 0;
        Reading r = new Reading();
        r.minute = j.minute;
        r.itemId = j.itemId;
        r.passive = j.passive;
        r.damage = damage;
        r.scoreOn = on[metric];
        r.scoreOff = without[metric];
        r.lossPct = on[metric] > 0 ? 100 * (1 - without[metric] / on[metric]) : 0;
        Map<String, Double> basket = damage ? damageBasket(b, j.minute) : defenseBasket();
        double top = num(search.get("ouro_maximo"), 6000);
        double tol = num(search.get("tolerancia"), 0.01) * Math.max(1e-9, on[metric]);
        int steps = (int) num(search.get("passos"), 6);
        if (Math.abs(r.scoreOn - r.scoreOff) <= tol) {
            return r;
        }
        boolean negative = r.scoreOff > r.scoreOn;
        // Worse with it: the gold the build with the passive needs to get back to the fight without it.
        DoubleUnaryOperator curve = gold -> {
            ItemEdit e = new ItemEdit();
            if (!negative) {
                e.off.add(j.passive);
            }
            e.extra.putAll(effect.goldStats(gold, basket));
            return score(b, sc, j.minute, Map.of(j.itemId, e))[metric];
        };
        GoldSearch.Found f = GoldSearch.find(curve, negative ? r.scoreOn : r.scoreOff, negative ? r.scoreOff : r.scoreOn, top, tol, steps);
        r.gold = negative ? -f.gold : f.gold;
        r.capped = f.capped;
        return r;
    }

    /** {time to die, damage dealt} of the build in the scenario. */
    private double[] score(BuildRepository.Build b, Scenario sc, double minute, Map<Long, ItemEdit> edits) {
        String kind = sc.kind == null ? "basico" : sc.kind;
        if ("arena".equals(kind)) {
            if (sc.id == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha um cenário salvo.");
            }
            Measures m = effect.savedScenario(sc.id, b, minute, edits);
            return new double[] {m.timeToDeath, m.dealt};
        }
        if ("teste".equals(kind)) {
            Measures m = effect.fightWith(b, minute, sc.key, edits);
            return new double[] {m.timeToDeath, m.dealt};
        }
        EffectModel.Report rep = effect.report("basico");
        double ttd = 0;
        double dealt = 0;
        double w = 0;
        for (EffectModel.Entry e : rep.tests) {
            Measures m = effect.fightWith(b, minute, e.key, edits);
            ttd += e.weight * m.timeToDeath;
            dealt += e.weight * m.dealt;
            w += e.weight;
        }
        return new double[] {w > 0 ? ttd / w : 0, w > 0 ? dealt / w : 0};
    }

    @SuppressWarnings("unchecked")
    private Map<String, Double> defenseBasket() {
        Map<String, Double> out = new LinkedHashMap<>();
        ((Map<String, Object>) search.get("cesta_defesa")).forEach((k, v) -> out.put(k, num(v, 0)));
        return out;
    }

    /** All the gold in the damage stat the build leans on. */
    private Map<String, Double> damageBasket(BuildRepository.Build b, double minute) {
        MinutePoint p = point(planner.calculate(b), minute);
        boolean ap = p.total.getOrDefault(Stats.ABILITY_POWER, 0.0) > p.total.getOrDefault(Stats.ATTACK_DAMAGE, 0.0);
        return Map.of(ap ? Stats.ABILITY_POWER : Stats.ATTACK_DAMAGE, 1.0);
    }

    private static MinutePoint point(TimelineResult r, double minute) {
        MinutePoint best = r.series.get(0);
        for (MinutePoint p : r.series) {
            if (p.minute <= minute + 1e-9) {
                best = p;
            }
        }
        return best;
    }

    private static String round(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 100) / 100.0).replace('.', ',');
    }

    private static double num(Object o, double def) {
        return o instanceof Number ? ((Number) o).doubleValue() : def;
    }
}
