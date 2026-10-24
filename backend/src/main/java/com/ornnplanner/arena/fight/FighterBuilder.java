package com.ornnplanner.arena.fight;

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
 * Builds the arena's champions from saved builds: each build at a game minute (its items, level and stats at that
 * moment), with the fight effects of its items and runes, and its conditional effects as triggers the fight turns on.
 * A build can leave items out ({@link Combatant#excludeItems}): the same champion without an item, which is what that
 * item was worth in a fight (the counterfactual of the reports and of the planner).
 */
@Service
public class FighterBuilder {

    /** A build in a fight: saved or given, at a minute, with items left out and triggers set by hand. */
    public static class Combatant {
        public Long buildId;
        /** A build that is not saved (the lab's ragdoll wearing a build's items); used instead of {@link #buildId}. */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public Build build;
        /** Items left out (the build without this item: its stats, effects and triggers go with it). */
        public java.util.Set<Long> excludeItems;
        /** Game minute (null = the build's end of match, else its last purchase). */
        public Double minute;
        /** Seconds of the fight at which a conditional effect turns on (key or source -> s), instead of its trigger. */
        public Map<String, Double> activations;
    }

    /** A combatant resolved: its build, inventory, level and minute. */
    private static final class Resolved {
        Build build;
        /** The planner's point at the minute (what its sheet shows). */
        MinutePoint point;
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

    public FighterBuilder(BuildRepository builds, PlannerService planner, ChampionCatalog champions, CombatCatalog catalog,
                          com.ornnplanner.seed.ItemEffectCatalog itemEffects, com.ornnplanner.seed.RuneCatalog runes) {
        this.builds = builds;
        this.planner = planner;
        this.champions = champions;
        this.catalog = catalog;
        this.itemEffects = itemEffects;
        this.runes = runes;
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

    /** The planner's point of the build at the minute: its sheet's stats, with the build's own switches. */
    public MinutePoint plannerPoint(Combatant c) {
        return resolve(c).point;
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
        out.fighter = fighter(r, base, p0);
        // The planner's sheet with every effect the fight decides off (the fighter's start) and all of them on at once.
        out.fighter.sheetAllOff = new LinkedHashMap<>(p0.total);
        if (all.stream().anyMatch(Triggered::fightTriggered)) {
            Build on = variant(r.build, all, null, formsOff);
            for (Triggered t : all) {
                if (t.fightTriggered()) {
                    setSwitch(on, t, true);
                }
            }
            out.fighter.sheetAllOn = new LinkedHashMap<>(planner.snapshot(on, r.items, r.level, r.minute).total);
        } else {
            out.fighter.sheetAllOn = new LinkedHashMap<>(p0.total);
        }
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
            setSwitch(b, t, t == onlyOn || t.followsBuild() && t.buildOn);
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

    /** Sets the build's own switch of a conditional effect (a variant's maps already exist). */
    private static void setSwitch(Build b, Triggered t, boolean on) {
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
        if (c == null || c.buildId == null && c.build == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha uma build para cada campeão.");
        }
        Build build = c.build != null ? c.build : builds.findBuild(c.buildId)
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
        r.point = p;
        r.c = c;
        r.minute = minute;
        r.level = p.level;
        r.items = new ArrayList<>();
        for (ItemContribution ic : p.contributions) {
            if (!ic.rune && (c.excludeItems == null || !c.excludeItems.contains(ic.itemId))) {
                r.items.add(ic.itemId);
            }
        }
        return r;
    }

    /**
     * The build with the delayed statuses switched on (purchases, runes, champion effects; the `ativacao` list of the
     * combat file), some champion effects forced off (the arena switches them itself: Noxian Might, Overheat, All Out)
     * or forced to a value.
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

    /**
     * A fighter from the build's stats at the minute, with the fight effects of its items and runes (champion effects
     * come from the arena's sheet).
     */
    @SuppressWarnings("unchecked")
    private Fighter fighter(Resolved r, Build b, MinutePoint p) {
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
                    : !c.champion ? catalog.runeEffects(c.itemName) : List.of();
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
            c.flat.forEach((stat, v) -> penetration(f, c.itemName, stat, v));
            for (PassivePart part : c.passiveParts) {
                penetration(f, c.itemName, part.stat, part.amount);
            }
            for (PassivePart part : c.passiveParts) {
                source(f, c.itemName, part.stat, part.amount, healInFight);
            }
        }
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
                    f.baseAttackSpeed = baseAs;
                }
            }
        }
        f.spells = b.spells == null ? new ArrayList<>() : new ArrayList<>(b.spells);
        f.runes = b.runePage == null ? new ArrayList<>() : b.runePage.chosen();
        return f;
    }

    private static final Set<String> PENETRATION = Set.of("% Armor Penetration", "Armor Penetration", "% Magic Penetration",
            "Magic Penetration");

    private static void penetration(Fighter f, String name, String stat, double v) {
        if (PENETRATION.contains(stat) && Math.abs(v) > 1e-9) {
            f.statSources.computeIfAbsent(stat, k -> new LinkedHashMap<>()).merge(name, v, Double::sum);
        }
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
}
