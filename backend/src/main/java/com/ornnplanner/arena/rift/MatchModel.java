package com.ornnplanner.arena.rift;

import com.ornnplanner.arena.ArenaModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A whole match: what is asked, and what comes back (plain DTOs, exposed as JSON). */
public final class MatchModel {
    private MatchModel() {
    }

    /** A champion of the match. */
    public static class Slot {
        public long buildId;
        /** 0 = blue, 1 = red. */
        public int team;
        /** barao | selva | meio | dupla | suporte; null = by the champion's lanes and the free roles. */
        public String role;
        /** script (null too) | tactical | auto | ai:&lt;id&gt;: its brain in the fights. */
        public String brain = "tactical";
    }

    public static class Request {
        public List<Slot> slots = new ArrayList<>();
        /** Minutes to stop at, when nobody destroyed a Nexus. */
        public double limit = 20;
        public long seed = 1;
    }

    /** One second of the replay: everyone's place, health, gold and level, and the towers standing. */
    public static class Second {
        public double t;
        public double[][] xy;
        public double[] hp;
        public double[] gold;
        public int[] level;
        public int[] items;
        public boolean[] dead;
        public double[] towers;
        public List<double[]> waves = new ArrayList<>();
    }

    public static class Event {
        public double t;
        public String text;
        public Integer team;

        public Event() {
        }

        public Event(double t, Integer team, String text) {
            this.t = t;
            this.team = team;
            this.text = text;
        }
    }

    /** A fight of the match, with the scenario that rebuilds it at the fine step. */
    public static class Fight {
        public double t;
        public double x;
        public double y;
        public double seconds;
        public String text;
        public ArenaModel.Scenario scenario;
    }

    /** How a champion ended, against what its build assumes. */
    public static class Player {
        public int seat;
        public long buildId;
        public String champion;
        public String build;
        public int team;
        public String role;
        public int level;
        public int kills;
        public int deaths;
        public double goldEarned;
        public double goldPerMin;
        public double plannedGoldPerMin;
        public double xp;
        public double xpPerMin;
        public double plannedXpPerMin;
        public int minions;
        public int monsters;
        public List<double[]> purchases = new ArrayList<>();
    }

    public static class Result {
        public int winner = -1;
        public double seconds;
        public List<Player> players = new ArrayList<>();
        public List<Second> timeline = new ArrayList<>();
        public List<Event> events = new ArrayList<>();
        public List<Fight> fights = new ArrayList<>();
        public List<double[]> towers = new ArrayList<>();
        public Map<String, Object> world = new LinkedHashMap<>();
        public List<String> left = new ArrayList<>();
        /** What the run cost, for the report: seconds of the machine, and how many of them in fights. */
        public double runSeconds;
        public double fightSeconds;
    }
}
