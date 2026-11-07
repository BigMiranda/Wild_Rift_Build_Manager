package com.ornnplanner.api;

import com.ornnplanner.arena.calc.CalculatorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The build calculator: an estimate before it starts, the job, its progress and a stop. */
@RestController
@org.springframework.context.annotation.Profile("!" + com.ornnplanner.publico.PublicMode.PROFILE)
@RequestMapping("/api/calc")
public class CalculatorController {

    private final CalculatorService calc;

    public CalculatorController(CalculatorService calc) {
        this.calc = calc;
    }

    @PostMapping("/estimate")
    public CalculatorService.Estimate estimate(@RequestBody CalculatorService.Request req) {
        return calc.estimate(req);
    }

    @PostMapping("/start")
    public CalculatorService.Job start(@RequestBody CalculatorService.Request req) {
        return calc.start(req);
    }

    @GetMapping("/{id}")
    public CalculatorService.Job job(@PathVariable long id) {
        return calc.job(id);
    }

    @PostMapping("/{id}/stop")
    public void stop(@PathVariable long id) {
        calc.stop(id);
    }
}
