package com.ornnplanner.arena;

import com.ornnplanner.arena.AiResultRepository.Row;
import com.ornnplanner.arena.ai.PolicyRepository;
import com.ornnplanner.arena.fight.ChampionProfiles;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * The automatic brain: at each run it picks the brain of a slot from what the saved AIs have done (table ai_result), in
 * this order: the best AI of the champion that won this scenario; the best one in scenarios alike (the same enemies or
 * their classes, the same map, close minutes); the champion's best one; the best AI of its class; the tactical rules.
 * "Best" is the order of the criteria of {@code ia_automatica_<patch>.yml}.
 * <p>
 * A fight stays deterministic for its brains; which brain plays depends on the history, so the same scenario may change
 * of brain after a new AI wins it. The result says which one played and why.
 */
@Service
public class AutoBrain {

    public static final String AUTO = "auto";
    /** Saved in the champion's place: an AI of a class serves every champion of that class. */
    public static final String CLASS_PREFIX = "class:";

    /** The brain the automatic picked for a slot, and why. */
    public static class Choice {
        public int fighter;
        /** cenario | parecido | geral | classe | tatico */
        public String kind;
        /** ai:&lt;id&gt; or tactical: what fixing it puts in the slot. */
        public String brain;
        public Long policyId;
        public Integer version;
        /** The champion, or the class of a class AI. */
        public String owner;
        /** What the row behind the choice says (null = no result of it yet). */
        public Boolean won;
        public Boolean clean;
        public Boolean died;
        public Double time;
    }

    private final AiResultRepository results;
    private final PolicyRepository policies;
    private final ChampionProfiles profiles;
    private final List<String> criteria = new ArrayList<>();
    private final double closeMinutes;
    private final Map<String, Object> budget;

