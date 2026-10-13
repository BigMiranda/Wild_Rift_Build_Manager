package com.ornnplanner.combat;

import com.ornnplanner.combat.CombatSimulator.Fighter;
import com.ornnplanner.combat.CombatSimulator.FighterResult;
import com.ornnplanner.combat.CombatSimulator.ItemEffect;
import com.ornnplanner.combat.CombatSimulator.Ledger;
import com.ornnplanner.combat.CombatSimulator.Result;
import com.ornnplanner.engine.Model.ItemContribution;
import com.ornnplanner.engine.Model.MinutePoint;
import com.ornnplanner.engine.Model.PassivePart;
import com.ornnplanner.engine.Model.TimelineResult;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.repo.BuildRepository.Build;
import com.ornnplanner.seed.ChampionCatalog;
import com.ornnplanner.service.PlannerService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Matchup simulator: each side is a list of saved builds, each at a game minute (its items, level and stats at that
 * moment), with an order of actions and a target. Runs the fight and, for every item of every champion, the same fight
 * without that item (its stats and effects removed, the rest of the build kept): what changes is what the item was
 * worth in that fight, next to the ledger of what its effects did and what the enemy's items cancelled of it.
 */
@Service
public class CombatService {

    /** Up to 5 per side, 6 when a champion brings clones (Wukong). */
    public static final int MAX_TEAM = 6;
    public static final double MAX_DURATION = 120;

    public static class Combatant {
        public Long buildId;
        /** Game minute (null = the build's end of match, else its last purchase). */
        public Double minute;
        public List<String> order;
        public String target;
        /** Seconds of the fight at which a delayed status turns on (source -> s), instead of its own conditions. */
        public Map<String, Double> activations;
    }

    public static class Request {
        public List<Combatant> teamA = new ArrayList<>();
        public List<Combatant> teamB = new ArrayList<>();
        public Double duration;
        /** Also run the fight without each item (default true). */
        public Boolean counterfactual;
    }

    public static class FighterInfo {
        public int index;
        public int team;
        public Long buildId;
        public String build;
        public String champion;
        public int level;
        public double minute;
        public List<Map<String, Object>> items = new ArrayList<>();
        public List<Map<String, Object>> abilities = new ArrayList<>();
        public double attackSpeed;
        public Map<String, Double> stats = new LinkedHashMap<>();
        /** Statuses that only count after some time / hits, with their conditions. */
        public List<Map<String, Object>> delays = new ArrayList<>();
    }

    /** What an item did in the fight, and how the fight goes without it. */
    public static class ItemReport {
        public int fighter;
        public String item;
        public Long itemId;
        public Ledger ledger;
        public Integer winnerWithout;
        public Double durationWithout;
        public Double dealtWithout;
        public Double takenWithout;
        public Double aliveWithout;
        /** Health left (health + shields) of the fighter's team and of the enemy team without the item. */
        public Double teamHealthWithout;
        public Double enemyHealthWithout;
    }

    public static class Response {
        public Result result;
        public List<FighterInfo> fighters = new ArrayList<>();
        public List<ItemReport> items = new ArrayList<>();
        /** Ledger of the other sources (abilities, attacks, runes, spells). */
        public List<Ledger> others = new ArrayList<>();
        public double teamHealth;
        public double enemyHealth;
        public List<String> warnings = new ArrayList<>();
    }

    /** A combatant resolved: its build, inventory, level and minute. */
    private static final class Resolved {
        Build build;
        List<Long> items;
        int level;
        double minute;
        Combatant c;
    }

    private final BuildRepository builds;
    private final PlannerService planner;
    private final ChampionCatalog champions;
    private final CombatCatalog catalog;
    private final com.ornnplanner.seed.ItemEffectCatalog itemEffects;
    private final com.ornnplanner.seed.RuneCatalog runes;

    public CombatService(BuildRepository builds, PlannerService planner, ChampionCatalog champions, CombatCatalog catalog,
                         com.ornnplanner.seed.ItemEffectCatalog itemEffects, com.ornnplanner.seed.RuneCatalog runes) {
        this.builds = builds;
        this.planner = planner;
        this.champions = champions;
        this.catalog = catalog;
        this.itemEffects = itemEffects;
        this.runes = runes;
    }

