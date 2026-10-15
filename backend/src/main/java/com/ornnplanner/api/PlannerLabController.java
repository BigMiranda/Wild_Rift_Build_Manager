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

/** The planner's panels: this build tested in a scenario or combo, and seen by another build. */
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

    /** Items whose on-hit effects an ally can bring (resistance reduction, shield cut, wounds). */
    @GetMapping("/ally-items")
    public List<String> allyItems() {
        return lab.allyItems();
    }

    /** How another build sees this one (or the other way), now and minute by minute. */
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
