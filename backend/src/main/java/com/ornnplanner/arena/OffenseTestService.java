package com.ornnplanner.arena;

import com.ornnplanner.arena.OffenseModel.Entry;
import com.ornnplanner.arena.OffenseModel.Job;
import com.ornnplanner.arena.OffenseModel.Measures;
import com.ornnplanner.arena.OffenseModel.Outcome;
import com.ornnplanner.arena.OffenseModel.Report;
import com.ornnplanner.arena.OffenseModel.Request;
import com.ornnplanner.arena.OffenseModel.Target;
import com.ornnplanner.arena.ai.ActionSpace;
import com.ornnplanner.arena.ai.Policy;
import com.ornnplanner.repo.BuildRepository;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Offensive effectiveness (Rodada 9, part 1b): the build, with the automatic brain, attacks a target ragdoll that does
 * not attack, and the measure is the time to kill it (or the damage rate when it does not die in the fight). The
 * reference is the same champion at the first minute of the level, with no items and the build's runes, as in the
 * defensive tests. Fights are cached and run in parallel; the Arena's rules decide everything (no parallel formula).
 */
@Service
public class OffenseTestService {

    /** Where the build stands; the target starts {@link #distance} ahead of it. */
    private static final double BUILD_X = 5000;
    /** The brain of the build that attacks. */
    public static final String BRAIN = AutoBrain.AUTO;