    public Response simulate(Request req) {
        if (req.teamA == null || req.teamB == null || req.teamA.isEmpty() || req.teamB.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cada lado precisa de ao menos 1 campeão.");
        }
        if (req.teamA.size() > MAX_TEAM || req.teamB.size() > MAX_TEAM) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No máximo " + MAX_TEAM + " por lado.");
        }
        double duration = Math.max(5, Math.min(MAX_DURATION, req.duration == null ? 30 : req.duration));
        Response out = new Response();
        List<Resolved> a = new ArrayList<>();
        List<Resolved> b = new ArrayList<>();
        for (Combatant c : req.teamA) {
            a.add(resolve(c));
        }
        for (Combatant c : req.teamB) {
            b.add(resolve(c));
        }
        List<Fighter> fa = new ArrayList<>();
        List<Fighter> fb = new ArrayList<>();
        for (Resolved r : a) {
            fa.add(fighter(r, r.items));
        }
        for (Resolved r : b) {
            fb.add(fighter(r, r.items));
        }
        out.result = new CombatSimulator(catalog).run(fa, fb, duration);
        List<Fighter> everyone = new ArrayList<>(fa);
        everyone.addAll(fb);
        for (int i = 0; i < everyone.size(); i++) {
            out.fighters.add(info(everyone.get(i), (i < a.size() ? a.get(i) : b.get(i - a.size())).build));
        }
        out.teamHealth = health(out.result, 0);
        out.enemyHealth = health(out.result, 1);

