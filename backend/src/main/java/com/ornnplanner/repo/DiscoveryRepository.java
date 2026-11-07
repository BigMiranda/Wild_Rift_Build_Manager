package com.ornnplanner.repo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rodada 9's tables: the meta builds the user keeps per champion (meta_build), the discovery results and the campaigns'
 * queue (build_discovery) and the guides (build_guide).
 */
@Repository
public class DiscoveryRepository {

    private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** A build someone plays (a site, a pro, a friend): items, boots and keystone, and where it came from. */
    public static class MetaBuild {
        public Long id;
        public String unitCode;
        public String name;
        public List<Long> items = new ArrayList<>();
        public Long boots;
        public String keystone;
        /** Where it came from ("site X, outubro/2026"). */
        public String source;
        public String createdAt;
    }

    private final JdbcTemplate jdbc;

    public DiscoveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static <T> T read(String s, Class<T> type) {
        try {
            return JSON.readValue(s, type);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ meta builds

    public List<MetaBuild> metaBuilds(String unitCode) {
        String sql = "SELECT * FROM meta_build" + (unitCode == null ? "" : " WHERE unit_code = ?") + " ORDER BY unit_code, name COLLATE NOCASE";
        Object[] args = unitCode == null ? new Object[0] : new Object[] {unitCode};
        return jdbc.query(sql, (rs, n) -> {
            MetaBuild m = new MetaBuild();
            m.id = rs.getLong("id");
            m.unitCode = rs.getString("unit_code");
            m.name = rs.getString("name");
            try {
                m.items = JSON.readValue(rs.getString("items"), new TypeReference<List<Long>>() { });
            } catch (Exception e) {
                m.items = new ArrayList<>();
            }
            long b = rs.getLong("boots");
            m.boots = rs.wasNull() ? null : b;
            m.keystone = rs.getString("keystone");
            m.source = rs.getString("source");
            m.createdAt = rs.getString("created_at");
            return m;
        }, args);
    }

    public MetaBuild saveMeta(MetaBuild m) {
        String now = Instant.now().toString();
        if (m.id != null && jdbc.update("UPDATE meta_build SET unit_code = ?, name = ?, items = ?, boots = ?, keystone = ?, source = ? WHERE id = ?",
                m.unitCode, m.name, write(m.items), m.boots, m.keystone, m.source, m.id) > 0) {
            return m;
        }
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO meta_build (unit_code, name, items, boots, keystone, source, created_at) "
                    + "VALUES (?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, m.unitCode);
            ps.setString(2, m.name);
            ps.setString(3, write(m.items));
            if (m.boots == null) {
                ps.setNull(4, java.sql.Types.INTEGER);
            } else {
                ps.setLong(4, m.boots);
            }
            ps.setString(5, m.keystone);
            ps.setString(6, m.source);
            ps.setString(7, now);
            return ps;
        }, kh);
        m.id = kh.getKey().longValue();
        m.createdAt = now;
        return m;
    }

    public void deleteMeta(long id) {
        jdbc.update("DELETE FROM meta_build WHERE id = ?", id);
    }

    // ------------------------------------------------------------------ discoveries and the campaigns' queue

    /** A row of build_discovery: one champion of a campaign (or a discovery run alone), its state and its result. */
    public static class DiscoveryRow {
        public Long id;
        public String patch;
        public String unitCode;
        public String champion;
        /** The configuration's hash: same champion and configuration = the same row (a campaign resumes it). */
        public String configKey;
        public String config;
        /** pendente | rodando | pausado | feito | erro | cancelado | pulado */
        public String status;
        public int priority;
        public String campaign;
        /** salvas | descoberta | matchups | fim: the champion's step (part 8). */
        public String step;
        public String result;
        public String error;
        public double seconds;
        public String createdAt;
        public String updatedAt;
    }

    public List<DiscoveryRow> discoveries(String campaign) {
        String sql = "SELECT * FROM build_discovery" + (campaign == null ? "" : " WHERE campaign = ?")
                + " ORDER BY priority DESC, id";
        Object[] args = campaign == null ? new Object[0] : new Object[] {campaign};
        return jdbc.query(sql, (rs, n) -> row(rs), args);
    }

    public DiscoveryRow discovery(long id) {
        List<DiscoveryRow> l = jdbc.query("SELECT * FROM build_discovery WHERE id = ?", (rs, n) -> row(rs), id);
        return l.isEmpty() ? null : l.get(0);
    }

    /** The newest finished result of a champion, for the quick analyses (null = none). */
    public DiscoveryRow latestDone(String unitCode, String patch) {
        List<DiscoveryRow> l = jdbc.query("SELECT * FROM build_discovery WHERE unit_code = ? AND patch = ? AND status = 'feito' "
                + "ORDER BY updated_at DESC LIMIT 1", (rs, n) -> row(rs), unitCode, patch);
        return l.isEmpty() ? null : l.get(0);
    }

