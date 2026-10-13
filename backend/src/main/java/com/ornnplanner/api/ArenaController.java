package com.ornnplanner.api;

import com.ornnplanner.arena.ArenaChain;
import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.ArenaService;
import com.ornnplanner.arena.ArenaTrainingService;
import com.ornnplanner.arena.ai.PolicyRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** Orchestrated matchups: run a scenario on the arena, read the ability sheets, store scenarios. */
@RestController
@RequestMapping("/api/arena")
public class ArenaController {

    private final ArenaService arena;
    private final ArenaTrainingService training;
    private final PolicyRepository policies;

    public ArenaController(ArenaService arena, ArenaTrainingService training, PolicyRepository policies) {
        this.arena = arena;
        this.training = training;
        this.policies = policies;
    }

    /** Starts training a learned policy in the background; poll the job for its progress. */
    @PostMapping("/train")
    public ArenaTrainingService.Job train(@RequestBody ArenaTrainingService.TrainRequest request) {
        return training.start(request);
    }

    /** Single trainings and chains (each with its kind: single | chain). */
    @GetMapping("/train")
    public List<Object> trainings() {
        return training.jobs();
    }

    /** Starts a chain training: the scenario's champions one after the other, round after round. */
    @PostMapping("/train/chain")
    public ArenaChain.Job trainChain(@RequestBody ArenaChain.Request request) {
        return training.startChain(request);
    }

    @GetMapping("/train/chain/{id}")
    public ArenaChain.Job chain(@PathVariable String id) {
        return training.chain(id);
    }

    /** The stage under way finishes (validated and saved), then the chain stops. */
    @PostMapping("/train/chain/{id}/stop-after-current")
    public ArenaChain.Job stopChainAfterCurrent(@PathVariable String id) {
        return training.stopAfterCurrent(id);
    }

    /** Cancels now: the stage under way is not saved. */
    @DeleteMapping("/train/chain/{id}")
    public ArenaChain.Job cancelChain(@PathVariable String id) {
        return training.cancelChain(id);
    }

    @GetMapping("/train/{job}")
    public ArenaTrainingService.Job training(@PathVariable String job) {
        return training.job(job);
    }

    @DeleteMapping("/train/{job}")
    public ArenaTrainingService.Job cancelTraining(@PathVariable String job) {
        return training.cancel(job);
    }

    /** Saved learned policies (without weights), a champion's only when given. */
    @GetMapping("/policies")
    public List<PolicyRepository.Record> policies(@RequestParam(required = false) String champion) {
        return policies.list(champion);
    }

    @DeleteMapping("/policies/{id}")
    public void deletePolicy(@PathVariable long id) {
        if (!policies.delete(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "IA " + id + " não encontrada.");
        }
    }

    @GetMapping("/kits")
    public Map<String, Object> kits() {
        return arena.kits();
    }

    @PostMapping("/run")
    public ArenaModel.Result run(@RequestBody ArenaModel.Scenario scenario) {
        return arena.run(scenario);
    }

    @GetMapping("/scenarios")
    public List<Map<String, Object>> scenarios() {
        return arena.list();
    }

    @GetMapping("/scenarios/{id}")
    public ArenaModel.Scenario scenario(@PathVariable long id) {
        return arena.find(id);
    }

    @PostMapping("/scenarios")
    public ArenaModel.Scenario create(@RequestBody ArenaModel.Scenario scenario) {
        scenario.id = null;
        return arena.save(scenario);
    }

    @PutMapping("/scenarios/{id}")
    public ArenaModel.Scenario update(@PathVariable long id, @RequestBody ArenaModel.Scenario scenario) {
        scenario.id = id;
        return arena.save(scenario);
    }

    @DeleteMapping("/scenarios/{id}")
    public void delete(@PathVariable long id) {
        arena.delete(id);
    }
}
