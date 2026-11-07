package com.ornnplanner.api;

import com.ornnplanner.arena.OffenseModel;
import com.ornnplanner.arena.OffenseTestService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Offensive effectiveness (Rodada 9, part 1b): the target ragdolls, the report's settings and the runs. */
@RestController
@org.springframework.context.annotation.Profile("!" + com.ornnplanner.publico.PublicMode.PROFILE)
@RequestMapping("/api/offense")
public class OffenseController {

    private final OffenseTestService offense;

    public OffenseController(OffenseTestService offense) {
        this.offense = offense;
    }

    @GetMapping("/meta")
    public OffenseModel.Meta meta() {
        return offense.meta();
    }

    @PostMapping("/targets")
    public OffenseModel.Target create(@RequestBody OffenseModel.Target t) {
        t.id = null;
        return offense.saveTarget(t);
    }

    @PutMapping("/targets/{id}")
    public OffenseModel.Target update(@PathVariable long id, @RequestBody OffenseModel.Target t) {
        t.id = id;
        return offense.saveTarget(t);
    }

    @DeleteMapping("/targets/{id}")
    public void delete(@PathVariable long id) {
        offense.deleteTarget(id);
    }

    @GetMapping("/report")
    public OffenseModel.Report report() {
        return offense.report();
    }

    @PutMapping("/report")
    public OffenseModel.Report saveReport(@RequestBody OffenseModel.Report r) {
        return offense.saveReport(r);
    }

    @DeleteMapping("/report")
    public OffenseModel.Report restoreReport() {
        return offense.restoreReport();
    }

    @PostMapping("/evaluate")
    public List<OffenseModel.Outcome> evaluate(@RequestBody OffenseModel.Request req) {
        return offense.evaluate(req);
    }
}
