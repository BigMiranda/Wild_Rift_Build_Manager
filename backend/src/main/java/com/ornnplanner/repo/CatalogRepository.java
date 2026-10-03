package com.ornnplanner.repo;

import com.ornnplanner.engine.GoldPricing.StatDef;
import com.ornnplanner.engine.Model.ComponentRef;
import com.ornnplanner.engine.Model.ItemDef;
import com.ornnplanner.engine.Model.PassiveText;
import com.ornnplanner.engine.Model.RefScope;
import com.ornnplanner.engine.Model.StatLine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Items, their stat lines, recipes and stat definitions. */
@Repository
public class CatalogRepository {

    private final JdbcTemplate jdbc;

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int countItems() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM item", Integer.class);
        return n == null ? 0 : n;
    }

    /** All items keyed by id, with stats and components, in id order. */
    public Map<Long, ItemDef> findAllItems() {
        Map<Long, ItemDef> items = new LinkedHashMap<>();
        jdbc.query("SELECT * FROM item ORDER BY id", rs -> {
            ItemDef i = mapItem(rs);
            items.put(i.id, i);
        });
        jdbc.query("SELECT * FROM item_stat ORDER BY item_id, seq", rs -> {
            ItemDef i = items.get(rs.getLong("item_id"));
            if (i != null) {
                i.stats.add(mapStat(rs));
            }
        });
        jdbc.query("SELECT * FROM item_passive ORDER BY item_id, seq", rs -> {
            ItemDef i = items.get(rs.getLong("item_id"));
            if (i != null) {
                i.passives.add(new PassiveText(rs.getString("name"), rs.getString("text")));
            }
        });
        jdbc.query("SELECT * FROM item_component ORDER BY item_id, component_id", rs -> {
            ItemDef i = items.get(rs.getLong("item_id"));
            if (i != null) {
                i.components.add(new ComponentRef(rs.getLong("component_id"), rs.getInt("quantity")));
            }
        });
        return items;
    }

    public Optional<ItemDef> findItem(long id) {
        return Optional.ofNullable(findAllItems().get(id));
    }

    /** Item row only (no stats / components). */
    public Optional<ItemDef> findItemHeader(long id) {
        List<ItemDef> list = jdbc.query("SELECT * FROM item WHERE id = ?", (rs, n) -> mapItem(rs), id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<Long> findItemIdByName(String name) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM item WHERE name = ?", Long.class, name);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    public boolean isEdited(long id) {
        Integer e = jdbc.queryForObject("SELECT edited FROM item WHERE id = ?", Integer.class, id);
        return e != null && e == 1;
    }

    public long insertItem(ItemDef item) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO item (name, cost, category, image, source_patch, edited, section, active, marker, summary, "
                            + "item_group, capture) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, item.name);
            ps.setInt(2, item.cost);
            ps.setString(3, categoryOf(item));
            ps.setString(4, item.image);
            ps.setString(5, item.sourcePatch);
            ps.setInt(6, item.edited ? 1 : 0);
            ps.setString(7, item.section);
            ps.setInt(8, item.active ? 1 : 0);
            ps.setString(9, item.marker);
            ps.setString(10, item.summary);
            ps.setString(11, item.group == null ? item.name : item.group);
            ps.setString(12, item.capture);
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        replaceStats(id, item.stats);
        replacePassives(id, item.passives);
        return id;
    }

    public void updateItem(long id, ItemDef item) {
        jdbc.update("UPDATE item SET name = ?, cost = ?, category = ?, image = ?, source_patch = ?, edited = ?, section = ?, "
                        + "active = ?, marker = ?, summary = ?, item_group = ?, capture = ? WHERE id = ?",
                item.name, item.cost, categoryOf(item), item.image, item.sourcePatch, item.edited ? 1 : 0, item.section,
                item.active ? 1 : 0, item.marker, item.summary, item.group == null ? item.name : item.group, item.capture, id);
        replaceStats(id, item.stats);
        replacePassives(id, item.passives);
    }

    /** Tabs are stored joined in `category`; an item edited without tabs keeps whatever category it was given. */
    private static String categoryOf(ItemDef item) {
        return item.tabs != null && !item.tabs.isEmpty() ? String.join(", ", item.tabs) : item.category;
    }

    public void replacePassives(long itemId, List<PassiveText> passives) {
        jdbc.update("DELETE FROM item_passive WHERE item_id = ?", itemId);
        int seq = 0;
        for (PassiveText p : passives == null ? List.<PassiveText>of() : passives) {
            jdbc.update("INSERT INTO item_passive (item_id, seq, name, text) VALUES (?,?,?,?)", itemId, seq++, p.name, p.text);
        }
    }

    public void replaceStats(long itemId, List<StatLine> stats) {
        jdbc.update("DELETE FROM item_stat WHERE item_id = ?", itemId);
        int seq = 0;
        for (StatLine s : stats) {
            jdbc.update("INSERT INTO item_stat (item_id, seq, stat_type, value, passive, ratio, ref, ref_type, ref_scope, "
                            + "value_max, conditional) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    itemId, seq++, s.type, s.value, s.passive, s.ratio, s.ref, s.refType,
                    (s.refScope == null ? RefScope.TOTAL : s.refScope).name(), s.valueMax, s.conditional ? 1 : 0);
        }
    }

    public void replaceComponents(long itemId, List<ComponentRef> components) {
        jdbc.update("DELETE FROM item_component WHERE item_id = ?", itemId);
        for (ComponentRef c : components) {
            jdbc.update("INSERT INTO item_component (item_id, component_id, quantity) VALUES (?,?,?)",
                    itemId, c.itemId, Math.max(1, c.quantity));
        }
    }

    public int countBuildsUsingItem(long itemId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM build_step WHERE item_id = ?", Integer.class, itemId);
        return n == null ? 0 : n;
    }

    public void deleteItem(long id) {
        jdbc.update("DELETE FROM item_component WHERE component_id = ?", id);
        jdbc.update("DELETE FROM item WHERE id = ?", id);
    }

    // ------------------------------------------------------------ stat defs

    public List<StatDef> findStatDefs() {
        return jdbc.query("SELECT * FROM stat_def ORDER BY seq", (rs, n) -> {
            StatDef d = new StatDef();
            d.name = rs.getString("name");
            d.seq = rs.getInt("seq");
            d.category = rs.getString("category");
            d.baseType = rs.getString("base_type");
            d.baseItem = rs.getString("base_item");
            d.fixedPrice = nullableDouble(rs, "fixed_price");
            d.alias = rs.getString("alias");
            d.relevant = rs.getInt("relevant") == 1;
            return d;
        });
    }

    public void upsertStatDef(StatDef d) {
        jdbc.update("INSERT INTO stat_def (name, seq, category, base_type, base_item, fixed_price, alias, relevant) "
                        + "VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(name) DO UPDATE SET seq = excluded.seq, "
                        + "category = excluded.category, base_type = excluded.base_type, base_item = excluded.base_item, "
                        + "fixed_price = excluded.fixed_price, alias = excluded.alias, relevant = excluded.relevant",
                d.name, d.seq, d.category, d.baseType, d.baseItem, d.fixedPrice, d.alias, d.relevant ? 1 : 0);
    }

    public int countStatDefs() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM stat_def", Integer.class);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------ mapping

    private static ItemDef mapItem(ResultSet rs) throws SQLException {
        ItemDef i = new ItemDef();
        i.id = rs.getLong("id");
        i.name = rs.getString("name");
        i.cost = rs.getInt("cost");
        i.category = rs.getString("category");
        i.image = rs.getString("image");
        i.sourcePatch = rs.getString("source_patch");
        i.edited = rs.getInt("edited") == 1;
        i.section = rs.getString("section");
        i.active = rs.getInt("active") == 1;
        i.marker = rs.getString("marker");
        i.summary = rs.getString("summary");
        i.group = rs.getString("item_group");
        i.capture = rs.getString("capture");
        if (i.category != null && !i.category.isBlank()) {
            i.tabs = new java.util.ArrayList<>(Arrays.asList(i.category.split("\\s*,\\s*")));
        }
        return i;
    }

    private static StatLine mapStat(ResultSet rs) throws SQLException {
        StatLine s = new StatLine();
        s.type = rs.getString("stat_type");
        s.value = nullableDouble(rs, "value");
        s.passive = rs.getString("passive");
        s.ratio = nullableDouble(rs, "ratio");
        s.ref = nullableDouble(rs, "ref");
        s.refType = rs.getString("ref_type");
        String scope = rs.getString("ref_scope");
        s.refScope = scope == null ? RefScope.TOTAL : RefScope.valueOf(scope);
        s.valueMax = nullableDouble(rs, "value_max");
        s.conditional = rs.getInt("conditional") == 1;
        return s;
    }

    static Double nullableDouble(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }
}
