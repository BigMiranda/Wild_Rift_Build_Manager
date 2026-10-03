package com.ornnplanner.repo;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Folders (single level) and saved builds. */
@Repository
public class BuildRepository {

    public static class Folder {
        public long id;
        public String name;
        public String createdAt;
    }

    /** Ragdoll stat as typed by the user; null = left blank. */
    public static class RagdollStat {
        public Double base;
        public Double growth;
    }

    /** One purchase of a build. */
    public static class Step {
        public long itemId;
        /** Count this item's conditional effects (stacks, in combat, low health...). */
        public boolean includeConditional = true;

        public Step() {
        }

        public Step(long itemId, boolean includeConditional) {
            this.itemId = itemId;
            this.includeConditional = includeConditional;
        }
    }

    public static class Build {
        public Long id;
        public long folderId;
        public String name;
        public String note;
        public String unitCode;
        public double goldPerMin;
        public double xpPerMin;
        public List<Step> steps = new ArrayList<>();
        public Map<String, RagdollStat> ragdollStats = new LinkedHashMap<>();
        public String createdAt;
        public String updatedAt;
    }

    private final JdbcTemplate jdbc;

    public BuildRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------ folders

    public List<Folder> findFolders() {
        return jdbc.query("SELECT * FROM folder ORDER BY name COLLATE NOCASE", (rs, n) -> {
            Folder f = new Folder();
            f.id = rs.getLong("id");
            f.name = rs.getString("name");
            f.createdAt = rs.getString("created_at");
            return f;
        });
    }

    public long insertFolder(String name) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("INSERT INTO folder (name, created_at) VALUES (?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, Instant.now().toString());
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public boolean renameFolder(long id, String name) {
        return jdbc.update("UPDATE folder SET name = ? WHERE id = ?", name, id) > 0;
    }

    public int countBuildsInFolder(long id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM build WHERE folder_id = ?", Integer.class, id);
        return n == null ? 0 : n;
    }

    public boolean deleteFolder(long id) {
        return jdbc.update("DELETE FROM folder WHERE id = ?", id) > 0;
    }

    public boolean folderExists(long id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM folder WHERE id = ?", Integer.class, id);
        return n != null && n > 0;
    }

    // ------------------------------------------------------------ builds

    /** Build headers (no items / ragdoll stats) for the folder tree. */
    public List<Build> findBuildSummaries() {
        return jdbc.query("SELECT * FROM build ORDER BY name COLLATE NOCASE", (rs, n) -> mapBuild(rs));
    }

    public Optional<Build> findBuild(long id) {
        List<Build> list = jdbc.query("SELECT * FROM build WHERE id = ?", (rs, n) -> mapBuild(rs), id);
        if (list.isEmpty()) {
            return Optional.empty();
        }
        Build b = list.get(0);
        b.steps = jdbc.query("SELECT item_id, include_conditional FROM build_step WHERE build_id = ? ORDER BY seq",
                (rs, n) -> new Step(rs.getLong("item_id"), rs.getInt("include_conditional") == 1), id);
        jdbc.query("SELECT * FROM build_ragdoll_stat WHERE build_id = ? ORDER BY rowid", rs -> {
            RagdollStat s = new RagdollStat();
            s.base = CatalogRepository.nullableDouble(rs, "base");
            s.growth = CatalogRepository.nullableDouble(rs, "growth");
            b.ragdollStats.put(rs.getString("stat"), s);
        }, id);
        return Optional.of(b);
    }

    public long insertBuild(Build b) {
        String now = Instant.now().toString();
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO build (folder_id, name, note, unit_code, gold_per_min, xp_per_min, created_at, updated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, b.folderId);
            ps.setString(2, b.name);
            ps.setString(3, b.note);
            ps.setString(4, b.unitCode);
            ps.setDouble(5, b.goldPerMin);
            ps.setDouble(6, b.xpPerMin);
            ps.setString(7, now);
            ps.setString(8, now);
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        writeChildren(id, b);
        return id;
    }

    public boolean updateBuild(long id, Build b) {
        int n = jdbc.update("UPDATE build SET folder_id = ?, name = ?, note = ?, unit_code = ?, gold_per_min = ?, "
                        + "xp_per_min = ?, updated_at = ? WHERE id = ?",
                b.folderId, b.name, b.note, b.unitCode, b.goldPerMin, b.xpPerMin, Instant.now().toString(), id);
        if (n == 0) {
            return false;
        }
        writeChildren(id, b);
        return true;
    }

    public boolean moveBuild(long id, long folderId) {
        return jdbc.update("UPDATE build SET folder_id = ?, updated_at = ? WHERE id = ?",
                folderId, Instant.now().toString(), id) > 0;
    }

    public boolean deleteBuild(long id) {
        return jdbc.update("DELETE FROM build WHERE id = ?", id) > 0;
    }

    private void writeChildren(long id, Build b) {
        jdbc.update("DELETE FROM build_step WHERE build_id = ?", id);
        for (int i = 0; i < b.steps.size(); i++) {
            Step s = b.steps.get(i);
            jdbc.update("INSERT INTO build_step (build_id, seq, item_id, include_conditional) VALUES (?,?,?,?)",
                    id, i, s.itemId, s.includeConditional ? 1 : 0);
        }
        jdbc.update("DELETE FROM build_ragdoll_stat WHERE build_id = ?", id);
        if (b.ragdollStats != null) {
            b.ragdollStats.forEach((stat, s) -> jdbc.update(
                    "INSERT INTO build_ragdoll_stat (build_id, stat, base, growth) VALUES (?,?,?,?)",
                    id, stat, s == null ? null : s.base, s == null ? null : s.growth));
        }
    }

    private static Build mapBuild(ResultSet rs) throws SQLException {
        Build b = new Build();
        b.id = rs.getLong("id");
        b.folderId = rs.getLong("folder_id");
        b.name = rs.getString("name");
        b.note = rs.getString("note");
        b.unitCode = rs.getString("unit_code");
        b.goldPerMin = rs.getDouble("gold_per_min");
        b.xpPerMin = rs.getDouble("xp_per_min");
        b.createdAt = rs.getString("created_at");
        b.updatedAt = rs.getString("updated_at");
        return b;
    }
}
