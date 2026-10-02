package com.ornnplanner.repo;

import com.ornnplanner.engine.Model.ForgeTier;
import com.ornnplanner.engine.Model.StatGrowth;
import com.ornnplanner.engine.Model.UnitProfile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/** Units (Ornn / ragdoll), Living Forge tiers and the XP-per-level table. */
@Repository
public class ReferenceRepository {

    private final JdbcTemplate jdbc;

    public ReferenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<UnitProfile> findUnits() {
        List<UnitProfile> units = jdbc.query("SELECT * FROM unit ORDER BY code", (rs, n) -> {
            UnitProfile u = new UnitProfile();
            u.code = rs.getString("code");
            u.name = rs.getString("name");
            u.livingForge = rs.getInt("living_forge") == 1;
            return u;
        });
        for (UnitProfile u : units) {
            jdbc.query("SELECT * FROM unit_stat WHERE unit_code = ? ORDER BY rowid", rs -> {
                u.stats.put(rs.getString("stat"), new StatGrowth(rs.getDouble("base"), rs.getDouble("growth")));
            }, u.code);
        }
        return units;
    }

    public Optional<UnitProfile> findUnit(String code) {
        return findUnits().stream().filter(u -> u.code.equals(code)).findFirst();
    }

    public void upsertUnit(UnitProfile u) {
        jdbc.update("INSERT INTO unit (code, name, living_forge) VALUES (?,?,?) ON CONFLICT(code) DO UPDATE SET "
                + "name = excluded.name, living_forge = excluded.living_forge", u.code, u.name, u.livingForge ? 1 : 0);
        jdbc.update("DELETE FROM unit_stat WHERE unit_code = ?", u.code);
        u.stats.forEach((stat, g) -> jdbc.update(
                "INSERT INTO unit_stat (unit_code, stat, base, growth) VALUES (?,?,?,?)", u.code, stat, g.base, g.growth));
    }

    public List<ForgeTier> findForgeTiers() {
        return jdbc.query("SELECT * FROM living_forge_tier ORDER BY min_level",
                (rs, n) -> new ForgeTier(rs.getInt("min_level"), rs.getDouble("pct")));
    }

    public void replaceForgeTiers(List<ForgeTier> tiers) {
        jdbc.update("DELETE FROM living_forge_tier");
        for (ForgeTier t : tiers) {
            jdbc.update("INSERT INTO living_forge_tier (min_level, pct) VALUES (?,?)", t.minLevel, t.pct);
        }
    }

    public NavigableMap<Integer, Double> findXpTable() {
        NavigableMap<Integer, Double> m = new TreeMap<>();
        jdbc.query("SELECT * FROM xp_level ORDER BY level", rs -> {
            m.put(rs.getInt("level"), rs.getDouble("cumulative_xp"));
        });
        return m;
    }

    public void replaceXpTable(NavigableMap<Integer, Double> table) {
        jdbc.update("DELETE FROM xp_level");
        table.forEach((level, xp) -> jdbc.update("INSERT INTO xp_level (level, cumulative_xp) VALUES (?,?)", level, xp));
    }
}
