package com.ornnplanner.api;

import com.ornnplanner.arena.MacroPolicyRepository;
import com.ornnplanner.arena.MacroTrainingService;
import com.ornnplanner.arena.MatchService;
import com.ornnplanner.arena.rift.MatchModel;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Whole matches on the full map. */
@RestController
@org.springframework.context.annotation.Profile("!" + com.ornnplanner.publico.PublicMode.PROFILE)
@RequestMapping("/api/match")
public class MatchController {

    private final MatchService matches;
    private final MacroTrainingService training;

    public MatchController(MatchService matches, MacroTrainingService training) {
        this.matches = matches;
        this.training = training;
    }

    @GetMapping("/meta")
    public Map<String, Object> meta() {
        return matches.meta();
    }

    @PostMapping("/run")
    public MatchModel.Result run(@RequestBody MatchModel.Request req) {
        return matches.run(req);
    }

    /** The trained macros, newest first (proposta-macro-treinada.md). */
    @GetMapping("/macros")
    public List<MacroPolicyRepository.Record> macros() {
        return training.repository().list();
    }

    @DeleteMapping("/macros/{id}")
    public Map<String, Object> deleteMacro(@PathVariable long id) {
        return Map.of("deleted", training.repository().delete(id));
    }

    @PostMapping("/macros/train")
    public MacroTrainingService.Job train(@RequestBody MacroTrainingService.TrainRequest req) {
        return training.start(req);
    }

    @GetMapping("/macros/train")
    public List<MacroTrainingService.Job> trainings() {
        return training.jobs();
    }

    @GetMapping("/macros/train/{id}")
    public MacroTrainingService.Job trainJob(@PathVariable String id) {
        return training.job(id);
    }

    @PostMapping("/macros/train/{id}/cancel")
    public MacroTrainingService.Job cancelTraining(@PathVariable String id) {
        return training.cancel(id);
    }
}
