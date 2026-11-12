package com.ornnplanner.arena;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ornnplanner.arena.ArenaModel.Scenario;
import com.ornnplanner.arena.ArenaModel.Slot;
import com.ornnplanner.arena.fight.ChampionProfiles;
import com.ornnplanner.arena.rift.Match;
import com.ornnplanner.arena.rift.MatchModel;
import com.ornnplanner.arena.rift.ScriptedMacro;
import com.ornnplanner.engine.Model;
import com.ornnplanner.engine.Stats;
import com.ornnplanner.repo.BuildRepository;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs whole matches on the saved builds: the shop and the stats by the planner, the fights on the arena. */
@Service
public class MatchService {

    private static final List<String> ROLES = List.of("barao", "selva", "meio", "dupla", "suporte");
    private static final Map<String, String> LANE_OF = Map.of("barao", "Barão", "selva", "Selva", "meio", "Meio", "dupla", "Dragão", "suporte", "Suporte");
    private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ArenaService arena;
    private final PlannerService planner;
    private final BuildRepository builds;
    private final ChampionProfiles profiles;
    private final Map<String, Object> macro;

    private final com.ornnplanner.arena.guide.GuideService guides;
    private final MacroPolicyRepository macros;

    /**
     * The seats with a guide (Rodada 9, part 7) buy by it: it is read at the draft against this match (the other side's
     * builds, the lane opponent by role) and becomes the build's order, by the planner's timeline. Gold and enemy items
     * are not known at the draft: those conditions read as false (no live re-reading during the match).
     */
    private void guides(List<MatchModel.Slot> slots, List<BuildRepository.Build> chosen, List<String> roles) {
        for (int i = 0; i < slots.size(); i++) {
            MatchModel.Slot s = slots.get(i);
            if (s.guideId == null) {
                continue;
            }
            com.ornnplanner.arena.guide.GuideService.Match m = new com.ornnplanner.arena.guide.GuideService.Match();
            m.build = chosen.get(i);
            for (int k = 0; k < slots.size(); k++) {
                if (slots.get(k).team != s.team) {
                    m.enemies.add(slots.get(k).buildId);
                    if (roles.get(k).equals(roles.get(i))) {
                        m.laneOpponent = slots.get(k).buildId;
                    }
                }
            }
            com.ornnplanner.arena.guide.GuideService.Reading r = guides.resolve(guides.get(s.guideId), m);
            if (!r.order.isEmpty()) {
                BuildRepository.Build b = guides.withOrderPublic(chosen.get(i), r.order);
                if (r.runePage != null) {
                    b.runePage = r.runePage;
                }
                b.name = chosen.get(i).name + " (guia)";
                chosen.set(i, b);
            }
        }
    }

