package com.ornnplanner.arena.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Learned arena policies (table arena_policy): their validation numbers and their weights as JSON. */
@Repository
public class PolicyRepository {

    /** A saved policy (weights only when read one by one). */
    public static class Record {
        public Long id;
        public String champion;
        public String objective;
        public int version;
        /** Mean fitness on validation. */
        public Double fitness;
        /** Share of validation fights where it did better than the scripted / tactical autopilot (ties count half). */
        public Double betterVsScript;
        public Double betterVsTactical;
        /** Same against the previous accepted version of the champion and objective (null for the first). */
        public Double betterVsPrevious;
        /** Validation wins (draws count half), and the scripted autopilot's in the same fights. */
        public Double winrate;
        public Double winrateScript;
        /** Better than the script and the previous version in at least 55% of validation fights. */
        public boolean accepted;
        public Integer generations;
        public Integer population;
        public Long seed;
        /** Name of the scenario it was trained on. */
        public String scenario;
        public int obsSchema;
        public int actions;
        public String createdAt;
        /** The chain training that made it (null = trained alone) and its round there. */
        public String chainId;
        public Integer chainRound;
        /** The side's plan used in its training fights (null = none). */
        public com.ornnplanner.arena.ArenaModel.TeamPlan teamPlan;
        /** The version its training started from (null = from the tactical autopilot, zero weights). */
        public Long parentId;
        /** {layers, weights, bonus}. */
        public Weights weights;

        /** The same numbers without the weights (what jobs and lists show). */
        public Record withoutWeights() {
            Record r = new Record();
            r.id = id;
            r.champion = champion;
            r.objective = objective;
            r.version = version;
            r.fitness = fitness;
            r.betterVsScript = betterVsScript;
            r.betterVsTactical = betterVsTactical;
            r.betterVsPrevious = betterVsPrevious;
            r.winrate = winrate;
            r.winrateScript = winrateScript;
            r.accepted = accepted;
            r.generations = generations;
            r.population = population;
            r.seed = seed;
            r.scenario = scenario;
            r.obsSchema = obsSchema;
            r.actions = actions;
            r.createdAt = createdAt;
            r.chainId = chainId;
            r.chainRound = chainRound;
            r.teamPlan = teamPlan;
            r.parentId = parentId;
            r.migratedFrom = migratedFrom;
            r.level = level;
            r.guidelines = guidelines;
            return r;
        }

        /** The observation schema its weights were migrated from when read (null = saved with the current one). */
        public Integer migratedFrom;
        /** Help level it was trained at (sozinha | descobertos | dicas; null = before levels, as sozinha). */
        public String level;
        /** The guidelines it was trained with (level dicas), a snapshot. */
        public List<com.ornnplanner.arena.ArenaModel.Guideline> guidelines;

        /**
         * Weights of the current observation and action layout, or of an older one they migrate from when read
         * ({@link #upgrade}): usable either way.
         */
        public boolean compatible() {
            return migratable(obsSchema, actions);
        }

        /** The learned policy of these weights, on the base it was trained with. */
        public NeuralPolicy policy(List<String> priority) {
            return weights.policy(priority);
        }
    }

    /** Observation schemas whose weights read into the current one, and the action counts they had. */
    static final Map<Integer, Integer> OBS_SIZES = Map.of(2, Observation.V2_SIZE, 3, Observation.SIZE);
    /** Actions of the layouts before the current one (32 until combos). */
    static final List<Integer> OLD_ACTIONS = List.of(32);

    public static boolean migratable(int obsSchema, int actions) {
        return OBS_SIZES.containsKey(obsSchema) && obsSchema <= Observation.SCHEMA_VERSION
                && (actions == ActionSpace.SIZE || OLD_ACTIONS.contains(actions));
    }

    /**
     * Weights of an older layout in the current one: the observation's inputs by the explicit index map of its schema
     * ({@link Observation#migration}, new inputs weigh zero) and the actions by theirs ({@link ActionSpace#migration},
     * new actions start at {@link ActionSpace#NEW_ACTION_BIAS}). The same weights when already current or unknown.
     */
    public static Weights upgrade(Weights w, int obsSchema, int actions) {
        if (w == null || w.layers == null || !migratable(obsSchema, actions)
                || obsSchema == Observation.SCHEMA_VERSION && actions == ActionSpace.SIZE
                || w.layers[0] != OBS_SIZES.get(obsSchema)) {
            return w;
        }
        Network net = w.network().migrate(Observation.migration(obsSchema), Observation.SIZE,
                ActionSpace.migration(actions), ActionSpace.SIZE, ActionSpace.NEW_ACTION_BIAS);
        Weights out = Weights.of(net, w.bonus);
        out.base = w.base;
        return out;
    }

