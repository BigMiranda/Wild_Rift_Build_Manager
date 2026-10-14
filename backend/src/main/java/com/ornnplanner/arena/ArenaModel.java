package com.ornnplanner.arena;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data of an orchestrated matchup (arena): the scenario a user writes, and what a run gives back. Plain
 * DTOs, exposed as JSON and stored as JSON (arena_scenario.data).
 */
public final class ArenaModel {
    private ArenaModel() {
    }

    /** A champion in the scenario: a saved build at a game minute, its side and where it starts. */
    public static class Slot {
        /** 0 = blue, 1 = red. */
        public int team;
        public Long buildId;
        /** Game minute of the build (null = its end of match, else its last purchase). */
        public Double minute;
        /** Start position (null = next to its side's Nexus). */
        public Double x;
        public Double y;
        /** Enemy it focuses when nothing else is ordered: index in the scenario's fighters (null = the nearest). */
        public Integer target;
        /** Uses its abilities on its own when the timeline does not say otherwise (in priority order). */
        public boolean autoAbilities = true;
        public List<String> priority = new ArrayList<>(List.of("R", "3", "1", "2"));
        /**
         * What decides when the timeline gives no order: script (null too: chase, attack, abilities in priority) or
         * tactical (also dodges, retreats, kites, recasts and uses actives and spells).
         */
        public String brain;
        /** Second at which a delayed status turns on (source -> s), instead of its own conditions. */
        public Map<String, Double> activations = new LinkedHashMap<>();
        /**
         * Takes the target its side's plan picks ({@link Scenario#teams}); false = its fixed {@link #target} or the
         * nearest enemy, as before team plans. Ignored when the scenario has no plans.
         */
        public boolean followTeam = true;
    }

    /** How a side picks its targets, shared by every champion of the side that follows it. */
    public static class TeamPlan {
        public static final List<String> FOCUS = List.of("nearest", "lowest_hp", "lowest_effective_hp", "most_damage", "order");

        /** nearest | lowest_hp | lowest_effective_hp | most_damage | order */
        public String focus = "nearest";
        /** Priority of the enemies (fighter indexes): what "order" follows, and the tie-break of the other criteria. */
        public List<Integer> order = new ArrayList<>();
        /** Everyone attacks the side's one target (true), or each applies the criterion from where it stands (false). */
        public boolean shared = true;
        /** Switches to another enemy in reach that is below this fraction of its health (null = never). */
        public Double switchBelow = 0.25;
        /** Does not stack crowd control: waits until an ally's control on the target is about to end before its own. */
        public boolean chainCc = false;
    }

    /** One order of the timeline. */
    public static class Action {
        /** Index of the fighter in the scenario. */
        public int fighter;
        /** Second of the fight it is given. */
        public double time;
        /**
         * ability (slot 1, 2, 3, R) | recast (the ability's second cast) | item (active of an item: `name`) | spell
         * (summoner spell: `name`) | move (to x, y) | attack (focus the target) | stop (hold still).
         */
        public String type;
        public String slot;
        public String name;
        /** Target fighter (index), or a point. */
        public Integer target;
        public Double x;
        public Double y;
        /** K'Sante's Path Maker: seconds charged. */
        public Double charge;
    }

    public static class Scenario {
        public Long id;
        public String name;
        public String note;
        public List<Slot> fighters = new ArrayList<>();
        public List<Action> actions = new ArrayList<>();
        /** Time limit of the run (s). */
        public double duration = 60;
        /**
         * Plan of each side (blue, red). Null = no plans, as in scenarios saved before them: each champion picks its
         * own target (its fixed one, else the nearest).
         */
        public TeamPlan[] teams;
    }

    // ------------------------------------------------------------------ results

    /** A champion in a frame. */
    public static class UnitFrame {
        public double x;
        public double y;
        public double hp;
        public double maxHp;
        public double shield;
        /** Short state: idle, move, attack, cast:<slot>, channel:<slot>, dash, stun, air, stasis, dead. */
        public String state;
        public boolean invisible;
        public Double resource;
        /** Seconds left of each cooldown (1, 2, 3, R, items with an active, spells); 0 = ready. */
        public Map<String, Double> cooldowns = new LinkedHashMap<>();
        /** Charges of abilities that keep them (Rumble's harpoons). */
        public Map<String, Integer> charges;
        /** Active buffs / debuffs (names). */
        public List<String> marks = new ArrayList<>();
    }

