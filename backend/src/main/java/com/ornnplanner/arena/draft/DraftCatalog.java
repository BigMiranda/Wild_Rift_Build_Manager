package com.ornnplanner.arena.draft;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rodada 20: the draft seed ({@code draft_7_3f.yml}): the formats (order of bans and picks, EST until the user confirms
 * Wild Rift's), the Fearless rule, the lanes and the weights of the draft notes.
 */
@Component
public class DraftCatalog {

    /** One action of a draft in order: a ban or a pick of a side, its index among that side's bans or picks. */
    public static class Turn {
        public String action;
        public String side;
        public int index;
        public boolean simultaneous;

        Turn(String action, String side, int index, boolean simultaneous) {
            this.action = action;
            this.side = side;
            this.index = index;
            this.simultaneous = simultaneous;
        }
    }

    public static class Format {
        public String key;
        public String name;
        public String source;
        public List<Turn> turns = new ArrayList<>();
        public int bansPerSide(String side) {
            return (int) turns.stream().filter(t -> "ban".equals(t.action) && t.side.equals(side)).count();
        }
    }

    private final Map<String, Object> seed;
    private final Map<String, Format> formats = new LinkedHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public DraftCatalog(@Value("${planner.seed.draft:classpath:seed/draft_7_3f.yml}") Resource file) {
        try (InputStream in = file.getInputStream()) {
            this.seed = new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + file, e);
        }
        readFormats();
    }

    /** For tests: a seed already read. */
    public DraftCatalog(Map<String, Object> seed) {
        this.seed = seed;
        readFormats();
    }

    @SuppressWarnings("unchecked")
    private void readFormats() {
        Map<String, Object> fs = (Map<String, Object>) seed.getOrDefault("formatos", Map.of());
        fs.forEach((key, v) -> {
            Map<String, Object> f = (Map<String, Object>) v;
            Format out = new Format();
            out.key = key;
            out.name = String.valueOf(f.getOrDefault("nome", key));
            out.source = f.get("fonte") == null ? null : String.valueOf(f.get("fonte"));
            Map<String, Integer> count = new LinkedHashMap<>();
            for (Map<String, Object> p : (List<Map<String, Object>>) f.getOrDefault("passos", List.of())) {
                String action = "ban".equals(p.get("acao")) ? "ban" : "escolha";
                String side = LiveGameModel.RED.equals(p.get("time")) ? LiveGameModel.RED : LiveGameModel.BLUE;
                int n = p.get("qtd") == null ? 1 : ((Number) p.get("qtd")).intValue();
                boolean sim = Boolean.TRUE.equals(p.get("simultaneo"));
                for (int i = 0; i < n; i++) {
                    String k = action + "|" + side;
                    int idx = count.merge(k, 1, Integer::sum) - 1;
                    out.turns.add(new Turn(action, side, idx, sim));
                }
            }
            formats.put(key, out);
        });
    }

    public Map<String, Object> seed() {
        return seed;
    }

    public Map<String, Format> formats() {
        return formats;
    }

    public Format format(String key) {
        Format f = formats.get(key);
        return f != null ? f : formats.values().iterator().next();
    }

    @SuppressWarnings("unchecked")
    public List<String> lanes() {
        return (List<String>) seed.getOrDefault("rotas", List.of("Barão", "Selva", "Meio", "Dragão", "Suporte"));
    }

    @SuppressWarnings("unchecked")
    public String fearlessDefault() {
        Map<String, Object> f = (Map<String, Object>) seed.getOrDefault("fearless", Map.of());
        return String.valueOf(f.getOrDefault("indisponivel", "ambos"));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> section(String name) {
        return (Map<String, Object>) seed.getOrDefault(name, Map.of());
    }

    /**
     * The champions a side cannot pick: both teams' picks and bans of this game, and in a Fearless series the champions
     * of the earlier games (every one, or only the side's own, by the scope).
     */
    public Set<String> unavailable(LiveGameModel.Game g, String side) {
        Set<String> out = new LinkedHashSet<>();
        for (LiveGameModel.Team t : List.of(g.blue, g.red)) {
            out.addAll(t.champions());
            for (String b : t.bans) {
                if (b != null && !b.isBlank()) {
                    out.add(b);
                }
            }
        }
        out.addAll(fearlessUsed(g, side));
        return out;
    }

    /** The Fearless series' champions this side cannot pick (empty outside Fearless). */
    public Set<String> fearlessUsed(LiveGameModel.Game g, String side) {
        Set<String> out = new LinkedHashSet<>();
        if (!g.fearless) {
            return out;
        }
        String scope = g.fearlessScope == null ? fearlessDefault() : g.fearlessScope;
        for (LiveGameModel.SeriesGame s : g.series) {
            boolean own = LiveGameModel.BLUE.equals(side);
            if ("proprio".equals(scope)) {
                out.addAll(own ? s.blue : s.red);
            } else {
                out.addAll(s.blue);
                out.addAll(s.red);
            }
        }
        out.removeIf(x -> x == null || x.isBlank());
        return out;
    }

    /**
     * The next turn of the draft (null = the draft is over): the first turn whose slot is still empty. A simultaneous
     * ban is filled in any order, so the first empty one counts.
     */
    public Turn next(LiveGameModel.Game g) {
        for (Turn t : format(g.mode).turns) {
            LiveGameModel.Team team = g.team(t.side);
            if ("ban".equals(t.action)) {
                if (t.index >= team.bans.size() || team.bans.get(t.index) == null || team.bans.get(t.index).isBlank()) {
                    return t;
                }
            } else {
                int picked = team.champions().size();
                if (picked <= t.index) {
                    return t;
                }
            }
        }
        return null;
    }
}