    private static DiscoveryRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        DiscoveryRow r = new DiscoveryRow();
        r.id = rs.getLong("id");
        r.patch = rs.getString("patch");
        r.unitCode = rs.getString("unit_code");
        r.champion = rs.getString("champion");
        r.configKey = rs.getString("config_key");
        r.config = rs.getString("config");
        r.status = rs.getString("status");
        r.priority = rs.getInt("priority");
        r.campaign = rs.getString("campaign");
        r.step = rs.getString("step");
        r.result = rs.getString("result");
        r.error = rs.getString("error");
        r.seconds = rs.getDouble("seconds");
        r.createdAt = rs.getString("created_at");
        r.updatedAt = rs.getString("updated_at");
        return r;
    }

    public DiscoveryRow insertDiscovery(DiscoveryRow r) {
        String now = Instant.now().toString();
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO build_discovery (patch, unit_code, champion, config_key, config, status, "
                    + "priority, campaign, step, result, error, seconds, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, r.patch);
            ps.setString(2, r.unitCode);
            ps.setString(3, r.champion);
            ps.setString(4, r.configKey);
            ps.setString(5, r.config);
            ps.setString(6, r.status);
            ps.setInt(7, r.priority);
            ps.setString(8, r.campaign);
            ps.setString(9, r.step);
            ps.setString(10, r.result);
            ps.setString(11, r.error);
            ps.setDouble(12, r.seconds);
            ps.setString(13, now);
            ps.setString(14, now);
            return ps;
        }, kh);
        r.id = kh.getKey().longValue();
        r.createdAt = now;
        r.updatedAt = now;
        return r;
    }

    public void updateDiscovery(DiscoveryRow r) {
        r.updatedAt = Instant.now().toString();
        jdbc.update("UPDATE build_discovery SET status = ?, priority = ?, step = ?, result = ?, error = ?, seconds = ?, updated_at = ? WHERE id = ?",
                r.status, r.priority, r.step, r.result, r.error, r.seconds, r.updatedAt, r.id);
    }

    public void deleteDiscovery(long id) {
        jdbc.update("DELETE FROM build_discovery WHERE id = ?", id);
    }

    /** At startup a row left "rodando" by a stopped server goes back to "pendente" (the queue resumes it). */
    public int resetRunning() {
        return jdbc.update("UPDATE build_discovery SET status = 'pendente' WHERE status = 'rodando'");
    }

    // ------------------------------------------------------------------ guides

    /** A guide: its champion, its name and its tree of conditions and decisions (as JSON). */
    public static class GuideRow {
        public Long id;
        public String unitCode;
        public String name;
        public String tree;
        public String createdAt;
        public String updatedAt;
        /** Goes to the public version's export (Rodada 11). */
        public boolean publico;
    }

    public List<GuideRow> guides(String unitCode) {
        String sql = "SELECT * FROM build_guide" + (unitCode == null ? "" : " WHERE unit_code = ?") + " ORDER BY unit_code, name COLLATE NOCASE";
        Object[] args = unitCode == null ? new Object[0] : new Object[] {unitCode};
        return jdbc.query(sql, (rs, n) -> {
            GuideRow g = new GuideRow();
            g.id = rs.getLong("id");
            g.unitCode = rs.getString("unit_code");
            g.name = rs.getString("name");
            g.tree = rs.getString("tree");
            g.createdAt = rs.getString("created_at");
            g.updatedAt = rs.getString("updated_at");
            g.publico = rs.getInt("publico") == 1;
            return g;
        }, args);
    }

    public GuideRow guide(long id) {
        return guides(null).stream().filter(g -> g.id == id).findFirst().orElse(null);
    }

    public GuideRow saveGuide(GuideRow g) {
        String now = Instant.now().toString();
        g.updatedAt = now;
        if (g.id != null && jdbc.update("UPDATE build_guide SET unit_code = ?, name = ?, tree = ?, updated_at = ?, publico = ? WHERE id = ?",
                g.unitCode, g.name, g.tree, now, g.publico ? 1 : 0, g.id) > 0) {
            return g;
        }
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO build_guide (unit_code, name, tree, created_at, updated_at, publico) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, g.unitCode);
            ps.setString(2, g.name);
            ps.setString(3, g.tree);
            ps.setString(4, now);
            ps.setString(5, now);
            ps.setInt(6, g.publico ? 1 : 0);
            return ps;
        }, kh);
        g.id = kh.getKey().longValue();
        g.createdAt = now;
        return g;
    }

    public void deleteGuide(long id) {
        jdbc.update("DELETE FROM build_guide WHERE id = ?", id);
    }

    public static Map<String, Object> asMap(String json) {
        return json == null ? null : read(json, Map.class);
    }
}
