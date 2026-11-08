package com.ornnplanner.publico;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Takes the private builds out of what the export keeps (Rodada 11.b, part 1). The discovery's results remember the
 * champion's known builds (the user's saved ones, of any folder), its base build, the saved builds the campaign
 * measured and the rivals of the matchups; guides suggested from a discovery and combos may name them too.
 * <ul>
 *   <li>In {@code build_discovery}: the known builds of folders that are not public go whole (name, items, readings),
 *   so do the saved builds' rows; the base build's name becomes "build base (privada)"; a matchup's rival build keeps
 *   its champion and loses its name; the request's build goes.</li>
 *   <li>Then every text column of every table but the catalog's is swept: a private build's or folder's name inside
 *   any string becomes "build (privada)" (JSON is walked, so it stays valid), and a {@code buildId} that is private
 *   becomes null.</li>
 * </ul>
 * Pure: it reads and writes the copy's connection only. A private name equal to (or inside) a public build's, folder's
 * or guide's name, or a catalog name (item, champion), is published anyway and is left alone.
 */
final class PublicScrubber {

    static final String PRIVATE = "build (privada)";
    static final String PRIVATE_BASE = "build base (privada)";
    private static final ObjectMapper JSON = new ObjectMapper();
    /** The catalog's tables (shop, champions, levels): no build is named there. */
    private static final Set<String> CATALOG = Set.of("item", "item_passive", "item_stat", "item_component", "unit",
            "unit_stat", "stat_def", "living_forge_tier", "xp_level", "sqlite_stat1", "sqlite_sequence");
    private static final Set<String> ID_KEYS = Set.of("buildId", "build_id", "baseBuildId", "referenceBuildId");

    /** A build as the scrubber needs it. */
    static final class BuildRef {
        final long id;
        final String name;
        final String unit;
        final Set<Long> items;

        BuildRef(long id, String name, String unit, Collection<Long> items) {
            this.id = id;
            this.name = name;
            this.unit = unit;
            this.items = new HashSet<>(items);
        }
    }

    private final Map<Long, BuildRef> privateBuilds = new LinkedHashMap<>();
    private final List<BuildRef> publicBuilds;
    /** Every build in the summary order, to find a campaign's base (the champion's first saved build). */
    private final List<BuildRef> all;
    /** Private names to replace, longest first (one inside another is replaced whole). */
    private final List<String> names = new ArrayList<>();
    final Map<String, Integer> touched = new TreeMap<>();

