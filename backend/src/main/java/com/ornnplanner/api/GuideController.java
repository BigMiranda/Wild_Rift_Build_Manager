package com.ornnplanner.api;

import com.ornnplanner.arena.guide.GuideService;
import com.ornnplanner.repo.BuildRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** The guides (Rodada 9, part 7): saved trees of decisions, read against a match, evaluated and suggested. */
@RestController
@RequestMapping("/api/guides")
public class GuideController {

    private final GuideService guides;
    private final BuildRepository builds;

    public GuideController(GuideService guides, BuildRepository builds) {
        this.guides = guides;
        this.builds = builds;
    }

    @GetMapping
    public List<GuideService.View> list(@RequestParam(required = false) String unit) {
        return guides.list(unit);
    }

    @GetMapping("/{id}")
    public GuideService.View get(@PathVariable long id) {
        return guides.get(id);
    }

    @PostMapping
    public GuideService.View create(@RequestBody GuideService.View v) {
        v.id = null;
        return guides.save(v);
    }

    @PutMapping("/{id}")
    public GuideService.View update(@PathVariable long id, @RequestBody GuideService.View v) {
        v.id = id;
        return guides.save(v);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        guides.delete(id);
    }

    /** A guide (saved, or the one being edited in the body's {@code guide}) read against a match. */
    public static class Resolve {
        public GuideService.View guide;
        public GuideService.Match match = new GuideService.Match();
    }

    @PostMapping("/resolve")
    public GuideService.Reading resolve(@RequestBody Resolve r) {
        return guides.resolve(r.guide, r.match);
    }

    public static class Evaluate {
        public GuideService.View guide;
        public Long buildId;
        public BuildRepository.Build build;
    }

    @PostMapping("/evaluate")
    public GuideService.Evaluation evaluate(@RequestBody Evaluate e) {
        BuildRepository.Build b = e.build != null ? e.build : builds.findBuild(e.buildId == null ? -1 : e.buildId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha uma build."));
        return guides.evaluate(e.guide, b);
    }

    @PostMapping("/suggest")
    public GuideService.View suggest(@RequestBody GuideService.Suggest s) {
        return guides.suggest(s);
    }
}
