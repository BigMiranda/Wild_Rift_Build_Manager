package com.ornnplanner.arena.draft;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rodada 20: a match followed from the draft to the end (Simulações › Draft e partida), as the user tells it. Everything
 * is optional: the screen works with what there is. Stored whole as JSON in table {@code live_game}.
 */
public final class LiveGameModel {

    private LiveGameModel() {
    }

    public static final String BLUE = "azul";
    public static final String RED = "vermelho";
    /** Rodada 20.b: a ban slot the team chose not to use (championship: "sem banimento", counts as done). */
    public static final String NO_BAN = "-";
    /** Rodada 20.b: the version of the stored game (null or 1 = Rodada 20: card per lane, the old formats). */
    public static final int VERSION = 2;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Game {
        public Long id;
        public String name;
        /** Rodada 20.b: {@link #VERSION} (null = a game saved before it, upgraded when read). */
        public Integer version;
        /** A format of draft_7_3f.yml: ranqueada | campeonato | campeonato_fearless. */
        public String mode = "ranqueada";
        /** A Fearless series: the champions of {@link #series} are banned before the draft (kept in step with the format). */
        public boolean fearless;
        /** Rodada 20 only (ambos | proprio); since 20.b the Fearless is always both teams. Read to warn, then dropped. */
        public String fearlessScope;
        /** The series' earlier games (Fearless): their ten champions are banned before this one starts. */
        public List<SeriesGame> series = new ArrayList<>();
        /** Rodada 20.b: the stage of the screen: draft | carregamento | partida. */
        public String stage;
        /** Rodada 20.b: what the upgrade of an old game changed (the screen shows it once; not stored). */
        @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
        public List<String> notices = new ArrayList<>();
        /** The side the user follows as "ally" (the left column). */
        public String allySide = BLUE;
        /** Elo of the meta (null = all). */
        public String elo;
        public Team blue = new Team();
        public Team red = new Team();
        /** Predictions the user fixed on empty enemy (or ally) slots: "azul:2" -> champion. */
        public Map<String, String> predictions = new LinkedHashMap<>();
        /** The clock: the game second the user set and the wall time (ms) it was set at; running while not paused. */
        public Clock clock = new Clock();
        public List<Event> events = new ArrayList<>();
        public String createdAt;
        public String updatedAt;

        public Team team(String side) {
            return RED.equals(side) ? red : blue;
        }

        public String enemyOf(String side) {
            return RED.equals(side) ? BLUE : RED;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SeriesGame {
        public List<String> blue = new ArrayList<>();
        public List<String> red = new ArrayList<>();
        /** Rodada 20.b: the game it came from (null = typed by hand, e.g. a series started outside the app). */
        public Long gameId;

        public List<String> all() {
            List<String> out = new ArrayList<>();
            for (String c : blue) {
                if (c != null && !c.isBlank()) {
                    out.add(c);
                }
            }
            for (String c : red) {
                if (c != null && !c.isBlank()) {
                    out.add(c);
                }
            }
            return out;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Team {
        public String name;
        public List<String> bans = new ArrayList<>();
        /**
         * Five players. Rodada 20.b: in pick order (1st pick, 2nd pick...), each with its possible lanes and, once the
         * loading screen fixes it, its lane. Rodada 20 games had one card per lane (upgraded when read).
         */
        public List<Player> players = defaultPlayers();
        public Integer dragons;
        public Integer heralds;
        public Integer barons;

        static List<Player> defaultPlayers() {
            List<Player> out = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                out.add(new Player());
            }
            return out;
        }

        /** The bans made (a skipped ban, {@link #NO_BAN}, is not a champion). */
        public List<String> bannedChampions() {
            List<String> out = new ArrayList<>();
            for (String b : bans) {
                if (b != null && !b.isBlank() && !NO_BAN.equals(b)) {
                    out.add(b);
                }
            }
            return out;
        }

        public List<String> champions() {
            List<String> out = new ArrayList<>();
            for (Player p : players) {
                if (p.champion != null && !p.champion.isBlank()) {
                    out.add(p.champion);
                }
            }
            return out;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Player {
        public String champion;
        /** The lane fixed on the loading screen (null = still open: the draft reads the likely assignment). */
        public String lane;
        /**
         * Rodada 20.b: the lanes this pick may play. null = not told (the seed's and the meta's); empty = undefined (any
         * lane, by the priors); else only these.
         */
        public List<String> lanes;
        public List<String> spells = new ArrayList<>();
        public Runes runes = new Runes();
        public Integer level;
        /** Finished items and boots held, in purchase order (shop ids). */
        public List<Long> items = new ArrayList<>();
        /** Components held (shop ids). */
        public List<Long> components = new ArrayList<>();
        /** The starting item, when the user told it (Parte 4). */
        public Long startItem;
        /** Gold the user read (null = the default pace). */
        public Double gold;
        public Integer kills;
        public Integer deaths;
        public Integer assists;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Runes {
        public String keystone;
        public String primary;
        public String secondary;
        public List<String> primaryRunes = new ArrayList<>();
        public String secondaryRune;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Clock {
        /** The game second the user set ("agora são 7:30" = 450). */
        public double seconds;
        /** Wall time (ms since the epoch) when it was set; null = stopped. */
        public Long setAt;
        public boolean paused = true;
    }

    /** What happened: compra | componente | morte | abate | dragao | arauto | barao | placar | ouro. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Event {
        public double t;
        public String kind;
        public String side;
        public Integer player;
        public Long item;
        /** Dragon element, or free text. */
        public String detail;
        public Integer value;
    }
}
