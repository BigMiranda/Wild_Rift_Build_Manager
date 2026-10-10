package com.ornnplanner.api;

import com.ornnplanner.seed.ChampionCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Champion reference (title, measured stats, passive and abilities), as transcribed from the game. */
@RestController
public class ChampionController {

    private final ChampionCatalog champions;

    public ChampionController(ChampionCatalog champions) {
        this.champions = champions;
    }

    @GetMapping("/api/champions")
    public java.util.List<Map<String, Object>> list() {
        return champions.summaries();
    }

    @GetMapping("/api/champions/{code}")
    public Map<String, Object> champion(@PathVariable String code) {
        return champions.find(code).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
