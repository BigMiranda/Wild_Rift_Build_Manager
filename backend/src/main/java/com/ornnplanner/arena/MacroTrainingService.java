package com.ornnplanner.arena;

import com.ornnplanner.arena.ai.Network;
import com.ornnplanner.arena.ai.Trainer;
import com.ornnplanner.arena.rift.MacroActions;
import com.ornnplanner.arena.rift.MacroObservation;
import com.ornnplanner.arena.rift.MacroPolicy;
import com.ornnplanner.arena.rift.MatchModel;
import com.ornnplanner.arena.rift.NeuralMacro;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Treinar a macro" (proposta-macro-treinada.md, Parte 4): one training at a time in the background, its progress by
 * generation, then the validation against the script and the new version saved in {@code macro_policy}, accepted when
 * its winrate reaches {@code treino_macro.aceite}.
 */
@Service
public class MacroTrainingService {

    /** What a training is asked. */
    public static class TrainRequest {
        /** Each draft: up to 5 build ids for one side, played the same on both sides. */
        public List<List<Long>> drafts = new ArrayList<>();
        public int generations = 30;
        public int population = 24;
        public int episodes = 4;
        public int validationSeeds = 20;
        public long seed = 1;
        public double limit = 20;
        /** Start from this version's weights (null: zero weights, the script). */
        public Long parentId;
        /** Also play against the earlier accepted versions, not only the script. */
        public boolean againstPrevious;
    }

    /** A training running or done. */
    public static class Job {
        public String id;
        /** rodando | validando | pronto | cancelado | erro */
        public volatile String status = "rodando";
        public volatile String message;
        public int generations;
        /** {generation, mean fitness, best fitness, seconds so far}. */
        public final List<double[]> progress = new CopyOnWriteArrayList<>();
        public volatile MacroPolicyRepository.Record result;
        public long startedAt = System.currentTimeMillis();
        @com.fasterxml.jackson.annotation.JsonIgnore
        volatile boolean cancel;
    }

    private final MatchService matches;
    private final MacroPolicyRepository repo;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "macro-training");
        t.setDaemon(true);
        return t;
    });

    public MacroTrainingService(MatchService matches, MacroPolicyRepository repo) {
        this.matches = matches;
        this.repo = repo;
    }

    public MacroPolicyRepository repository() {
        return repo;
    }

    public Job start(TrainRequest req) {
        if (jobs.values().stream().anyMatch(j -> "rodando".equals(j.status) || "validando".equals(j.status))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já há um treino da macro rodando.");
        }
        if (req.drafts == null || req.drafts.isEmpty() || req.drafts.stream().anyMatch(d -> d == null || d.isEmpty() || d.size() > 5)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha de 1 a 5 builds para cada composição de treino.");
        }
        List<MatchService.Draft> drafts = new ArrayList<>();
        for (List<Long> ids : req.drafts) {
            MatchModel.Request r = new MatchModel.Request();
            for (int side = 0; side < 2; side++) {
                for (Long id : ids) {
                    MatchModel.Slot s = new MatchModel.Slot();
                    s.buildId = id;
                    s.team = side;
                    r.slots.add(s);
                }
            }
            drafts.add(matches.draft(r));
        }
        MacroPolicy script = matches.scripted();
        Network start = MacroTraining.zeros();
        if (req.parentId != null) {
            matches.macroOf("macro:" + req.parentId, script);   // refuses another schema
            start = repo.weights(req.parentId).network();
        }
        List<MacroPolicy> opponents = new ArrayList<>();
        if (req.againstPrevious) {
            opponents.add(script);
            for (MacroPolicyRepository.Record r : repo.list()) {
                if (r.accepted && r.obsSchema == MacroObservation.SCHEMA && r.actions == MacroActions.SIZE && opponents.size() < 4) {
                    opponents.add(matches.macroOf("macro:" + r.id, script));
                }
            }
        }
        MacroTraining.Config c = new MacroTraining.Config();
        c.generations = Math.max(1, Math.min(200, req.generations));
        c.population = Math.max(2, Math.min(128, req.population));
        c.episodesPerGeneration = Math.max(1, Math.min(32, req.episodes));
        c.validationSeeds = Math.max(1, Math.min(100, req.validationSeeds));
        c.seed = req.seed;
        c.limitMinutes = Math.max(5, Math.min(40, req.limit));
        MacroTraining training = new MacroTraining(matches, drafts, c, opponents);
        Job job = new Job();
        job.id = UUID.randomUUID().toString();
        job.generations = c.generations;
        job.message = "Treinando: " + drafts.size() + " composição(ões), " + c.population + " candidatos, " + c.episodesPerGeneration
                + " episódios (×2 lados) por geração.";
        jobs.put(job.id, job);
        Network from = start;
        runner.submit(() -> run(job, training, from, req, c));
        return job;
    }

    private void run(Job job, MacroTraining training, Network start, TrainRequest req, MacroTraining.Config c) {
        long t0 = System.nanoTime();
        try {
            Network trained = training.train(start, new MacroTraining.Listener() {
                @Override
                public void generation(Trainer.Generation g) {
                    job.progress.add(new double[] {g.index, g.mean, g.best, (System.nanoTime() - t0) / 1e9});
                }

                @Override
                public boolean cancelled() {
                    return job.cancel;
                }
            });
            if (job.cancel) {
                job.status = "cancelado";
                job.message = "Treino cancelado: nada foi salvo.";
                return;
            }
            job.status = "validando";
            job.message = "Validando contra o roteiro em sementes que o treino não viu.";
            MacroTraining.Validation v = training.validate(trained);
            MacroPolicyRepository.Record r = new MacroPolicyRepository.Record();
            r.parentId = req.parentId;
            r.winrate = v.winrate;
            r.games = v.games;
            r.fitness = v.fitness;
            r.goldPerMin = v.goldPerMin;
            r.opponentGoldPerMin = v.opponentGoldPerMin;
            r.deviations = v.deviations;
            r.accepted = v.winrate >= training.acceptAt();
            r.generations = c.generations;
            r.population = c.population;
            r.episodes = c.episodesPerGeneration;
            r.seed = c.seed;
            r.drafts = req.drafts;
            r.obsSchema = MacroObservation.SCHEMA;
            r.actions = MacroActions.SIZE;
            r.rules = MacroTraining.RULES;
            r.weights = MacroPolicyRepository.Weights.of(trained, NeuralMacro.BASE_BONUS);
            job.result = repo.insert(r);
            job.status = "pronto";
            job.message = String.format("Macro treinada v%d: %.0f%% de vitórias contra o roteiro em %d partidas (%s).", r.version,
                    v.winrate * 100, v.games, r.accepted ? "aceita" : "abaixo de " + Math.round(training.acceptAt() * 100) + "%, não aceita");
        } catch (RuntimeException e) {
            job.status = "erro";
            job.message = "O treino falhou: " + e.getMessage();
        }
    }

    public Job job(String id) {
        Job j = jobs.get(id);
        if (j == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Treino " + id + " não existe.");
        }
        return j;
    }

    public Job cancel(String id) {
        Job j = job(id);
        j.cancel = true;
        return j;
    }

    public List<Job> jobs() {
        List<Job> out = new ArrayList<>(jobs.values());
        out.sort((a, b) -> Long.compare(b.startedAt, a.startedAt));
        return out;
    }
}
