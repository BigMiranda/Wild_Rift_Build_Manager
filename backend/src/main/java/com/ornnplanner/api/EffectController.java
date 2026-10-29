package com.ornnplanner.api;

import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.EffectModel;
import com.ornnplanner.arena.EffectTestService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Effectiveness by test scenarios: the presets and the user's scenarios, the reports' settings and the runs. */
@RestController
@RequestMapping("/api/effect")
public class EffectController {

    private final EffectTestService effect;

    public EffectController(EffectTestService effect) {
        this.effect = effect;
    }

    /** Roles, presets, the sweeps' reference attacker and dimensions, the gold mixes and the limits. */
    @GetMapping("/meta")
    public Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("roles", effect.roles());
        m.put("presets", effect.presets().values());
        m.put("sweepAttacker", effect.sweepAttacker());
        m.put("sweeps", effect.defaultSweeps());
        m.put("mixes", EffectTestService.MIXES);
        m.put("maxAttackers", EffectTestService.MAX_ATTACKERS);
        m.put("maxAllies", EffectTestService.MAX_ALLIES);
        return m;
    }

    @GetMapping("/tests")
    public List<EffectModel.Test> tests() {
        return effect.userTests();
    }

    @PostMapping("/tests")
    public EffectModel.Test create(@RequestBody EffectModel.Test t) {
        t.id = null;
        return effect.saveTest(t);
    }

    @PutMapping("/tests/{id}")
    public EffectModel.Test update(@PathVariable long id, @RequestBody EffectModel.Test t) {
        t.id = id;
        return effect.saveTest(t);
    }

    @DeleteMapping("/tests/{id}")
    public void delete(@PathVariable long id) {
        effect.deleteTest(id);
    }

    @GetMapping("/reports/{kind}")
    public EffectModel.Report report(@PathVariable String kind) {
        return effect.report(kind);
    }

    @PutMapping("/reports/{kind}")
    public EffectModel.Report saveReport(@PathVariable String kind, @RequestBody EffectModel.Report r) {
        return effect.saveReport(kind, r);
    }

    /** "Restaurar o padrão". */
    @DeleteMapping("/reports/{kind}")
    public EffectModel.Report restore(@PathVariable String kind) {
        return effect.restoreReport(kind);
    }

    /** The build's items at the minute (per-item readings, candidate slots). */
    @PostMapping("/items")
    public List<EffectModel.HeldItem> items(@RequestBody EffectModel.Request req) {
        return effect.itemsAt(req);
    }

    /** A batch of runs (the screen sends a few at a time for its progress bar and "Parar"). */
    @PostMapping("/evaluate")
    public Map<String, Object> evaluate(@RequestBody EffectModel.Request req) {
        long t0 = System.nanoTime();
        List<EffectModel.Outcome> out = effect.evaluate(req);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("outcomes", out);
        m.put("ms", (System.nanoTime() - t0) / 1e6);
        return m;
    }

    /** The arena scenario of the first job, to open the fight in the Arena. */
    @PostMapping("/scenario")
    public ArenaModel.Scenario scenario(@RequestBody EffectModel.Request req) {
        return effect.scenario(req, req.jobs.isEmpty() ? new EffectModel.Job() : req.jobs.get(0));
    }
}
