package com.ornnplanner.arena;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ornnplanner.arena.EffectModel.Bot;
import com.ornnplanner.arena.EffectModel.HeldItem;
import com.ornnplanner.arena.EffectModel.Job;
import com.ornnplanner.arena.EffectModel.Measures;
import com.ornnplanner.arena.EffectModel.Outcome;
import com.ornnplanner.arena.EffectModel.Reference;
import com.ornnplanner.arena.EffectModel.Report;
import com.ornnplanner.arena.EffectModel.Request;
import com.ornnplanner.arena.EffectModel.Role;
import com.ornnplanner.arena.EffectModel.Stage;
import com.ornnplanner.arena.EffectModel.Sweep;
import com.ornnplanner.arena.EffectModel.Test;
import com.ornnplanner.arena.EffectModel.Variant;
import com.ornnplanner.arena.fight.FighterBuilder;
import com.ornnplanner.engine.Model.ItemContribution;
import com.ornnplanner.engine.Model.ItemDef;
import com.ornnplanner.engine.Model.StatLine;
import com.ornnplanner.engine.Stats;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.service.PlannerService;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Effectiveness by test scenarios: standard fights of the build tested (tactical brain, its conditional effects firing
 * for real) against test ragdolls that attack, with optional ragdoll allies, all in the arena by the fight's own rules.
 * The index of a scenario is the build's time to die over the reference's (the same champion at the same level with
 * no items and the build's runes, another build, or a folder's mean) x 100.
 * <p>
 * Every fight is short, without frames, deterministic and cached by (build, minute, scenario, stage, variant); the
 * fights of a batch run in parallel. The screens send batches (a few scenarios at a time) to show progress and stop.
 */
@Service
public class EffectTestService {

    /** Dimensions of the complete report's sweeps (the stage value replaces that attribute of the reference attacker). */
    public static final List<String> DIMENSIONS = List.of("pen_pct", "pen_pct_fisica", "pen_pct_magica", "pen_fixa",
            "critico", "dda", "pdh", "resistencias", "vida", "inimigos");
    /** The shares of the gold in pure stats tried for the per-item reading (c): Health, Armor, Magic Resist in thirds. */
    public static final List<Map<String, Double>> MIXES = mixes();
    public static final int MAX_ATTACKERS = 5;
    public static final int MAX_ALLIES = 4;
    /** The most % penetration a sweep stage may ask for. */
    public static final double MAX_PEN_PCT = 75;
    /** The % penetration stages before the 75% limit: a report saved with them follows the new default. */
    private static final List<Double> OLD_PEN_STAGES = List.of(0.0, 25.0, 50.0, 75.0, 95.0);
    private static final double BUILD_X = 5000;
    private static final int CACHE = 6000;
    /** A build that survives the whole fight: its estimated time to die is at most this many times the duration. */
    static final double ESTIMATE_CAP = 5;

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ArenaService arena;
    private final FighterBuilder combat;
    private final PlannerService planner;
    private final BuildRepository builds;
    private final JdbcTemplate jdbc;

    private final Map<String, Role> roles = new LinkedHashMap<>();
    /** Presets as read (items by name, resolved to ids on first use: the catalog is imported after start). */
    private final Map<String, Map<String, Object>> rawPresets = new LinkedHashMap<>();
    private Map<String, Test> presets;
    private Map<String, Object> rawSweepBot;
    private Bot sweepBot;
    private double sweepDuration = 180;
    private final List<Sweep> defaultSweeps = new ArrayList<>();
    private final Map<String, Report> defaultReports = new LinkedHashMap<>();

