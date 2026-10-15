package com.ornnplanner.arena;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ornnplanner.arena.ArenaModel.Guideline;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The user's guidelines and event reward weights per champion (table arena_guideline). */
@Repository
public class GuidelineRepository {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** A champion's guidelines and the points of each event in its training (null weights = the defaults). */
    public static class Set {
        public String champion;
        public List<Guideline> guidelines = new ArrayList<>();
        public Map<String, Double> rewards;
        public String updatedAt;
    }

    private final JdbcTemplate jdbc;

    public GuidelineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Set find(String champion) {
        List<String> data = jdbc.queryForList("SELECT data FROM arena_guideline WHERE champion = ?", String.class, champion);
        Set s = new Set();
        if (!data.isEmpty()) {
            try {
                s = JSON.readValue(data.get(0), Set.class);
            } catch (Exception e) {
                s = new Set();
            }
        }
        s.champion = champion;
        int n = 0;
        for (Guideline g : s.guidelines) {
            if (g.id == null || g.id.isBlank()) {
                g.id = "regra" + (++n);
            }
        }
        return s;
    }

    public List<Set> all() {
        List<Set> out = new ArrayList<>();
        for (String c : jdbc.queryForList("SELECT champion FROM arena_guideline ORDER BY champion", String.class)) {
            out.add(find(c));
        }
        return out;
    }

    public Set save(Set s) {
        s.updatedAt = Instant.now().toString();
        String data;
        try {
            data = JSON.writeValueAsString(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        if (jdbc.update("UPDATE arena_guideline SET data = ?, updated_at = ? WHERE champion = ?", data, s.updatedAt, s.champion) == 0) {
            jdbc.update("INSERT INTO arena_guideline (champion, data, updated_at) VALUES (?,?,?)", s.champion, data, s.updatedAt);
        }
        return s;
    }

    /** The reward weights in use for a champion: its own, else the defaults. */
    public Map<String, Double> rewards(String champion) {
        Map<String, Double> w = find(champion).rewards;
        return w == null || w.isEmpty() ? new LinkedHashMap<>(ArenaTraining.REWARDS) : w;
    }
}
