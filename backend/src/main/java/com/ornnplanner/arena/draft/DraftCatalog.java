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
 * Rodada 20: the draft seed ({@code draft_7_3f.yml}): the formats (order of bans and picks), the lanes and the weights of
 * the draft notes. Rodada 20.b: the user's three formats (Ranqueada, Campeonato, Campeonato Fearless), the championship's
 * visible bans without repeats that a team may skip, the Fearless series always over both teams, and the upgrade of
 * games saved with the old formats.
 */
@Component
public class DraftCatalog {

    /** One action of a draft in order: a ban or a pick of a side, its index among that side's bans or picks. */
    public static class Turn {
        public String action;
        public String side;
        public int index;
        public boolean simultaneous;
        /** Rodada 20.b: the phase of a championship (1 or 2; 1 in the ranked). */
        public int phase = 1;
        /** Rodada 20.b: the step's own source when it differs from the format's (EST). */
        public String source;
        /** Rodada 20.b: the position of this turn in the whole draft (0-based). */
        public int order;

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
        /** Rodada 20.b: the earlier games' ten champions are banned before the draft. */
        public boolean fearless;
        /** Rodada 20.b: simultaneous blind bans, which may repeat (ranked); else visible, without repeats. */
        public boolean bansRepeat;
        /** Rodada 20.b: a team may skip a ban ("sem banimento"). */
        public boolean banSkip;
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
            out.fearless = Boolean.TRUE.equals(f.get("fearless"));
            out.bansRepeat = "simultaneos".equals(f.get("banimentos"));
            out.banSkip = Boolean.TRUE.equals(f.get("pode_pular_ban"));
            Map<String, Integer> count = new LinkedHashMap<>();
            for (Map<String, Object> p : (List<Map<String, Object>>) f.getOrDefault("passos", List.of())) {
                String action = "ban".equals(p.get("acao")) ? "ban" : "escolha";
                String side = LiveGameModel.RED.equals(p.get("time")) ? LiveGameModel.RED : LiveGameModel.BLUE;
                int n = p.get("qtd") == null ? 1 : ((Number) p.get("qtd")).intValue();
                boolean sim = Boolean.TRUE.equals(p.get("simultaneo"));
                int phase = p.get("fase") == null ? 1 : ((Number) p.get("fase")).intValue();
                String stepSource = p.get("fonte") == null ? null : String.valueOf(p.get("fonte"));
                for (int i = 0; i < n; i++) {
                    String k = action + "|" + side;
                    int idx = count.merge(k, 1, Integer::sum) - 1;
                    Turn t = new Turn(action, side, idx, sim);
                    t.phase = phase;
                    t.source = stepSource;
                    t.order = out.turns.size();
                    out.turns.add(t);
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
    public Map<String, Object> section(String name) {
        return (Map<String, Object>) seed.getOrDefault(name, Map.of());
    }

    /**
     * The champions a side cannot pick: both teams' picks and bans of this game, and in a Fearless series the ten
     * champions of every earlier game (both teams).
     */
    public Set<String> unavailable(LiveGameModel.Game g, String side) {
        Set<String> out = new LinkedHashSet<>();
        for (LiveGameModel.Team t : List.of(g.blue, g.red)) {
            out.addAll(t.champions());
            out.addAll(t.bannedChampions());
        }
        out.addAll(fearlessUsed(g, side));
        return out;
    }

    /** The Fearless series' champions, banned before the draft for both teams (empty outside Fearless). */
    public Set<String> fearlessUsed(LiveGameModel.Game g, String side) {
        Set<String> out = new LinkedHashSet<>();
        if (!fearless(g)) {
            return out;
        }
        for (LiveGameModel.SeriesGame s : g.series) {
            out.addAll(s.all());
        }
        return out;
    }

    /** The game's format bans the series' champions (the format's flag; a Rodada 20 game's checkbox until upgraded). */
    public boolean fearless(LiveGameModel.Game g) {
        Format f = formats.get(g.mode);
        return f != null ? f.fearless : g.fearless;
    }

    /**
     * Why a side cannot ban a champion in a slot (null = it can): picked by anyone ("escolhido"), banned before the draft
     * by the Fearless series ("fearless") or, where bans are visible (championship), banned already in another slot
     * ("banido"). Blind simultaneous bans (ranked) may repeat.
     */
    public String cannotBan(LiveGameModel.Game g, String side, int index, String champion) {
        if (champion == null || champion.isBlank() || LiveGameModel.NO_BAN.equals(champion)) {
            return null;
        }
        if (g.blue.champions().contains(champion) || g.red.champions().contains(champion)) {
            return "escolhido";
        }
        if (fearlessUsed(g, side).contains(champion)) {
            return "fearless";
        }
        if (!format(g.mode).bansRepeat) {
            for (String s : List.of(LiveGameModel.BLUE, LiveGameModel.RED)) {
                List<String> bans = g.team(s).bans;
                for (int i = 0; i < bans.size(); i++) {
                    if (champion.equals(bans.get(i)) && !(s.equals(side) && i == index)) {
                        return "banido";
                    }
                }
            }
        }
        return null;
    }

    /**
     * The next turn of the draft (null = the draft is over): the first turn whose slot is still empty. A simultaneous
     * ban is filled in any order, so the first empty one counts; a skipped ban ({@link LiveGameModel#NO_BAN}) is done.
     */
    public Turn next(LiveGameModel.Game g) {
        for (Turn t : format(g.mode).turns) {
            LiveGameModel.Team team = g.team(t.side);
            if ("ban".equals(t.action)) {
                if (t.index >= team.bans.size() || team.bans.get(t.index) == null || team.bans.get(t.index).isBlank()) {
                    return t;
                }
            } else {
                // Rodada 20.b: the players are in pick order, so the k-th pick is the k-th card
                LiveGameModel.Player p = t.index < team.players.size() ? team.players.get(t.index) : null;
                if (p == null || p.champion == null || p.champion.isBlank()) {
                    return t;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ Rodada 20.b: old games and the series

    /**
     * Brings a game saved before Rodada 20.b to the new model, in place, and says what changed (keys of
     * {@code live.upgrade.*}): the format becomes the nearest of the three (a Fearless one becomes Campeonato Fearless,
     * always over both teams), the empty cards lose their lane (the lanes are now open until the loading screen) and the
     * picked ones keep theirs as fixed. A game of the current version only gets its Fearless flag in step with its format.
     */
    public List<String> upgrade(LiveGameModel.Game g) {
        List<String> notes = new ArrayList<>();
        if (g.version != null && g.version >= LiveGameModel.VERSION) {
            if (!formats.containsKey(g.mode)) {
                notes.add("formatoDesconhecido");
                g.mode = g.fearless ? "campeonato_fearless" : "ranqueada";
            }
            g.fearless = fearless(g);
            g.fearlessScope = null;
            g.notices = notes;
            return notes;
        }
        String old = g.mode;
        if (g.fearless) {
            g.mode = formats.containsKey("campeonato_fearless") ? "campeonato_fearless" : "campeonato";
            notes.add("ranqueada".equals(old) ? "fearlessDaRanqueada" : "fearless");
            if ("proprio".equals(g.fearlessScope)) {
                notes.add("fearlessProprio");
            }
        } else if ("campeonato".equals(old)) {
            g.mode = "campeonato";
            notes.add("campeonato");
        } else {
            g.mode = "ranqueada";
            notes.add("ranqueada".equals(old) ? "ranqueada" : "formatoDesconhecido");
        }
        for (LiveGameModel.Team t : List.of(g.blue, g.red)) {
            while (t.players.size() < 5) {
                t.players.add(new LiveGameModel.Player());
            }
            for (LiveGameModel.Player p : t.players) {
                if (p.champion == null || p.champion.isBlank()) {
                    p.lane = null;
                }
            }
        }
        notes.add("rotasLivres");
        g.fearless = fearless(g);
        g.fearlessScope = null;
        g.version = LiveGameModel.VERSION;
        g.notices = notes;
        return notes;
    }

    /**
     * Rodada 20.b: the next game of a Fearless series: a new game that inherits the earlier games' bans plus this game's
     * ten champions (both teams), the teams' names, the followed side and the elo. The board starts empty.
     */
    public LiveGameModel.Game nextInSeries(LiveGameModel.Game g) {
        upgrade(g);
        LiveGameModel.Game n = new LiveGameModel.Game();
        n.version = LiveGameModel.VERSION;
        n.mode = g.mode;
        n.fearless = fearless(g);
        for (LiveGameModel.SeriesGame s : g.series) {
            LiveGameModel.SeriesGame c = new LiveGameModel.SeriesGame();
            c.blue.addAll(s.blue);
            c.red.addAll(s.red);
            c.gameId = s.gameId;
            n.series.add(c);
        }
        LiveGameModel.SeriesGame mine = new LiveGameModel.SeriesGame();
        mine.blue.addAll(g.blue.champions());
        mine.red.addAll(g.red.champions());
        mine.gameId = g.id;
        n.series.add(mine);
        n.allySide = g.allySide;
        n.elo = g.elo;
        n.blue.name = g.blue.name;
        n.red.name = g.red.name;
        String base = g.name == null ? "Série" : g.name.replaceAll("\\s+[—-]\\s+jogo\\s+\\d+$", "");
        n.name = base + " — jogo " + (n.series.size() + 1);
        n.stage = "draft";
        return n;
    }
}