    private final Map<String, Measures> results = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, Measures>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Measures> e) {
                    return size() > CACHE;
                }
            });
    private final Map<String, Arena.Input> inputs = new java.util.concurrent.ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public EffectTestService(ArenaService arena, FighterBuilder combat, PlannerService planner, BuildRepository builds,
                             JdbcTemplate jdbc,
                             @Value("${planner.seed.effectiveness:classpath:seed/efetividade_7_3d.yml}") Resource file) {
        this.arena = arena;
        this.combat = combat;
        this.planner = planner;
        this.builds = builds;
        this.jdbc = jdbc;
        try (InputStream in = file.getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            ((Map<String, Object>) root.getOrDefault("funcoes", Map.of())).forEach((k, v) -> {
                Map<String, Object> m = (Map<String, Object>) v;
                Role r = new Role();
                r.key = k;
                r.attackRange = num(m.get("alcance"), 150);
                r.position = String.valueOf(m.getOrDefault("posicao", "frente"));
                r.pulses = pulses((List<Object>) m.get("pulsos"));
                roles.put(k, r);
            });
            ((Map<String, Object>) root.getOrDefault("cenarios", Map.of()))
                    .forEach((k, v) -> rawPresets.put(k, (Map<String, Object>) v));
            Map<String, Object> sweep = (Map<String, Object>) root.getOrDefault("varredura", Map.of());
            rawSweepBot = (Map<String, Object>) sweep.get("atacante");
            sweepDuration = num(sweep.get("duracao"), 180);
            for (Object o : (List<Object>) sweep.getOrDefault("dimensoes", List.of())) {
                Map<String, Object> m = (Map<String, Object>) o;
                Sweep s = new Sweep();
                s.key = String.valueOf(m.get("chave"));
                s.name = String.valueOf(m.get("nome"));
                for (Object st : (List<Object>) m.getOrDefault("estagios", List.of())) {
                    s.stages.add(num(st, 0));
                }
                defaultSweeps.add(s);
            }
            ((Map<String, Object>) root.getOrDefault("relatorios", Map.of())).forEach((k, v) -> {
                Map<String, Object> m = (Map<String, Object>) v;
                Report r = new Report();
                r.kind = k;
                r.name = String.valueOf(m.getOrDefault("nome", k));
                r.isDefault = true;
                for (Object o : (List<Object>) m.getOrDefault("cenarios", List.of())) {
                    Map<String, Object> e = (Map<String, Object>) o;
                    EffectModel.Entry en = new EffectModel.Entry();
                    en.key = String.valueOf(e.get("cenario"));
                    en.weight = num(e.get("peso"), 1);
                    r.tests.add(en);
                }
                for (Object o : (List<Object>) m.getOrDefault("varreduras", List.of())) {
                    defaultSweeps.stream().filter(s -> s.key.equals(String.valueOf(o))).findFirst()
                            .ifPresent(s -> r.sweeps.add(copy(s)));
                }
                defaultReports.put(k, r);
            });
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + file, e);
        }
    }

    // ------------------------------------------------------------------ seed and the user's tests

    public Map<String, Role> roles() {
        return roles;
    }

    /** The presets, with their items resolved to the shop's ids. */
    public synchronized Map<String, Test> presets() {
        if (presets == null) {
            Map<String, Long> ids = new LinkedHashMap<>();
            planner.itemViews().forEach(i -> ids.put(i.name, i.id));
            Map<String, Test> out = new LinkedHashMap<>();
            rawPresets.forEach((k, m) -> out.put(k, test(k, m, ids)));
            sweepBot = rawSweepBot == null ? new Bot() : bot(rawSweepBot, ids);
            presets = out;
        }
        return presets;
    }

    public Bot sweepAttacker() {
        presets();
        return sweepBot;
    }

    public List<Sweep> defaultSweeps() {
        List<Sweep> out = new ArrayList<>();
        defaultSweeps.forEach(s -> out.add(copy(s)));
        return out;
    }

    public List<Test> userTests() {
        List<Test> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT id, data FROM effect_test WHERE kind = 'cenario' ORDER BY name COLLATE NOCASE")) {
            Test t = read(String.valueOf(row.get("data")), Test.class);
            t.id = ((Number) row.get("id")).longValue();
            t.key = null;
            t.preset = false;
            out.add(t);
        }
        return out;
    }

    public Test saveTest(Test t) {
        validate(t);
        t.key = null;
        t.preset = false;
        String name = t.name == null || t.name.isBlank() ? "Cenário de teste" : t.name.trim();
        t.name = name;
        t.id = upsert("cenario", t.id, name, write(t));
        return t;
    }

    public void deleteTest(long id) {
        jdbc.update("DELETE FROM effect_test WHERE id = ? AND kind = 'cenario'", id);
    }

    /** A report's settings: the user's, or the seed's default. */
    public Report report(String kind) {
        Report def = defaultReports.get(kind);
        if (def == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Relatório desconhecido: " + kind);
        }
        List<String> data = jdbc.queryForList("SELECT data FROM effect_test WHERE kind = ?", String.class, "relatorio_" + kind);
        if (data.isEmpty()) {
            return JSON.convertValue(def, Report.class);
        }
        Report r = read(data.get(0), Report.class);
        r.kind = kind;
        r.isDefault = false;
        for (Sweep s : r.sweeps) {
            if (s.key.startsWith("pen_pct") && OLD_PEN_STAGES.equals(s.stages)) {
                defaultSweeps.stream().filter(d -> d.key.equals(s.key)).findFirst().ifPresent(d -> s.stages = new ArrayList<>(d.stages));
            }
        }
        return r;
    }

    public Report saveReport(String kind, Report r) {
        report(kind);
        r.kind = kind;
        r.isDefault = false;
        if (r.name == null) {
            r.name = defaultReports.get(kind).name;
        }
        List<Long> ids = jdbc.queryForList("SELECT id FROM effect_test WHERE kind = ?", Long.class, "relatorio_" + kind);
        upsert("relatorio_" + kind, ids.isEmpty() ? null : ids.get(0), r.name, write(r));
        return r;
    }

    /** Back to the seed's default. */
    public Report restoreReport(String kind) {
        jdbc.update("DELETE FROM effect_test WHERE kind = ?", "relatorio_" + kind);
        return report(kind);
    }

    private long upsert(String kind, Long id, String name, String data) {
        String now = Instant.now().toString();
        if (id != null && jdbc.update("UPDATE effect_test SET name = ?, data = ?, updated_at = ? WHERE id = ? AND kind = ?",
                name, data, now, id, kind) > 0) {
            return id;
        }
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO effect_test (kind, name, data, created_at, updated_at) VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, kind);
            ps.setString(2, name);
            ps.setString(3, data);
            ps.setString(4, now);
            ps.setString(5, now);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    private static void validate(Test t) {
        if (t.attackers == null || t.attackers.isEmpty() || t.attackers.size() > MAX_ATTACKERS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Um cenário de teste tem de 1 a " + MAX_ATTACKERS
                    + " bonecos atacantes.");
        }
        if (t.allies != null && t.allies.size() > MAX_ALLIES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No máximo " + MAX_ALLIES + " bonecos aliados.");
        }
    }

    // ------------------------------------------------------------------ the build at the minute

    /** The build's items at the minute (what the per-item readings and the candidates work on). */
    public List<HeldItem> itemsAt(Request req) {
        FighterBuilder.Combatant c = combatant(req, null);
        Map<Long, ItemDef> defs = planner.referenceData().items;
        List<HeldItem> out = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (ItemContribution ic : combat.plannerPoint(c).contributions) {
            if (ic.rune || !seen.add(ic.itemId)) {
                continue;
            }
            HeldItem h = new HeldItem();
            h.id = ic.itemId;
            h.name = ic.itemName;
            ItemDef d = defs.get(ic.itemId);
            h.cost = d == null ? 0 : d.cost;
            h.slot = ic.implied ? null : ic.buildIndex;
            out.add(h);
        }
        return out;
    }

    private BuildRepository.Build buildOf(Request req) {
        if (req.build != null) {
            return req.build;
        }
        if (req.buildId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha uma build.");
        }
        return builds.findBuild(req.buildId).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Build " + req.buildId + " não existe."));
    }

    private FighterBuilder.Combatant combatant(Request req, BuildRepository.Build b) {
        FighterBuilder.Combatant c = new FighterBuilder.Combatant();
        c.build = b != null ? b : buildOf(req);
        c.minute = req.minute;
        return c;
    }

    // ------------------------------------------------------------------ runs

    /** Runs a batch: every job's fight and its reference, the ones not cached in parallel. */
    public List<Outcome> evaluate(Request req) {
        BuildRepository.Build build = buildOf(req);
        String buildKey = hash(write(build)) + "@" + req.minute;
        Reference ref = req.reference == null ? new Reference() : req.reference;
        List<Outcome> out = new ArrayList<>();
        Map<String, Fight> todo = new LinkedHashMap<>();
        List<String[]> keys = new ArrayList<>();   // per job: build fight key, reference fight keys...
        for (Job job : req.jobs) {
            Outcome o = new Outcome();
            o.tag = job.tag;
            out.add(o);
            try {
                Test test = testOf(job);
                o.testName = test.name;
                Variant v = job.variant == null ? new Variant() : job.variant;
                String tk = hash(write(test));
                String bk = buildKey + "|" + tk + "|" + write(v);
                plan(todo, bk, () -> fight(slotFor(build, req.minute, v), test));
                if ("candidato".equals(v.kind)) {
                    o.held = holds(candidate(build, v), req.minute, v.candidateId);
                }
                List<String> refKeys = new ArrayList<>();
                for (BuildRepository.Build rb : referenceBuilds(build, ref)) {
                    boolean own = rb == build;
                    String rk = (own ? buildKey : hash(write(rb)) + "@" + req.minute) + "|" + tk + "|ref:" + (own ? "proprio" : "build");
                    refKeys.add(rk);
                    plan(todo, rk, () -> fight(own ? noItems(build, req) : slot(rb, req.minute), test));
                }
                List<String> all = new ArrayList<>();
                all.add(bk);
                all.addAll(refKeys);
                keys.add(all.toArray(new String[0]));
            } catch (ResponseStatusException e) {
                o.error = e.getReason();
                keys.add(new String[0]);
            }
        }
        run(todo);
        for (int i = 0; i < out.size(); i++) {
            String[] k = keys.get(i);
            Outcome o = out.get(i);
            if (k.length == 0) {
                continue;
            }
            o.build = results.get(k[0]);
            List<Measures> refs = new ArrayList<>();
            for (int j = 1; j < k.length; j++) {
                refs.add(results.get(k[j]));
            }
            o.reference = mean(refs);
            if (o.build != null && o.reference != null && o.reference.timeToDeath > 1e-9) {
                o.index = 100 * o.build.timeToDeath / o.reference.timeToDeath;
            }
        }
        return out;
    }

    /** The arena scenario of one job (to open the fight in the Arena): the build as it is, with frames. */
    public ArenaModel.Scenario scenario(Request req, Job job) {
        BuildRepository.Build build = buildOf(req);
        Test test = testOf(job);
        ArenaModel.Scenario sc = new ArenaModel.Scenario();
        sc.name = "Efetividade: " + build.name + " × " + test.name;
        sc.duration = test.duration;
        ArenaModel.Slot me = slotFor(build, req.minute, job.variant == null ? new Variant() : job.variant);
        if (me.replaceItems != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Essa variante da build não abre na Arena.");
        }
        if (req.build == null && me.build == build) {
            me.buildId = req.buildId;   // a saved build: the scenario names it (the slot's build is never stored)
            me.build = null;
        }
        me.x = BUILD_X;
        me.y = 0.0;
        sc.fighters.add(me);
        placeBots(test, (team, bot, x, y) -> {
            ArenaModel.Slot s = new ArenaModel.Slot();
            s.team = team;
            s.dummy = dummy(bot, team == 1);
            s.x = x;
            s.y = y;
            sc.fighters.add(s);
        });
        return sc;
    }

    /** A fight to run: its inputs (prepared on the calling thread: builds and the database) and what to read. */
    private static final class Fight {
        List<Arena.Input> inputs;
        int attackers;
        int allies;
        double duration;
    }

    private interface FightMaker {
        Fight make();
    }

    private void plan(Map<String, Fight> todo, String key, FightMaker maker) {
        if (!results.containsKey(key) && !todo.containsKey(key)) {
            todo.put(key, maker.make());
        }
    }

    private void run(Map<String, Fight> todo) {
        List<String> keys = new ArrayList<>(todo.keySet());
        List<Callable<Measures>> runs = new ArrayList<>();
        for (String k : keys) {
            Fight f = todo.get(k);
            runs.add(() -> measure(arena.runInputs(f.inputs, f.duration), f));
        }
        List<Measures> done = ArenaService.parallel(runs);
        for (int i = 0; i < keys.size(); i++) {
            results.put(keys.get(i), done.get(i));
        }
    }

    private Fight fight(ArenaModel.Slot buildSlot, Test test) {
        Fight f = new Fight();
        f.duration = Math.max(5, Math.min(ArenaService.MAX_DURATION, test.duration <= 0 ? 60 : test.duration));
        f.inputs = new ArrayList<>();
        Arena.Input me = buildInput(buildSlot).copy();
        me.x = BUILD_X;
        me.y = 0;
        f.inputs.add(me);
        placeBots(test, (team, bot, x, y) -> {
            Arena.Input in = botInput(bot, team).copy();
            in.x = x;
            in.y = y;
            f.inputs.add(in);
        });
        f.attackers = test.attackers.size();
        f.allies = test.allies == null ? 0 : test.allies.size();
        return f;
    }

    private interface Placer {
        void place(int team, Bot bot, double x, double y);
    }

    /** Attackers across the build (spread on the lane), allies on its side by their role's position. */
    private void placeBots(Test test, Placer p) {
        int n = test.attackers.size();
        for (int k = 0; k < n; k++) {
            Bot b = test.attackers.get(k);
            double back = "tras".equals(role(b).position) ? 250 : "meio".equals(role(b).position) ? 120 : 0;
            p.place(1, b, BUILD_X + 700 + back, (k - (n - 1) / 2.0) * 170);
        }
        List<Bot> allies = test.allies == null ? List.of() : test.allies;
        for (int k = 0; k < allies.size(); k++) {
            Bot b = allies.get(k);
            String pos = role(b).position;
            double x = BUILD_X - ("tras".equals(pos) ? 350 : "meio".equals(pos) ? 200 : 60);
            p.place(0, b, x, (k % 2 == 0 ? 1 : -1) * (200 + 120 * (k / 2)));
        }
    }

    private Role role(Bot b) {
        Role r = roles.get(b.role);
        return r != null ? r : roles.getOrDefault("lutador", new Role());
    }

    /** The arena's ragdoll of a test bot: it walks to its target and attacks, with its role's pattern by default. */
    ArenaModel.Dummy dummy(Bot b) {
        return dummy(b, false);
    }

    /** {@code attacker}: it never dies (the fight lasts until the build dies). */
    ArenaModel.Dummy dummy(Bot b, boolean attacker) {
        ArenaModel.Dummy d = new ArenaModel.Dummy();
        Role r = role(b);
        d.name = b.name == null ? r.key : b.name;
        d.behavior = "ataca";
        d.role = b.role;
        d.health = Math.max(1, b.health);
        d.armor = b.armor;
        d.magicResist = b.magicResist;
        d.moveSpeed = b.moveSpeed;
        d.attackDamage = b.attackDamage;
        d.attackSpeed = b.attackSpeed > 0 ? b.attackSpeed : 1;
        d.attackRange = b.attackRange != null ? b.attackRange : r.attackRange;
        d.stats = new LinkedHashMap<>(b.stats == null ? Map.of() : b.stats);
        d.items = new ArrayList<>(b.items == null ? List.of() : b.items);
        d.spells = new ArrayList<>();
        d.immortal = attacker;
        d.pulses = new ArrayList<>();
        for (ArenaModel.Pulse p : b.pulses != null ? b.pulses : r.pulses) {
            d.pulses.add(p.copy());
        }
        return d;
    }

    private Arena.Input botInput(Bot b, int team) {
        ArenaModel.Dummy d = dummy(b, team == 1);
        String key = "bot|" + team + "|" + write(d);
        return inputs.computeIfAbsent(key, k -> {
            Arena.Input in = arena.dummyInput(d);
            in.f.team = team;
            in.policy = (o, m) -> com.ornnplanner.arena.ai.Policy.Decision.of(com.ornnplanner.arena.ai.ActionSpace.IDLE);
            in.followTeam = false;
            return in;
        });
    }

    private Arena.Input buildInput(ArenaModel.Slot slot) {
        String key = "build|" + hash(write(slot.build)) + "|" + slot.minute + "|" + slot.excludeItems + "|"
                + (slot.replaceItems == null ? "" : write(slot.replaceItems)) + "|" + (slot.edits == null ? "" : write(slot.edits));
        if (inputs.size() > 400) {
            inputs.clear();
        }
        return inputs.computeIfAbsent(key, k -> {
            ArenaModel.Scenario sc = new ArenaModel.Scenario();
            ArenaModel.Slot other = new ArenaModel.Slot();
            other.team = 1;
            other.dummy = new ArenaModel.Dummy();
            sc.fighters = List.of(slot, other);
            Arena.Input in = arena.prepare(sc).get(0);
            in.followTeam = false;
            return in;
        });
    }

    private static ArenaModel.Slot slot(BuildRepository.Build b, Double minute) {
        ArenaModel.Slot s = new ArenaModel.Slot();
        s.team = 0;
        s.build = b;
        s.minute = minute;
        s.brain = "tactical";
        return s;
    }

    /** The build's slot held as the variant says. */
    private ArenaModel.Slot slotFor(BuildRepository.Build build, Double minute, Variant v) {
        String kind = v.kind == null ? "build" : v.kind;
        switch (kind) {
            case "build":
                return slot(build, minute);
            case "sem": {
                ArenaModel.Slot s = slot(build, minute);
                s.excludeItems = Set.of(item(v).id);
                return s;
            }
            case "so_status": {
                ArenaModel.Slot s = slot(build, minute);
                ItemDef it = item(v);
                s.replaceItems = Map.of(it.id, flatOnly(it));
                return s;
            }
            case "ouro": {
                ArenaModel.Slot s = slot(build, minute);
                ItemDef it = item(v);
                s.replaceItems = Map.of(it.id, pureStats(it, v.mix == null ? MIXES.get(0) : v.mix));
                return s;
            }
            case "candidato":
                return slot(candidate(build, v), minute);
            default:
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Variante desconhecida: " + kind);
        }
    }

    /** The build with the candidate item in the purchase at the slot (the timeline and the shop's rules recomputed). */
    private BuildRepository.Build candidate(BuildRepository.Build build, Variant v) {
        return planner.withItem(build, v.slot, v.candidateId, v.atMinute);
    }

    private boolean holds(BuildRepository.Build b, Double minute, Long itemId) {
        FighterBuilder.Combatant c = new FighterBuilder.Combatant();
        c.build = b;
        c.minute = minute;
        return combat.plannerPoint(c).contributions.stream().anyMatch(ic -> !ic.rune && itemId != null && ic.itemId == itemId);
    }

    /** The reference's slot: the same champion at the same minute (level) with no items, and the build's runes. */
    private ArenaModel.Slot noItems(BuildRepository.Build build, Request req) {
        ArenaModel.Slot s = slot(build, req.minute);
        Set<Long> all = new LinkedHashSet<>();
        for (ItemContribution ic : combat.plannerPoint(combatant(req, build)).contributions) {
            if (!ic.rune) {
                all.add(ic.itemId);
            }
        }
        s.excludeItems = all;
        return s;
    }

    /** The builds the reference runs (the build itself stands for "the same champion without items"). */
    private List<BuildRepository.Build> referenceBuilds(BuildRepository.Build build, Reference ref) {
        String kind = ref.kind == null ? "proprio" : ref.kind;
        if ("build".equals(kind) && ref.buildId != null) {
            return List.of(builds.findBuild(ref.buildId).orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Build " + ref.buildId + " não existe.")));
        }
        if ("pasta".equals(kind) && ref.folderId != null) {
            List<BuildRepository.Build> out = new ArrayList<>();
            for (BuildRepository.Build s : builds.findBuildSummaries()) {
                if (s.folderId == ref.folderId) {
                    builds.findBuild(s.id).ifPresent(out::add);
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        return List.of(build);
    }

    private ItemDef item(Variant v) {
        ItemDef it = v.itemId == null ? null : planner.referenceData().items.get(v.itemId);
        if (it == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item " + v.itemId + " não existe.");
        }
        return it;
    }

    /** The item with only its flat stats: no passive, active, conditional or fight effect (a new id and name). */
    public static ItemDef flatOnly(ItemDef it) {
        ItemDef d = synthetic(it, -(1_000_000L + it.id), it.name + " (só status)");
        for (StatLine l : it.stats) {
            if (l.passive == null && l.ratio == null && !l.conditional && l.perMinute == null) {
                d.stats.add(l);
            }
        }
        return d;
    }

    /** The item's gold in pure stats (Health, Armor, Magic Resist by the shares of {@code mix}), at their gold prices. */
    ItemDef pureStats(ItemDef it, Map<String, Double> mix) {
        int idx = Math.max(0, MIXES.indexOf(mix));
        ItemDef d = synthetic(it, -(2_000_000L + it.id * 100 + idx), "Ouro de " + it.name + " em status");
        goldStats(it.cost, mix).forEach((stat, v) -> d.stats.add(StatLine.flat(stat, v)));
        return d;
    }

    /** What an amount of gold buys in pure stats, by the shares of {@code mix}, at the gold prices. */
    public Map<String, Double> goldStats(double gold, Map<String, Double> mix) {
        Map<String, Double> prices = planner.referenceData().statPrices;
        Map<String, Double> out = new LinkedHashMap<>();
        mix.forEach((stat, share) -> {
            double price = prices.getOrDefault(stat, 0.0);
            if (share != null && share > 0 && price > 0) {
                out.put(stat, gold * share / price);
            }
        });
        return out;
    }

    /** The build at a minute with items held differently, in a scenario (preset key or "u:<id>"), cached. */
    public Measures fightWith(BuildRepository.Build build, double minute, String testKey, Map<Long, com.ornnplanner.engine.Model.ItemEdit> edits) {
        Job job = new Job();
        job.testKey = testKey;
        Test test = testOf(job);
        String key = "edit|" + hash(write(build)) + "@" + minute + "|" + hash(write(test)) + "|" + write(edits);
        Measures m = results.get(key);
        if (m == null) {
            ArenaModel.Slot s = slot(build, minute);
            s.edits = edits.isEmpty() ? null : edits;
            Fight f = fight(s, test);
            m = measure(arena.runInputs(f.inputs, f.duration), f);
            results.put(key, m);
        }
        return m;
    }

    /** The build at a minute, held as {@code edits} says, in its champion's slot of a saved arena scenario. */
    public Measures savedScenario(long id, BuildRepository.Build build, double minute, Map<Long, com.ornnplanner.engine.Model.ItemEdit> edits) {
        ArenaModel.Scenario sc = arena.find(id);
        int slot = -1;
        for (int k = 0; k < sc.fighters.size() && slot < 0; k++) {
            Long b = sc.fighters.get(k).buildId;
            if (b != null && builds.findBuild(b).map(x -> java.util.Objects.equals(build.unitCode, x.unitCode)).orElse(false)) {
                slot = k;
            }
        }
        if (slot < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O cenário não tem esse campeão.");
        }
        ArenaModel.Slot s = sc.fighters.get(slot);
        s.buildId = null;
        s.build = build;
        s.minute = minute;
        s.excludeItems = null;
        s.edits = edits.isEmpty() ? null : edits;
        double duration = ArenaService.duration(sc);
        return measure(arena.runQuiet(sc), slot, 0, 0, duration);
    }

    private static ItemDef synthetic(ItemDef it, long id, String name) {
        ItemDef d = new ItemDef();
        d.id = id;
        d.name = name;
        d.cost = it.cost;
        d.category = it.category;
        d.section = it.section;
        d.tabs = new ArrayList<>(it.tabs == null ? List.of() : it.tabs);
        d.group = name;
        return d;
    }

    private static List<Map<String, Double>> mixes() {
        List<Map<String, Double>> out = new ArrayList<>();
        for (int h = 3; h >= 0; h--) {
            for (int a = 3 - h; a >= 0; a--) {
                Map<String, Double> m = new LinkedHashMap<>();
                m.put(Stats.MAX_HEALTH, h / 3.0);
                m.put(Stats.ARMOR, a / 3.0);
                m.put(Stats.MAGIC_RESIST, (3 - h - a) / 3.0);
                out.add(java.util.Collections.unmodifiableMap(m));
            }
        }
        return java.util.Collections.unmodifiableList(out);
    }

    // ------------------------------------------------------------------ scenarios and sweeps

    private Test testOf(Job job) {
        Test base;
        if (job.stage != null) {
            return sweep(job.stage);
        }
        if (job.test != null) {
            base = job.test;
        } else if (job.testKey != null && job.testKey.startsWith("u:")) {
            long id;
            try {
                id = Long.parseLong(job.testKey.substring(2));
            } catch (NumberFormatException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cenário desconhecido: " + job.testKey);
            }
            base = userTests().stream().filter(t -> t.id == id).findFirst().orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "O cenário " + id + " não existe mais."));
        } else {
            base = presets().get(job.testKey);
            if (base == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cenário desconhecido: " + job.testKey);
            }
        }
        validate(base);
        return base;
    }

    /** The sweep's scenario at a stage: the reference attacker with one attribute set (or that many of it). */
    public Test sweep(Stage st) {
        if (st.dimension == null || !DIMENSIONS.contains(st.dimension)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dimensão desconhecida: " + st.dimension);
        }
        Bot b = sweepAttacker().copy();
        double v = st.dimension.startsWith("pen_pct") ? Math.min(MAX_PEN_PCT, st.value) : st.value;
        switch (st.dimension) {
            case "pen_pct":
                b.stats.put("% Armor Penetration", v);
                b.stats.put("% Magic Penetration", v);
                break;
            case "pen_pct_fisica":
                b.stats.put("% Armor Penetration", v);
                break;
            case "pen_pct_magica":
                b.stats.put("% Magic Penetration", v);
                break;
            case "pen_fixa":
                b.stats.put("Armor Penetration", v);
                b.stats.put("Magic Penetration", v);
                break;
            case "critico":
                b.stats.put("% Critical Rate", v);
                break;
            case "dda":
                b.attackDamage = v;
                break;
            case "pdh":
                b.stats.put(Stats.ABILITY_POWER, v);
                break;
            case "resistencias":
                b.armor = v;
                b.magicResist = v;
                break;
            case "vida":
                b.health = Math.max(1, v);
                break;
            default:
                break;
        }
        Test t = new Test();
        t.duration = sweepDuration;
        t.key = "varredura:" + st.dimension;
        t.name = b.name + " · " + st.dimension + " " + (v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v));
        t.group = "inimigos".equals(st.dimension) && v > 1 ? "equipe" : "1x1";
        int n = "inimigos".equals(st.dimension) ? (int) Math.max(1, Math.min(MAX_ATTACKERS, Math.round(v))) : 1;
        for (int k = 0; k < n; k++) {
            Bot c = b.copy();
            c.name = n == 1 ? b.name : b.name + " " + (k + 1);
            t.attackers.add(c);
        }
        return t;
    }

    // ------------------------------------------------------------------ measures

    /** The build's numbers of a fight (fighter 0), the attackers after it, then the allies. */
    static Measures measure(ArenaModel.Result r, Fight f) {
        return measure(r, f.attackers, f.allies, f.duration);
    }

    public static Measures measure(ArenaModel.Result r, int attackers, int allies, double duration) {
        return measure(r, 0, attackers, allies, duration);
    }

    /** The same, for fighter {@code index}. */
    static Measures measure(ArenaModel.Result r, int index, int attackers, int allies, double duration) {
        ArenaModel.FighterStats me = r.fighters.get(index);
        Measures m = new Measures();
        double elapsed = Math.max(1e-6, r.duration);
        m.fightLength = r.duration;
        m.maxHealth = me.maxHealth;
        m.rawTaken = me.rawTaken;
        m.taken = me.taken;
        m.mitigatedPct = me.rawTaken > 0 ? 100 * (me.rawTaken - me.taken) / me.rawTaken : 0;
        m.shieldAbsorbed = me.shieldAbsorbed;
        m.healed = me.healed;
        m.healLost = me.healLost;
        m.dealt = me.dealt;
        m.controlApplied = me.events.getOrDefault("controle_causado", 0.0);
        // The fight stops when the build dies: the team readings cover the time it was in the fight.
        if (me.deathTime != null) {
            m.died = true;
            m.timeToDeath = me.deathTime;
            m.effectiveHealth = me.rawTaken;
        } else {
            m.estimated = true;
            double lost = me.maxHealth - Math.max(0, me.health);
            double cap = ESTIMATE_CAP * duration;
            m.timeToDeath = lost > 1e-6 ? Math.min(cap, elapsed * me.maxHealth / lost) : cap;
            m.effectiveHealth = me.rawTaken * m.timeToDeath / elapsed;
        }
        if (allies > 0) {
            double team = me.rawTaken;
            double alive = 0;
            for (int k = 0; k < allies; k++) {
                ArenaModel.FighterStats a = r.fighters.get(1 + attackers + k);
                team += a.rawTaken;
                alive += a.deathTime != null ? a.deathTime : r.duration;
            }
            m.absorbedShare = team > 0 ? 100 * me.rawTaken / team : 0;
            m.alliesAlive = alive / allies;
        }
        return m;
    }

    /** The mean of the reference's measures (a folder's builds); one is itself. */
    private static Measures mean(List<Measures> list) {
        List<Measures> ok = new ArrayList<>();
        for (Measures m : list) {
            if (m != null) {
                ok.add(m);
            }
        }
        if (ok.isEmpty()) {
            return null;
        }
        if (ok.size() == 1) {
            return ok.get(0);
        }
        Measures out = new Measures();
        int n = ok.size();
        for (Measures m : ok) {
            out.timeToDeath += m.timeToDeath / n;
            out.effectiveHealth += m.effectiveHealth / n;
            out.rawTaken += m.rawTaken / n;
            out.taken += m.taken / n;
            out.mitigatedPct += m.mitigatedPct / n;
            out.shieldAbsorbed += m.shieldAbsorbed / n;
            out.healed += m.healed / n;
            out.healLost += m.healLost / n;
            out.dealt += m.dealt / n;
            out.controlApplied += m.controlApplied / n;
            out.fightLength += m.fightLength / n;
            out.maxHealth += m.maxHealth / n;
            out.died |= m.died;
            out.estimated |= m.estimated;
        }
        return out;
    }

    // ------------------------------------------------------------------ yaml

    @SuppressWarnings("unchecked")
    private Test test(String key, Map<String, Object> m, Map<String, Long> ids) {
        Test t = new Test();
        t.key = key;
        t.preset = true;
        t.name = String.valueOf(m.getOrDefault("nome", key));
        t.note = m.get("nota") == null ? null : String.valueOf(m.get("nota"));
        t.group = String.valueOf(m.getOrDefault("grupo", "1x1"));
        t.duration = num(m.get("duracao"), 60);
        for (Object o : (List<Object>) m.getOrDefault("atacantes", List.of())) {
            t.attackers.add(bot((Map<String, Object>) o, ids));
        }
        for (Object o : (List<Object>) m.getOrDefault("aliados", List.of())) {
            t.allies.add(bot((Map<String, Object>) o, ids));
        }
        return t;
    }

    @SuppressWarnings("unchecked")
    private Bot bot(Map<String, Object> m, Map<String, Long> ids) {
        Bot b = new Bot();
        b.name = m.get("nome") == null ? null : String.valueOf(m.get("nome"));
        b.role = String.valueOf(m.getOrDefault("funcao", "lutador"));
        b.health = num(m.get("vida"), 8000);
        b.armor = num(m.get("armadura"), 60);
        b.magicResist = num(m.get("rm"), 60);
        b.attackDamage = num(m.get("dda"), 100);
        b.attackSpeed = num(m.get("velocidade_ataque"), 1);
        b.moveSpeed = num(m.get("vdm"), 350);
        b.attackRange = m.get("alcance") == null ? null : num(m.get("alcance"), 150);
        ((Map<String, Object>) m.getOrDefault("status", Map.of())).forEach((k, v) -> b.stats.put(k, num(v, 0)));
        for (Object o : (List<Object>) m.getOrDefault("itens", List.of())) {
            Long id = ids.get(String.valueOf(o));
            if (id == null) {
                throw new IllegalStateException("Item da loja desconhecido em efetividade: " + o);
            }
            b.items.add(id);
        }
        if (m.containsKey("pulsos")) {
            b.pulses = pulses((List<Object>) m.get("pulsos"));
        }
        return b;
    }

    @SuppressWarnings("unchecked")
    private static List<ArenaModel.Pulse> pulses(List<Object> raw) {
        List<ArenaModel.Pulse> out = new ArrayList<>();
        for (Object o : raw == null ? List.of() : raw) {
            Map<String, Object> m = (Map<String, Object>) o;
            ArenaModel.Pulse p = new ArenaModel.Pulse();
            p.name = String.valueOf(m.getOrDefault("nome", "Habilidade"));
            p.kind = String.valueOf(m.getOrDefault("tipo", "dano"));
            p.type = String.valueOf(m.getOrDefault("dano_tipo", "magico"));
            p.base = num(m.get("base"), 0);
            p.ad = num(m.get("dda"), 0);
            p.ap = num(m.get("pdh"), 0);
            p.every = num(m.get("a_cada"), 6);
            p.range = num(m.get("alcance"), 600);
            p.duration = num(m.get("duracao"), 2.5);
            out.add(p);
        }
        return out;
    }

    private static Sweep copy(Sweep s) {
        Sweep c = new Sweep();
        c.key = s.key;
        c.name = s.name;
        c.stages = new ArrayList<>(s.stages);
        return c;
    }

    private static double num(Object o, double def) {
        return o instanceof Number ? ((Number) o).doubleValue() : def;
    }

    static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> T read(String s, Class<T> type) {
        try {
            return JSON.readValue(s, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Dados inválidos em effect_test", e);
        }
    }

    static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
