package com.ornnplanner.arena;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ornnplanner.arena.ArenaModel.Scenario;
import com.ornnplanner.arena.ArenaModel.Slot;
import com.ornnplanner.arena.ai.NeuralPolicy;
import com.ornnplanner.arena.ai.PolicyRepository;
import com.ornnplanner.arena.ai.TacticalPolicy;
import com.ornnplanner.arena.fight.CombatCatalog;
import com.ornnplanner.arena.fight.FighterBuilder;
import com.ornnplanner.arena.fight.Fighter;
import com.ornnplanner.arena.fight.ItemEffect;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.seed.RuneCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrated matchups: runs a scenario on the arena (each champion from a saved build at a minute, with its ability
 * sheet from {@code kits_<patch>.yml}) and stores scenarios (table arena_scenario, as JSON).
 */
@Service
public class ArenaService {

    /** Up to 5 per side, 6 with clones (Wukong). */
    public static final int MAX_TEAM = 6;
    public static final double MAX_DURATION = 180;

    /** Cooldowns of item actives the combat file does not carry (s). */
    private static final Map<String, Double> ITEM_COOLDOWNS = Map.of(
            "Ampulheta de Zhonya", 90.0, "Armaguarda da Caçadora", 150.0, "Placa Gargolítica", 60.0,
            "Medalhão dos Solari de Ferro", 60.0, "Bênção de Mikael", 120.0, "Cimitarra Mercurial", 90.0,
            "Bandana de Mercúrio", 90.0);

