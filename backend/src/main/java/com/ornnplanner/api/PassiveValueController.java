package com.ornnplanner.api;

import com.ornnplanner.arena.PassiveValueService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The gold value of the passives: estimated by the numbers of the moment, or measured in a scenario. */
@RestController
@RequestMapping("/api/passives")
public class PassiveValueController {

    private final PassiveValueService values;

    public PassiveValueController(PassiveValueService values) {
        this.values = values;
    }

    @PostMapping("/estimate")
    public List<PassiveValueService.Sample> estimate(@RequestBody PassiveValueService.EstimateRequest req) {
        return values.estimate(req);
    }

    /** A batch of passives (the screen sends a few at a time for its progress bar and "Parar"). */
    @PostMapping("/measure")
    public List<PassiveValueService.Reading> measure(@RequestBody PassiveValueService.MeasureRequest req) {
        return values.measure(req);
    }
}
