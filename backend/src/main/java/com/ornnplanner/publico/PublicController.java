package com.ornnplanner.publico;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** What the public version tells its page: that it is the public one, and its limits. */
@RestController
@RequestMapping("/api/public")
@Profile(PublicMode.PROFILE)
public class PublicController {

    private final PublicLimits limits;

    public PublicController(PublicLimits limits) {
        this.limits = limits;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("public", true);
        m.put("computePerMinute", limits.getComputePerMinute());
        m.put("computeSeconds", limits.getComputeSeconds());
        m.put("arenaMaxSeconds", limits.getArenaMaxSeconds());
        m.put("effectJobs", limits.getEffectJobs());
        return m;
    }
}