    PublicScrubber(List<BuildRef> all, Set<Long> publicIds, Collection<String> privateFolders, Collection<String> keptNames) {
        this.all = all;
        this.publicBuilds = new ArrayList<>();
        Set<String> candidates = new HashSet<>(privateFolders);
        for (BuildRef b : all) {
            if (publicIds.contains(b.id)) {
                publicBuilds.add(b);
            } else {
                privateBuilds.put(b.id, b);
                candidates.add(b.name);
            }
        }
        for (String n : candidates) {
            if (n == null || n.trim().length() < 3) {
                continue;   // "AP", "X": no name to keep secret, and it would mangle any text
            }
            String t = n.trim();
            boolean published = keptNames.stream().anyMatch(k -> k != null && k.contains(t))
                    || publicBuilds.stream().anyMatch(b -> b.name != null && b.name.contains(t));
            if (!published) {
                names.add(t);
            }
        }
        names.sort(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()));
    }

    List<String> privateNames() {
        return names;
    }

    // ------------------------------------------------------------------ the discovery

    /** A discovery row's request: its build goes; a private build id is cleared. */
    String config(String config) {
        JsonNode n = parse(config);
        if (n == null) {
            return config;
        }
        dropBuild(n);
        if (n.path("discovery").isObject()) {
            dropBuild(n.path("discovery"));
        }
        return write(n);
    }

    private void dropBuild(JsonNode n) {
        ObjectNode o = (ObjectNode) n;
        if (o.has("build")) {
            o.putNull("build");
        }
    }

    /** The champion's base of a discovery: the request's build, else its first saved build (a campaign's). */
    Long baseOf(String config, String unit) {
        JsonNode n = parse(config);
        if (n != null) {
            for (JsonNode x : new JsonNode[] {n.path("buildId"), n.path("discovery").path("buildId")}) {
                if (x.canConvertToLong()) {
                    return x.asLong();
                }
            }
        }
        for (BuildRef b : all) {
            if (b.unit != null && b.unit.equals(unit)) {
                return b.id;
            }
        }
        return null;
    }

    /** A discovery's stored result: either {saved, discovery, matchups} (a campaign's) or {discovery} (one alone). */
    String result(String result, String unit, Long baseId) {
        JsonNode root = parse(result);
        if (root == null || !root.isObject()) {
            return result;
        }
        ObjectNode o = (ObjectNode) root;
        if (o.path("saved").isArray()) {
            ArrayNode saved = (ArrayNode) o.get("saved");
            for (Iterator<JsonNode> it = saved.elements(); it.hasNext(); ) {
                JsonNode s = it.next();
                if (!s.path("buildId").canConvertToLong() || privateBuilds.containsKey(s.path("buildId").asLong())) {
                    it.remove();
                    count("build_discovery.saved");
                }
            }
        }
        JsonNode d = o.path("discovery");
        if (d.isObject() && d.path("known").isArray()) {
            ArrayNode known = (ArrayNode) d.get("known");
            for (Iterator<JsonNode> it = known.elements(); it.hasNext(); ) {
                JsonNode k = it.next();
                if (!"meta".equals(k.path("origin").asText()) && !isPublic(k, unit)) {
                    it.remove();
                    count("build_discovery.known");
                }
            }
        }
        if (o.path("matchups").isArray()) {
            for (JsonNode m : o.get("matchups")) {
                String b = m.path("build").asText(null);
                if (b != null && m.isObject() && !isPublicName(b)) {
                    ((ObjectNode) m).put("build", PRIVATE);
                    count("build_discovery.matchups");
                }
            }
        }
        BuildRef base = baseId == null ? null : privateBuilds.get(baseId);
        return write(scrub(root, base == null ? null : base.name));
    }

    /** A known build that is a public build of the champion: the same name and its kit among that build's items. */
    private boolean isPublic(JsonNode known, String unit) {
        String label = known.path("label").asText("");
        for (BuildRef b : publicBuilds) {
            if (b.name.equals(label) && (unit == null || unit.equals(b.unit))) {
                boolean inside = true;
                for (JsonNode i : known.path("items")) {
                    inside &= b.items.contains(i.asLong());
                }
                if (inside) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isPublicName(String name) {
        return publicBuilds.stream().anyMatch(b -> b.name.equals(name));
    }

    // ------------------------------------------------------------------ the sweep

    /** Every text column of every table but the catalog's; returns how many values changed. */
    int sweep(Connection c) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        int changed = 0;
        for (String t : tables) {
            if (CATALOG.contains(t)) {
                continue;
            }
            List<String> cols = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(" + t + ")")) {
                while (rs.next()) {
                    String type = rs.getString("type");
                    if (type == null || type.isEmpty() || type.toUpperCase(java.util.Locale.ROOT).contains("TEXT")) {
                        cols.add(rs.getString("name"));
                    }
                }
            }
            for (String col : cols) {
                Map<Long, String> updates = new LinkedHashMap<>();
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT rowid, \"" + col + "\" FROM \"" + t + "\" WHERE \"" + col + "\" IS NOT NULL")) {
                    while (rs.next()) {
                        Object raw = rs.getObject(2);
                        if (!(raw instanceof String)) {
                            continue;
                        }
                        String v = (String) raw;
                        String w = text(v);
                        if (!w.equals(v)) {
                            updates.put(rs.getLong(1), w);
                        }
                    }
                } catch (SQLException e) {
                    continue;   // a WITHOUT ROWID table: nothing of ours
                }
                if (updates.isEmpty()) {
                    continue;
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE \"" + t + "\" SET \"" + col + "\" = ? WHERE rowid = ?")) {
                    for (Map.Entry<Long, String> e : updates.entrySet()) {
                        ps.setString(1, e.getValue());
                        ps.setLong(2, e.getKey());
                        ps.executeUpdate();
                    }
                }
                touched.merge(t + "." + col, updates.size(), Integer::sum);
                changed += updates.size();
            }
        }
        return changed;
    }

    /** A value: JSON is walked (and stays valid), anything else is plain text. */
    String text(String v) {
        String s = v.trim();
        if ((s.startsWith("{") && s.endsWith("}")) || (s.startsWith("[") && s.endsWith("]"))) {
            JsonNode n = parse(v);
            if (n != null) {
                String before = write(n);
                String after = write(scrub(n, null));
                return before.equals(after) ? v : after;
            }
        }
        return replace(v, null);
    }

    /** Walks a tree: names in strings (and in keys) replaced, private build ids cleared. */
    JsonNode scrub(JsonNode n, String baseName) {
        if (n.isObject()) {
            ObjectNode o = (ObjectNode) n;
            List<String> keys = new ArrayList<>();
            o.fieldNames().forEachRemaining(keys::add);
            for (String k : keys) {
                JsonNode v = o.get(k);
                if (ID_KEYS.contains(k) && v.canConvertToLong() && privateBuilds.containsKey(v.asLong())) {
                    o.putNull(k);
                    continue;
                }
                JsonNode w = v.isTextual() ? TextNode.valueOf(replace(v.asText(), baseName)) : scrub(v, baseName);
                String nk = replace(k, baseName);
                if (!nk.equals(k)) {
                    o.remove(k);
                }
                o.set(nk, w);
            }
        } else if (n.isArray()) {
            ArrayNode a = (ArrayNode) n;
            for (int i = 0; i < a.size(); i++) {
                JsonNode v = a.get(i);
                a.set(i, v.isTextual() ? TextNode.valueOf(replace(v.asText(), baseName)) : scrub(v, baseName));
            }
        }
        return n;
    }

    /** The private names inside a string, the base's first. */
    String replace(String s, String baseName) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        // placeholders first, so a short name is never found inside a replacement of a longer one
        String out = s;
        boolean any = false;
        if (baseName != null && names.contains(baseName.trim()) && out.contains(baseName.trim())) {
            out = out.replace(baseName.trim(), "\u0000B\u0000");
            any = true;
        }
        for (String n : names) {
            if (out.contains(n)) {
                out = out.replace(n, "\u0000P\u0000");
                any = true;
            }
        }
        return any ? out.replace("\u0000B\u0000", PRIVATE_BASE).replace("\u0000P\u0000", PRIVATE) : s;
    }

    private void count(String what) {
        touched.merge(what, 1, Integer::sum);
    }

    private static JsonNode parse(String s) {
        if (s == null) {
            return null;
        }
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String write(JsonNode n) {
        try {
            return JSON.writeValueAsString(n);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
