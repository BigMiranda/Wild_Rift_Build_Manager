package com.ornnplanner.api;

import com.ornnplanner.arena.fight.BuildArchetypes;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.seed.ReferenceSeeder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/** The archetype of each saved build, or of a draft, at a minute. */
@RestController
@RequestMapping("/api/archetypes")
public class ArchetypeController {

    /** A build (saved or the planner's draft) at a minute. */
    public static class Request {
        public BuildRepository.Build build;
        public Double minute;
    }

    private final BuildArchetypes archetypes;
    private final BuildRepository builds;

    public ArchetypeController(BuildArchetypes archetypes, BuildRepository builds) {
        this.archetypes = archetypes;
        this.builds = builds;
    }

    /** Build id -> archetype, at each build's own end (or at {@code minute}). A build that cannot run yet is left out. */
    @GetMapping
    public Map<Long, BuildArchetypes.Archetype> all(@RequestParam(required = false) Double minute) {
        Map<Long, BuildArchetypes.Archetype> out = new LinkedHashMap<>();
        for (BuildRepository.Build s : builds.findBuildSummaries()) {
            if (ReferenceSeeder.RAGDOLL.equals(s.unitCode)) {
                continue;
            }
            try {
                builds.findBuild(s.id).ifPresent(b -> out.put(b.id, archetypes.of(b, minute)));
            } catch (ResponseStatusException e) {
                // no gold or XP per minute yet: nothing to classify
            }
        }
        return out;
    }

    @PostMapping
    public BuildArchetypes.Archetype one(@RequestBody Request req) {
        return archetypes.of(req.build, req.minute);
    }
}
