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

    /** "Salvar como build": the base build with a found order and page of runes, saved in the same folder. */
    public static class Save {
        public Long baseBuildId;
        public String name;
        public java.util.List<Long> order = new java.util.ArrayList<>();
        public com.ornnplanner.repo.BuildRepository.RunePage runePage;
    }

    private final DiscoveryService discovery;
    private final com.ornnplanner.repo.BuildRepository builds;

    public DiscoveryController(DiscoveryService discovery, com.ornnplanner.repo.BuildRepository builds) {
        this.discovery = discovery;
        this.builds = builds;
    }

    @PostMapping("/save")
    public com.ornnplanner.repo.BuildRepository.Build save(@RequestBody Save s) {
        com.ornnplanner.repo.BuildRepository.Build b = builds.findBuild(s.baseBuildId == null ? -1 : s.baseBuildId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Build não existe."));
        b.id = null;
        b.name = b.name + " · " + (s.name == null || s.name.isBlank() ? "descoberta" : s.name);
        b.steps = new java.util.ArrayList<>();
        for (long id : s.order) {
            b.steps.add(new com.ornnplanner.repo.BuildRepository.Step(id, true));
        }
        if (s.runePage != null) {
            b.runePage = s.runePage;
        }
        b.id = builds.insertBuild(b);
        return b;
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