    @SuppressWarnings("unchecked")
    public AutoBrain(AiResultRepository results, PolicyRepository policies, ChampionProfiles profiles,
                     @Value("${planner.seed.auto:classpath:seed/ia_automatica_7_3d.yml}") Resource file) {
        this.results = results;
        this.policies = policies;
        this.profiles = profiles;
        try (InputStream in = file.getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            ((List<Object>) root.get("criterios")).forEach(c -> criteria.add(String.valueOf(c)));
            closeMinutes = ((Number) ((Map<String, Object>) root.get("parecido")).get("minutos")).doubleValue();
            budget = (Map<String, Object>) root.get("orcamento");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + file, e);
        }
    }

    /** The default budget of "Treinar automaticamente". */
    public Map<String, Object> budget() {
        return budget;
    }

    /** The order of the criteria: negative when {@code a} is better. */
    public static int compare(List<String> criteria, Row a, Row b) {
        for (String c : criteria) {
            int r = 0;
            switch (c) {
                case "vitoria_limpa_sem_morrer":
                    r = Boolean.compare(b.won && b.clean && !b.died, a.won && a.clean && !a.died);
                    break;
                case "vitoria_sem_morrer":
                    r = Boolean.compare(b.won && !b.died, a.won && !a.died);
                    break;
                case "vitoria":
                    r = Boolean.compare(b.won, a.won);
                    break;
                case "mais_rapido":
                    // between two losses, the one that lasted longer did better
                    r = a.won ? Double.compare(a.time, b.time) : Double.compare(b.time, a.time);
                    break;
                case "mais_vida":
                    r = Double.compare(b.health, a.health);
                    break;
                default:
                    throw new IllegalStateException("Critério desconhecido: " + c);
            }
            if (r != 0) {
                return r;
            }
        }
        return 0;
    }

    public List<String> criteria() {
        return criteria;
    }

    // ------------------------------------------------------------------ the choice

    /** The brain of the champion at {@code slot} of a prepared scenario. */
    public Choice choose(ArenaModel.Scenario sc, List<Arena.Input> inputs, int slot) {
        Row me = print(sc, inputs, slot);
        String champion = inputs.get(slot).f.champion;
        Map<Long, PolicyRepository.Record> usable = new LinkedHashMap<>();
        policies.list(champion).stream().filter(PolicyRepository.Record::compatible).forEach(p -> usable.put(p.id, p));
        List<String> classes = profiles.classesOf(champion);
        for (String c : classes) {
            policies.list(CLASS_PREFIX + c).stream().filter(PolicyRepository.Record::compatible).forEach(p -> usable.put(p.id, p));
        }
        List<Row> rows = new ArrayList<>();
        for (Row r : results.forChampion(champion)) {
            if (r.policyId != null && usable.containsKey(r.policyId)) {
                rows.add(r);
            }
        }
        Choice c = new Choice();
        c.fighter = slot;
        Row exact = best(rows, r -> r.won && Objects.equals(r.scenarioKey, me.scenarioKey));
        if (exact != null) {
            return fill(c, "cenario", usable.get(exact.policyId), exact);
        }
        Row alike = best(rows, r -> r.won && Objects.equals(r.map, me.map) && Math.abs(r.minute - me.minute) <= closeMinutes
                && (r.enemies.equals(me.enemies) || r.enemyClasses.equals(me.enemyClasses)));
        if (alike != null) {
            return fill(c, "parecido", usable.get(alike.policyId), alike);
        }
        Row general = best(rows, r -> !usable.get(r.policyId).champion.startsWith(CLASS_PREFIX));
        if (general != null) {
            return fill(c, "geral", usable.get(general.policyId), general);
        }
        PolicyRepository.Record saved = bestSaved(usable, champion);
        if (saved != null) {
            return fill(c, "geral", saved, null);
        }
        for (String cl : classes) {
            PolicyRepository.Record p = bestSaved(usable, CLASS_PREFIX + cl);
            if (p != null) {
                return fill(c, "classe", p, null);
            }
        }
        c.kind = "tatico";
        c.brain = "tactical";
        return c;
    }

    /** Where a new training of a champion starts from: its best AI, else the best of its class (null = zero weights). */
    public PolicyRepository.Record warmStart(String champion) {
        Map<Long, PolicyRepository.Record> usable = new LinkedHashMap<>();
        policies.list(champion).stream().filter(PolicyRepository.Record::compatible).forEach(p -> usable.put(p.id, p));
        List<Row> rows = new ArrayList<>();
        for (Row r : results.forChampion(champion)) {
            if (r.policyId != null && usable.containsKey(r.policyId)) {
                rows.add(r);
            }
        }
        Row best = best(rows, r -> true);
        PolicyRepository.Record out = best != null ? usable.get(best.policyId) : bestSaved(usable, champion);
        if (out == null) {
            for (String c : profiles.classesOf(champion)) {
                Map<Long, PolicyRepository.Record> ofClass = new LinkedHashMap<>();
                policies.list(CLASS_PREFIX + c).stream().filter(PolicyRepository.Record::compatible).forEach(p -> ofClass.put(p.id, p));
                out = bestSaved(ofClass, CLASS_PREFIX + c);
                if (out != null) {
                    break;
                }
            }
        }
        return out == null ? null : policies.find(out.id).orElse(null);
    }

    private interface Filter {
        boolean test(Row r);
    }

    /** The best row that passes; the current patch comes before the criteria (results of old patches only count alone). */
    private Row best(List<Row> rows, Filter f) {
        String patch = policies.gamePatch();
        Row out = null;
        Comparator<Row> order = Comparator.<Row, Boolean>comparing(r -> patch != null && !patch.equals(r.gamePatch))
                .thenComparing((a, b) -> compare(criteria, a, b));
        for (Row r : rows) {
            if (f.test(r) && (out == null || order.compare(r, out) < 0)) {
                out = r;
            }
        }
        return out;
    }

    /** An owner's saved AI with no fight behind it: accepted first, the one that wins its own scenario, the newest. */
    private static PolicyRepository.Record bestSaved(Map<Long, PolicyRepository.Record> usable, String owner) {
        return usable.values().stream().filter(p -> p.champion.equalsIgnoreCase(owner))
                .max(Comparator.<PolicyRepository.Record, Boolean>comparing(p -> p.accepted)
                        .thenComparing(p -> Boolean.TRUE.equals(p.winsScenario))
                        .thenComparingInt(p -> p.version)).orElse(null);
    }

    private static Choice fill(Choice c, String kind, PolicyRepository.Record p, Row r) {
        c.kind = kind;
        c.brain = "ai:" + p.id;
        c.policyId = p.id;
        c.version = p.version;
        c.owner = p.champion.startsWith(CLASS_PREFIX) ? p.champion.substring(CLASS_PREFIX.length()) : p.champion;
        if (r != null) {
            c.won = r.won;
            c.clean = r.clean;
            c.died = r.died;
            c.time = r.time;
        }
        return c;
    }

    // ------------------------------------------------------------------ the history

    /** Writes a fight: one row per champion whose brain is an AI or the automatic. */
    public void record(ArenaModel.Scenario sc, List<Arena.Input> inputs, ArenaModel.Result r, String source) {
        for (int k = 0; k < sc.fighters.size(); k++) {
            ArenaModel.Slot s = sc.fighters.get(k);
            Choice choice = inputs.get(k).autoChoice;
            String brain = choice != null ? choice.brain : s.brain;
            if (s.dummy != null || brain == null || choice == null && !brain.startsWith("ai:")) {
                continue;
            }
            Row row = print(sc, inputs, k);
            row.policyId = brain.startsWith("ai:") ? Long.valueOf(brain.substring(3)) : null;
            ArenaModel.FighterStats me = r.fighters.get(k);
            row.won = r.winner == s.team;
            row.died = me.deathTime != null;
            row.clean = true;
            for (ArenaModel.FighterStats f : r.fighters) {
                row.clean &= f.team == s.team || f.deathTime != null;
            }
            row.time = r.duration;
            row.health = me.health / Math.max(1, me.maxHealth);
            row.gamePatch = policies.gamePatch();
            row.source = source;
            results.insert(row);
        }
    }

    /** The fingerprint of a scenario from one slot's side: what makes two fights the same, or alike. */
    private Row print(ArenaModel.Scenario sc, List<Arena.Input> inputs, int slot) {
        Arena.Input me = inputs.get(slot);
        Row r = new Row();
        r.champion = me.f.champion;
        r.minute = Math.round(me.f.minute * 10) / 10.0;
        r.map = "abismo";
        TreeSet<String> enemies = new TreeSet<>();
        TreeSet<String> classes = new TreeSet<>();
        List<String> sides = new ArrayList<>();
        for (int k = 0; k < inputs.size(); k++) {
            Arena.Input in = inputs.get(k);
            boolean enemy = in.f.team != me.f.team;
            if (enemy) {
                enemies.add(in.f.champion);
                List<String> cl = profiles.classesOf(in.f.champion);
                classes.addAll(cl.isEmpty() ? List.of(in.f.champion) : cl);
            }
            sides.add((k == slot ? "eu" : enemy ? "inimigo" : "aliado") + ":" + in.f.champion + ":" + in.f.minute + ":"
                    + new TreeSet<>(in.f.items.keySet()) + ":" + Math.round(in.x / 400) + ":" + Math.round(in.y / 300));
        }
        r.enemies = new ArrayList<>(enemies);
        r.enemyClasses = new ArrayList<>(classes);
        java.util.Collections.sort(sides);
        r.impression = String.join(" | ", sides) + " | zona " + (sc.safeZone == null ? "-" : sc.safeZone.team + ":" + sc.safeZone.depth)
                + " | " + ArenaService.duration(sc) + " s | ordens " + (sc.actions == null ? 0 : sc.actions.size());
        r.scenarioKey = EffectTestService.hash(r.impression);
        return r;
    }
}
