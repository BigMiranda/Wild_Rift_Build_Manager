package com.ornnplanner.arena;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** What each AI did in each fight, on the screen or in training (table ai_result): what the automatic brain reads. */
@Repository
public class AiResultRepository {

    /** One champion in one fight. */
    public static class Row {
        public Long id;
        /** The AI it played with (null = the tactical rules, when the automatic brain fell back to them). */
        public Long policyId;
        public String champion;
        /** The fingerprint of the scenario, from this champion's side: equal means the same fight. */
        public String scenarioKey;
        /** The enemies, sorted: the same ones, or the same classes, make a scenario alike. */
        public List<String> enemies = new ArrayList<>();
        public List<String> enemyClasses = new ArrayList<>();
        public String map;
        public double minute;
        /** The fight in words: champions, builds and minutes, objectives, positions in bands. */
        public String impression;
        public boolean won;
        /** Every enemy died. */
        public boolean clean;
        /** The champion died. */
        public boolean died;
        public double time;
        /** Share of its health left at the end. */
        public double health;
        public String gamePatch;
        /** tela | treino */
        public String source;
        public String createdAt;
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;

    public AiResultRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Writes a row, unless the same AI just did the same in the same fight: the screen runs the fight again at each edit,
     * and a deterministic repeat says nothing new.
     */
    public void insert(Row r) {
        Integer same = jdbc.queryForObject("SELECT COUNT(*) FROM ai_result WHERE champion = ? AND scenario_key = ? AND policy_id IS ? "
                + "AND won = ? AND clean = ? AND died = ? AND duration = ? AND health = ?", Integer.class, r.champion, r.scenarioKey, r.policyId,
                r.won ? 1 : 0, r.clean ? 1 : 0, r.died ? 1 : 0, r.time, r.health);
        if (same != null && same > 0) {
            return;
        }
        jdbc.update("INSERT INTO ai_result (policy_id, champion, scenario_key, enemies, enemy_classes, map, minute, impression, won, clean, "
                        + "died, duration, health, game_patch, source, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                r.policyId, r.champion, r.scenarioKey, write(r.enemies), write(r.enemyClasses), r.map, r.minute, r.impression, r.won ? 1 : 0,
                r.clean ? 1 : 0, r.died ? 1 : 0, r.time, r.health, r.gamePatch, r.source, Instant.now().toString());
    }

    /** What a champion has done, whatever the AI. */
    public List<Row> forChampion(String champion) {
        return jdbc.query("SELECT * FROM ai_result WHERE champion = ? COLLATE NOCASE ORDER BY id", this::row, champion);
    }

    /** An AI that was deleted leaves no results behind. */
    public void forgetPolicy(long policyId) {
        jdbc.update("DELETE FROM ai_result WHERE policy_id = ?", policyId);
    }

    private Row row(ResultSet rs, int i) throws SQLException {
        Row r = new Row();
        r.id = rs.getLong("id");
        long policy = rs.getLong("policy_id");
        r.policyId = rs.wasNull() ? null : policy;
        r.champion = rs.getString("champion");
        r.scenarioKey = rs.getString("scenario_key");
        r.enemies = read(rs.getString("enemies"));
        r.enemyClasses = read(rs.getString("enemy_classes"));
        r.map = rs.getString("map");
        r.minute = rs.getDouble("minute");
        r.impression = rs.getString("impression");
        r.won = rs.getInt("won") != 0;
        r.clean = rs.getInt("clean") != 0;
        r.died = rs.getInt("died") != 0;
        r.time = rs.getDouble("duration");
        r.health = rs.getDouble("health");
        r.gamePatch = rs.getString("game_patch");
        r.source = rs.getString("source");
        r.createdAt = rs.getString("created_at");
        return r;
    }

    private static String write(List<String> list) {
        try {
            return JSON.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> read(String s) {
        try {
            return JSON.readValue(s, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Dados inválidos em ai_result", e);
        }
    }
}