    private final FighterBuilder combat;
    private final CombatCatalog catalog;
    private final BuildRepository builds;
    private final RuneCatalog runes;
    private final JdbcTemplate jdbc;
    private final PolicyRepository policies;
    private final ComboRepository combos;
    private final Map<String, Object> kits = new LinkedHashMap<>();
    private final Map<String, Object> map = new LinkedHashMap<>();
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @SuppressWarnings("unchecked")
    public ArenaService(FighterBuilder combat, CombatCatalog catalog, BuildRepository builds, RuneCatalog runes,
                        JdbcTemplate jdbc, PolicyRepository policies, ComboRepository combos,
                        @Value("${planner.seed.kits:classpath:seed/kits_7_3d.yml}") Resource kitsFile) {
        this.combat = combat;
        this.catalog = catalog;
        this.builds = builds;
        this.runes = runes;
        this.jdbc = jdbc;
        this.policies = policies;
        this.combos = combos;
        try (InputStream in = kitsFile.getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            kits.putAll((Map<String, Object>) root.get("campeoes"));
            map.putAll((Map<String, Object>) root.get("mapa"));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + kitsFile, e);
        }
    }

    /** The ability sheets and the map, for the screen (ranges to draw, shapes, sources). */
    public Map<String, Object> kits() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mapa", map);
        out.put("campeoes", kits);
        return out;
    }

    public ArenaModel.Result run(Scenario sc) {
        List<Arena.Input> inputs = prepare(sc);
        return new Arena(catalog, map).withSafeZone(sc.safeZone).run(inputs, sc.actions == null ? List.of() : sc.actions, duration(sc));
    }

    /**
     * What each item and rune effect of a build (saved or not) is worth at a minute, by the fight's own formulas: a
     * zero-second fight against the ragdoll ({@link Arena#withEffectValues}).
     */
    public List<Map<String, Object>> effectValues(BuildRepository.Build build, Double minute) {
        Scenario sc = new Scenario();
        Slot me = new Slot();
        me.team = 0;
        me.build = build;
        me.minute = minute;
        Slot other = new Slot();
        other.team = 1;
        other.dummy = new ArenaModel.Dummy();
        sc.fighters = List.of(me, other);
        List<Arena.Input> inputs = prepare(sc);
        // the stats of the planner's sheet at the minute (the conditional effects as the build switches them)
        FighterBuilder.Combatant c = new FighterBuilder.Combatant();
        c.build = build;
        c.minute = minute;
        com.ornnplanner.engine.Model.MinutePoint p = combat.plannerPoint(c);
        inputs.get(0).f.sheet = new LinkedHashMap<>(p.total);
        inputs.get(0).f.sheetBase = new LinkedHashMap<>(p.base);
        return new Arena(catalog, map).withoutFrames().withEffectValues().run(inputs, List.of(), 0).fighters.get(0).effectValues;
    }

    /** The stats a build (saved or not) starts a fight with at a minute: the zero-second run of {@link SeenBy}. */
    public SeenBy.Base startBase(BuildRepository.Build build, Double minute) {
        Slot me = new Slot();
        me.team = 0;
        me.build = build;
        me.minute = minute;
        Slot other = new Slot();
        other.team = 1;
        other.dummy = new ArenaModel.Dummy();
        Scenario sc = new Scenario();
        sc.fighters = List.of(me, other);
        List<Arena.Input> in = prepare(sc);
        return SeenBy.base(catalog, map, in.get(0), in.get(1));
    }

    /** A fight of prepared champions without frames (numbers only): the effectiveness tests reuse their inputs. */
    public ArenaModel.Result runInputs(List<Arena.Input> inputs, double duration) {
        return new Arena(catalog, map).withoutFrames().stopWhenDead(0).run(inputs, List.of(), duration);
    }

    /** The same fight without frames (numbers only). */
    public ArenaModel.Result runQuiet(Scenario sc) {
        List<Arena.Input> inputs = prepare(sc);
        return new Arena(catalog, map).withSafeZone(sc.safeZone).withoutFrames()
                .run(inputs, sc.actions == null ? List.of() : sc.actions, duration(sc));
    }

    /**
     * The value of each item of each champion in the scenario: the same fight without the item (no frames, the runs in
     * parallel). One fight per item, so it is asked for, not run with every fight.
     */
    public List<ArenaModel.ItemValue> itemValues(Scenario sc) {
        return itemValues(sc, null);
    }

    /** The same, only for the items of one slot (null = every slot). */
    public List<ArenaModel.ItemValue> itemValues(Scenario sc, Integer onlySlot) {
        List<Arena.Input> base = prepare(sc);
        List<ArenaModel.ItemValue> rows = new ArrayList<>();
        List<java.util.concurrent.Callable<ArenaModel.Result>> runs = new ArrayList<>();
        for (int k = 0; k < sc.fighters.size(); k++) {
            Slot slot = sc.fighters.get(k);
            if (slot.dummy != null || slot.buildId == null || onlySlot != null && onlySlot != k) {
                continue;
            }
            for (Map.Entry<String, Long> item : base.get(k).f.items.entrySet()) {
                Scenario without = JSON.convertValue(sc, Scenario.class);
                Slot s = without.fighters.get(k);
                s.excludeItems = new LinkedHashSet<>(slot.excludeItems == null ? Set.of() : slot.excludeItems);
                s.excludeItems.add(item.getValue());
                List<Arena.Input> inputs = prepare(without);   // builds (and the database) on this thread
                ArenaModel.ItemValue v = new ArenaModel.ItemValue();
                v.fighter = k;
                v.item = item.getKey();
                v.itemId = item.getValue();
                rows.add(v);
                runs.add(() -> new Arena(catalog, map).withSafeZone(without.safeZone).withoutFrames()
                        .run(inputs, without.actions == null ? List.of() : without.actions, duration(without)));
            }
        }
        List<ArenaModel.Result> results = parallel(runs);
        for (int i = 0; i < rows.size(); i++) {
            fill(rows.get(i), results.get(i));
        }
        return rows;
    }

    /** The numbers of a fight without the item, for the champion it was taken from. */
    static void fill(ArenaModel.ItemValue v, ArenaModel.Result alt) {
        ArenaModel.FighterStats me = alt.fighters.get(v.fighter);
        v.winner = alt.winner;
        v.duration = alt.duration;
        v.dealt = me.dealt;
        v.alive = me.deathTime != null ? me.deathTime : alt.duration;
        for (ArenaModel.FighterStats f : alt.fighters) {
            if (f.team == me.team) {
                v.teamHealth += Math.max(0, f.health);
            } else {
                v.enemyHealth += Math.max(0, f.health);
            }
        }
    }

    /** Runs fights on all cores; results in the order given (each fight is deterministic on its own). */
    static <T> List<T> parallel(List<java.util.concurrent.Callable<T>> runs) {
        if (runs.isEmpty()) {
            return List.of();
        }
        int threads = Math.max(1, Math.min(runs.size(), Runtime.getRuntime().availableProcessors()));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            List<T> out = new ArrayList<>();
            for (java.util.concurrent.Future<T> f : pool.invokeAll(runs)) {
                out.add(f.get());
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof RuntimeException ? (RuntimeException) e.getCause() : new IllegalStateException(e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    static double duration(Scenario sc) {
        return Math.max(5, Math.min(MAX_DURATION, sc.duration <= 0 ? 60 : sc.duration));
    }

    /** The arena's champions for a scenario (builds resolved once; training reuses them for every fight). */
    @SuppressWarnings("unchecked")
    List<Arena.Input> prepare(Scenario sc) {
        if (sc.fighters == null || sc.fighters.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O cenário não tem campeões.");
        }
        long blue = sc.fighters.stream().filter(f -> f.team == 0).count();
        long red = sc.fighters.size() - blue;
        if (blue == 0 || red == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cada lado precisa de ao menos 1 campeão.");
        }
        if (blue > MAX_TEAM || red > MAX_TEAM) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No máximo " + MAX_TEAM + " por lado.");
        }
        List<Arena.Input> inputs = new ArrayList<>();
        int[] perTeam = new int[2];
        int[] ofTeam = {(int) blue, (int) red};
        for (Slot slot : sc.fighters) {
            if (slot.dummy != null) {
                Arena.Input in = dummyInput(slot.dummy);
                in.f.team = slot.team;
                in.target = slot.target;
                in.team = plan(sc, slot.team);
                in.followTeam = slot.followTeam;
                double[] start = start(slot.team, perTeam[slot.team]++, ofTeam[slot.team]);
                in.x = slot.x != null ? slot.x : start[0];
                in.y = slot.y != null ? slot.y : start[1];
                if ("reage".equals(slot.dummy.behavior)) {
                    in.policy = new ComboBench.ReactPolicy(in.priority);
                } else {
                    in.policy = (o, m) -> com.ornnplanner.arena.ai.Policy.Decision.of(com.ornnplanner.arena.ai.ActionSpace.IDLE);
                }
                inputs.add(in);
                continue;
            }
            if (slot.buildId == null && slot.build == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha uma build para cada campeão.");
            }
            BuildRepository.Build build = slot.build != null ? slot.build : builds.findBuild(slot.buildId).orElseThrow(
                    () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Build " + slot.buildId + " não existe."));
            FighterBuilder.Combatant c = new FighterBuilder.Combatant();
            c.buildId = slot.buildId;
            c.build = slot.build;
            c.minute = slot.minute;
            c.activations = slot.activations;
            c.excludeItems = slot.excludeItems;
            c.replaceItems = slot.replaceItems;
            c.edits = slot.edits;
            // The champion's name decides its sheet; the sheet's form effects are switched by the arena itself.
            Map<String, Object> kit = (Map<String, Object>) kits.get(combat.championName(c));
            Set<String> forms = new LinkedHashSet<>();
            if (kit != null && kit.get("efeitos_de_forma") instanceof Map) {
                forms.addAll(((Map<String, Object>) kit.get("efeitos_de_forma")).keySet());
            }
            FighterBuilder.ArenaBuild ab = combat.arenaBuild(c, forms);
            Fighter f = ab.fighter;
            f.team = slot.team;
            Arena.Input in = new Arena.Input();
            in.f = f;
            in.triggers = ab.triggers;
            in.build = build.name;
            in.kit = kit;
            in.forms = forms.isEmpty() ? new LinkedHashMap<>() : combat.formDeltas(c, forms);
            in.target = slot.target;
            in.team = plan(sc, slot.team);
            in.followTeam = slot.followTeam;
            in.autoAbilities = slot.autoAbilities;
            if (slot.priority != null && !slot.priority.isEmpty()) {
                in.priority = new ArrayList<>(slot.priority);
            }
            in.combos = combos.forPolicy(in.f.champion, null);   // the library's active combos, best first
            in.policy = policy(slot.brain, in);
            in.baseAttackSpeed = f.baseAttackSpeed > 0 ? f.baseAttackSpeed : 0.7;
            double[] start = start(slot.team, perTeam[slot.team]++, ofTeam[slot.team]);
            in.x = slot.x != null ? slot.x : start[0];
            in.y = slot.y != null ? slot.y : start[1];
            for (String item : f.items.keySet()) {
                Double cd = ITEM_COOLDOWNS.get(item);
                for (ItemEffect e : f.effects) {
                    if (e.item.equals(item) && "ativo".equals(e.kind())) {
                        cd = ((Number) e.p.getOrDefault("recarga", 60)).doubleValue();
                    }
                }
                if (cd != null) {
                    in.activeCooldowns.put(item, cd);
                }
            }
            for (String spell : f.spells) {
                runes.catalog().spells.stream().filter(s -> s.name.equals(spell) && s.cooldown != null)
                        .findFirst().ifPresent(s -> in.activeCooldowns.put(spell, s.cooldown.doubleValue()));
            }
            inputs.add(in);
        }
        return inputs;
    }

    /** A side's plan (null when the scenario has none, as the ones saved before plans). */
    private static ArenaModel.TeamPlan plan(Scenario sc, int team) {
        ArenaModel.TeamPlan p = sc.teams != null && team < sc.teams.length ? sc.teams[team] : null;
        if (p != null && p.focus != null && !ArenaModel.TeamPlan.FOCUS.contains(p.focus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Critério de foco desconhecido: " + p.focus + ".");
        }
        return p;
    }

    /**
     * The policy of a brain: script (null), tactical, or ai:&lt;id&gt; (a saved learned policy of that champion, with
     * weights of the current observation and action layout).
     */
    com.ornnplanner.arena.ai.Policy policy(String brain, Arena.Input in) {
        if (brain == null || brain.isBlank() || "script".equals(brain)) {
            return null;
        }
        if ("tactical".equals(brain)) {
            return new TacticalPolicy(in.priority);
        }
        if (brain.startsWith("ai:")) {
            long id;
            try {
                id = Long.parseLong(brain.substring(3));
            } catch (NumberFormatException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cérebro desconhecido: " + brain + ".");
            }
            PolicyRepository.Record r = policies.find(id).orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "A IA " + id + " não existe mais."));
            if (!r.compatible()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A IA v" + r.version + " de " + r.champion
                        + " foi treinada para outra versão da arena; treine de novo.");
            }
            if (!r.champion.equalsIgnoreCase(in.f.champion)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A IA v" + r.version + " é de " + r.champion
                        + ", não de " + in.f.champion + ".");
            }
            // migrated when read, on the tactical rules it was trained over; its level decides its combos and guidelines
            in.combos = levelCombos(r.level, in.f.champion);
            NeuralPolicy n = r.policy(in.priority);
            return ArenaTraining.LEVEL_HINTS.equals(r.level) && r.guidelines != null && !r.guidelines.isEmpty()
                    ? new com.ornnplanner.arena.ai.GuidedPolicy(n, r.guidelines) : n;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cérebro desconhecido: " + brain + ".");
    }

    public ComboRepository combos() {
        return combos;
    }

    /** The combos a champion's AI of a help level may run: none alone, the found ones, or found and manual (hints). */
    public List<ArenaModel.Combo> levelCombos(String level, String champion) {
        if (ArenaTraining.LEVEL_FOUND.equals(level)) {
            return combos.forPolicy(champion, List.of("descoberto"));
        }
        if (ArenaTraining.LEVEL_HINTS.equals(level)) {
            return combos.forPolicy(champion, null);
        }
        return List.of();
    }

    /** A champion of a saved build at a minute, alone (the lab): as a scenario's slot of the blue side. */
    public Arena.Input labInput(long buildId, Double minute) {
        return labInput(buildId, minute, null);
    }

    /** The same without some of its items (null = all of them). */
    public Arena.Input labInput(long buildId, Double minute, java.util.Set<Long> without) {
        Scenario sc = new Scenario();
        Slot me = new Slot();
        me.team = 0;
        me.buildId = buildId;
        me.minute = minute;
        me.excludeItems = without;
        Slot other = new Slot();
        other.team = 1;
        other.dummy = new ArenaModel.Dummy();
        sc.fighters = List.of(me, other);
        return prepare(sc).get(0);
    }

    /**
     * The ragdoll's fighter: its stats alone, or wearing a saved build's items and runes at a minute (the build as
     * the planner's "Boneco de pano" unit, with the ragdoll's stats as its base).
     */
    public Arena.Input dummyInput(ArenaModel.Dummy d) {
        if (d.wearBuildId == null && (d.items == null || d.items.isEmpty())) {
            return ComboBench.dummyInput(d);
        }
        if (d.wearBuildId == null) {
            return itemsDummy(d);
        }
        BuildRepository.Build worn = builds.findBuild(d.wearBuildId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Build " + d.wearBuildId + " não existe."));
        BuildRepository.Build b = JSON.convertValue(worn, BuildRepository.Build.class);
        b.id = null;
        b.unitCode = com.ornnplanner.seed.ReferenceSeeder.RAGDOLL;
        b.championSetup = null;
        b.ragdollStats = new LinkedHashMap<>();
        b.ragdollStats.put(com.ornnplanner.engine.Stats.MAX_HEALTH, ragdoll(d.health));
        b.ragdollStats.put(com.ornnplanner.engine.Stats.ARMOR, ragdoll(d.armor));
        b.ragdollStats.put(com.ornnplanner.engine.Stats.MAGIC_RESIST, ragdoll(d.magicResist));
        b.ragdollStats.put(com.ornnplanner.engine.Stats.MOVE_SPEED, ragdoll(d.moveSpeed));
        FighterBuilder.Combatant c = new FighterBuilder.Combatant();
        c.build = b;
        c.minute = d.wearMinute;
        FighterBuilder.ArenaBuild ab = combat.arenaBuild(c, java.util.Set.of());
        Fighter f = ab.fighter;
        f.name = d.name == null ? "Boneco de pano" : d.name;
        f.champion = "Boneco de pano";
        f.team = 1;
        f.ranks = new int[4];
        f.abilities = new ArrayList<>();
        Arena.Input in = new Arena.Input();
        in.f = f;
        in.triggers = ab.triggers;
        in.build = worn.name;
        in.baseAttackSpeed = ComboBench.dummyAttackSpeed(d);
        if (d.attackDamage > 0) {
            f.total.put(com.ornnplanner.engine.Stats.ATTACK_DAMAGE, f.total.getOrDefault(com.ornnplanner.engine.Stats.ATTACK_DAMAGE, 0.0)
                    + d.attackDamage);
        }
        in.kit = ComboBench.dummyKit(d);
        ComboBench.withSpells(in, d);
        in.dummy = d;
        return in;
    }

    /** Game minute at which a test ragdoll holds its items (every item bought, the shop's 10:00 rule passed). */
    static final double DUMMY_ITEMS_MINUTE = 30;

    /**
     * A ragdoll wearing shop items (the effectiveness tests' attackers and allies): the planner's ragdoll unit at level
     * 15 with its stats as base and the items bought at once, so their stats, passives and fight effects count as for a
     * champion; then its other stats on top.
     */
    private Arena.Input itemsDummy(ArenaModel.Dummy d) {
        BuildRepository.Build b = new BuildRepository.Build();
        b.name = d.name == null ? "Boneco de pano" : d.name;
        b.unitCode = com.ornnplanner.seed.ReferenceSeeder.RAGDOLL;
        b.goldPerMin = 1e6;
        b.xpPerMin = 13100 / DUMMY_ITEMS_MINUTE;   // level 15 well before the minute
        b.matchEnd = DUMMY_ITEMS_MINUTE;
        b.steps = new ArrayList<>();
        for (Long id : d.items) {
            if (id != null) {
                b.steps.add(new BuildRepository.Step(id, true));
            }
        }
        b.ragdollStats = new LinkedHashMap<>();
        b.ragdollStats.put(com.ornnplanner.engine.Stats.MAX_HEALTH, ragdoll(d.health));
        b.ragdollStats.put(com.ornnplanner.engine.Stats.ARMOR, ragdoll(d.armor));
        b.ragdollStats.put(com.ornnplanner.engine.Stats.MAGIC_RESIST, ragdoll(d.magicResist));
        b.ragdollStats.put(com.ornnplanner.engine.Stats.MOVE_SPEED, ragdoll(d.moveSpeed));
        Map<String, Double> extra = d.stats == null ? Map.of() : d.stats;
        b.ragdollStats.put(com.ornnplanner.engine.Stats.ATTACK_DAMAGE,
                ragdoll(d.attackDamage + extra.getOrDefault(com.ornnplanner.engine.Stats.ATTACK_DAMAGE, 0.0)));
        for (String stat : com.ornnplanner.engine.Stats.UNIT_STATS) {
            if (!b.ragdollStats.containsKey(stat) && extra.containsKey(stat)) {
                b.ragdollStats.put(stat, ragdoll(extra.get(stat)));
            }
        }
        FighterBuilder.Combatant c = new FighterBuilder.Combatant();
        c.build = b;
        c.minute = DUMMY_ITEMS_MINUTE;
        FighterBuilder.ArenaBuild ab = combat.arenaBuild(c, java.util.Set.of());
        Fighter f = ab.fighter;
        f.name = b.name;
        f.champion = "Boneco de pano";
        f.team = 1;
        f.ranks = new int[4];
        f.abilities = new ArrayList<>();
        ComboBench.withStats(f, d, false);
        Arena.Input in = new Arena.Input();
        in.f = f;
        in.triggers = ab.triggers;
        in.build = b.name;
        in.baseAttackSpeed = ComboBench.dummyAttackSpeed(d);
        in.kit = ComboBench.dummyKit(d);
        ComboBench.withSpells(in, d);
        in.dummy = d;
        return in;
    }

    private static BuildRepository.RagdollStat ragdoll(double base) {
        BuildRepository.RagdollStat s = new BuildRepository.RagdollStat();
        s.base = base;
        s.growth = 0.0;
        return s;
    }

    /** Default start: next to the side's Nexus, side by side. */
    private double[] start(int team, int k, int count) {
        List<?> nexus = (List<?>) map.get(team == 0 ? "nexus_azul" : "nexus_vermelho");
        double x = ((Number) nexus.get(0)).doubleValue();
        double y = ((Number) nexus.get(1)).doubleValue() + (k - (count - 1) / 2.0) * 160;
        return new double[] {x, y};
    }

    // ------------------------------------------------------------------ stored scenarios

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT id, name, updated_at AS updatedAt FROM arena_scenario ORDER BY name COLLATE NOCASE");
    }

    public Scenario find(long id) {
        List<String> data = jdbc.queryForList("SELECT data FROM arena_scenario WHERE id = ?", String.class, id);
        if (data.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        try {
            Scenario sc = JSON.readValue(data.get(0), Scenario.class);
            sc.id = id;
            return sc;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cenário " + id + " inválido", e);
        }
    }

    public Scenario save(Scenario sc) {
        String name = sc.name == null || sc.name.isBlank() ? "Cenário" : sc.name.trim();
        String now = Instant.now().toString();
        String data;
        try {
            sc.name = name;
            data = JSON.writeValueAsString(sc);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
        if (sc.id == null) {
            KeyHolder kh = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO arena_scenario (name, data, created_at, updated_at) VALUES (?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, name);
                ps.setString(2, data);
                ps.setString(3, now);
                ps.setString(4, now);
                return ps;
            }, kh);
            sc.id = kh.getKey().longValue();
        } else if (jdbc.update("UPDATE arena_scenario SET name = ?, data = ?, updated_at = ? WHERE id = ?",
                name, data, now, sc.id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return sc;
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM arena_scenario WHERE id = ?", id);
    }
}