    private final EffectTestService effect;
    private final ArenaService arena;
    private final JdbcTemplate jdbc;
    private final BuildRepository builds;
    private final Map<String, Target> presets = new LinkedHashMap<>();
    private final List<EffectModel.Sweep> sweeps = new ArrayList<>();
    private final List<Map<String, Double>> mixes = new ArrayList<>();
    private final Report defaultReport = new Report();
    private Target sweepTarget = new Target();
    private double duration = 30;
    private double distance = 550;
    private final Map<String, Measures> results = Collections.synchronizedMap(new LinkedHashMap<String, Measures>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Measures> e) {
            return size() > 20000;
        }
    });

    @SuppressWarnings("unchecked")
    public OffenseTestService(EffectTestService effect, ArenaService arena, JdbcTemplate jdbc, BuildRepository builds,
                              @Value("${planner.seed.effectiveness:classpath:seed/efetividade_7_3d.yml}") Resource file) {
        this.effect = effect;
        this.arena = arena;
        this.jdbc = jdbc;
        this.builds = builds;
        try (InputStream in = file.getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> off = (Map<String, Object>) root.getOrDefault("ofensivo", Map.of());
            duration = num(off.get("duracao"), duration);
            distance = num(off.get("distancia"), distance);
            for (Object o : (List<Object>) off.getOrDefault("misturas", List.of())) {
                Map<String, Double> m = new LinkedHashMap<>();
                ((Map<String, Object>) o).forEach((k, v) -> m.put(k, num(v, 0)));
                mixes.add(Collections.unmodifiableMap(m));
            }
            ((Map<String, Object>) off.getOrDefault("alvos", Map.of())).forEach((k, v) -> {
                Target t = target((Map<String, Object>) v);
                t.key = k;
                t.preset = true;
                presets.put(k, t);
            });
            Map<String, Object> sw = (Map<String, Object>) off.getOrDefault("varredura", Map.of());
            if (sw.get("alvo") != null) {
                sweepTarget = target((Map<String, Object>) sw.get("alvo"));
            }
            for (Object o : (List<Object>) sw.getOrDefault("dimensoes", List.of())) {
                Map<String, Object> m = (Map<String, Object>) o;
                EffectModel.Sweep s = new EffectModel.Sweep();
                s.key = String.valueOf(m.get("chave"));
                s.name = String.valueOf(m.get("nome"));
                for (Object st : (List<Object>) m.getOrDefault("estagios", List.of())) {
                    s.stages.add(num(st, 0));
                }
                sweeps.add(s);
            }
            Map<String, Object> rep = (Map<String, Object>) off.getOrDefault("relatorio", Map.of());
            defaultReport.name = String.valueOf(rep.getOrDefault("nome", "Causar dano"));
            defaultReport.isDefault = true;
            for (Object o : (List<Object>) rep.getOrDefault("alvos", List.of())) {
                Map<String, Object> e = (Map<String, Object>) o;
                Entry en = new Entry();
                en.key = String.valueOf(e.get("alvo"));
                en.weight = num(e.get("peso"), 1);
                defaultReport.targets.add(en);
            }
            for (Object o : (List<Object>) rep.getOrDefault("varreduras", List.of())) {
                sweeps.stream().filter(s -> s.key.equals(String.valueOf(o))).findFirst().ifPresent(s -> defaultReport.sweeps.add(copy(s)));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + file, e);
        }
    }

    private static Target target(Map<String, Object> m) {
        Target t = new Target();
        t.name = String.valueOf(m.getOrDefault("nome", "Alvo"));
        t.note = m.get("nota") == null ? null : String.valueOf(m.get("nota"));
        t.health = num(m.get("vida"), t.health);
        t.armor = num(m.get("armadura"), t.armor);
        t.magicResist = num(m.get("rm"), t.magicResist);
        t.moveSpeed = num(m.get("vdm"), t.moveSpeed);
        t.behavior = String.valueOf(m.getOrDefault("comportamento", "parado"));
        t.healPct = num(m.get("cura_pct"), 0);
        t.shield = num(m.get("escudo"), 0);
        t.shieldEvery = num(m.get("escudo_a_cada"), t.shieldEvery);
        t.shieldDuration = num(m.get("escudo_duracao"), t.shieldDuration);
        t.growTo = m.get("cresce_ate") == null ? null : num(m.get("cresce_ate"), 0);
        t.growSeconds = num(m.get("cresce_em"), t.growSeconds);
        t.dashEvery = num(m.get("avanco_a_cada"), 0);
        t.dashDistance = num(m.get("avanco_distancia"), t.dashDistance);
        t.retaliate = num(m.get("revide"), 0);
        return t;
    }

    // ------------------------------------------------------------------ settings

    public OffenseModel.Meta meta() {
        OffenseModel.Meta m = new OffenseModel.Meta();
        m.presets = presets();
        m.userTargets = userTargets();
        m.sweeps = new ArrayList<>();
        sweeps.forEach(s -> m.sweeps.add(copy(s)));
        m.mixes = mixes;
        m.report = report();
        m.duration = duration;
        return m;
    }

    public Map<String, Target> presets() {
        Map<String, Target> out = new LinkedHashMap<>();
        presets.forEach((k, t) -> out.put(k, t.copy()));
        return out;
    }

    public List<Map<String, Double>> mixes() {
        return mixes;
    }

    public List<Target> userTargets() {
        List<Target> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT id, data FROM effect_test WHERE kind = 'alvo' ORDER BY name COLLATE NOCASE")) {
            Target t = EffectTestService.read(String.valueOf(row.get("data")), Target.class);
            t.id = ((Number) row.get("id")).longValue();
            t.key = null;
            t.preset = false;
            out.add(t);
        }
        return out;
    }

    public Target saveTarget(Target t) {
        if (t.health <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O alvo precisa de Vida.");
        }
        t.key = null;
        t.preset = false;
        t.name = t.name == null || t.name.isBlank() ? "Alvo" : t.name.trim();
        t.id = upsert("alvo", t.id, t.name, EffectTestService.write(t));
        return t;
    }

    public void deleteTarget(long id) {
        jdbc.update("DELETE FROM effect_test WHERE id = ? AND kind = 'alvo'", id);
    }

    public Report report() {
        List<String> data = jdbc.queryForList("SELECT data FROM effect_test WHERE kind = 'relatorio_ofensivo'", String.class);
        if (data.isEmpty()) {
            return EffectTestService.read(EffectTestService.write(defaultReport), Report.class);
        }
        Report r = EffectTestService.read(data.get(0), Report.class);
        r.isDefault = false;
        return r;
    }

    public Report saveReport(Report r) {
        r.isDefault = false;
        if (r.name == null) {
            r.name = defaultReport.name;
        }
        List<Long> ids = jdbc.queryForList("SELECT id FROM effect_test WHERE kind = 'relatorio_ofensivo'", Long.class);
        upsert("relatorio_ofensivo", ids.isEmpty() ? null : ids.get(0), r.name, EffectTestService.write(r));
        return r;
    }

    public Report restoreReport() {
        jdbc.update("DELETE FROM effect_test WHERE kind = 'relatorio_ofensivo'");
        return report();
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

    // ------------------------------------------------------------------ runs

    private Target targetOf(Job job) {
        Target t;
        if (job.target != null) {
            t = job.target.copy();
        } else if (job.targetKey != null && job.targetKey.startsWith("u:")) {
            long id = Long.parseLong(job.targetKey.substring(2));
            t = userTargets().stream().filter(x -> x.id == id).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Alvo " + id + " não existe."));
        } else if (job.targetKey != null && presets.containsKey(job.targetKey)) {
            t = presets.get(job.targetKey).copy();
        } else if (job.stage != null) {
            t = sweepTarget.copy();
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Alvo desconhecido: " + job.targetKey);
        }
        if (job.stage != null) {
            sweep(t, job.stage);
        }
        return t;
    }

    /** One stage of a sweep on a target. */
    static void sweep(Target t, EffectModel.Stage st) {
        double v = st.value;
        switch (st.dimension) {
            case "vida":
                t.health = Math.max(1, v);
                break;
            case "resistencias":
                t.armor = v;
                t.magicResist = v;
                break;
            case "cura":
                t.healPct = v;
                break;
            case "escudo":
                t.shield = v;
                t.shieldEvery = 1;
                t.shieldDuration = 1.2;
                break;
            case "velocidade":
                t.moveSpeed = v;
                t.behavior = "anda";
                break;
            case "avancos":
                t.dashEvery = v > 0 ? 1 / v : 0;
                break;
            default:
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dimensão desconhecida: " + st.dimension);
        }
        t.name = t.name + " · " + st.dimension + " " + v;
    }

    /** The arena's ragdoll of a target: mortal, never attacking, with its healing, shields and retaliation as pulses. */
    static ArenaModel.Dummy dummy(Target t, long seed) {
        ArenaModel.Dummy d = new ArenaModel.Dummy();
        d.name = t.name;
        d.health = Math.max(1, t.health);
        d.armor = t.armor;
        d.magicResist = t.magicResist;
        d.moveSpeed = t.moveSpeed;
        d.behavior = t.behavior == null ? "parado" : t.behavior;
        d.spells = new ArrayList<>();
        d.immortal = false;
        d.growTo = t.growTo;
        d.growSeconds = t.growSeconds;
        d.dashEvery = t.dashEvery;
        d.dashDistance = t.dashDistance;
        d.seed = seed;
        d.pulses = new ArrayList<>();
        if (t.healPct > 0) {
            ArenaModel.Pulse p = new ArenaModel.Pulse();
            p.name = "Cura do alvo";
            p.kind = "cura";
            p.base = t.health * t.healPct / 100;
            p.every = 1;
            p.range = 0;
            d.pulses.add(p);
        }
        if (t.shield > 0) {
            ArenaModel.Pulse p = new ArenaModel.Pulse();
            p.name = "Escudo do alvo";
            p.kind = "escudo";
            p.base = t.shield;
            p.every = Math.max(0.5, t.shieldEvery);
            p.duration = t.shieldDuration;
            p.range = 0;
            d.pulses.add(p);
        }
        if (t.retaliate > 0) {
            ArenaModel.Pulse p = new ArenaModel.Pulse();
            p.name = "Revide";
            p.kind = "dano";
            p.type = "fisico";
            p.base = t.retaliate;
            p.every = 1;
            p.range = 700;
            d.pulses.add(p);
        }
        return d;
    }

    private Arena.Input targetInput(Target t, long seed) {
        Arena.Input in = arena.dummyInput(dummy(t, seed)).copy();
        in.f.team = 1;
        in.policy = (o, m) -> Policy.Decision.of(ActionSpace.IDLE);
        in.followTeam = false;
        return in;
    }

    /** Where the target starts, by start: one place, or (the mean of starts) distance and side varied. */
    private double[][] starts(String mode, Integer n0) {
        if (!EffectModel.STARTS.equals(mode)) {
            return new double[][] {{distance, 0}};
        }
        int n = Math.max(2, Math.min(16, n0 == null ? 8 : n0));
        double[][] out = new double[n][];
        for (int k = 0; k < n; k++) {
            out[k] = new double[] {distance - 150 + 300.0 * k / (n - 1), (k % 2 == 0 ? 1 : -1) * (60 * (k % 4))};
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

    /** Runs a batch: every job's fights and the reference's, the ones not cached in parallel. */
    public List<Outcome> evaluate(Request req) {
        BuildRepository.Build build = buildOf(req);
        String buildKey = EffectTestService.hash(EffectTestService.write(build)) + "@" + req.minute;
        String mode = req.readMode == null || req.readMode.isBlank() ? EffectModel.AS_PLAYED : req.readMode;
        double[][] at = starts(mode, req.starts);
        double refMinute = effect.referenceMinute(build, req.minute);
        Map<String, List<Arena.Input>> todo = new LinkedHashMap<>();
        // this batch's measures, kept apart from the shared cache: another batch running at the same time can push
        // an entry out of it between the check and the mean
        Map<String, Measures> got = new java.util.HashMap<>();
        Map<String, Double> lengths = new LinkedHashMap<>();
        List<Outcome> out = new ArrayList<>();
        List<List<List<String>>> keys = new ArrayList<>();
        for (Job job : req.jobs) {
            Outcome o = new Outcome();
            o.tag = job.tag;
            o.readMode = mode;
            out.add(o);
            try {
                Target t = targetOf(job);
                o.targetName = t.name;
                o.targetNote = t.note;
                EffectModel.Variant v = job.variant == null ? new EffectModel.Variant() : job.variant;
                String tk = EffectTestService.hash(EffectTestService.write(t));
                List<String> mine = new ArrayList<>();
                List<String> ref = new ArrayList<>();
                for (int k = 0; k < at.length; k++) {
                    double[] p = at[k];
                    long seed = k + 1;
                    String bk = buildKey + "|" + EffectTestService.write(v) + "|" + tk + "|" + k + "|" + mode;
                    mine.add(bk);
                    if (!cached(bk, got) && !todo.containsKey(bk)) {
                        todo.put(bk, inputs(effect.fighter(build, req.minute, v, BRAIN), t, p, seed));
                    }
                    String rk = EffectTestService.hash(EffectTestService.write(build)) + "@ref" + refMinute + "|" + tk + "|" + k + "|" + mode;
                    ref.add(rk);
                    if (!cached(rk, got) && !todo.containsKey(rk)) {
                        todo.put(rk, inputs(effect.referenceFighter(build, req.minute, BRAIN), t, p, seed));
                    }
                }
                keys.add(List.of(mine, ref));
            } catch (ResponseStatusException e) {
                o.error = e.getReason();
                keys.add(null);
            }
        }
        got.putAll(run(todo));
        for (int i = 0; i < out.size(); i++) {
            if (keys.get(i) == null) {
                continue;
            }
            Outcome o = out.get(i);
            o.build = mean(keys.get(i).get(0), got);
            o.reference = mean(keys.get(i).get(1), got);
            setIndex(o);
        }
        return out;
    }

    private List<Arena.Input> inputs(Arena.Input build, Target t, double[] at, long seed) {
        Arena.Input me = build.copy();
        me.x = BUILD_X;
        me.y = 0;
        Arena.Input target = targetInput(t, seed);
        target.x = BUILD_X + at[0];
        target.y = at[1];
        return List.of(me, target);
    }

    /** Whether the cache has {@code k}; a hit is copied into the batch's own {@code got}. */
    private boolean cached(String k, Map<String, Measures> got) {
        Measures m = results.get(k);
        if (m != null) {
            got.put(k, m);
        }
        return m != null;
    }

    private Map<String, Measures> run(Map<String, List<Arena.Input>> todo) {
        List<String> ks = new ArrayList<>(todo.keySet());
        List<Callable<Measures>> runs = new ArrayList<>();
        for (String k : ks) {
            List<Arena.Input> in = todo.get(k);
            runs.add(() -> measure(arena.runInputs(in, duration)));
        }
        List<Measures> done = ArenaService.parallel(runs);
        Map<String, Measures> out = new java.util.HashMap<>();
        for (int i = 0; i < ks.size(); i++) {
            results.put(ks.get(i), done.get(i));
            out.put(ks.get(i), done.get(i));
        }
        return out;
    }

    /** The build's numbers (fighter 0) against the target (fighter 1). */
    public static Measures measure(ArenaModel.Result r) {
        ArenaModel.FighterStats me = r.fighters.get(0);
        ArenaModel.FighterStats t = r.fighters.get(1);
        Measures m = new Measures();
        double elapsed = Math.max(1e-6, r.duration);
        m.fightLength = r.duration;
        m.killed = t.deathTime != null;
        m.timeToKill = m.killed ? t.deathTime : elapsed;
        m.healthLeftPct = m.killed ? 0 : 100 * Math.max(0, t.health) / Math.max(1, t.maxHealth);
        double removed = t.maxHealth - Math.max(0, m.killed ? 0 : t.health);
        m.effectiveDps = removed / Math.max(1e-6, m.timeToKill);
        double net = Math.max(1e-6, t.taken - t.shieldAbsorbed - t.healed);
        m.smoothTime = m.killed ? Math.min(m.timeToKill, t.maxHealth * m.timeToKill / net) : m.timeToKill;
        m.raw = t.rawTaken;
        m.lostToResist = Math.max(0, t.rawTaken - t.taken);
        m.lostToShield = t.shieldAbsorbed;
        m.lostToHeal = t.healed;
        double raw = Math.max(1e-6, t.rawTaken);
        m.lostToResistPct = 100 * m.lostToResist / raw;
        m.lostToShieldPct = 100 * m.lostToShield / raw;
        m.lostToHealPct = 100 * m.lostToHeal / raw;
        m.aimedCast = me.events.getOrDefault("mira_lancada", 0.0);
        m.aimedHit = me.events.getOrDefault("mira_acertou", 0.0);
        m.attacks = me.events.getOrDefault("ataque_iniciado", 0.0);
        m.attacksHit = me.events.getOrDefault("ataque_acertou", 0.0);
        m.inRange = me.events.getOrDefault("em_alcance", 0.0);
        return m;
    }

    /** The mean of the starts' measures (killed: when most starts killed). */
    private Measures mean(List<String> ks, Map<String, Measures> got) {
        List<Measures> list = new ArrayList<>();
        ks.forEach(k -> list.add(got.get(k)));
        if (list.size() == 1) {
            return list.get(0);
        }
        Measures m = new Measures();
        int killed = 0;
        for (Measures x : list) {
            killed += x.killed ? 1 : 0;
            m.timeToKill += x.timeToKill;
            m.smoothTime += x.smoothTime;
            m.healthLeftPct += x.healthLeftPct;
            m.effectiveDps += x.effectiveDps;
            m.raw += x.raw;
            m.lostToResist += x.lostToResist;
            m.lostToShield += x.lostToShield;
            m.lostToHeal += x.lostToHeal;
            m.aimedCast += x.aimedCast;
            m.aimedHit += x.aimedHit;
            m.attacks += x.attacks;
            m.attacksHit += x.attacksHit;
            m.inRange += x.inRange;
            m.fightLength += x.fightLength;
        }
        double n = list.size();
        m.killed = killed * 2 > n;
        m.timeToKill /= n;
        m.smoothTime /= n;
        m.healthLeftPct /= n;
        m.effectiveDps /= n;
        m.raw /= n;
        m.lostToResist /= n;
        m.lostToShield /= n;
        m.lostToHeal /= n;
        m.aimedCast /= n;
        m.aimedHit /= n;
        m.attacks /= n;
        m.attacksHit /= n;
        m.inRange /= n;
        m.fightLength /= n;
        double raw = Math.max(1e-6, m.raw);
        m.lostToResistPct = 100 * m.lostToResist / raw;
        m.lostToShieldPct = 100 * m.lostToShield / raw;
        m.lostToHealPct = 100 * m.lostToHeal / raw;
        return m;
    }

    /** The offensive index of an outcome. */
    static void setIndex(Outcome o) {
        if (o.build == null || o.reference == null) {
            return;
        }
        o.index = index(o.build, o.reference);
        o.byRate = !(o.build.killed && o.reference.killed);
    }

    /**
     * Reference's time to kill ÷ the build's × 100 (the smooth times: the last blow counts for the health it took); when
     * either does not kill, the effective damage rates instead.
     */
    public static double index(Measures build, Measures ref) {
        if (build.killed && ref.killed) {
            return 100 * ref.smoothTime / Math.max(1e-6, build.smoothTime);
        }
        return 100 * build.effectiveDps / Math.max(1, ref.effectiveDps);
    }

    /**
     * The offensive index of a build at a minute over the report's targets, weighted (what the class score of the
     * calculator and the discovery read). Null when nothing ran.
     */
    public Double reportIndex(BuildRepository.Build build, double minute, List<Entry> targets, String readMode) {
        Request req = new Request();
        req.build = build;
        req.minute = minute;
        req.readMode = readMode;
        for (Entry e : targets) {
            Job j = new Job();
            j.targetKey = e.key;
            req.jobs.add(j);
        }
        List<Outcome> out = evaluate(req);
        double sum = 0;
        double w = 0;
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).index != null) {
                sum += targets.get(i).weight * out.get(i).index;
                w += targets.get(i).weight;
            }
        }
        return w > 0 ? sum / w : null;
    }

    private static EffectModel.Sweep copy(EffectModel.Sweep s) {
        EffectModel.Sweep c = new EffectModel.Sweep();
        c.key = s.key;
        c.name = s.name;
        c.stages = new ArrayList<>(s.stages);
        return c;
    }

    private static double num(Object o, double def) {
        return o instanceof Number ? ((Number) o).doubleValue() : def;
    }
}
