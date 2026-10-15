package com.ornnplanner.api;

import com.ornnplanner.arena.PlannerLabService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** The lab of the build planner: the open build at a minute against the ragdoll, and seen by another build. */
@RestController
@RequestMapping("/api/lab")
public class PlannerLabController {

    private final PlannerLabService lab;

    public PlannerLabController(PlannerLabService lab) {
        this.lab = lab;
    }

    /** The best combo of the library for the build and minute (made now by a small search when there is none). */
    @PostMapping("/combo")
    public PlannerLabService.ComboResult combo(@RequestBody PlannerLabService.Request req) {
        return lab.combo(req);
    }

    /** The combo with and without each item (and the damage received from another build's combo, for tanks). */
    @PostMapping("/items")
    public PlannerLabService.ItemsResult items(@RequestBody PlannerLabService.Request req) {
        return lab.items(req);
    }

    /** Item × ragdoll scenario. */
    @PostMapping("/scenarios")
    public Map<String, Object> scenarios(@RequestBody PlannerLabService.Request req) {
        return lab.scenarios(req);
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
