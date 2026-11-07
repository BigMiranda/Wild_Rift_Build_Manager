package com.ornnplanner.publico;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What the public version tells its page: that it is the public one, its limits, and what the export calculated. */
@RestController
@RequestMapping("/api/public")
@Profile(PublicMode.PROFILE)
public class PublicController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PublicLimits limits;
    private final JdbcTemplate jdbc;

    public PublicController(PublicLimits limits, JdbcTemplate jdbc) {
        this.limits = limits;
        this.jdbc = jdbc;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("public", true);
        m.put("computePerMinute", limits.getComputePerMinute());
        m.put("computeSeconds", limits.getComputeSeconds());
        m.put("arenaMaxSeconds", limits.getArenaMaxSeconds());
        m.put("effectJobs", limits.getEffectJobs());
        try {
            Map<String, String> export = new LinkedHashMap<>();
            jdbc.query("SELECT key, value FROM public_export ORDER BY key", rs -> {
                export.put(rs.getString(1), rs.getString(2));
            });
            m.put("export", export);
        } catch (DataAccessException e) {
            m.put("export", null);   // a database that was not exported (tests)
        }
        return m;
    }

    /** The basic effectiveness of each public build, calculated at the export (at its last purchase). */
    @GetMapping("/effect")
    public List<Object> effect() {
        List<Object> out = new ArrayList<>();
        try {
            jdbc.query("SELECT data FROM public_effect ORDER BY build_id", rs -> {
                try {
                    out.add(JSON.readValue(rs.getString(1), Map.class));
                } catch (java.io.IOException e) {
                    // unreadable row: left out
                }
            });
        } catch (DataAccessException e) {
            // a database that was not exported
        }
        return out;
    }
}
