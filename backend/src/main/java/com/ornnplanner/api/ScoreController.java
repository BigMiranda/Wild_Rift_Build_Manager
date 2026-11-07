package com.ornnplanner.api;

import com.ornnplanner.arena.calc.BuildScore;
import com.ornnplanner.repo.BuildRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** The class score of a build (Rodada 9, part 1): one minute, or robust over the minutes against a reference. */
@RestController
@RequestMapping("/api/score")
public class ScoreController {

    /** A build (saved or the draft), a minute (null = robust over the YAML's minutes) and an optional reference build. */
    public static class Request {
        public Long buildId;
        public BuildRepository.Build build;
        public Double minute;
        public Long referenceBuildId;
    }

    private final BuildScore scorer;
    private final BuildRepository builds;

    public ScoreController(BuildScore scorer, BuildRepository builds) {
        this.scorer = scorer;
        this.builds = builds;
    }

    @GetMapping("/rules")
    public Map<String, Object> rules() {
        return scorer.rules();
    }

    @PostMapping
    public Object score(@RequestBody Request req) {
        BuildRepository.Build b = req.build != null ? req.build : find(req.buildId);
        if (req.minute != null) {
            return scorer.score(b, req.minute);
        }
        return scorer.robust(b, req.referenceBuildId == null ? null : find(req.referenceBuildId));
    }

    private BuildRepository.Build find(Long id) {
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha uma build.");
        }
        return builds.findBuild(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Build " + id + " não existe."));
    }
}
