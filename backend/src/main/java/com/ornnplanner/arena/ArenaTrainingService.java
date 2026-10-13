package com.ornnplanner.arena;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ornnplanner.arena.ArenaModel.Scenario;
import com.ornnplanner.arena.ArenaModel.Slot;
import com.ornnplanner.arena.ai.ActionMask;
import com.ornnplanner.arena.ai.ActionSpace;
import com.ornnplanner.arena.ai.Network;
import com.ornnplanner.arena.ai.NeuralPolicy;
import com.ornnplanner.arena.ai.Observation;
import com.ornnplanner.arena.ai.Policy;
import com.ornnplanner.arena.ai.PolicyRepository;
import com.ornnplanner.arena.ai.ScriptedPolicy;
import com.ornnplanner.arena.ai.TacticalPolicy;
import com.ornnplanner.arena.ai.Trainer;
import com.ornnplanner.combat.CombatCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Trains learned policies in the background (one training at a time; the others wait in line).
 *
 * <p>From a scenario and one of its champions: episodes vary the starts (positions moved along and across the lane),
 * the minute of every build (the chosen one, 3 minutes before and after) and the enemies' brain (scripted, tactical,
 * the best saved policies of their champions — the hall of fame — and, in a mirror match, the policy being trained as
 * it was a few generations before: self-play). The trainer ({@link Trainer}) learns on a pool of these episodes; a
 * separate selection set picks the best generation, and a validation set never used before gives the numbers that
 * are saved: how often it does better than the scripted and the tactical autopilots and than the previous accepted
 * version. A version is accepted when it does better than the script and the previous one in at least 55% of them.
 */
@Service
public class ArenaTrainingService {

