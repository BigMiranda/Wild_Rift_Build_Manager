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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Game {
        public Long id;
        public String name;
        /** A format of draft_7_3f.yml: ranqueada | campeonato. */
        public String mode = "ranqueada";
        /** A Fearless series: the champions of {@link #series} are unavailable. */
        public boolean fearless;
        /** ambos | proprio (null = the seed's). */
        public String fearlessScope;
        /** The series' earlier games (Fearless). */
        public List<SeriesGame> series = new ArrayList<>();
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
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Team {
        public String name;
        public List<String> bans = new ArrayList<>();
        /** Five players in card order (lanes Barão, Selva, Meio, Dragão, Suporte unless the user moved them). */
        public List<Player> players = defaultPlayers();
        public Integer dragons;
        public Integer heralds;
        public Integer barons;

        static List<Player> defaultPlayers() {
            List<Player> out = new ArrayList<>();
            for (String lane : List.of("Barão", "Selva", "Meio", "Dragão", "Suporte")) {
                Player p = new Player();
                p.lane = lane;
                out.add(p);
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
        public String lane;
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
