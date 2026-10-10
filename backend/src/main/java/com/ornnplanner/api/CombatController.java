package com.ornnplanner.api;

import com.ornnplanner.combat.CombatService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Matchup simulator: two sides of saved builds at given minutes. */
@RestController
public class CombatController {

    private final CombatService combat;

    public CombatController(CombatService combat) {
        this.combat = combat;
    }

    @PostMapping("/api/combat")
    public CombatService.Response simulate(@RequestBody CombatService.Request request) {
        return combat.simulate(request);
    }
}
