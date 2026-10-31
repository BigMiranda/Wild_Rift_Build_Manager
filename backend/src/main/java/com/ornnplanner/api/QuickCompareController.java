package com.ornnplanner.api;

import com.ornnplanner.arena.QuickCompareService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The planner's quick item comparison: no fight, the measures of the timeline and of a zero-second run. */
@RestController
@RequestMapping("/api/quick")
public class QuickCompareController {

    private final QuickCompareService quick;

    public QuickCompareController(QuickCompareService quick) {
        this.quick = quick;
    }

    /** The candidates side by side in a purchase of the build (or as a new purchase). */
    @PostMapping("/compare")
    public QuickCompareService.Result compare(@RequestBody QuickCompareService.Request req) {
        return quick.compare(req);
    }

    /** The best items of the shop tab that fit there, by the criterion. */
    @PostMapping("/suggest")
    public QuickCompareService.Result suggest(@RequestBody QuickCompareService.Request req) {
        return quick.suggest(req);
    }
}
