package com.ornnplanner.arena.draft;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Rodada 20: the followed matches (table live_game), each a whole {@link LiveGameModel.Game} in JSON. */
@Repository
public class LiveGameRepository {

    static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final JdbcTemplate jdbc;

    public LiveGameRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every game: id, name, mode and dates (not the data). */
    public List<Map<String, Object>> list() {
        return jdbc.query("SELECT id, name, data, created_at, updated_at FROM live_game ORDER BY updated_at DESC, id DESC", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("name", rs.getString("name"));
            LiveGameModel.Game g = read(rs.getString("data"));
            m.put("mode", g.mode);
            m.put("fearless", g.fearless);
            m.put("blue", g.blue.champions());
            m.put("red", g.red.champions());
            m.put("createdAt", rs.getString("created_at"));
            m.put("updatedAt", rs.getString("updated_at"));
            return m;
        });
    }

    public Optional<LiveGameModel.Game> find(long id) {
        return jdbc.query("SELECT * FROM live_game WHERE id = ?", (rs, i) -> {
            LiveGameModel.Game g = read(rs.getString("data"));
            g.id = rs.getLong("id");
            g.name = rs.getString("name");
            g.createdAt = rs.getString("created_at");
            g.updatedAt = rs.getString("updated_at");
            return g;
        }, id).stream().findFirst();
    }

    public LiveGameModel.Game save(LiveGameModel.Game g) {
        String now = Instant.now().toString();
        if (g.name == null || g.name.isBlank()) {
            g.name = "Partida " + now.substring(0, 16).replace('T', ' ');
        }
        if (g.id == null) {
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("INSERT INTO live_game (name, data, created_at, updated_at) VALUES (?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, g.name);
                ps.setString(2, write(g));
                ps.setString(3, now);
                ps.setString(4, now);
                return ps;
            }, key);
            g.id = key.getKey().longValue();
            g.createdAt = now;
        } else {
            int n = jdbc.update("UPDATE live_game SET name = ?, data = ?, updated_at = ? WHERE id = ?", g.name, write(g), now, g.id);
            if (n == 0) {
                throw new IllegalArgumentException("Partida " + g.id + " não existe.");
            }
        }
        g.updatedAt = now;
        return g;
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM live_game WHERE id = ?", id);
    }

    /** A deep copy of a build (through JSON), to change it without touching the original. */
    static com.ornnplanner.repo.BuildRepository.Build copy(com.ornnplanner.repo.BuildRepository.Build b) {
        return JSON.convertValue(b, com.ornnplanner.repo.BuildRepository.Build.class);
    }

    static LiveGameModel.Game read(String s) {
        try {
            return JSON.readValue(s, LiveGameModel.Game.class);
        } catch (Exception e) {
            throw new IllegalStateException("Dados inválidos em live_game", e);
        }
    }

    static String write(LiveGameModel.Game g) {
        try {
            return JSON.writeValueAsString(g);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