    public MatchService(ArenaService arena, PlannerService planner, BuildRepository builds, ChampionProfiles profiles,
                        @org.springframework.context.annotation.Lazy com.ornnplanner.arena.guide.GuideService guides,
                        MacroPolicyRepository macros,
                        @Value("${planner.seed.macro:classpath:seed/macro_7_3d.yml}") Resource macroFile) {
        this.guides = guides;
        this.macros = macros;
        this.arena = arena;
        this.planner = planner;
        this.builds = builds;
        this.profiles = profiles;
        try (InputStream in = macroFile.getInputStream()) {
            macro = new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + macroFile, e);
        }
    }

    /** A kit good enough for a whole match: quality A or B. */
    public boolean playable(String champion) {
        Object kit = ((Map<?, ?>) arena.kits().get("campeoes")).get(champion);
        Object q = kit instanceof Map ? ((Map<?, ?>) kit).get("qualidade") : null;
        return "A".equals(q) || "B".equals(q);
    }

    /**
     * A match's draft, ready to be played many times (the macro's training): the seats, their builds after the guides,
     * their roles, the planner's timelines and the fighters made so far. Shared by every match played on it, also across
     * threads: the fighters' cache is keyed by everything that makes a fighter, so it gives the same fighter whoever
     * asks first.
     */
    public final class Draft {
        final List<MatchModel.Slot> slots;
        final List<BuildRepository.Build> chosen;
        final List<String> roles;
        final List<String> names;
        final List<String> left;
        final List<List<Model.TimelineStep>> steps = new ArrayList<>();
        final Model.ReferenceData ref = planner.referenceData();
        final Map<String, Arena.Input> inputs = new java.util.concurrent.ConcurrentHashMap<>();

        Draft(List<MatchModel.Slot> slots, List<BuildRepository.Build> chosen, List<String> roles, List<String> names, List<String> left) {
            this.slots = slots;
            this.chosen = chosen;
            this.roles = roles;
            this.names = names;
            this.left = left;
            for (BuildRepository.Build b : chosen) {
                steps.add(planner.calculate(b).steps);
            }
        }

        public List<String> roles() {
            return roles;
        }

        public List<String> champions() {
            return names;
        }

        public List<Integer> teams() {
            List<Integer> out = new ArrayList<>();
            slots.forEach(s -> out.add(s.team));
            return out;
        }

        public int fightersMade() {
            return inputs.size();
        }
    }

    /** The scripted macro of {@code macro_<patch>.yml}. */
    public com.ornnplanner.arena.rift.MacroPolicy scripted() {
        return new ScriptedMacro(macro);
    }

    /** The parameters of {@code macro_<patch>.yml}, read-only. */
    public Map<String, Object> macroParams() {
        return java.util.Collections.unmodifiableMap(macro);
    }

    public MatchModel.Result run(MatchModel.Request req) {
        com.ornnplanner.arena.rift.MacroPolicy script = scripted();
        com.ornnplanner.arena.rift.MacroPolicy blue = macroOf(req.macroBlue, script);
        com.ornnplanner.arena.rift.MacroPolicy red = macroOf(req.macroRed, script);
        MatchModel.Result r = run(draft(req), req.seed, req.limit, blue, red);
        r.macros = List.of(macroLabel(req.macroBlue), macroLabel(req.macroRed));
        return r;
    }

    private static Long macroId(String ref) {
        if (ref == null || ref.isBlank() || "script".equals(ref)) {
            return null;
        }
        if (!ref.startsWith("macro:")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Macro desconhecida: " + ref);
        }
        try {
            return Long.parseLong(ref.substring("macro:".length()));
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Macro desconhecida: " + ref);
        }
    }

    /**
     * A side's macro: the script, or a trained one ("macro:&lt;id&gt;") over the same script. One of another observation
     * schema or action count is refused.
     */
    public com.ornnplanner.arena.rift.MacroPolicy macroOf(String ref, com.ornnplanner.arena.rift.MacroPolicy script) {
        Long id = macroId(ref);
        if (id == null) {
            return script;
        }
        MacroPolicyRepository.Record rec = macros.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A macro treinada " + id + " não existe."));
        if (rec.obsSchema != com.ornnplanner.arena.rift.MacroObservation.SCHEMA || rec.actions != com.ornnplanner.arena.rift.MacroActions.SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A macro treinada v" + rec.version
                    + " é de outro esquema de observação ou de ações: treine de novo.");
        }
        MacroPolicyRepository.Weights w = macros.weights(id);
        return new com.ornnplanner.arena.rift.NeuralMacro(w.network(), new ScriptedMacro(macro), w.bonus);
    }

    private String macroLabel(String ref) {
        Long id = macroId(ref);
        return id == null ? "Roteiro" : macros.find(id).map(r -> "Macro treinada v" + r.version).orElse("?");
    }

    /** The seats of a request with a sheet good enough, their builds (after the guides) and roles. */
    public Draft draft(MatchModel.Request req) {
        List<MatchModel.Slot> slots = new ArrayList<>();
        List<String> left = new ArrayList<>();
        List<BuildRepository.Build> chosen = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (MatchModel.Slot s : req.slots) {
            BuildRepository.Build b = builds.findBuild(s.buildId).orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Build " + s.buildId + " não existe."));
            com.ornnplanner.arena.fight.FighterBuilder.Combatant c = new com.ornnplanner.arena.fight.FighterBuilder.Combatant();
            c.buildId = s.buildId;
            String champion = arena.championOf(c);
            if (!playable(champion)) {
                left.add(champion + " (" + b.name + "): sem ficha de qualidade A ou B, fica fora até a Etapa 7");
                continue;
            }
            slots.add(s);
            chosen.add(b);
            names.add(champion);
        }
        if (slots.stream().noneMatch(s -> s.team == 0) || slots.stream().noneMatch(s -> s.team == 1)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cada lado precisa de ao menos 1 campeão com ficha.");
        }
        List<String> roles = roles(slots, names);
        guides(slots, chosen, roles);
        return new Draft(slots, chosen, roles, names, left);
    }

    /**
     * Plays a match of a draft: the blue side's macro and the red side's (the same one for both is the plain match).
     * Deterministic by the seed, whatever else runs on the same draft at the same time.
     */
    public MatchModel.Result run(Draft d, long seed, double limitMinutes, com.ornnplanner.arena.rift.MacroPolicy blue, com.ornnplanner.arena.rift.MacroPolicy red) {
        long start = System.nanoTime();
        List<MatchModel.Slot> slots = d.slots;
        List<BuildRepository.Build> chosen = d.chosen;
        List<String> roles = d.roles;
        List<String> names = d.names;
        Live world = new Live(d);
        com.ornnplanner.arena.rift.MacroPolicy policy = blue == red ? blue : new com.ornnplanner.arena.rift.SeatMacro(blue, red);
        Match m = new Match(arena.riftMap(), arena.riftRules(), macro, world, policy, seed, roles, d.teams());
        List<String> styles = new ArrayList<>();
        slots.forEach(s -> styles.add(s.style));
        m.styles(styles);
        for (int i = 0; i < slots.size(); i++) {
            MatchModel.Player p = new MatchModel.Player();
            p.seat = i;
            p.buildId = slots.get(i).buildId;
            p.champion = names.get(i);
            p.build = chosen.get(i).name;
            p.team = slots.get(i).team;
            p.role = roles.get(i);
            p.plannedGoldPerMin = chosen.get(i).goldPerMin;
            p.plannedXpPerMin = chosen.get(i).xpPerMin;
            m.result.players.add(p);
        }
        MatchModel.Result r = m.run(Math.max(1, limitMinutes) * 60);
        for (Match.Seat s : m.seats()) {
            MatchModel.Player p = r.players.get(s.index);
            double minutes = Math.max(0.1, r.seconds / 60);
            p.level = s.level;
            p.kills = s.kills;
            p.deaths = s.deaths;
            p.minions = s.minions;
            p.monsters = s.monsters;
            p.goldEarned = Math.round(s.gold);
            p.goldPerMin = Math.round((s.gold - 500) / minutes);
            p.xp = Math.round(s.xp);
            p.xpPerMin = Math.round(s.xp / minutes);
            p.purchases = s.purchases;
        }
        m.towers.forEach(t -> r.towers.add(new double[] {t.team, t.x, t.y}));
        r.world = arena.riftMap().drawing();
        r.left = d.left;
        r.runSeconds = (System.nanoTime() - start) / 1e9;
        r.fightSeconds = world.fightNanos / 1e9;
        return r;
    }

    /**
     * Rodada 14: a draft of builds that need not be saved (the campaign's kits), each with its champion, team, role and
     * lane style; no guide. The champions must be playable (sheet A or B).
     */
    public Draft draftOf(List<BuildRepository.Build> chosen, List<String> names, List<Integer> teams, List<String> roles, List<String> styles) {
        List<MatchModel.Slot> slots = new ArrayList<>();
        for (int i = 0; i < chosen.size(); i++) {
            MatchModel.Slot s = new MatchModel.Slot();
            s.buildId = chosen.get(i).id == null ? 0 : chosen.get(i).id;
            s.team = teams.get(i);
            s.role = roles.get(i);
            s.style = styles.get(i);
            slots.add(s);
        }
        return new Draft(slots, new ArrayList<>(chosen), new ArrayList<>(roles), new ArrayList<>(names), new ArrayList<>());
    }

    /** The match role of a champion's first lane (barao | selva | meio | dupla | suporte), "barao" when it has none. */
    public String roleOf(String champion) {
        List<String> lanes = laneNames(champion);
        for (Map.Entry<String, String> e : new java.util.TreeMap<>(LANE_OF).entrySet()) {
            if (!lanes.isEmpty() && lanes.get(0).equals(e.getValue())) {
                return e.getKey();
            }
        }
        return "barao";
    }

    /** Asked roles first; the others by the champion's lanes, then whatever is free on the side. */
    private List<String> roles(List<MatchModel.Slot> slots, List<String> names) {
        List<String> out = new ArrayList<>();
        Map<Integer, List<String>> free = new HashMap<>();
        free.put(0, new ArrayList<>(ROLES));
        free.put(1, new ArrayList<>(ROLES));
        for (MatchModel.Slot s : slots) {
            out.add(s.role);
            if (s.role != null) {
                free.get(s.team).remove(s.role);
            }
        }
        for (int i = 0; i < slots.size(); i++) {
            if (out.get(i) != null) {
                continue;
            }
            List<String> open = free.get(slots.get(i).team);
            List<String> lanes = laneNames(names.get(i));
            String pick = open.stream().filter(r -> lanes.contains(LANE_OF.get(r))).findFirst().orElse(open.isEmpty() ? "meio" : open.get(0));
            open.remove(pick);
            out.set(i, pick);
        }
        return out;
    }

    private List<String> laneNames(String champion) {
        for (ChampionProfiles.Profile p : profiles.all()) {
            if (champion.equals(p.name)) {
                return p.lanes;
            }
        }
        return List.of();
    }

    /** The match's world: real builds, the planner's shop, and fights on the arena at the fine step. */
    private final class Live implements Match.World {
        final List<MatchModel.Slot> slots;
        final List<BuildRepository.Build> chosen;
        final List<List<Model.TimelineStep>> steps;
        final Model.ReferenceData ref;
        final Map<String, Arena.Input> inputs;
        long fightNanos;

        final List<String> names;

        Live(Draft d) {
            this.names = d.names;
            this.slots = d.slots;
            this.chosen = d.chosen;
            this.steps = d.steps;
            this.ref = d.ref;
            this.inputs = d.inputs;
        }

        /** How many purchases of the build are done after this many steps of its timeline (assumed parts do not count). */
        private int prefix(int seat, int done) {
            List<Model.TimelineStep> list = steps.get(seat);
            return done == 0 ? 0 : list.get(done - 1).buildIndex + (list.get(done - 1).implied ? 0 : 1);
        }

        private Slot slot(int seat, int done, int level, double minute) {
            Slot s = new Slot();
            s.team = slots.get(seat).team;
            if (slots.get(seat).guideId != null || slots.get(seat).buildId <= 0) {
                s.build = chosen.get(seat);   // the guide's order, not the saved one; or a build not saved (Rodada 14)
            } else {
                s.buildId = slots.get(seat).buildId;
            }
            s.minute = (double) Math.max(1, Math.round(minute));
            s.matchSteps = prefix(seat, done);
            s.matchLevel = level;
            s.brain = slots.get(seat).brain;
            return s;
        }

        private Arena.Input input(int seat, int done, int level, double minute) {
            // everything that makes the fighter: the slot's minute is rounded the same way below
            String key = seat + "|" + prefix(seat, done) + "|" + level + "|" + Math.max(1, Math.round(minute));
            return inputs.computeIfAbsent(key, k -> {
                Slot other = new Slot();
                other.team = 1 - slots.get(seat).team;
                other.dummy = new ArenaModel.Dummy();
                Scenario sc = new Scenario();
                sc.fighters = List.of(slot(seat, done, level, minute), other);
                return arena.prepare(sc).get(0);
            });
        }

        @Override
        public double[] stats(int seat, int done, int level, double minute) {
            Arena.Input in = input(seat, done, level, minute);
            double as = in.baseAttackSpeed * (1 + in.f.stat("% Attack Speed") / 100);
            return new double[] {in.f.stat(Stats.MAX_HEALTH), in.f.stat(Stats.ATTACK_DAMAGE), as, in.f.stat(Stats.ABILITY_POWER), in.f.stat(Stats.MOVE_SPEED)};
        }

        @Override
        public String name(int seat) {
            return names.get(seat);
        }

        @Override
        public boolean teleport(int seat) {
            return chosen.get(seat).spells != null && chosen.get(seat).spells.contains("Teleporte");
        }

        @Override
        public boolean shopsAnywhere(int seat) {
            return Boolean.TRUE.equals(kit(seat).get("compra_fora_da_base"));
        }

        @Override
        public boolean sellsToAllies(int seat) {
            return Boolean.TRUE.equals(kit(seat).get("compra_para_aliados"));
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> kit(int seat) {
            Object all = arena.kits().get("campeoes");
            Object k = all instanceof Map ? ((Map<String, Object>) all).get(names.get(seat)) : null;
            return k instanceof Map ? (Map<String, Object>) k : Map.of();
        }

        @Override
        public int steps(int seat) {
            return steps.get(seat).size();
        }

        @Override
        public double cost(int seat, int done) {
            return done == 0 ? 0 : steps.get(seat).get(done - 1).cumulativeGold;
        }

        @Override
        public boolean sold(int seat, int index, double minute) {
            Model.ItemDef item = ref.items.get(steps.get(seat).get(index).itemId);
            return item == null || planner.shopRuleCheck().allowedAt(item, minute);
        }

        @Override
        public int levelFor(double xp) {
            return levelOf(xp);
        }

        private int levelOf(double xp) {
            int level = 1;
            for (Map.Entry<Integer, Double> e : ref.xpTable.entrySet()) {
                if (xp >= e.getValue()) {
                    level = e.getKey();
                }
            }
            return level;
        }

        @Override
        public Match.FightOutcome fight(List<Match.Seat> who, double x, double y, double minute, List<int[]> fallen, Match match) {
            long t0 = System.nanoTime();
            Scenario sc = new Scenario();
            sc.name = "Luta da partida aos " + (int) minute + ":" + String.format("%02d", (int) (minute * 60) % 60);
            sc.duration = RiftValue.of(macro, "luta_duracao", 20);
            sc.rift = new ArenaModel.RiftOptions();
            sc.rift.minute = minute;
            sc.rift.minions = false;
            sc.rift.jungle = false;
            sc.rift.plants = false;
            sc.rift.objectives = false;
            sc.rift.around = new double[] {x, y, 1800};
            sc.rift.fallen = new ArrayList<>();
            for (int[] f : fallen) {
                sc.rift.fallen.add(f[0]);
            }
            sc.objectives = new ArenaModel.ObjectiveBuffs[] {match.buffs[0], match.buffs[1]};
            sc.fighters = new ArrayList<>();
            List<Arena.Input> run = new ArrayList<>();
            for (Match.Seat s : who) {
                Slot slot = slot(s.index, s.steps, s.level, minute);
                slot.x = (double) Math.round(s.x);
                slot.y = (double) Math.round(s.y);
                slot.startHealth = Math.max(0.05, s.hp);
                sc.fighters.add(slot);
                Arena.Input in = input(s.index, s.steps, s.level, minute).copy();
                in.x = s.x;
                in.y = s.y;
                in.startHealth = slot.startHealth;
                run.add(in);
            }
            ArenaModel.Result r = arena.matchFight(run, sc, RiftValue.of(macro, "luta_calma", 3));
            Match.FightOutcome o = new Match.FightOutcome();
            o.seconds = r.duration;
            o.scenario = JSON.convertValue(sc, Scenario.class);
            for (int i = 0; i < who.size(); i++) {
                ArenaModel.FighterStats f = r.fighters.get(i);
                o.after.put(who.get(i).index, new double[] {f.health / Math.max(1, f.maxHealth), f.endX, f.endY, f.deathTime != null ? 1 : 0,
                    f.goldEarned, f.xpEarned, f.events.getOrDefault("abate", 0.0)});
            }
            fightNanos += System.nanoTime() - t0;
            return o;
        }
    }

    /** Numbers of the macro file. */
    private static final class RiftValue {
        static double of(Map<String, Object> m, String key, double def) {
            return com.ornnplanner.arena.rift.RiftRules.value(m.get(key), def);
        }
    }

    /** The players a match can have, for the screen. */
    public Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<String> ok = new ArrayList<>();
        ((Map<?, ?>) arena.kits().get("campeoes")).keySet().forEach(k -> {
            if (playable(String.valueOf(k))) {
                ok.add(String.valueOf(k));
            }
        });
        m.put("champions", ok);
        m.put("roles", ROLES);
        // what a trained macro must match to be current (the screen warns about the others)
        m.put("macroSchema", com.ornnplanner.arena.rift.MacroObservation.SCHEMA);
        m.put("macroActions", com.ornnplanner.arena.rift.MacroActions.SIZE);
        m.put("macroRules", MacroTraining.RULES);
        m.put("patch", macros.gamePatch());
        return m;
    }
}
