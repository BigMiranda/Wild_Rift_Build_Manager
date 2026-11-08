package com.ornnplanner.arena;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ornnplanner.arena.ai.Network;
import com.ornnplanner.seed.ChampionCatalog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** The whole match's trained macros (table {@code macro_policy}, proposta-macro-treinada.md, Parte 4). */
@Repository
public class MacroPolicyRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A trained macro: how it was trained and how it did against the script. */
    public static class Record {
        public long id;
        public int version;
        public Long parentId;
        public boolean accepted;
        public double winrate;
        public int games;
        public double fitness;
        public double goldPerMin;
        public double opponentGoldPerMin;
        public double deviations;
        public int generations;
        public int population;
        public int episodes;
        public long seed;
        public List<List<Long>> drafts = new ArrayList<>();
        public int obsSchema;
        public int actions;
        public String gamePatch;
        public int rules;
        public String createdAt;
        /** Not in the list's JSON: read by {@link #weights}. */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public Weights weights;
    }

    /** The network's layers and weights, and the bonus of the script's choice. */
    public static class Weights {
        public int[] layers;
        public double[] weights;
        public double bonus;

        public static Weights of(Network net, double bonus) {
            Weights w = new Weights();
            w.layers = net.layers();
            w.weights = net.weights();
            w.bonus = bonus;
            return w;
        }

        public Network network() {
            return new Network(layers, weights);
        }
    }

    private final JdbcTemplate jdbc;
    private final String gamePatch;
    /** Weights never change once saved: read once. */
    private final Map<Long, Weights> cache = new ConcurrentHashMap<>();

    public MacroPolicyRepository(JdbcTemplate jdbc, ChampionCatalog champions) {
        this.jdbc = jdbc;
        this.gamePatch = champions.patch();
    }

    public String gamePatch() {
        return gamePatch;
    }

    private static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Record row(ResultSet rs, int i) throws SQLException {
        Record r = new Record();
        r.id = rs.getLong("id");
        r.version = rs.getInt("version");
        long parent = rs.getLong("parent_id");
        r.parentId = rs.wasNull() ? null : parent;
        r.accepted = rs.getInt("accepted") != 0;
        r.winrate = rs.getDouble("winrate");
        r.games = rs.getInt("games");
        r.fitness = rs.getDouble("fitness");
        r.goldPerMin = rs.getDouble("gold_per_min");
        r.opponentGoldPerMin = rs.getDouble("opponent_gold_per_min");
        r.deviations = rs.getDouble("deviations");
        r.generations = rs.getInt("generations");
        r.population = rs.getInt("population");
        r.episodes = rs.getInt("episodes");
        r.seed = rs.getLong("seed");
        String drafts = rs.getString("drafts");
        try {
            r.drafts = drafts == null ? new ArrayList<>() : JSON.readValue(drafts, new TypeReference<List<List<Long>>>() { });
        } catch (JsonProcessingException e) {
            r.drafts = new ArrayList<>();
        }
        r.obsSchema = rs.getInt("obs_schema");
        r.actions = rs.getInt("actions");
        r.gamePatch = rs.getString("game_patch");
        r.rules = rs.getInt("rules");
        r.createdAt = rs.getString("created_at");
        return r;
    }

    private static final String COLUMNS = "id, version, parent_id, accepted, winrate, games, fitness, gold_per_min, opponent_gold_per_min, "
            + "deviations, generations, population, episodes, seed, drafts, obs_schema, actions, game_patch, rules, created_at";

    /** Newest first. */
    public List<Record> list() {
        return jdbc.query("SELECT " + COLUMNS + " FROM macro_policy ORDER BY id DESC", MacroPolicyRepository::row);
    }

    public Optional<Record> find(long id) {
        List<Record> l = jdbc.query("SELECT " + COLUMNS + " FROM macro_policy WHERE id = ?", MacroPolicyRepository::row, id);
        return l.stream().findFirst();
    }

    public Weights weights(long id) {
        return cache.computeIfAbsent(id, k -> {
            String w = jdbc.queryForObject("SELECT weights_json FROM macro_policy WHERE id = ?", String.class, k);
            try {
                return JSON.readValue(w, Weights.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("macro " + k + ": unreadable weights", e);
            }
        });
    }

    /** Saves a new version (its number is the next one) with the current patch; returns it with its id. */
    public Record insert(Record r) {
        Integer last = jdbc.queryForObject("SELECT MAX(version) FROM macro_policy", Integer.class);
        r.version = last == null ? 1 : last + 1;
        r.gamePatch = gamePatch;
        r.createdAt = Instant.now().toString();
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO macro_policy (version, parent_id, accepted, winrate, games, fitness, "
                    + "gold_per_min, opponent_gold_per_min, deviations, generations, population, episodes, seed, drafts, weights_json, "
                    + "obs_schema, actions, game_patch, rules, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            int k = 1;
            ps.setInt(k++, r.version);
            if (r.parentId == null) {
                ps.setNull(k++, Types.INTEGER);
            } else {
                ps.setLong(k++, r.parentId);
            }
            ps.setInt(k++, r.accepted ? 1 : 0);
            ps.setDouble(k++, r.winrate);
            ps.setInt(k++, r.games);
            ps.setDouble(k++, r.fitness);
            ps.setDouble(k++, r.goldPerMin);
            ps.setDouble(k++, r.opponentGoldPerMin);
            ps.setDouble(k++, r.deviations);
            ps.setInt(k++, r.generations);
            ps.setInt(k++, r.population);
            ps.setInt(k++, r.episodes);
            ps.setLong(k++, r.seed);
            ps.setString(k++, json(r.drafts));
            ps.setString(k++, json(r.weights));
            ps.setInt(k++, r.obsSchema);
            ps.setInt(k++, r.actions);
            ps.setString(k++, r.gamePatch);
            ps.setInt(k++, r.rules);
            ps.setString(k, r.createdAt);
            return ps;
        }, kh);
        r.id = kh.getKey().longValue();
        return r;
    }

    public boolean delete(long id) {
        cache.remove(id);
        return jdbc.update("DELETE FROM macro_policy WHERE id = ?", id) > 0;
    }
}
