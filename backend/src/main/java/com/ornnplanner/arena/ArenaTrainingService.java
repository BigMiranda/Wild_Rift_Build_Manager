package com.ornnplanner.arena;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ornnplanner.arena.ArenaModel.Scenario;
import com.ornnplanner.arena.ArenaModel.Slot;
import com.ornnplanner.arena.ai.Plateau;
import com.ornnplanner.arena.ai.PolicyRepository;
import com.ornnplanner.combat.CombatCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Trains learned policies in the background, one job at a time (the others wait in line): a single champion
 * ({@link ArenaTraining#trainStage}) or a chain of them ({@link ArenaChain}).
 */
@Service
public class ArenaTrainingService {

    private static final Logger log = LoggerFactory.getLogger(ArenaTrainingService.class);

    public static class TrainRequest {
        public Scenario scenario;
        /** The champion whose policy is trained (index in the scenario). */
        public int fighter;
        /** win_fast | damage | survive | team */
        public String objective = "win_fast";
        public int generations = 30;
        public int population = 32;
        public int episodesPerGeneration = 8;
        public long seed = 1;
        /** Stop rule: victory (when it wins every selection fight) | plateau. */
        public String stopWhen = "victory";
        /** Plateau: least gain in points over the window, after a minimum of generations (0 = off). */
        public double plateauPct = 2;
        public int plateauWindow = 6;
        public int minGenerations = 8;
    }

    public static class Job {
        public final String kind = "single";
        public String id;
        /** queued | running | done | failed | cancelled */
        public volatile String status = "queued";
        public String champion;
        public String objective;
        public String scenario;
        public int generations;
        public volatile int generation;
        public final List<ArenaTraining.Point> progress = new java.util.concurrent.CopyOnWriteArrayList<>();
        /** Opponents of the training episodes (script, tactical, ai v2, self...). */
        public volatile List<String> opponents = new ArrayList<>();
        public int episodes = ArenaTraining.POOL;
        public volatile String message;
        /** Stopped by the plateau rule before its last generation. */
        public volatile boolean plateau;
        /** Stopped at total victory. */
        public volatile boolean victory;
        /** The saved version (without weights) when done. */
        public volatile PolicyRepository.Record policy;
        public long startedAt;
        public volatile Long finishedAt;
        @JsonIgnore
        volatile boolean cancel;
    }

    private final ArenaService arena;
    private final PolicyRepository policies;
    private final ArenaTraining training;
    private final ArenaChain chains;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Map<String, ArenaChain.Job> chainJobs = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "arena-training");
        t.setDaemon(true);
        return t;
    });

    public ArenaTrainingService(ArenaService arena, PolicyRepository policies, CombatCatalog catalog) {
        this.arena = arena;
        this.policies = policies;
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) arena.kits().get("mapa");
        this.training = new ArenaTraining(catalog, map, policies);
        this.chains = new ArenaChain(training, policies);
    }

    @PreDestroy
    void stop() {
        jobs.values().forEach(j -> j.cancel = true);
        chainJobs.values().forEach(j -> j.cancel = true);
        runner.shutdownNow();
    }

    // ------------------------------------------------------------------ one champion

    public Job start(TrainRequest req) {
        Scenario sc = req.scenario;
        if (sc == null || sc.fighters == null || req.fighter < 0 || req.fighter >= sc.fighters.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha o campeão a treinar.");
        }
        req.objective = objective(req.objective);
        List<Arena.Input> inputs = arena.prepare(sc);   // checks the scenario now, not in the background
        Job job = new Job();
        job.id = String.valueOf(ids.incrementAndGet());
        job.champion = inputs.get(req.fighter).f.champion;
        job.objective = req.objective;
        job.scenario = sc.name;
        job.generations = generations(req.generations);
        job.startedAt = System.currentTimeMillis();
        job.message = "na fila";
        jobs.put(job.id, job);
        runner.submit(() -> {
            try {
                run(job, req, inputs);
            } catch (Throwable e) {
                log.warn("Arena training {} failed", job.id, e);
                job.status = "failed";
                job.message = "falhou: " + e.getMessage();
                job.finishedAt = System.currentTimeMillis();
            }
        });
        return job;
    }

    public Job job(String id) {
        Job j = jobs.get(id);
        if (j == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Treino " + id + " não encontrado.");
        }
        return j;
    }

    public Job cancel(String id) {
        Job j = job(id);
        j.cancel = true;
        if ("queued".equals(j.status)) {
            j.status = "cancelled";
            j.message = "cancelado";
        }
        return j;
    }

    /** Single jobs and chains, oldest first. */
    public List<Object> jobs() {
        List<Object> out = new ArrayList<>();
        out.addAll(jobs.values());
        out.addAll(chainJobs.values());
        out.sort(Comparator.comparingLong(o -> o instanceof Job ? ((Job) o).startedAt : ((ArenaChain.Job) o).startedAt));
        return out;
    }

    private void run(Job job, TrainRequest req, List<Arena.Input> base) {
        if (job.cancel) {
            return;
        }
        job.status = "running";
        Scenario sc = req.scenario;
        ArenaTraining.StageSpec st = new ArenaTraining.StageSpec();
        st.variants = variants(sc, base);
        st.actions = sc.actions == null ? List.of() : sc.actions;
        st.duration = ArenaService.duration(sc);
        st.me = req.fighter;
        st.objective = req.objective;
        st.scenario = sc.name;
        st.generations = job.generations;
        st.population = Math.max(4, Math.min(128, req.population));
        st.episodesPerGeneration = Math.max(1, Math.min(ArenaTraining.POOL, req.episodesPerGeneration));
        st.seed = req.seed;
        st.previous = policies.best(job.champion, req.objective).orElse(null);
        st.teamPlan = plan(sc, base.get(req.fighter).f.team);
        st.stopAtVictory = victory(req.stopWhen);
        st.plateau = plateau(req.plateauPct, req.plateauWindow, req.minGenerations);
        ArenaTraining.StageResult r = training.trainStage(st, new ArenaTraining.Listener() {
            @Override
            public void message(String text) {
                job.message = text;
            }

            @Override
            public void opponents(List<String> labels) {
                job.opponents = new ArrayList<>(labels);
            }

            @Override
            public void point(ArenaTraining.Point p) {
                job.progress.add(p);
                job.generation = p.generation;
            }

            @Override
            public boolean cancelled() {
                return job.cancel;
            }
        });
        if (r.cancelled) {
            job.status = "cancelled";
            job.message = "cancelado na geração " + job.generation;
            job.finishedAt = System.currentTimeMillis();
            return;
        }
        job.plateau = r.plateau;
        job.victory = r.victory;
        job.policy = r.policy.withoutWeights();
        job.status = "done";
        job.message = (r.victory ? "vitória total na geração " + r.generations + "; "
                : r.plateau ? "platô na geração " + r.generations + "; " : "")
                + (r.policy.accepted ? "versão aceita" : "versão salva, mas não aceita (não superou o roteiro e a anterior em 55%)");
        job.finishedAt = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ chains

    public ArenaChain.Job startChain(ArenaChain.Request req) {
        Scenario sc = req.scenario;
        if (sc == null || sc.fighters == null || sc.fighters.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O cenário não tem campeões.");
        }
        List<Arena.Input> base = arena.prepare(sc);
        List<Integer> slots = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (Integer i : req.fighters == null || req.fighters.isEmpty() ? allSlots(sc) : req.fighters) {
            if (i == null || i < 0 || i >= sc.fighters.size()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Campeão " + i + " não está no cenário.");
            }
            if (seen.add(i)) {
                slots.add(i);
            }
        }
        ArenaChain.Spec spec = new ArenaChain.Spec();
        spec.actions = sc.actions == null ? List.of() : sc.actions;
        spec.duration = ArenaService.duration(sc);
        spec.scenario = sc.name;
        spec.teams = sc.teams;
        spec.slots = slots;
        spec.objective = objective(req.objective);
        spec.objectives = new java.util.HashMap<>();
        if (req.objectives != null) {
            req.objectives.forEach((slot, o) -> spec.objectives.put(slot, objective(o)));
        }
        spec.generations = generations(req.generations);
        spec.population = Math.max(4, Math.min(128, req.population));
        spec.episodesPerGeneration = Math.max(1, Math.min(ArenaTraining.POOL, req.episodesPerGeneration));
        spec.seed = req.seed;
        spec.maxRounds = Math.max(1, Math.min(20, req.maxRounds));
        spec.stopAtVictory = victory(req.stopWhen);
        spec.plateau = plateau(req.plateauPct, req.plateauWindow, req.minGenerations);
        ArenaChain.Job job = new ArenaChain.Job();
        job.id = "c" + ids.incrementAndGet();
        job.scenario = sc.name;
        job.maxRounds = spec.maxRounds;
        job.startedAt = System.currentTimeMillis();
        job.message = "na fila";
        chainJobs.put(job.id, job);
        runner.submit(() -> {
            try {
                spec.variants = variants(sc, base);
                chains.run(job, spec);
            } catch (Throwable e) {
                log.warn("Arena chain training {} failed", job.id, e);
                job.status = "failed";
                job.message = "falhou: " + e.getMessage();
                job.finishedAt = System.currentTimeMillis();
            }
        });
        return job;
    }

    public ArenaChain.Job chain(String id) {
        ArenaChain.Job j = chainJobs.get(id);
        if (j == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cadeia " + id + " não encontrada.");
        }
        return j;
    }

    /** Lets the stage under way finish (validated and saved), then stops the chain. */
    public ArenaChain.Job stopAfterCurrent(String id) {
        ArenaChain.Job j = chain(id);
        j.stopAfterCurrent = true;
        if ("running".equals(j.status)) {
            j.status = "stopping";
        }
        return j;
    }

    /** Stops now: the stage under way is thrown away. */
    public ArenaChain.Job cancelChain(String id) {
        ArenaChain.Job j = chain(id);
        j.cancel = true;
        if ("queued".equals(j.status)) {
            j.status = "cancelled";
            j.message = "cancelado";
            j.finishedAt = System.currentTimeMillis();
        }
        return j;
    }

    // ------------------------------------------------------------------ shared

    private static List<Integer> allSlots(Scenario sc) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < sc.fighters.size(); i++) {
            out.add(i);
        }
        return out;
    }

    private static String objective(String o) {
        String out = o == null ? "win_fast" : o;
        if (!ArenaScore.OBJECTIVES.contains(out)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Objetivo desconhecido: " + out + ".");
        }
        return out;
    }

    private static int generations(int n) {
        return Math.max(1, Math.min(200, n));
    }

    private static boolean victory(String stopWhen) {
        if (stopWhen != null && !"victory".equals(stopWhen) && !"plateau".equals(stopWhen)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Critério de parada desconhecido: " + stopWhen + ".");
        }
        return !"plateau".equals(stopWhen);
    }

    private static Plateau plateau(double pct, int window, int min) {
        return pct <= 0 ? Plateau.off()
                : new Plateau(Math.min(100, pct), Math.max(1, Math.min(100, window)), Math.max(1, Math.min(200, min)));
    }

    private static ArenaModel.TeamPlan plan(Scenario sc, int team) {
        return sc.teams != null && team < sc.teams.length ? sc.teams[team] : null;
    }

    /** The builds at the chosen minute, then 3 minutes before and after (when the scenario has chosen minutes). */
    private List<List<Arena.Input>> variants(Scenario sc, List<Arena.Input> base) {
        List<List<Arena.Input>> out = new ArrayList<>();
        out.add(base);
        for (int d : new int[] {-3, 3}) {
            Scenario shifted = shiftMinutes(sc, d);
            if (shifted != null) {
                try {
                    out.add(arena.prepare(shifted));
                } catch (RuntimeException e) {
                    log.debug("minute variant {} skipped: {}", d, e.getMessage());
                }
            }
        }
        return out;
    }

    /** The scenario with every build's minute moved by d (null when no build has a chosen minute). */
    private static Scenario shiftMinutes(Scenario sc, int d) {
        ObjectMapper json = new ObjectMapper();
        Scenario c = json.convertValue(sc, Scenario.class);
        boolean any = false;
        for (Slot s : c.fighters) {
            if (s.minute != null) {
                s.minute = Math.max(1, s.minute + d);
                any = true;
            }
        }
        return any ? c : null;
    }
}
