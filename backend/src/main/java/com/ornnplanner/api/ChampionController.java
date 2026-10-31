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
    private final com.ornnplanner.arena.fight.ChampionProfiles profiles;

    public ChampionController(ChampionCatalog champions, com.ornnplanner.arena.fight.ChampionProfiles profiles) {
        this.champions = champions;
        this.profiles = profiles;
    }

    /** Official classes, roles and lanes of every champion, with the profile computed from its sheet. */
    @GetMapping("/api/champions/profiles")
    public java.util.List<com.ornnplanner.arena.fight.ChampionProfiles.Profile> profiles() {
        return profiles.all();
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
