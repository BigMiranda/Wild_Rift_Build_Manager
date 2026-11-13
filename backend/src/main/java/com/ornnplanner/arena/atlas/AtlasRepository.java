package com.ornnplanner.arena.atlas;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The atlas's runs (table atlas_run). */
@Repository
public class AtlasRepository {

    static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final JdbcTemplate jdbc;

    public AtlasRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(AtlasModel.Config c) {
        String now = Instant.now().toString();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO atlas_run (name, config, status, stage, done, total, created_at, updated_at) "
                    + "VALUES (?, ?, 'pendente', NULL, 0, 0, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, c.name == null ? c.a + " × " + c.b : c.name);
            ps.setString(2, write(c));
            ps.setString(3, now);
            ps.setString(4, now);
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    /** Every run, without its result (the list). */
    public List<AtlasModel.Run> list() {
        return jdbc.query("SELECT id, name, config, status, stage, done, total, error, NULL AS result, created_at, updated_at "
                + "FROM atlas_run ORDER BY id DESC", (rs, i) -> row(rs));
    }

    public Optional<AtlasModel.Run> find(long id) {
        return jdbc.query("SELECT * FROM atlas_run WHERE id = ?", (rs, i) -> row(rs), id).stream().findFirst();
    }

    public void progress(long id, String status, String stage, int done, int total) {
        jdbc.update("UPDATE atlas_run SET status = ?, stage = ?, done = ?, total = ?, updated_at = ? WHERE id = ?",
                status, stage, done, total, Instant.now().toString(), id);
    }

    public void finish(long id, String status, String error, AtlasModel.Result result) {
        jdbc.update("UPDATE atlas_run SET status = ?, error = ?, result = ?, stage = NULL, updated_at = ? WHERE id = ?",
                status, error, result == null ? null : write(result), Instant.now().toString(), id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM atlas_run WHERE id = ?", id);
    }

    /** Runs left running by a stop of the app: back to pending, to run again from the start. */
    public int resetRunning() {
        return jdbc.update("UPDATE atlas_run SET status = 'pendente', stage = NULL, done = 0 WHERE status = 'rodando'");
    }

    private static AtlasModel.Run row(ResultSet rs) throws SQLException {
        AtlasModel.Run r = new AtlasModel.Run();
        r.id = rs.getLong("id");
        r.name = rs.getString("name");
        r.config = read(rs.getString("config"), AtlasModel.Config.class);
        r.status = rs.getString("status");
        r.stage = rs.getString("stage");
        r.done = rs.getInt("done");
        r.total = rs.getInt("total");
        r.error = rs.getString("error");
        String res = rs.getString("result");
        r.result = res == null ? null : read(res, AtlasModel.Result.class);
        r.createdAt = rs.getString("created_at");
        r.updatedAt = rs.getString("updated_at");
        return r;
    }

    static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static <T> T read(String s, Class<T> type) {
        try {
            return JSON.readValue(s, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Dados inválidos em atlas_run", e);
        }
    }
}
