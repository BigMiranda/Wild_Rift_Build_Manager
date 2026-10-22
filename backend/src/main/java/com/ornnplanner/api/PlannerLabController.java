package com.ornnplanner.api;

import com.ornnplanner.arena.PlannerLabService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** The planner's panels: this build tested in a scenario or combo, and seen under chosen effects. */
@RestController
@RequestMapping("/api/lab")
public class PlannerLabController {

    private final PlannerLabService lab;

    public PlannerLabController(PlannerLabService lab) {
        this.lab = lab;
    }

    /** The saved scenarios with this build's champion, and the champion's library combos. */
    @GetMapping("/test-options")
    public Map<String, Object> testOptions(@RequestParam long buildId) {
        return lab.testOptions(buildId);
    }

    /** This build in a saved scenario (in its champion's slot) or a library combo on the ragdoll, compact. */
    @PostMapping("/test")
    public PlannerLabService.TestResult test(@RequestBody PlannerLabService.TestRequest req) {
        return lab.test(req);
    }

    /** Items and champion passives / abilities the "seen under effects" panel can apply, with their values. */
    @GetMapping("/effect-sources")
    public List<com.ornnplanner.arena.EffectSources.Source> effectSources() {
        return lab.effectSources();
    }

    /** A build (the planner's draft, saved or not) at a minute. */
    public static class EffectRequest {
        public com.ornnplanner.repo.BuildRepository.Build build;
        public Double minute;
    }

    /** What each item and rune effect of the build is worth at the minute, by the arena's formulas. */
    @PostMapping("/effect-values")
    public List<Map<String, Object>> effectValues(@RequestBody EffectRequest req) {
        if (req.build == null) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Sem build.");
        }
        return lab.effectValues(req.build, req.minute);
    }

    /** The build under chosen item and champion effects, now and minute by minute. */
    @PostMapping("/seen-by")
    public Map<String, Object> seenBy(@RequestBody PlannerLabService.Request req) {
        return lab.seenBy(req);
    }

    /** Builds side by side on the bench, each with its best combo: what the automatic build search will call. */
    @PostMapping("/compare-builds")
    public List<Map<String, Object>> compareBuilds(@RequestBody PlannerLabService.Request req) {
        return lab.compareBuilds(req);
    }
}
