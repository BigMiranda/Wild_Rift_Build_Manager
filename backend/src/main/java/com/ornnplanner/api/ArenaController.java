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
    private final com.ornnplanner.arena.LabService lab;

    public ArenaController(ArenaService arena, ArenaTrainingService training, PolicyRepository policies,
                           com.ornnplanner.arena.LabService lab) {
        this.arena = arena;
        this.training = training;
        this.policies = policies;
        this.lab = lab;
    }

    // ------------------------------------------------------------------ the lab (ragdoll, bench, combo search)

    /** A combo on the bench against the ragdoll: numbers on each behavior, and one fight to watch. */
    @PostMapping("/lab/run")
    public com.ornnplanner.arena.LabService.RunResult labRun(@RequestBody com.ornnplanner.arena.LabService.Request req) {
        return lab.run(req);
    }

    /** The combo with its waits calibrated. */
    @PostMapping("/lab/calibrate")
    public com.ornnplanner.arena.LabService.RunResult labCalibrate(@RequestBody com.ornnplanner.arena.LabService.Request req) {
        return lab.calibrate(req);
    }

    /** The combo with each keystone, and the search again with it (synchronous, small budget). */
    @PostMapping("/lab/compare-runes")
    public List<com.ornnplanner.arena.LabService.RuneRow> labRunes(@RequestBody com.ornnplanner.arena.LabService.Request req) {
        return lab.compareRunes(req);
    }

    /** Starts a combo search in the background (its combos become the champion's library for the build and minute). */
    @PostMapping("/lab/search")
    public com.ornnplanner.arena.LabService.SearchJob labSearch(@RequestBody com.ornnplanner.arena.LabService.Request req) {
        return lab.startSearch(req);
    }

    @GetMapping("/lab/search/{id}")
    public com.ornnplanner.arena.LabService.SearchJob labSearchJob(@PathVariable String id) {
        return lab.job(id);
    }

    @DeleteMapping("/lab/search/{id}")
    public com.ornnplanner.arena.LabService.SearchJob cancelLabSearch(@PathVariable String id) {
        return lab.cancel(id);
    }

    /** Presets and the user's saved ragdolls. */
    @GetMapping("/dummies")
    public Map<String, Object> dummies() {
        return Map.of("presets", ArenaModel.Dummy.presets(), "saved", lab.dummies());
    }

    @PostMapping("/dummies")
    public ArenaModel.Dummy saveDummy(@RequestBody ArenaModel.Dummy d) {
        return lab.saveDummy(d);
    }

    @DeleteMapping("/dummies/{id}")
    public void deleteDummy(@PathVariable long id) {
        lab.deleteDummy(id);
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

    /** The same training at each help level, and a table of their numbers on validation. */
    @PostMapping("/train/levels")
    public ArenaTrainingService.LevelsJob trainLevels(@RequestBody ArenaTrainingService.TrainRequest request) {
        return training.startLevels(request);
    }

    @GetMapping("/train/levels/{id}")
    public ArenaTrainingService.LevelsJob levels(@PathVariable String id) {
        return training.levels(id);
    }

    @DeleteMapping("/train/levels/{id}")
    public ArenaTrainingService.LevelsJob cancelLevels(@PathVariable String id) {
        return training.cancelLevels(id);
    }

    /** A champion's guidelines and event reward weights (empty when none). */
    @GetMapping("/guidelines/{champion}")
    public com.ornnplanner.arena.GuidelineRepository.Set guidelines(@PathVariable String champion) {
        return training.guidelines().find(champion);
    }

    @PutMapping("/guidelines/{champion}")
    public com.ornnplanner.arena.GuidelineRepository.Set saveGuidelines(@PathVariable String champion,
                                                                       @RequestBody com.ornnplanner.arena.GuidelineRepository.Set set) {
        set.champion = champion;
        for (ArenaModel.Guideline g : set.guidelines) {
            if (!List.of("preferir", "evitar", "obrigar", "proibir").contains(g.effect)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Efeito desconhecido: " + g.effect + ".");
            }
            for (ArenaModel.Condition c : g.when) {
                if (!com.ornnplanner.arena.ai.Facts.known(c.fact)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fato desconhecido: " + c.fact + ".");
                }
            }
        }
        return training.guidelines().save(set);
    }

    /** The generic preset: do not overwrite a mark (one guideline per ability). */
    @GetMapping("/guidelines-preset")
    public List<ArenaModel.Guideline> guidelinePreset() {
        return com.ornnplanner.arena.ai.GuidedPolicy.dontOverwriteMarks();
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

    // ------------------------------------------------------------------ combo library

    /** A champion's combos (all when none is given), best score first. */
    @GetMapping("/combos")
    public List<ArenaModel.Combo> combos(@RequestParam(required = false) String champion) {
        return arena.combos().list(champion);
    }

    @PostMapping("/combos")
    public ArenaModel.Combo createCombo(@RequestBody ArenaModel.Combo combo) {
        combo.id = null;
        return arena.combos().save(checked(combo));
    }

    @PutMapping("/combos/{id}")
    public ArenaModel.Combo updateCombo(@PathVariable long id, @RequestBody ArenaModel.Combo combo) {
        arena.combos().find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Combo " + id + " não existe."));
        combo.id = id;
        return arena.combos().save(checked(combo));
    }

    @PutMapping("/combos/{id}/active")
    public void comboActive(@PathVariable long id, @RequestBody Map<String, Boolean> body) {
        if (!arena.combos().setActive(id, Boolean.TRUE.equals(body.get("active")))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Combo " + id + " não existe.");
        }
    }

    @DeleteMapping("/combos/{id}")
    public void deleteCombo(@PathVariable long id) {
        if (!arena.combos().delete(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Combo " + id + " não existe.");
        }
    }

    /** The facts conditions can use (combos, guidelines). */
    @GetMapping("/facts")
    public List<String> facts() {
        return com.ornnplanner.arena.ai.Facts.names();
    }

    private static ArenaModel.Combo checked(ArenaModel.Combo c) {
        if (c.champion == null || c.champion.isBlank() || c.name == null || c.name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Campeão e nome do combo são obrigatórios.");
        }
        if (c.steps == null || c.steps.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O combo precisa de ao menos um passo.");
        }
        for (ArenaModel.Condition k : concat(c.start, c.abort)) {
            if (!com.ornnplanner.arena.ai.Facts.known(k.fact)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fato desconhecido: " + k.fact + ".");
            }
        }
        if (!"manual".equals(c.origin) && !"descoberto".equals(c.origin)) {
            c.origin = "manual";
        }
        c.maxTime = Math.max(0.5, Math.min(20, c.maxTime));
        return c;
    }

    private static List<ArenaModel.Condition> concat(List<ArenaModel.Condition> a, List<ArenaModel.Condition> b) {
        List<ArenaModel.Condition> out = new java.util.ArrayList<>();
        if (a != null) {
            out.addAll(a);
        }
        if (b != null) {
            out.addAll(b);
        }
        return out;
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