    private static final Logger log = LoggerFactory.getLogger(ArenaTrainingService.class);
    static final double ACCEPT = 0.55;
    private static final int POOL = 24;
    private static final int SELECTION = 8;
    private static final int VALIDATION = 16;
    private static final int SELF_PLAY_EVERY = 5;

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
    }

    public static class Point {
        public int generation;
        /** Mean and best fitness of the candidates on the generation's episodes. */
        public double mean;
        public double best;
        /** Share of selection fights where the generation's policy did better than the script (when measured). */
        public Double selection;
    }

    public static class Job {
        public String id;
        /** queued | running | done | failed | cancelled */
        public volatile String status = "queued";
        public String champion;
        public String objective;
        public String scenario;
        public int generations;
        public volatile int generation;
        public final List<Point> progress = new java.util.concurrent.CopyOnWriteArrayList<>();
        /** Opponents of the training episodes (script, tactical, ai v2, self...). */
        public List<String> opponents = new ArrayList<>();
        public int episodes;
        public volatile String message;
        /** The saved version (without weights) when done. */
        public volatile PolicyRepository.Record policy;
        public long startedAt;
        public volatile Long finishedAt;
        @JsonIgnore
        volatile boolean cancel;
    }

    private final ArenaService arena;
    private final PolicyRepository policies;
    private final CombatCatalog catalog;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "arena-training");
        t.setDaemon(true);
        return t;
    });

    public ArenaTrainingService(ArenaService arena, PolicyRepository policies, CombatCatalog catalog) {
        this.arena = arena;
        this.policies = policies;
        this.catalog = catalog;
    }

    @PreDestroy
    void stop() {
        jobs.values().forEach(j -> j.cancel = true);
        runner.shutdownNow();
    }

    public Job start(TrainRequest req) {
        Scenario sc = req.scenario;
        if (sc == null || sc.fighters == null || req.fighter < 0 || req.fighter >= sc.fighters.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha o campeão a treinar.");
        }
        if (req.objective == null) {
            req.objective = "win_fast";
        }
        if (!ArenaScore.OBJECTIVES.contains(req.objective)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Objetivo desconhecido: " + req.objective + ".");
        }
        List<Arena.Input> inputs = arena.prepare(sc);   // checks the scenario now, not in the background
        Job job = new Job();
        job.id = String.valueOf(ids.incrementAndGet());
        job.champion = inputs.get(req.fighter).f.champion;
        job.objective = req.objective;
        job.scenario = sc.name;
        job.generations = Math.max(1, Math.min(200, req.generations));
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

    public List<Job> jobs() {
        return new ArrayList<>(jobs.values());
    }

    // ------------------------------------------------------------------ the training

    /** One kind of opponent: its label and, for an enemy input, its policy (null = not available for it). */
    private interface Opponent {
        String label();

        Policy policyFor(Arena.Input enemy);
    }

    private void run(Job job, TrainRequest req, List<Arena.Input> base) {
        if (job.cancel) {
            return;
        }
        job.status = "running";
        job.message = "preparando os episódios";
        Scenario sc = req.scenario;
        int me = req.fighter;
        int team = sc.fighters.get(me).team;
        double duration = ArenaService.duration(sc);
        List<ArenaModel.Action> actions = sc.actions == null ? List.of() : sc.actions;
        Random rnd = new Random(req.seed);

        // the builds at the chosen minute, 3 minutes before and after
        List<List<Arena.Input>> variants = new ArrayList<>();
        variants.add(base);
        for (int d : new int[] {-3, 3}) {
            Scenario shifted = shiftMinutes(sc, d);
            if (shifted != null) {
                try {
                    variants.add(arena.prepare(shifted));
                } catch (RuntimeException e) {
                    log.debug("minute variant {} skipped: {}", d, e.getMessage());
                }
            }
        }

        // opponents: script, tactical, the hall of fame, self-play in a mirror
        AtomicReference<Policy> self = new AtomicReference<>();
        String myChampion = base.get(me).f.champion;
        List<Opponent> opponents = new ArrayList<>();
        opponents.add(opponent("roteiro", in -> new ScriptedPolicy(in.priority)));
        opponents.add(opponent("tático", in -> new TacticalPolicy(in.priority)));
        Map<String, Opponent> hall = new LinkedHashMap<>();
        for (int i = 0; i < base.size(); i++) {
            String champion = base.get(i).f.champion;
            if (base.get(i).f.team != team && !hall.containsKey(champion.toLowerCase())) {
                policies.list(champion).stream().filter(r -> r.accepted && r.compatible()).limit(2).forEach(r -> {
                    PolicyRepository.Record full = policies.find(r.id).orElseThrow();
                    Network net = full.weights.network();
                    double bonus = full.weights.bonus;
                    hall.put(champion.toLowerCase() + r.id, opponent("IA v" + r.version + " " + champion,
                            in -> in.f.champion.equalsIgnoreCase(champion) ? new NeuralPolicy(net, in.priority, bonus) : null));
                });
                if (champion.equalsIgnoreCase(myChampion)) {
                    hall.put("self", opponent("ela mesma (self-play)", in -> in.f.champion.equalsIgnoreCase(myChampion)
                            ? new SelfPlay(self, in.priority) : null));
                }
            }
        }
        opponents.addAll(hall.values());
        opponents.forEach(o -> job.opponents.add(o.label()));

        List<Gym.Episode> pool = episodes(variants, me, team, duration, actions, opponents, POOL, rnd);
        List<Opponent> fixed = opponents.subList(0, 2);   // selection and validation: script and tactical only
        List<Gym.Episode> selection = episodes(variants, me, team, duration, actions, fixed, SELECTION, rnd);
        List<Gym.Episode> validation = episodes(variants, me, team, duration, actions, fixed, VALIDATION,
                new Random(req.seed * 7919 + 17));
        job.episodes = pool.size();

        Gym gym = new Gym(catalog, map());
        List<String> priority = base.get(me).priority;
        String objective = req.objective;
        Trainer.Config cfg = new Trainer.Config();
        cfg.population = Math.max(4, Math.min(128, req.population));
        cfg.generations = job.generations;
        cfg.episodesPerGeneration = Math.max(1, Math.min(POOL, req.episodesPerGeneration));
        cfg.seed = req.seed;
        Network start = Network.zeros(Network.DEFAULT_LAYERS);
        self.set(new NeuralPolicy(start, priority));
        ScriptedPolicy script = new ScriptedPolicy(priority);
        Network[] best = {start};
        double[] bestSelection = {gym.compare(selection, new NeuralPolicy(start, priority), script, objective).better};
        job.message = "treinando";
        new Trainer(cfg).train(start, pool.size(), (net, e) -> {
            Gym.Episode ep = pool.get(e);
            return ArenaScore.fitness(gym.play(ep, new NeuralPolicy(net, priority)), objective, ep.me, team);
        }, new Trainer.Listener() {
            @Override
            public void generation(Trainer.Generation g) {
                Point p = new Point();
                p.generation = g.index;
                p.mean = g.mean;
                p.best = g.best;
                if (g.index % SELF_PLAY_EVERY == 0 || g.index == cfg.generations) {
                    NeuralPolicy now = new NeuralPolicy(g.center, priority);
                    p.selection = gym.compare(selection, now, script, objective).better;
                    if (p.selection >= bestSelection[0]) {
                        bestSelection[0] = p.selection;
                        best[0] = g.center;
                    }
                    self.set(now);
                }
                job.progress.add(p);
                job.generation = g.index;
            }

            @Override
            public boolean cancelled() {
                return job.cancel;
            }
        });
        if (job.cancel) {
            job.status = "cancelled";
            job.message = "cancelado na geração " + job.generation;
            job.finishedAt = System.currentTimeMillis();
            return;
        }

        job.message = "validando";
        NeuralPolicy ai = new NeuralPolicy(best[0], priority);
        Gym.Report vsScript = gym.compare(validation, ai, script, objective);
        Gym.Report vsTactical = gym.compare(validation, ai, new TacticalPolicy(priority), objective);
        PolicyRepository.Record previous = policies.best(myChampion, objective).orElse(null);
        Double vsPrevious = previous == null ? null : gym.compare(validation, ai,
                new NeuralPolicy(previous.weights.network(), priority, previous.weights.bonus), objective).better;

        PolicyRepository.Record r = new PolicyRepository.Record();
        r.champion = myChampion;
        r.objective = objective;
        r.fitness = vsScript.meanFitness;
        r.betterVsScript = vsScript.better;
        r.betterVsTactical = vsTactical.better;
        r.betterVsPrevious = vsPrevious;
        r.winrate = vsScript.winRate;
        r.winrateScript = vsScript.referenceWinRate;
        r.accepted = vsScript.better >= ACCEPT && (vsPrevious == null || vsPrevious >= ACCEPT);
        r.generations = job.generation;
        r.population = cfg.population;
        r.seed = req.seed;
        r.scenario = sc.name;
        r.obsSchema = Observation.SCHEMA_VERSION;
        r.actions = ActionSpace.SIZE;
        r.weights = PolicyRepository.Weights.of(best[0], NeuralPolicy.BASE_BONUS);
        policies.insert(r);
        r.weights = null;
        job.policy = r;
        job.status = "done";
        job.message = r.accepted ? "versão aceita" : "versão salva, mas não aceita (não superou o roteiro e a anterior em 55%)";
        job.finishedAt = System.currentTimeMillis();
    }

    private Map<String, Object> map() {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) arena.kits().get("mapa");
        return m;
    }

    private static Opponent opponent(String label, java.util.function.Function<Arena.Input, Policy> f) {
        return new Opponent() {
            @Override
            public String label() {
                return label;
            }

            @Override
            public Policy policyFor(Arena.Input enemy) {
                return f.apply(enemy);
            }
        };
    }

    /**
     * Episodes: a build variant, an opponent kind for all the enemies (script where that kind has nothing for one of
     * them) and starts moved at random: the whole fight along the lane, the sides closer or farther, each champion
     * across it.
     */
    private static List<Gym.Episode> episodes(List<List<Arena.Input>> variants, int me, int team, double duration,
                                              List<ArenaModel.Action> actions, List<Opponent> opponents, int count,
                                              Random rnd) {
        List<Gym.Episode> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            List<Arena.Input> base = variants.get(i % variants.size());
            Opponent opp = opponents.get(i % opponents.size());
            double shift = rnd.nextInt(3001) - 1500;
            double gap = rnd.nextInt(501) - 200;
            List<Arena.Input> in = new ArrayList<>();
            Policy[] policies = new Policy[base.size()];
            for (int k = 0; k < base.size(); k++) {
                Arena.Input c = base.get(k).copy();
                double side = c.f.team == team ? -1 : 1;
                c.x = Math.max(300, Math.min(9700, c.x + shift + side * gap / 2));
                c.y = Math.max(-450, Math.min(450, c.y + rnd.nextInt(601) - 300));
                in.add(c);
                if (c.f.team != team) {
                    Policy p = opp.policyFor(c);
                    policies[k] = p != null ? p : new ScriptedPolicy(c.priority);
                }
            }
            out.add(new Gym.Episode(in, actions, duration, me, policies, opp.label()));
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

    /** The trained policy as it was at the last self-play snapshot (the opponent of a mirror match). */
    private static final class SelfPlay implements Policy {
        private final AtomicReference<Policy> current;
        private final List<String> priority;

        SelfPlay(AtomicReference<Policy> current, List<String> priority) {
            this.current = current;
            this.priority = priority;
        }

        @Override
        public Decision decide(Observation obs, ActionMask mask) {
            Policy p = current.get();
            if (p instanceof NeuralPolicy) {
                return new NeuralPolicy(((NeuralPolicy) p).network(), priority).decide(obs, mask);
            }
            return new TacticalPolicy(priority).decide(obs, mask);
        }

        @Override
        public double interval() {
            return NeuralPolicy.INTERVAL;
        }
    }
}
