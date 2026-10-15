package com.ornnplanner.arena;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ornnplanner.arena.ArenaModel.Combo;
import com.ornnplanner.arena.ai.ActionSpace;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The arena's combo library (table arena_combo): per champion, found by the search or typed by the user. */
@Repository
public class ComboRepository {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final String COLUMNS = "id, champion, name, origin, active, score, build, build_id, minute, data, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public ComboRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A champion's combos (all champions' when null), best score first. */
    public List<Combo> list(String champion) {
        return champion == null || champion.isBlank()
                ? jdbc.query("SELECT " + COLUMNS + " FROM arena_combo ORDER BY champion, score DESC, id", this::row)
                : jdbc.query("SELECT " + COLUMNS + " FROM arena_combo WHERE champion = ? COLLATE NOCASE ORDER BY score DESC, id",
                this::row, champion);
    }

    /**
     * The combos a policy of the champion may run: its active ones of the given origins (null = any), best score first,
     * at most {@link ActionSpace#COMBOS}.
     */
    public List<Combo> forPolicy(String champion, List<String> origins) {
        List<Combo> out = new ArrayList<>();
        for (Combo c : list(champion)) {
            if (c.active && (origins == null || origins.contains(c.origin)) && out.size() < ActionSpace.COMBOS) {
                out.add(c);
            }
        }
        return out;
    }

    public Optional<Combo> find(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM arena_combo WHERE id = ?", this::row, id).stream().findFirst();
    }

    public Combo save(Combo c) {
        String now = Instant.now().toString();
        String data;
        try {
            data = JSON.writeValueAsString(body(c));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        c.updatedAt = now;
        if (c.id == null) {
            c.createdAt = now;
            KeyHolder kh = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("INSERT INTO arena_combo (champion, name, origin, active, score, build, "
                        + "build_id, minute, data, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
                int i = 1;
                ps.setString(i++, c.champion);
                ps.setString(i++, c.name);
                ps.setString(i++, c.origin);
                ps.setInt(i++, c.active ? 1 : 0);
                ps.setObject(i++, c.score);
                ps.setString(i++, c.build);
                ps.setObject(i++, c.buildId);
                ps.setObject(i++, c.minute);
                ps.setString(i++, data);
                ps.setString(i++, now);
                ps.setString(i, now);
                return ps;
            }, kh);
            c.id = kh.getKey() == null ? null : kh.getKey().longValue();
        } else {
            jdbc.update("UPDATE arena_combo SET champion = ?, name = ?, origin = ?, active = ?, score = ?, build = ?, build_id = ?, "
                    + "minute = ?, data = ?, updated_at = ? WHERE id = ?", c.champion, c.name, c.origin, c.active ? 1 : 0, c.score,
                    c.build, c.buildId, c.minute, data, now, c.id);
        }
        return c;
    }

    public boolean setActive(long id, boolean active) {
        return jdbc.update("UPDATE arena_combo SET active = ?, updated_at = ? WHERE id = ?", active ? 1 : 0,
                Instant.now().toString(), id) > 0;
    }

    public boolean delete(long id) {
        return jdbc.update("DELETE FROM arena_combo WHERE id = ?", id) > 0;
    }

    /** Removes a champion's combos found for a build and minute (a new search replaces its library). */
    public int deleteFound(String champion, Long buildId, Double minute) {
        return jdbc.update("DELETE FROM arena_combo WHERE champion = ? COLLATE NOCASE AND origin = 'descoberto' "
                + "AND build_id IS ? AND minute IS ?", champion, buildId, minute);
    }

    /** What goes in the data column: everything but the columns of their own. */
    private static Combo body(Combo c) {
        Combo b = new Combo();
        b.steps = c.steps;
        b.start = c.start;
        b.abort = c.abort;
        b.maxTime = c.maxTime;
        b.metrics = c.metrics;
        b.note = c.note;
        return b;
    }

    private Combo row(ResultSet rs, int i) throws SQLException {
        Combo c;
        try {
            c = JSON.readValue(rs.getString("data"), Combo.class);
        } catch (Exception e) {
            c = new Combo();   // unreadable data: the row is still listed, with no steps
            c.note = "dados ilegíveis: " + e.getMessage();
        }
        c.id = rs.getLong("id");
        c.champion = rs.getString("champion");
        c.name = rs.getString("name");
        c.origin = rs.getString("origin");
        c.active = rs.getInt("active") != 0;
        double score = rs.getDouble("score");
        c.score = rs.wasNull() ? null : score;
        c.build = rs.getString("build");
        long buildId = rs.getLong("build_id");
        c.buildId = rs.wasNull() ? null : buildId;
        double minute = rs.getDouble("minute");
        c.minute = rs.wasNull() ? null : minute;
        c.createdAt = rs.getString("created_at");
        c.updatedAt = rs.getString("updated_at");
        return c;
    }
}
