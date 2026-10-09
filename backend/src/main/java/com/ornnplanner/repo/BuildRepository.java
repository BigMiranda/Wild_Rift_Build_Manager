package com.ornnplanner.repo;

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

    /** One entry of a build's purchase sequence: an item purchase, or a purchase moment (kind "moment"). */
    public static class Step {
        public static final String MOMENT = "moment";

        /** "item" (default) or "moment". */
        public String kind;
        public Long itemId;
        /** Count this item's conditional effects (stacks, in combat, low health...). */
        public boolean includeConditional = true;
        /** Moment: exact game minute, or minutes after the previous purchase (one of the two). */
        public Double atMinute;
        public Double afterMinutes;

        public Step() {
        }

        public Step(long itemId, boolean includeConditional) {
            this.itemId = itemId;
            this.includeConditional = includeConditional;
        }

        public boolean moment() {
            return MOMENT.equals(kind);
        }
    }

    /** From a game minute on (until the next period), so many activations / stacks per minute. */
    public static class RatePeriod {
        public double start;
        public double perMinute;
    }

    /** Gold earned at a game minute. */
    public static class GoldEvent {
        public double minute;
        public double gold;
    }

    /** Rune page: 1 keystone, one rune per row of the primary tree, one rune of the secondary tree. */
    public static class RunePage {
        public String primary;
        public String secondary;
        public String keystone;
        /** Rows 1..3 of the primary tree (null = not chosen). */
        public List<String> primaryRunes = new ArrayList<>();
        public String secondaryRune;
        /** Rune name -> option value (enemies nearby, stacks...). */
        public Map<String, Integer> options = new LinkedHashMap<>();
        /** Rune name -> count its conditional effects (default true). */
        public Map<String, Boolean> conditional = new LinkedHashMap<>();
        /**
         * Rune name -> activations / stacks per minute, in periods (runes that scale without limit): each period
         * from its start minute until the next one.
         */
        public Map<String, List<RatePeriod>> rates = new LinkedHashMap<>();

        /** Reads the periods; a plain number (builds saved before periods existed) is one period from 0:00. */
        @com.fasterxml.jackson.annotation.JsonSetter("rates")
        public void readRates(Map<String, Object> raw) {
            rates = new LinkedHashMap<>();
            if (raw == null) {
                return;
            }
            raw.forEach((rune, v) -> {
                List<RatePeriod> list = new ArrayList<>();
                if (v instanceof Number) {
                    RatePeriod p = new RatePeriod();
                    p.perMinute = ((Number) v).doubleValue();
                    list.add(p);
                } else if (v instanceof List) {
                    for (Object o : (List<?>) v) {
                        if (o instanceof Map) {
                            Map<?, ?> m = (Map<?, ?>) o;
                            RatePeriod p = new RatePeriod();
                            p.start = m.get("start") instanceof Number ? ((Number) m.get("start")).doubleValue() : 0;
                            p.perMinute = m.get("perMinute") instanceof Number ? ((Number) m.get("perMinute")).doubleValue() : 0;
                            list.add(p);
                        }
                    }
                }
                rates.put(rune, list);
            });
        }
        /** Rune name -> gold it earned, at given minutes (runes that earn gold). */
        public Map<String, List<GoldEvent>> gold = new LinkedHashMap<>();

        /** Chosen runes, keystone first. */
        public List<String> chosen() {
            List<String> out = new ArrayList<>();
            if (keystone != null) {
                out.add(keystone);
            }
            for (String r : primaryRunes) {
                if (r != null) {
                    out.add(r);
                }
            }
            if (secondaryRune != null) {
                out.add(secondaryRune);
            }
            return out;
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
        /** Assume the missing half items of each purchase are bought before it, as gold allows. */
        public boolean assumeHalfItems;
        /** Assume the missing smaller (basic) items of each purchase are bought before it. */
        public boolean assumeSmallItems;
        public List<Step> steps = new ArrayList<>();
        public RunePage runePage;
        /** Game minute the match ended (optional): the timeline goes up to it. */
        public Double matchEnd;
        /** Two summoner spells (names). */
        public List<String> spells = new ArrayList<>();
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
        java.util.TreeMap<Integer, Step> bySeq = new java.util.TreeMap<>();
        jdbc.query("SELECT seq, item_id, include_conditional FROM build_step WHERE build_id = ?", rs -> {
            bySeq.put(rs.getInt("seq"), new Step(rs.getLong("item_id"), rs.getInt("include_conditional") == 1));
        }, id);
        jdbc.query("SELECT seq, at_minute, after_minutes FROM build_moment WHERE build_id = ?", rs -> {
            Step m = new Step();
            m.kind = Step.MOMENT;
            m.atMinute = CatalogRepository.nullableDouble(rs, "at_minute");
            m.afterMinutes = CatalogRepository.nullableDouble(rs, "after_minutes");
            bySeq.put(rs.getInt("seq"), m);
        }, id);
        b.steps = new ArrayList<>(bySeq.values());
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
                    "INSERT INTO build (folder_id, name, note, unit_code, gold_per_min, xp_per_min, created_at, updated_at, "
                            + "assume_half_items, assume_small_items, rune_page, spells, match_end) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, b.folderId);
            ps.setString(2, b.name);
            ps.setString(3, b.note);
            ps.setString(4, b.unitCode);
            ps.setDouble(5, b.goldPerMin);
            ps.setDouble(6, b.xpPerMin);
            ps.setString(7, now);
            ps.setString(8, now);
            ps.setInt(9, b.assumeHalfItems ? 1 : 0);
            ps.setInt(10, b.assumeSmallItems ? 1 : 0);
            ps.setString(11, json(b.runePage));
            ps.setString(12, json(b.spells));
            if (b.matchEnd == null) {
                ps.setNull(13, java.sql.Types.REAL);
            } else {
                ps.setDouble(13, b.matchEnd);
            }
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        writeChildren(id, b);
        return id;
    }

    public boolean updateBuild(long id, Build b) {
        int n = jdbc.update("UPDATE build SET folder_id = ?, name = ?, note = ?, unit_code = ?, gold_per_min = ?, "
                        + "xp_per_min = ?, assume_half_items = ?, assume_small_items = ?, rune_page = ?, spells = ?, "
                        + "match_end = ?, updated_at = ? WHERE id = ?",
                b.folderId, b.name, b.note, b.unitCode, b.goldPerMin, b.xpPerMin, b.assumeHalfItems ? 1 : 0,
                b.assumeSmallItems ? 1 : 0, json(b.runePage), json(b.spells), b.matchEnd, Instant.now().toString(), id);
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
        jdbc.update("DELETE FROM build_moment WHERE build_id = ?", id);
        for (int i = 0; i < b.steps.size(); i++) {
            Step s = b.steps.get(i);
            if (s.moment()) {
                jdbc.update("INSERT INTO build_moment (build_id, seq, at_minute, after_minutes) VALUES (?,?,?,?)",
                        id, i, s.atMinute, s.afterMinutes);
            } else {
                jdbc.update("INSERT INTO build_step (build_id, seq, item_id, include_conditional) VALUES (?,?,?,?)",
                        id, i, s.itemId, s.includeConditional ? 1 : 0);
            }
        }
        jdbc.update("DELETE FROM build_ragdoll_stat WHERE build_id = ?", id);
        if (b.ragdollStats != null) {
            b.ragdollStats.forEach((stat, s) -> jdbc.update(
                    "INSERT INTO build_ragdoll_stat (build_id, stat, base, growth) VALUES (?,?,?,?)",
                    id, stat, s == null ? null : s.base, s == null ? null : s.growth));
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String json(Object o) {
        try {
            return o == null ? null : JSON.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
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
        b.assumeHalfItems = rs.getInt("assume_half_items") != 0;
        b.assumeSmallItems = rs.getInt("assume_small_items") != 0;
        b.matchEnd = CatalogRepository.nullableDouble(rs, "match_end");
        try {
            String page = rs.getString("rune_page");
            b.runePage = page == null ? null : JSON.readValue(page, RunePage.class);
            String spells = rs.getString("spells");
            b.spells = spells == null ? new ArrayList<>()
                    : JSON.readValue(spells, JSON.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            throw new SQLException("Página de runas inválida na build " + b.id, e);
        }
        b.createdAt = rs.getString("created_at");
        b.updatedAt = rs.getString("updated_at");
        return b;
    }
}
