package com.ornnplanner.arena.draft;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rodada 20, Parte 2: the voice reader's nicknames: the seed {@code apelidos_7_3f.yml} with the user's edits on top
 * (table {@code draft_apelido}; an empty target removes a seed nickname). Keys are compared without accents or case.
 */
@Component
public class VoiceAliases {

    /** One nickname: what is said, what it means, of which kind (campeoes, feiticos, runas, itens, rotas...). */
    public static class Alias {
        public String alias;
        public String target;
        public String kind;
        /** seed | usuario */
        public String source;

        public Alias() {
        }

        Alias(String alias, String target, String kind, String source) {
            this.alias = alias;
            this.target = target;
            this.kind = kind;
            this.source = source;
        }
    }

    private final Map<String, Map<String, String>> seed = new LinkedHashMap<>();
    private final JdbcTemplate jdbc;

    @SuppressWarnings("unchecked")
    public VoiceAliases(JdbcTemplate jdbc, @Value("${planner.seed.aliases:classpath:seed/apelidos_7_3f.yml}") Resource file) {
        this.jdbc = jdbc;
        Map<String, Object> root;
        try (InputStream in = file.getInputStream()) {
            root = new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + file, e);
        }
        Map<String, Object> all = (Map<String, Object>) root.getOrDefault("apelidos", Map.of());
        all.forEach((kind, v) -> {
            Map<String, String> m = new LinkedHashMap<>();
            ((Map<Object, Object>) v).forEach((k, t) -> m.put(key(String.valueOf(k)), String.valueOf(t)));
            seed.put(kind, m);
        });
    }

    /** The seed alone, by kind (for tests). */
    public Map<String, Map<String, String>> seed() {
        return seed;
    }

    /** Every nickname in force: the seed's, then the user's edits (which replace or remove a seed one). */
    public List<Alias> all() {
        Map<String, Alias> out = new LinkedHashMap<>();
        seed.forEach((kind, m) -> m.forEach((a, t) -> out.put(kind + "|" + a, new Alias(a, t, kind, "seed"))));
        for (Map<String, Object> row : jdbc.queryForList("SELECT alias, target, kind FROM draft_apelido ORDER BY alias")) {
            String kind = row.get("kind") == null ? "campeoes" : String.valueOf(row.get("kind"));
            // stored as "kind|alias", so the same word can mean different things in two kinds
            String stored = String.valueOf(row.get("alias"));
            String a = stored.contains("|") ? stored.substring(stored.indexOf('|') + 1) : key(stored);
            String target = String.valueOf(row.get("target"));
            if (target.isBlank()) {
                out.remove(kind + "|" + a);
            } else {
                out.put(kind + "|" + a, new Alias(a, target, kind, "usuario"));
            }
        }
        return new ArrayList<>(out.values());
    }

    /** Adds or replaces a nickname of the user. */
    public void put(Alias a) {
        if (a.alias == null || a.alias.isBlank()) {
            throw new IllegalArgumentException("Apelido vazio.");
        }
        String kind = a.kind == null || a.kind.isBlank() ? "campeoes" : a.kind;
        jdbc.update("INSERT INTO draft_apelido (alias, target, kind) VALUES (?, ?, ?) "
                + "ON CONFLICT(alias) DO UPDATE SET target = excluded.target, kind = excluded.kind",
                kind + "|" + key(a.alias), a.target == null ? "" : a.target, kind);
    }

    /** Removes a nickname: the user's row goes; a seed one is hidden by an empty row. */
    public void remove(String kind, String alias) {
        String k = (kind == null || kind.isBlank() ? "campeoes" : kind) + "|" + key(alias);
        jdbc.update("DELETE FROM draft_apelido WHERE alias = ?", k);
        Map<String, String> m = seed.get(kind);
        if (m != null && m.containsKey(key(alias))) {
            jdbc.update("INSERT INTO draft_apelido (alias, target, kind) VALUES (?, '', ?)", k, kind);
        }
    }

    /** Back to the seed: the user's edits are dropped. */
    public void reset() {
        jdbc.update("DELETE FROM draft_apelido");
    }

    /** Without accents, lower case, single spaces: how the reader compares. */
    public static String key(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9' ]", " ").replaceAll("\\s+", " ").trim();
    }
}
