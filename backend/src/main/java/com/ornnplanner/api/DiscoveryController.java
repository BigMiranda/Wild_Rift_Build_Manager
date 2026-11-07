package com.ornnplanner.api;

import com.ornnplanner.arena.discovery.DiscoveryModel;
import com.ornnplanner.arena.discovery.DiscoveryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The build discovery (Rodada 9): estimate, start, follow and stop a job. */
@RestController
@RequestMapping("/api/discovery")
public class DiscoveryController {

    private final DiscoveryService discovery;

    public DiscoveryController(DiscoveryService discovery) {
        this.discovery = discovery;
    }

    @PostMapping("/estimate")
    public DiscoveryModel.Estimate estimate(@RequestBody DiscoveryModel.Request r) {
        return discovery.estimate(r);
    }

    @PostMapping("/start")
    public DiscoveryModel.Job start(@RequestBody DiscoveryModel.Request r) {
        return discovery.start(r);
    }

    @GetMapping("/{id}")
    public DiscoveryModel.Job job(@PathVariable long id) {
        return discovery.job(id);
    }

    @PostMapping("/{id}/stop")
    public void stop(@PathVariable long id) {
        discovery.stop(id);
    }
}
