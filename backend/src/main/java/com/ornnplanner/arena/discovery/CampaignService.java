package com.ornnplanner.arena.discovery;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.ArenaService;
import com.ornnplanner.arena.calc.BuildScore;
import com.ornnplanner.arena.fight.ChampionProfiles;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.repo.DiscoveryRepository;
import com.ornnplanner.repo.DiscoveryRepository.DiscoveryRow;
import com.ornnplanner.seed.ChampionCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The campaigns (Rodada 9, parts 6 and 8): the build discovery for a list of champions (or all of them), in the
 * background, one champion at a time, in a queue kept in {@code build_discovery} by patch, champion and configuration.
 * Each champion goes through its steps: the saved builds first (measured and kept), then the discovery, then the
 * matchups (x1 against the representatives of each class and the usual lane opponents). Pause, resume, cancel and the
 * priority work on the queue; a server stopped mid-way resumes the row it was on (its step again).
 */
@Service
public class CampaignService {

    private static final Logger log = LoggerFactory.getLogger(CampaignService.class);
    private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    public static final List<String> STEPS = List.of("salvas", "descoberta", "matchups", "fim");

    /** What a campaign asks. */
    public static class Request {
        public String name;
        /** Champions by unit code; empty with {@link #all} = every champion of the catalog. */
        public List<String> units = new ArrayList<>();
        public boolean all;
        /** The discovery's settings (the build is each champion's, so buildId is ignored). */
        public DiscoveryModel.Request discovery = new DiscoveryModel.Request();
        public boolean skipLowConfidence = true;
        public boolean saved = true;
        public boolean matchups = true;
        public int priority;
    }

    /** A champion's result in the table. */
    public static class ChampionResult {
        public List<Map<String, Object>> saved = new ArrayList<>();
        public DiscoveryModel.Result discovery;
        public List<Map<String, Object>> matchups = new ArrayList<>();
        public boolean syntheticBase;
    }

    public static class Estimate {
        public int champions;
        public int lowConfidence;
        public int alreadyDone;
        public double seconds;
        public double secondsPerChampion;
    }

    /** The queue's state for the screen. */
    public static class State {
        public boolean running;
        public Long current;
        public String stage;
        public int done;
        public int total;
        public List<DiscoveryRow> rows = new ArrayList<>();
    }