        Map<String, Ledger> byKey = new LinkedHashMap<>();
        for (Ledger l : out.result.ledger) {
            byKey.put(l.fighter + "|" + l.source, l);
        }
        boolean counterfactual = req.counterfactual == null || req.counterfactual;
        List<Resolved> resolved = new ArrayList<>(a);
        resolved.addAll(b);
        for (int i = 0; i < everyone.size(); i++) {
            Fighter f = everyone.get(i);
            Set<Long> seen = new LinkedHashSet<>();
            for (Map.Entry<String, Long> item : f.items.entrySet()) {
                if (!seen.add(item.getValue())) {
                    continue;
                }
                ItemReport rep = new ItemReport();
                rep.fighter = i;
                rep.item = item.getKey();
                rep.itemId = item.getValue();
                rep.ledger = byKey.remove(i + "|" + item.getKey());
                if (counterfactual) {
                    without(rep, i, item.getValue(), resolved, everyone, a.size(), duration);
                }
                out.items.add(rep);
            }
        }
        out.others.addAll(byKey.values());
        return out;
    }

    /** The fight again with the fighter's item removed (the other fighters as they were). */
    private void without(ItemReport rep, int fighter, long itemId, List<Resolved> resolved, List<Fighter> everyone,
                         int sizeA, double duration) {
        List<Fighter> fa = new ArrayList<>();
        List<Fighter> fb = new ArrayList<>();
        for (int k = 0; k < everyone.size(); k++) {
            Fighter f = everyone.get(k);
            if (k == fighter) {
                List<Long> items = new ArrayList<>(resolved.get(k).items);
                items.remove(Long.valueOf(itemId));
                f = fighter(resolved.get(k), items);
            }
            (k < sizeA ? fa : fb).add(f);
        }
        Result alt = new CombatSimulator(catalog).run(fa, fb, duration);
        FighterResult fr = alt.fighters.get(fighter);
        int team = fighter < sizeA ? 0 : 1;
        rep.winnerWithout = alt.winner;
        rep.durationWithout = alt.duration;
        rep.dealtWithout = fr.dealt;
        rep.takenWithout = fr.taken;
        rep.aliveWithout = fr.alive;
        rep.teamHealthWithout = health(alt, team);
        rep.enemyHealthWithout = health(alt, 1 - team);
    }

    private static double health(Result r, int team) {
        double[] last = r.series.get(r.series.size() - 1);
        double sum = 0;
        for (FighterResult f : r.fighters) {
            if (f.team == team) {
                sum += last[f.index + 1];
            }
        }
        return sum;
    }

    /** A conditional effect of the planner (item passive, rune, champion effect) and when it counts in the arena. */
    public static class Triggered {
        /** "Item|Effect" (item effects and the shop's conditional lines), rune or champion effect name. */
        public String key;
        /** Item, rune or champion effect it belongs to (the ledger's source). */
        public String source;
        public String effect;
        /** item | loja | runa | campeao */
        public String kind;
        /** The trigger (gatilhos of the combat file); null = none written: it follows the build's switch. */
        public Map<String, Object> spec;
        /** The trigger's "quando", or "estatico". */
        public String mode;
        /** The build's own switch for it. */
        public boolean buildOn;
        /** What it adds when it is on (stats, and shields / heals / stasis as effective stats). */
        public Map<String, Double> delta = new LinkedHashMap<>();
        /** Second of the fight at which it turns on, set by hand on the scenario (instead of its trigger). */
        public Double fixedAt;
        Long itemId;

        /** Counts the whole fight as the build says: no trigger, it depends on the match (kills, stacks, mana). */
        public boolean followsBuild() {
            return "estatico".equals(mode);
        }

        /** Turned on by something in the fight (not static, not done by a fight effect, not impossible). */
        public boolean fightTriggered() {
            return !followsBuild() && !"modelado".equals(mode) && !"nunca".equals(mode);
        }
    }

    /** A champion for the arena: the build at the minute with its conditional effects off, and those effects. */
    public static class ArenaBuild {
        public Fighter fighter;
        public List<Triggered> triggers = new ArrayList<>();
    }

    /** The champion's name of a combatant's build (decides its ability sheet). */
    public String championName(Combatant c) {
        Resolved r = resolve(c);
        Map<String, Object> champ = champions.find(r.build.unitCode).orElse(null);
        return champ == null ? r.build.unitCode : String.valueOf(champ.get("nome"));
    }

    /**
     * The fighter of the arena: every conditional effect of the build that the fight decides is off (it turns on by
     * its trigger, with the stats it adds measured as the build with it on minus off: Living Forge and the rest of the
     * build included); the ones that depend on the match follow the build's switch; form effects stay off (the arena
     * switches them with the champion's sheet).
     */
    public ArenaBuild arenaBuild(Combatant c, java.util.Set<String> formsOff) {
        Resolved r = resolve(c);
        List<Triggered> all = discover(r, formsOff);
        Build base = variant(r.build, all, null, formsOff);
        MinutePoint p0 = planner.snapshot(base, r.items, r.level, r.minute);
        ArenaBuild out = new ArenaBuild();
        out.fighter = fighter(r, base, r.items, p0, formsOff, true);
        for (Triggered t : all) {
            if (!t.fightTriggered()) {
                continue;
            }
            MinutePoint p1 = planner.snapshot(variant(r.build, all, t, formsOff), r.items, r.level, r.minute);
            p1.total.forEach((stat, v) -> {
                double d = v - p0.total.getOrDefault(stat, 0.0);
                if (Math.abs(d) > 1e-9) {
                    t.delta.put(stat, d);
                }
            });
            if (r.c.activations != null) {
                Double at = r.c.activations.containsKey(t.key) ? r.c.activations.get(t.key) : r.c.activations.get(t.source);
                if (at != null) {
                    t.fixedAt = Math.max(0, at);
                }
            }
        }
        out.triggers = all;
        return out;
    }

    /** The conditional effects of the build at the minute: of its items, the shop's lines, its runes and champion. */
    private List<Triggered> discover(Resolved r, java.util.Set<String> formsOff) {
        Build b = r.build;
        List<Triggered> out = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (Long id : r.items) {
            String name = seen.add(id) ? itemName(id) : null;
            if (name == null) {
                continue;
            }
            for (com.ornnplanner.seed.EffectSpec.Effect e : itemEffects.effects(name)) {
                if (!e.hasConditional) {
                    continue;
                }
                String key = com.ornnplanner.seed.ItemEffectCatalog.key(name, e.name);
                Boolean on = b.itemSetup == null ? null : b.itemSetup.conditional.get(key);
                out.add(triggered(key, name, e.name, "item", on != null ? on : e.defaultOn, id));
            }
            Boolean purchase = null;
            for (BuildRepository.Step s : b.steps == null ? List.<BuildRepository.Step>of() : b.steps) {
                if (!s.moment() && id.equals(s.itemId)) {
                    purchase = s.includeConditional;
                }
            }
            for (String passive : shopConditionalPassives(id)) {
                out.add(triggered(name + "|" + passive, name, passive, "loja", purchase == null || purchase, id));
            }
        }
        if (b.runePage != null) {
            for (String name : b.runePage.chosen()) {
                com.ornnplanner.seed.RuneCatalog.Rune rune = runes.find(name).orElse(null);
                if (rune != null && rune.hasConditional) {
                    boolean on = b.runePage.conditional == null || !Boolean.FALSE.equals(b.runePage.conditional.get(name));
                    out.add(triggered(name, name, name, "runa", on, null));
                }
            }
        }
        for (com.ornnplanner.seed.EffectSpec.Effect e : champions.effects(b.unitCode)) {
            if (!e.hasConditional || formsOff.contains(e.name)) {
                continue;
            }
            Boolean on = b.championSetup == null ? null : b.championSetup.conditional.get(e.name);
            out.add(triggered(e.name, e.name, e.name, "campeao", on != null ? on : e.defaultOn, null));
        }
        return out;
    }

    private Triggered triggered(String key, String source, String effect, String kind, boolean buildOn, Long itemId) {
        Triggered t = new Triggered();
        t.key = key;
        t.source = source;
        t.effect = effect;
        t.kind = kind;
        t.buildOn = buildOn;
        t.itemId = itemId;
        t.spec = catalog.trigger(key);
        t.mode = t.spec == null ? "estatico" : String.valueOf(t.spec.getOrDefault("quando", "estatico"));
        return t;
    }

    /** Passives of an item (shop data) with conditional lines. */
    private List<String> shopConditionalPassives(long id) {
        return shopConditional.computeIfAbsent(id, k -> {
            List<String> out = new ArrayList<>();
            for (PlannerService.ItemView v : planner.itemViews()) {
                if (v.id != k) {
                    continue;
                }
                for (com.ornnplanner.engine.Model.StatLine l : v.stats) {
                    if (l.conditional && !l.switched && l.passive != null && !out.contains(l.passive)) {
                        out.add(l.passive);
                    }
                }
            }
            return out;
        });
    }

    private final Map<Long, List<String>> shopConditional = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The build with the switches of its conditional effects set: the ones the fight decides off (or {@code onlyOn}
     * on, with its option at 1 when each activation is worth one), the others as the build has them; forms off.
     */
    private Build variant(Build original, List<Triggered> all, Triggered onlyOn, java.util.Set<String> formsOff) {
        Build b = JSON.convertValue(original, Build.class);
        if (b.itemSetup == null) {
            b.itemSetup = new BuildRepository.ItemSetup();
        }
        if (b.championSetup == null) {
            b.championSetup = new BuildRepository.ChampionSetup();
        }
        b.itemSetup.conditional = b.itemSetup.conditional == null ? new LinkedHashMap<>() : b.itemSetup.conditional;
        b.itemSetup.options = b.itemSetup.options == null ? new LinkedHashMap<>() : b.itemSetup.options;
        b.championSetup.conditional = b.championSetup.conditional == null ? new LinkedHashMap<>() : b.championSetup.conditional;
        b.championSetup.options = b.championSetup.options == null ? new LinkedHashMap<>() : b.championSetup.options;
        if (b.runePage != null) {
            b.runePage.conditional = b.runePage.conditional == null ? new LinkedHashMap<>() : b.runePage.conditional;
            b.runePage.options = b.runePage.options == null ? new LinkedHashMap<>() : b.runePage.options;
        }
        for (Triggered t : all) {
            boolean on = t == onlyOn || t.followsBuild() && t.buildOn;
            switch (t.kind) {
                case "item":
                    b.itemSetup.conditional.put(t.key, on);
                    break;
                case "loja":
                    for (BuildRepository.Step s : b.steps == null ? List.<BuildRepository.Step>of() : b.steps) {
                        if (!s.moment() && t.itemId.equals(s.itemId)) {
                            s.includeConditional = on;
                        }
                    }
                    break;
                case "runa":
                    if (b.runePage != null) {
                        b.runePage.conditional.put(t.key, on);
                    }
                    break;
                default:
                    b.championSetup.conditional.put(t.key, on);
                    break;
            }
        }
        if (onlyOn != null && onlyOn.spec != null && Boolean.TRUE.equals(onlyOn.spec.get("por_acionamento"))) {
            switch (onlyOn.kind) {
                case "item":
                    b.itemSetup.options.put(onlyOn.key, 1);
                    break;
                case "runa":
                    if (b.runePage != null) {
                        b.runePage.options.put(onlyOn.key, 1);
                    }
                    break;
                case "campeao":
                    b.championSetup.options.put(onlyOn.key, 1);
                    break;
                default:
                    break;
            }
        }
        for (String form : formsOff) {
            b.championSetup.conditional.put(form, false);
        }
        return b;
    }

    /**
     * What each form effect (Noxian Might, Overheat, All Out...) changes in the fighter's stats at the minute: stats
     * with it on minus with it off.
     */
    public Map<String, Map<String, Double>> formDeltas(Combatant c, java.util.Set<String> forms) {
        Resolved r = resolve(c);
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        Map<String, Boolean> allOff = new LinkedHashMap<>();
        forms.forEach(f -> allOff.put(f, false));
        Map<String, Double> off = planner.snapshot(withDelayedOn(r.build, forms, allOff), r.items, r.level, r.minute).total;
        for (String form : forms) {
            Map<String, Boolean> one = new LinkedHashMap<>(allOff);
            one.put(form, true);
            Map<String, Double> on = planner.snapshot(withDelayedOn(r.build, java.util.Set.of(), one), r.items, r.level,
                    r.minute).total;
            Map<String, Double> delta = new LinkedHashMap<>();
            on.forEach((k, v) -> {
                double d = v - off.getOrDefault(k, 0.0);
                if (Math.abs(d) > 1e-9) {
                    delta.put(k, d);
                }
            });
            out.put(form, delta);
        }
        return out;
    }

    private Resolved resolve(Combatant c) {
        if (c == null || c.buildId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha uma build para cada campeão.");
        }
        Build build = builds.findBuild(c.buildId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Build " + c.buildId + " não existe."));
        TimelineResult full = planner.calculate(build);
        if (full.series.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A build \"" + build.name + "\" não tem ouro/min e XP/min para calcular.");
        }
        double last = full.steps.isEmpty() ? 0 : full.steps.get(full.steps.size() - 1).minute;
        double minute = c.minute != null ? c.minute : build.matchEnd != null ? build.matchEnd : last;
        MinutePoint p = full.series.get(0);
        for (MinutePoint x : full.series) {
            if (x.minute <= minute + 1e-9) {
                p = x;
            }
        }
        Resolved r = new Resolved();
        r.build = build;
        r.c = c;
        r.minute = minute;
        r.level = p.level;
        r.items = new ArrayList<>();
        for (ItemContribution ic : p.contributions) {
            if (!ic.rune) {
                r.items.add(ic.itemId);
            }
        }
        return r;
    }

    /**
     * The build with the delayed statuses switched on (purchases, runes, champion effects): the fight decides when they
     * count.
     */
    private Build withDelayedOn(Build original) {
        return withDelayedOn(original, java.util.Set.of(), java.util.Map.of());
    }

    /**
     * Same, with some champion effects forced off (the arena switches them itself: Noxian Might, Overheat, All Out) or
     * forced to a value.
     */
    private Build withDelayedOn(Build original, java.util.Set<String> off, Map<String, Boolean> forced) {
        Build b = JSON.convertValue(original, Build.class);
        java.util.Set<String> names = catalog.activations().keySet();
        if (b.steps != null) {
            for (BuildRepository.Step s : b.steps) {
                if (!s.moment() && s.itemId != null) {
                    String name = itemName(s.itemId);
                    if (name != null && names.contains(name)) {
                        s.includeConditional = true;
                    }
                }
            }
        }
        for (String n : names) {
            if (b.championSetup != null) {
                b.championSetup.conditional.put(n, true);
            }
            if (b.runePage != null) {
                b.runePage.conditional.put(n, true);
            }
        }
        if (b.championSetup == null) {
            b.championSetup = new BuildRepository.ChampionSetup();
            for (String n : names) {
                b.championSetup.conditional.put(n, true);
            }
        }
        for (String n : off) {
            b.championSetup.conditional.put(n, false);
        }
        b.championSetup.conditional.putAll(forced);
        return b;
    }

    private String itemName(long id) {
        if (!itemNames.containsKey(id)) {
            planner.itemViews().forEach(i -> itemNames.put(i.id, i.name));
        }
        return itemNames.get(id);
    }

    private final Map<Long, String> itemNames = new java.util.concurrent.ConcurrentHashMap<>();
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Fighter fighter(Resolved r, List<Long> items) {
        return fighter(r, items, java.util.Set.of());
    }

    private Fighter fighter(Resolved r, List<Long> items, java.util.Set<String> formsOff) {
        Build b = withDelayedOn(r.build, formsOff, Map.of());
        return fighter(r, b, items, planner.snapshot(b, items, r.level, r.minute), formsOff, false);
    }

    /**
     * A fighter from the build's stats at the minute. The arena's ({@code arena}) also gets its runes' fight effects and
     * no delayed statuses (its triggers turn them on).
     */
    @SuppressWarnings("unchecked")
    private Fighter fighter(Resolved r, Build b, List<Long> items, MinutePoint p, java.util.Set<String> formsOff,
                            boolean arena) {
        Fighter f = new Fighter();
        Map<String, Object> champ = champions.find(b.unitCode).orElse(null);
        f.champion = champ == null ? b.unitCode : String.valueOf(champ.get("nome"));
        f.name = f.champion + " (" + b.name + ")";
        f.level = r.level;
        f.minute = r.minute;
        f.total.putAll(p.total);
        f.base.putAll(p.base);
        for (ItemContribution c : p.contributions) {
            List<Map<String, Object>> fx = !c.rune ? catalog.effects(c.itemName)
                    : arena && !c.champion ? catalog.runeEffects(c.itemName) : List.of();
            if (!c.rune) {
                f.items.put(c.itemName, c.itemId);
            }
            for (Map<String, Object> e : fx) {
                f.effects.add(new ItemEffect(c.itemName, e));
            }
            // An item / rune whose heal the fight models (Desespero Eterno, Aurora e Crepúsculo, Aperto...) keeps it out
            // of the pool.
            boolean healInFight = fx.stream().anyMatch(e -> e.keySet().stream().anyMatch(k -> k.startsWith("cura")));
            c.flat.forEach((stat, v) -> source(f, c.itemName, stat, v, healInFight));
            for (PassivePart part : c.passiveParts) {
                source(f, c.itemName, part.stat, part.amount, healInFight);
            }
            Map<String, Object> act = arena || formsOff.contains(c.itemName) ? null : catalog.activation(c.itemName);
            if (act != null) {
                CombatSimulator.Delay d = new CombatSimulator.Delay();
                d.source = c.itemName;
                Object passive = act.get("passiva");
                for (PassivePart part : c.passiveParts) {
                    if (passive == null || passive.equals(part.passive)) {
                        d.amounts.merge(part.stat, part.amount, Double::sum);
                    }
                }
                d.seconds = act.get("segundos") == null ? null : ((Number) act.get("segundos")).doubleValue();
                d.hits = act.get("acertos") == null ? null : ((Number) act.get("acertos")).intValue();
                d.hitsTaken = act.get("acertos_sofridos") == null ? null : ((Number) act.get("acertos_sofridos")).intValue();
                Double fixed = r.c.activations == null ? null : r.c.activations.get(c.itemName);
                if (fixed != null) {
                    d.seconds = Math.max(0, fixed);
                    d.fixed = true;
                }
                if (!d.amounts.isEmpty()) {
                    f.delays.add(d);
                }
            }
        }
        f.mechanics.putAll(catalog.champion(f.champion));
        if (champ != null) {
            f.abilities = AbilityParser.abilities(champ);
            com.ornnplanner.repo.BuildRepository.ChampionSetup setup = b.championSetup;
            f.ranks = ChampionCatalog.ranks(setup == null ? null : setup.skillOrder, setup == null ? null : setup.skillLevels, r.level);
            Map<String, Object> status = (Map<String, Object>) champ.get("status");
            if (status != null) {
                Map<String, Object> l1 = (Map<String, Object>) status.get("nv1");
                Map<String, Object> l15 = (Map<String, Object>) status.get("nv15");
                if (l1 != null && l1.get("VdA") != null && l15 != null && l15.get("VdA") != null) {
                    double a1 = ((Number) l1.get("VdA")).doubleValue();
                    double a15 = ((Number) l15.get("VdA")).doubleValue();
                    double baseAs = a1 + (a15 - a1) * (r.level - 1) / 14.0;
                    f.attackSpeed = Math.min(3.0, baseAs * (1 + f.total.getOrDefault("% Attack Speed", 0.0) / 100));
                    f.baseAttackSpeed = baseAs;
                }
            }
        }
        if (r.c.order != null && !r.c.order.isEmpty()) {
            f.order = new ArrayList<>(r.c.order);
        }
        if (r.c.target != null) {
            f.target = r.c.target;
        }
        f.spells = b.spells == null ? new ArrayList<>() : new ArrayList<>(b.spells);
        return f;
    }

    private static void source(Fighter f, String name, String stat, double v, boolean healInFight) {
        if (v <= 0 || healInFight && "Heal".equals(stat)) {
            return;
        }
        switch (stat) {
            case "Shield":
                f.shieldSources.merge(name, v, Double::sum);
                break;
            case "Magic Shield":
                f.magicShieldSources.merge(name, v, Double::sum);
                break;
            case "Physical Shield":
                f.physicalShieldSources.merge(name, v, Double::sum);
                break;
            case "Heal":
                f.healSources.merge(name, v, Double::sum);
                break;
            case "Ally Shield":
                f.allyShieldSources.merge(name, v, Double::sum);
                break;
            case "Ally Heal":
                f.allyHealSources.merge(name, v, Double::sum);
                break;
            case "% Lifesteal":
            case "% Omnivamp":
                f.vampSources.merge(name, v, Double::sum);
                break;
            default:
                break;
        }
    }

    private FighterInfo info(Fighter f, Build b) {
        FighterInfo i = new FighterInfo();
        i.index = f.index;
        i.team = f.team;
        i.buildId = b.id;
        i.build = b.name;
        i.champion = f.champion;
        i.level = f.level;
        i.minute = f.minute;
        i.attackSpeed = f.attackSpeed;
        f.items.forEach((name, id) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("id", id);
            m.put("combat", !catalog.effects(name).isEmpty());
            i.items.add(m);
        });
        for (int k = 0; k < f.abilities.size(); k++) {
            AbilityParser.Ability a = f.abilities.get(k);
            Map<String, Object> m = new LinkedHashMap<>();
            int rank = f.ranks[k];
            m.put("slot", a.slot);
            m.put("name", a.name);
            m.put("rank", rank);
            if (a.damage != null && rank > 0) {
                m.put("damage", a.damage.at(rank, f.total, f.base));
                m.put("targetBased", a.damage.targetBased());
                m.put("type", a.damage.type);
                m.put("source", a.damage.source);
            }
            double haste = f.total.getOrDefault("Ability Haste", 0.0)
                    + f.total.getOrDefault(k == 3 ? "Ultimate Haste" : "Basic Ability Haste", 0.0);
            if (rank > 0) {
                m.put("cooldown", a.cooldown[Math.max(0, Math.min(a.cooldown.length, rank) - 1)] / (1 + haste / 100));
            }
            i.abilities.add(m);
        }
        for (CombatSimulator.Delay d : f.delays) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", d.source);
            m.put("seconds", d.seconds);
            m.put("hits", d.hits);
            m.put("hitsTaken", d.hitsTaken);
            m.put("fixed", d.fixed);
            m.put("amounts", d.amounts);
            i.delays.add(m);
        }
        for (String s : List.of("Max Health", "Armor", "Magic Resistance", "Attack Damage", "Ability Power",
                "% Critical Rate", "% Attack Speed", "Armor Penetration", "% Armor Penetration", "Magic Penetration",
                "% Magic Penetration", "% Lifesteal", "% Omnivamp", "Ability Haste", "Shield", "Magic Shield",
                "Physical Shield", "Heal", "Stasis")) {
            double v = f.total.getOrDefault(s, 0.0);
            if (Math.abs(v) > 1e-9) {
                i.stats.put(s, v);
            }
        }
        return i;
    }
}