    /** Anything that is not a champion: projectiles, areas, fields, pillars, the elemental. */
    public static class Shape {
        public String id;
        /** circle | rect | cone | line */
        public String kind;
        public int team;
        /** What it is, shown on hover: "Ruptura Vulcânica (Ornn)", "Pilar de magma"... */
        public String label;
        public double x;
        public double y;
        public Double radius;
        public Double length;
        public Double width;
        /** Direction in radians (rect, cone, line). */
        public Double angle;
        public Double arc;
    }

    public static class Frame {
        public double t;
        public List<UnitFrame> units = new ArrayList<>();
        public List<Shape> shapes = new ArrayList<>();
    }

    public static class Event {
        public double t;
        public Integer fighter;
        public String text;

        public Event() {
        }

        public Event(double t, Integer fighter, String text) {
            this.t = t;
            this.fighter = fighter;
            this.text = text;
        }
    }

    /** Totals of a fighter over the run. */
    public static class FighterStats {
        public int index;
        public int team;
        public String name;
        public String champion;
        public String build;
        public int level;
        public double minute;
        public double maxHealth;
        public double health;
        public double dealt;
        public double taken;
        public double mitigated;
        public double shieldAbsorbed;
        public double healed;
        public double healLost;
        public Double deathTime;
        public Map<String, Double> damageBySource = new LinkedHashMap<>();
        /** Item actives and summoner spells it can use (name -> cooldown, s). */
        public Map<String, Double> actives = new LinkedHashMap<>();
        /** What the arena knows of the champion's abilities (kit sheet, or read from the game's text). */
        public List<Map<String, Object>> abilities = new ArrayList<>();
        public List<String> notes = new ArrayList<>();
        /** Structured events of the fight, counted by key (see {@link ArenaEvents}); never change the fight. */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public Map<String, Double> events = new LinkedHashMap<>();
        /** The events in words, for the report. */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public String eventSummary;
    }

    /** A conditional effect of the planner in the run: when it counts, and what it did. */
    public static class TriggerReport {
        public int fighter;
        public String key;
        public String source;
        public String effect;
        /** item | loja | runa | campeao */
        public String kind;
        /** The trigger ("quando"), "fixo" when set by hand, "estatico" / "modelado" / "nunca". */
        public String mode;
        /** How the arena counts it, in words. */
        public String how;
        /** The build's own switch for it (what the planner counts). */
        public boolean buildOn;
        public int activations;
        public Double first;
        public double activeSeconds;
        /** Shields it gave and health it restored. */
        public double shield;
        public double healed;
        /** What it adds when on (stats; shields / heals as effective stats). */
        public Map<String, Double> delta = new LinkedHashMap<>();
    }

    public static class Result {
        /** 0, 1, or -1 when both sides still stand at the time limit. */
        public int winner = -1;
        public double duration;
        public double frameEvery;
        public List<FighterStats> fighters = new ArrayList<>();
        public List<Frame> frames = new ArrayList<>();
        public List<Event> events = new ArrayList<>();
        /** Static terrain (map walls) for drawing. */
        public List<Shape> terrain = new ArrayList<>();
        public double mapLength;
        public double mapWidth;
        /** Orders of the timeline that could not be done (not ready, dead, out of range...), with why. */
        public List<Event> skipped = new ArrayList<>();
        /** What each item, rune, ability and spell did (same ledger as the quick matchup). */
        public List<com.ornnplanner.combat.CombatSimulator.Ledger> ledger = new ArrayList<>();
        /** Every conditional effect of the builds (items, shop lines, runes, champion) and how it went. */
        public List<TriggerReport> triggers = new ArrayList<>();
        /**
         * Casts, recasts, actives and spells a policy (tactical or learned) chose and did, as orders of the timeline:
         * the fight can be turned into an editable timeline.
         */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public List<Action> policyOrders = new ArrayList<>();
        /** What a learned policy chose, each time its choice changed, with its three best scores. */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public List<Event> decisions = new ArrayList<>();
    }
}