    private final DiscoveryRepository repo;
    private final DiscoveryService discovery;
    private final BuildRepository builds;
    private final ChampionCatalog champions;
    private final ChampionProfiles profiles;
    private final BuildScore scorer;
    private final ArenaService arena;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ornnplanner.publico.PublicMode publicMode;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "campaigns");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running;
    private volatile Long current;
    private volatile DiscoveryModel.Job job;
    /** pausar | cancelar: what to do with the row on the way when its discovery stops. */
    private volatile String interrupt;

    public CampaignService(DiscoveryRepository repo, DiscoveryService discovery, BuildRepository builds, ChampionCatalog champions,
                           ChampionProfiles profiles, BuildScore scorer, ArenaService arena) {
        this.repo = repo;
        this.discovery = discovery;
        this.builds = builds;
        this.champions = champions;
        this.profiles = profiles;
        this.scorer = scorer;
        this.arena = arena;
    }

    /** After the schema is up: a row left "rodando" by a stopped server goes back to the queue. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void resume() {
        if (publicMode != null && publicMode.on()) {
            return;   // the public version reads the campaigns' results, never runs or changes them
        }
        int n = repo.resetRunning();
        if (n > 0) {
            log.info("Campanhas: {} campeão(ões) interrompido(s) voltaram para a fila (Retomar continua).", n);
        }
    }

    public String patch() {
        return champions.patch();
    }

    // ------------------------------------------------------------------ the queue

    private List<String> units(Request r) {
        List<String> out = new ArrayList<>();
        if (r.all || r.units.isEmpty()) {
            champions.summaries().forEach(c -> out.add(String.valueOf(c.get("code"))));
        } else {
            out.addAll(r.units);
        }
        return out;
    }

    String nameOf(String unit) {
        return champions.find(unit).map(c -> String.valueOf(c.get("nome"))).orElse(unit);
    }

    boolean lowConfidence(String unit) {
        String q = discovery.quality(nameOf(unit));
        return !"A".equals(q) && !"B".equals(q);
    }

    static String configKey(String unit, Request r) {
        DiscoveryModel.Request d = DiscoveryService.copy(r.discovery, DiscoveryModel.Request.class);
        d.buildId = null;
        d.build = null;
        return unit + "|" + hash(write(d) + r.saved + r.matchups);
    }

    public Estimate estimate(Request r) {
        Estimate e = new Estimate();
        DiscoveryModel.Request d = DiscoveryService.copy(r.discovery, DiscoveryModel.Request.class);
        double perDiscovery = discovery.estimate(d).seconds;
        int savedTotal = 0;
        for (String unit : units(r)) {
            boolean low = lowConfidence(unit);
            e.lowConfidence += low ? 1 : 0;
            if (low && r.skipLowConfidence) {
                continue;
            }
            DiscoveryRow done = repo.discoveries(null).stream().filter(x -> x.configKey.equals(configKey(unit, r)) && "feito".equals(x.status)
                    && patch().equals(x.patch)).findFirst().orElse(null);
            if (done != null) {
                e.alreadyDone++;
                continue;
            }
            e.champions++;
            savedTotal += r.saved ? (int) builds.findBuildSummaries().stream().filter(b -> unit.equals(b.unitCode)).count() : 0;
        }
        e.secondsPerChampion = perDiscovery + (r.matchups ? 6 * 3 : 0);
        e.seconds = e.champions * e.secondsPerChampion + savedTotal * discovery.secondsPerFull;
        return e;
    }

    /** Puts the champions of a campaign in the queue (the ones already done for this patch and configuration stay done). */
    public List<DiscoveryRow> enqueue(Request r) {
        String campaign = r.name == null || r.name.isBlank() ? "Campanha " + java.time.LocalDate.now() : r.name.trim();
        List<DiscoveryRow> out = new ArrayList<>();
        List<DiscoveryRow> existing = repo.discoveries(null);
        for (String unit : units(r)) {
            String key = configKey(unit, r);
            DiscoveryRow same = existing.stream().filter(x -> x.configKey.equals(key) && patch().equals(x.patch)).findFirst().orElse(null);
            if (same != null && !"cancelado".equals(same.status) && !"erro".equals(same.status)) {
                out.add(same);
                continue;
            }
            DiscoveryRow row = new DiscoveryRow();
            row.patch = patch();
            row.unitCode = unit;
            row.champion = nameOf(unit);
            row.configKey = key;
            row.config = write(r);
            row.campaign = campaign;
            row.priority = r.priority;
            row.step = STEPS.get(0);
            row.status = lowConfidence(unit) && r.skipLowConfidence ? "pulado" : "pendente";
            row.error = "pulado".equals(row.status) ? "baixa confiança: ficha C, D ou sem ficha" : null;
            out.add(repo.insertDiscovery(row));
        }
        start();
        return out;
    }

    public State state() {
        State s = new State();
        s.running = running;
        s.current = current;
        DiscoveryModel.Job j = job;
        if (j != null) {
            s.stage = j.stage;
            s.done = j.done;
            s.total = j.total;
        }
        for (DiscoveryRow r : repo.discoveries(null)) {
            r.result = null;   // the list stays light; the result comes by its id
            r.config = null;
            s.rows.add(r);
        }
        return s;
    }

    public DiscoveryRow row(long id) {
        DiscoveryRow r = repo.discovery(id);
        if (r == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return r;
    }

    /** Starts (or resumes) the worker on the pending rows. */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        interrupt = null;
        worker.submit(this::loop);
    }

    /** Pauses: the champion on the way stops at its next check and goes back to the queue as "pausado". */
    public void pause() {
        interrupt = "pausar";
        DiscoveryModel.Job j = job;
        if (j != null) {
            j.stop = true;
        }
    }

    public void resumeRow(long id) {
        DiscoveryRow r = row(id);
        if ("pausado".equals(r.status) || "erro".equals(r.status) || "cancelado".equals(r.status) || "pulado".equals(r.status)) {
            r.status = "pendente";
            r.error = null;
            repo.updateDiscovery(r);
        }
        start();
    }

    public void cancel(long id) {
        DiscoveryRow r = row(id);
        if (current != null && current == id) {
            interrupt = "cancelar";
            DiscoveryModel.Job j = job;
            if (j != null) {
                j.stop = true;
            }
            return;
        }
        if (!"feito".equals(r.status)) {
            r.status = "cancelado";
            repo.updateDiscovery(r);
        }
    }

    public void priority(long id, int priority) {
        DiscoveryRow r = row(id);
        r.priority = priority;
        repo.updateDiscovery(r);
    }

    public void delete(long id) {
        if (current != null && current == id) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pare o campeão antes de apagar.");
        }
        repo.deleteDiscovery(id);
    }

    private void loop() {
        try {
            while (interrupt == null) {
                DiscoveryRow next = repo.discoveries(null).stream().filter(x -> "pendente".equals(x.status)).findFirst().orElse(null);
                if (next == null) {
                    break;
                }
                runRow(next);
            }
        } finally {
            running = false;
            current = null;
            job = null;
            if ("pausar".equals(interrupt)) {
                interrupt = null;
            }
        }
    }

    /** One champion through its steps; the result is written after each step, so a pause loses at most the step on the way. */
    void runRow(DiscoveryRow row) {
        current = row.id;
        row.status = "rodando";
        repo.updateDiscovery(row);
        long t0 = System.nanoTime();
        Request r = read(row.config, Request.class);
        ChampionResult res = row.result == null ? new ChampionResult() : read(row.result, ChampionResult.class);
        try {
            BuildRepository.Build base = base(row.unitCode, res);
            int step = Math.max(0, STEPS.indexOf(row.step));
            for (; step < STEPS.size() - 1 && interrupt == null; step++) {
                row.step = STEPS.get(step);
                repo.updateDiscovery(row);
                job = new DiscoveryModel.Job();
                job.stage = row.step;
                if ("salvas".equals(row.step) && r.saved) {
                    res.saved = saved(row.unitCode);
                } else if ("descoberta".equals(row.step)) {
                    DiscoveryModel.Request d = DiscoveryService.copy(r.discovery, DiscoveryModel.Request.class);
                    d.buildId = null;
                    d.build = base;
                    res.discovery = discovery.run(d, job);
                    if (job.stop) {
                        break;
                    }
                } else if ("matchups".equals(row.step) && r.matchups && res.discovery != null && res.discovery.standard != null) {
                    res.matchups = matchups(base, res.discovery.standard);
                }
                row.result = write(res);
                row.seconds += (System.nanoTime() - t0) / 1e9;
                t0 = System.nanoTime();
                repo.updateDiscovery(row);
            }
            if (interrupt == null) {
                row.step = "fim";
                row.status = "feito";
            } else {
                row.status = "cancelar".equals(interrupt) ? "cancelado" : "pausado";
                if ("cancelar".equals(interrupt)) {
                    interrupt = null;
                }
            }
        } catch (RuntimeException e) {
            log.warn("Campanha: {} falhou", row.champion, e);
            row.status = "erro";
            row.error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        row.result = write(res);
        row.seconds += (System.nanoTime() - t0) / 1e9;
        repo.updateDiscovery(row);
        current = null;
    }

    /** The champion's base: its first saved build (runes, spells, gold), else a synthetic one with the champion alone. */
    BuildRepository.Build base(String unit, ChampionResult res) {
        for (BuildRepository.Build b : builds.findBuildSummaries()) {
            if (unit.equals(b.unitCode)) {
                return builds.findBuild(b.id).orElseThrow();
            }
        }
        res.syntheticBase = true;
        BuildRepository.Build b = new BuildRepository.Build();
        b.name = nameOf(unit);
        b.unitCode = unit;
        b.goldPerMin = 700;
        b.xpPerMin = 700;
        b.runePage = new BuildRepository.RunePage();
        return b;
    }

    /** Step 1: every saved build of the champion through the robust score. */
    List<Map<String, Object>> saved(String unit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (BuildRepository.Build s : builds.findBuildSummaries()) {
            if (!unit.equals(s.unitCode) || interrupt != null) {
                continue;
            }
            BuildRepository.Build b = builds.findBuild(s.id).orElse(null);
            if (b == null || b.steps.isEmpty()) {
                continue;
            }
            BuildScore.Robust rob = scorer.robust(b, null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("buildId", b.id);
            m.put("name", b.name);
            m.put("score", rob.total);
            m.put("mean", rob.mean);
            m.put("p20", rob.p20);
            out.add(m);
        }
        return out;
    }

    /**
     * Step 3: the standard build in x1 against the representatives of each class and the usual lane opponents (the
     * champions sharing a lane with saved builds), with the automatic brain, at 10, 15 and 20 minutes: the share of
     * health left, mine minus theirs.
     */
    List<Map<String, Object>> matchups(BuildRepository.Build base, DiscoveryModel.KitView standard) {
        BuildRepository.Build mine = DiscoveryService.copy(base, BuildRepository.Build.class);
        mine.id = null;
        mine.steps = new ArrayList<>();
        for (long id : standard.order) {
            mine.steps.add(new BuildRepository.Step(id, true));
        }
        if (standard.runePage != null) {
            mine.runePage = standard.runePage;
        }
        String me = scorer.championOf(mine);
        List<BuildRepository.Build> rivals = new ArrayList<>(scorer.representatives(me));
        ChampionProfiles.Profile p = profiles.of(me);
        if (p != null) {
            for (BuildRepository.Build s : builds.findBuildSummaries()) {
                String other = scorer.championOf(s);
                ChampionProfiles.Profile q = profiles.of(other);
                boolean lane = q != null && q.lanes.stream().anyMatch(p.lanes::contains);
                if (lane && !other.equals(me) && rivals.stream().noneMatch(x -> scorer.championOf(x).equals(other)) && rivals.size() < 10) {
                    builds.findBuild(s.id).ifPresent(rivals::add);
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (BuildRepository.Build rival : rivals) {
            if (interrupt != null) {
                break;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rival", scorer.championOf(rival));
            m.put("build", rival.name);
            Map<String, Double> byMinute = new LinkedHashMap<>();
            for (double minute : new double[] {10, 15, 20}) {
                byMinute.put(String.valueOf(minute), duel(mine, rival, minute));
            }
            m.put("minutes", byMinute);
            m.put("mean", byMinute.values().stream().mapToDouble(Double::doubleValue).average().orElse(0));
            out.add(m);
        }
        return out;
    }

    double duel(BuildRepository.Build a, BuildRepository.Build b, double minute) {
        ArenaModel.Slot sa = new ArenaModel.Slot();
        sa.team = 0;
        sa.build = a;
        sa.minute = minute;
        sa.brain = com.ornnplanner.arena.AutoBrain.AUTO;
        ArenaModel.Slot sb = new ArenaModel.Slot();
        sb.team = 1;
        sb.build = b;
        sb.minute = minute;
        sb.brain = com.ornnplanner.arena.AutoBrain.AUTO;
        ArenaModel.Scenario sc = new ArenaModel.Scenario();
        sc.fighters = List.of(sa, sb);
        List<Arena.Input> in = arena.prepareSlots(sc);
        Arena.Input x = in.get(0).copy();
        Arena.Input y = in.get(1).copy();
        x.x = 4600;
        x.y = 0;
        y.x = 5400;
        y.y = 0;
        ArenaModel.Result res = arena.runPlain(List.of(x, y), 45);
        ArenaModel.FighterStats us = res.fighters.get(0);
        ArenaModel.FighterStats them = res.fighters.get(1);
        return 100 * (Math.max(0, us.health) / Math.max(1, us.maxHealth) - Math.max(0, them.health) / Math.max(1, them.maxHealth));
    }

    // ------------------------------------------------------------------ the quick analyses

    /** The newest finished result of a champion for this patch (null = none): what the quick analyses read. */
    public ChampionResult latest(String unit) {
        DiscoveryRow r = repo.latestDone(unit, patch());
        return r == null || r.result == null ? null : read(r.result, ChampionResult.class);
    }

    static String hash(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static <T> T read(String s, Class<T> t) {
        try {
            return JSON.readValue(s, t);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