    public static class Weights {
        /** The tactical rules it is residual over: the ones with predictions (schema 3 on), null for older versions. */
        public static final String PREDICTIVE = "tatico-previsoes";

        public int[] layers;
        public double[] weights;
        public double bonus = NeuralPolicy.BASE_BONUS;
        /** {@link #PREDICTIVE}, or null: the tactical rules before the predictions ({@link TacticalPolicy#legacy}). */
        public String base;

        public static Weights of(Network net, double bonus) {
            Weights w = new Weights();
            w.layers = net.layers();
            w.weights = net.weights();
            w.bonus = bonus;
            return w;
        }

        /** Weights of a new training: residual over the tactical rules with predictions. */
        public static Weights trained(Network net, double bonus) {
            Weights w = of(net, bonus);
            w.base = PREDICTIVE;
            return w;
        }

        public Network network() {
            return new Network(layers, weights);
        }

        /** The policy of these weights on their own base. */
        public NeuralPolicy policy(List<String> priority) {
            return new NeuralPolicy(network(), bonus,
                    PREDICTIVE.equals(base) ? new TacticalPolicy(priority) : TacticalPolicy.legacy(priority));
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COLUMNS = "id, champion, objective, version, fitness, better_vs_script, winrate, winrate_script, "
            + "better_vs_tactical, better_vs_previous, accepted, generations, population, seed, scenario, obs_schema, actions, created_at, "
            + "chain_id, chain_round, team_plan, parent_id, level, guidelines";

    private final JdbcTemplate jdbc;
    /** Weights never change once saved: read once. */
    private final Map<Long, Weights> cache = new ConcurrentHashMap<>();

    public PolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Saved policies, newest version first (a champion's only when given). */
    public List<Record> list(String champion) {
        return champion == null || champion.isBlank()
                ? jdbc.query("SELECT " + COLUMNS + " FROM arena_policy ORDER BY champion, objective, version DESC", this::row)
                : jdbc.query("SELECT " + COLUMNS + " FROM arena_policy WHERE champion = ? COLLATE NOCASE ORDER BY objective, version DESC",
                this::row, champion);
    }

    public Optional<Record> find(long id) {
        List<Record> out = jdbc.query("SELECT " + COLUMNS + ", weights_json FROM arena_policy WHERE id = ?", (rs, i) -> {
            Record r = row(rs, i);
            // older layouts are migrated once, when read ("migrate on load")
            r.weights = cache.computeIfAbsent(r.id, k -> upgrade(read(rs), r.obsSchema, r.actions));
            return r;
        }, id);
        return out.stream().findFirst();
    }

    /** The latest accepted, compatible version of a champion for an objective (the one a new version must beat). */
    public Optional<Record> best(String champion, String objective) {
        return list(champion).stream().filter(r -> r.accepted && r.compatible() && r.objective.equals(objective))
                .findFirst().flatMap(r -> find(r.id));
    }

    /**
     * The latest accepted version of a champion for an objective, compatible or not, with its weights: where a chain
     * stage starts from (an incompatible one is reported and skipped).
     */
    public Optional<Record> latestAccepted(String champion, String objective) {
        return list(champion).stream().filter(r -> r.accepted && r.objective.equals(objective))
                .findFirst().flatMap(r -> find(r.id));
    }

    public int nextVersion(String champion) {
        Integer v = jdbc.queryForObject("SELECT MAX(version) FROM arena_policy WHERE champion = ? COLLATE NOCASE",
                Integer.class, champion);
        return v == null ? 1 : v + 1;
    }

    public Record insert(Record r) {
        String weights;
        try {
            weights = JSON.writeValueAsString(r.weights);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        String plan;
        try {
            plan = r.teamPlan == null ? null : JSON.writeValueAsString(r.teamPlan);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        String guidelines;
        try {
            guidelines = r.guidelines == null ? null : JSON.writeValueAsString(r.guidelines);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        r.createdAt = Instant.now().toString();
        r.version = nextVersion(r.champion);
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(c -> {
            PreparedStatement ps = c.prepareStatement("INSERT INTO arena_policy (champion, objective, version, fitness, "
                    + "better_vs_script, winrate, winrate_script, better_vs_tactical, better_vs_previous, accepted, generations, "
                    + "population, seed, scenario, weights_json, obs_schema, actions, created_at, chain_id, chain_round, team_plan, "
                    + "parent_id, level, guidelines) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setString(i++, r.champion);
            ps.setString(i++, r.objective);
            ps.setInt(i++, r.version);
            ps.setObject(i++, r.fitness);
            ps.setObject(i++, r.betterVsScript);
            ps.setObject(i++, r.winrate);
            ps.setObject(i++, r.winrateScript);
            ps.setObject(i++, r.betterVsTactical);
            ps.setObject(i++, r.betterVsPrevious);
            ps.setInt(i++, r.accepted ? 1 : 0);
            ps.setObject(i++, r.generations);
            ps.setObject(i++, r.population);
            ps.setObject(i++, r.seed);
            ps.setString(i++, r.scenario);
            ps.setString(i++, weights);
            ps.setInt(i++, r.obsSchema);
            ps.setInt(i++, r.actions);
            ps.setString(i++, r.createdAt);
            ps.setString(i++, r.chainId);
            ps.setObject(i++, r.chainRound);
            ps.setString(i++, plan);
            ps.setObject(i++, r.parentId);
            ps.setString(i++, r.level);
            ps.setString(i, guidelines);
            return ps;
        }, key);
        r.id = key.getKey() == null ? null : key.getKey().longValue();
        return r;
    }

    public boolean delete(long id) {
        cache.remove(id);
        return jdbc.update("DELETE FROM arena_policy WHERE id = ?", id) > 0;
    }

    private Record row(ResultSet rs, int i) throws SQLException {
        Record r = new Record();
        r.id = rs.getLong("id");
        r.champion = rs.getString("champion");
        r.objective = rs.getString("objective");
        r.version = rs.getInt("version");
        r.fitness = dbl(rs, "fitness");
        r.betterVsScript = dbl(rs, "better_vs_script");
        r.winrate = dbl(rs, "winrate");
        r.winrateScript = dbl(rs, "winrate_script");
        r.betterVsTactical = dbl(rs, "better_vs_tactical");
        r.betterVsPrevious = dbl(rs, "better_vs_previous");
        r.accepted = rs.getInt("accepted") != 0;
        r.generations = integer(rs, "generations");
        r.population = integer(rs, "population");
        r.seed = lng(rs, "seed");
        r.scenario = rs.getString("scenario");
        r.obsSchema = rs.getInt("obs_schema");
        r.actions = rs.getInt("actions");
        r.createdAt = rs.getString("created_at");
        r.chainId = rs.getString("chain_id");
        r.chainRound = integer(rs, "chain_round");
        r.parentId = lng(rs, "parent_id");
        if (r.obsSchema != Observation.SCHEMA_VERSION && r.compatible()) {
            r.migratedFrom = r.obsSchema;   // its weights are migrated when read
        }
        r.level = rs.getString("level");
        String gl = rs.getString("guidelines");
        if (gl != null) {
            try {
                r.guidelines = JSON.readValue(gl, new com.fasterxml.jackson.core.type.TypeReference<List<com.ornnplanner.arena.ArenaModel.Guideline>>() { });
            } catch (JsonProcessingException e) {
                r.guidelines = null;
            }
        }
        String plan = rs.getString("team_plan");
        if (plan != null) {
            try {
                r.teamPlan = JSON.readValue(plan, com.ornnplanner.arena.ArenaModel.TeamPlan.class);
            } catch (JsonProcessingException e) {
                r.teamPlan = null;   // unreadable plan: the policy itself is still usable
            }
        }
        return r;
    }

    private static Double dbl(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private static Integer integer(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static Long lng(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Weights read(ResultSet rs) {
        try {
            return JSON.readValue(rs.getString("weights_json"), Weights.class);
        } catch (Exception e) {
            throw new IllegalStateException("unreadable weights", e);
        }
    }
}
