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

    public CombatService(BuildRepository builds, PlannerService planner, ChampionCatalog champions, CombatCatalog catalog) {
        this.builds = builds;
        this.planner = planner;
        this.champions = champions;
        this.catalog = catalog;
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

    @SuppressWarnings("unchecked")
    private Fighter fighter(Resolved r, List<Long> items) {
        Build b = r.build;
        MinutePoint p = planner.snapshot(b, items, r.level, r.minute);
        Fighter f = new Fighter();
        Map<String, Object> champ = champions.find(b.unitCode).orElse(null);
        f.champion = champ == null ? b.unitCode : String.valueOf(champ.get("nome"));
        f.name = f.champion + " (" + b.name + ")";
        f.level = r.level;
        f.minute = r.minute;
        f.total.putAll(p.total);
        f.base.putAll(p.base);
        for (ItemContribution c : p.contributions) {
            if (!c.rune) {
                f.items.put(c.itemName, c.itemId);
                for (Map<String, Object> e : catalog.effects(c.itemName)) {
                    f.effects.add(new ItemEffect(c.itemName, e));
                }
            }
            // An item whose heal the fight models (Desespero Eterno, Aurora e Crepúsculo...) keeps it out of the pool.
            boolean healInFight = catalog.effects(c.itemName).stream()
                    .anyMatch(e -> e.keySet().stream().anyMatch(k -> k.startsWith("cura")));
            c.flat.forEach((stat, v) -> source(f, c.itemName, stat, v, healInFight));
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
                    f.attackSpeed = Math.min(3.0, baseAs * (1 + f.total.getOrDefault("% Attack Speed", 0.0) / 100));
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
        for (String s : List.of("Max Health", "Armor", "Magic Resistance", "Attack Damage", "Ability Power",
                "% Critical Rate", "% Attack Speed", "Armor Penetration", "% Armor Penetration", "Magic Penetration",
                "% Magic Penetration", "% Lifesteal", "% Omnivamp", "Ability Haste", "Shield", "Heal", "Stasis")) {
            double v = f.total.getOrDefault(s, 0.0);
            if (Math.abs(v) > 1e-9) {
                i.stats.put(s, v);
            }
        }
        return i;
    }
}
