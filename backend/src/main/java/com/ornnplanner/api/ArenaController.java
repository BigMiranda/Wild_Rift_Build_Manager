package com.ornnplanner.api;

import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.ArenaService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Orchestrated matchups: run a scenario on the arena, read the ability sheets, store scenarios. */
@RestController
@RequestMapping("/api/arena")
public class ArenaController {

    private final ArenaService arena;

    public ArenaController(ArenaService arena) {
        this.arena = arena;
    }

    @GetMapping("/kits")
    public Map<String, Object> kits() {
        return arena.kits();
    }

    @PostMapping("/run")
    public ArenaModel.Result run(@RequestBody ArenaModel.Scenario scenario) {
        return arena.run(scenario);
    }

    @PostMapping("/search")
    public ArenaService.SearchResult search(@RequestBody ArenaService.SearchRequest request) {
        return arena.search(request);
    }

    @GetMapping("/scenarios")
    public List<Map<String, Object>> scenarios() {
        return arena.list();
    }

    @GetMapping("/scenarios/{id}")
    public ArenaModel.Scenario scenario(@PathVariable long id) {
        return arena.find(id);
    }

    @PostMapping("/scenarios")
    public ArenaModel.Scenario create(@RequestBody ArenaModel.Scenario scenario) {
        scenario.id = null;
        return arena.save(scenario);
    }

    @PutMapping("/scenarios/{id}")
    public ArenaModel.Scenario update(@PathVariable long id, @RequestBody ArenaModel.Scenario scenario) {
        scenario.id = id;
        return arena.save(scenario);
    }

    @DeleteMapping("/scenarios/{id}")
    public void delete(@PathVariable long id) {
        arena.delete(id);
    }
}
