package com.ornnplanner.api;

import com.ornnplanner.arena.MatchService;
import com.ornnplanner.arena.rift.MatchModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Whole matches on the full map. */
@RestController
@RequestMapping("/api/match")
public class MatchController {

    private final MatchService matches;

    public MatchController(MatchService matches) {
        this.matches = matches;
    }

    @GetMapping("/meta")
    public Map<String, Object> meta() {
        return matches.meta();
    }

    @PostMapping("/run")
    public MatchModel.Result run(@RequestBody MatchModel.Request req) {
        return matches.run(req);
    }
}
