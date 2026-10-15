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
        /** A ragdoll instead of a build ({@link #buildId} is then ignored). */
        public Dummy dummy;
    }

    /**
     * The ragdoll of the lab (the planner's "Boneco de pano" unit): its stats, how it moves, the build it may wear
     * (items and runes of a saved build, at a minute) and, optionally, the on-hit effects of those items applied to the
     * champion it faces, as if it hit every few seconds or already at their most stacks.
     */
    public static class Dummy {
        public Long id;
        public String name;
        public double health = 10000;
        public double armor = 100;
        public double magicResist = 100;
        public double radius = 80;
        public double moveSpeed = 350;
        /** parado | anda (side to side) | foge (away from its attacker) | reage (Flash / Cleanse by the tactical rules) */
        public String behavior = "parado";
        /** A saved build whose items and runes it wears (null = none), at this minute (null = the build's end). */
        public Long wearBuildId;
        public Double wearMinute;
        /** Spells it may use when it reacts (Flash, Purificar...). */
        public List<String> spells = new ArrayList<>(List.of("Flash", "Purificar"));
        /** Applies the on-hit effects of the items it wears (shred, Grievous Wounds, shield cut) to the champion it faces. */
        public boolean appliesEffects;
        /** Every this many seconds (as if it hit), or at their most stacks from the start. */
        public double hitEvery = 1;
        public boolean atMax;

        /** Presets: the ragdolls of the lab's first screen. */
        public static List<Dummy> presets() {
            List<Dummy> out = new ArrayList<>();
            out.add(preset("Tanque 100/100", 10000, 100, 100));
            out.add(preset("Mago 40/40", 10000, 40, 40));
            out.add(preset("Atirador 30/30", 2500, 30, 30));
            return out;
        }

        private static Dummy preset(String name, double hp, double armor, double mr) {
            Dummy d = new Dummy();
            d.name = name;
            d.health = hp;
            d.armor = armor;
            d.magicResist = mr;
            return d;
        }

        public Dummy copy(String behavior) {
            Dummy d = new Dummy();
            d.id = id;
            d.name = name;
            d.health = health;
            d.armor = armor;
            d.magicResist = magicResist;
            d.radius = radius;
            d.moveSpeed = moveSpeed;
            d.behavior = behavior;
            d.wearBuildId = wearBuildId;
            d.wearMinute = wearMinute;
            d.spells = new ArrayList<>(spells);
            d.appliesEffects = appliesEffects;
            d.hitEvery = hitEvery;
            d.atMax = atMax;
            return d;
        }
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
        /** A side's safe zone at its end of the lane (tower or fountain), or null: nowhere to run. */
        public SafeZone safeZone;
    }

    /**
     * Where one side is safe: the stretch of the lane from its end (its Nexus) as deep as {@link #depth}. A champion of
     * that side who gets there alive has escaped (it leaves the fight); enemies inside take {@link #damagePerSecond}
     * (a tower), or nothing when it is 0 (only "arrived = escaped").
     */
    public static class SafeZone {
        /** The side it protects (0 = blue, at x = 0; 1 = red, at the far end). */
        public int team;
        public double depth = 1200;
        public double damagePerSecond = 0;
    }

    // ------------------------------------------------------------------ combos

    /** A fact of the observation compared with a value: {@code alvo.controle_restante > 0.3} (see ai.Facts). */
    public static class Condition {
        public String fact;
        /** &lt; | &lt;= | &gt; | &gt;= | == | != */
        public String op = ">";
        public double value;

        public Condition() {
        }

        public Condition(String fact, String op, double value) {
            this.fact = fact;
            this.op = op;
            this.value = value;
        }

        @Override
        public String toString() {
            return fact + " " + op + " " + value;
        }
    }

    /**
     * One step of a combo: an action, and optionally a wait before it until an event of the fight (relative to the
     * event, never to the clock). A move step moves until its event instead.
     */
    public static class ComboStep {
        /** habilidade:S | reconjurar:S | ativo:&lt;name&gt; | atacar | mover:alvo | mover:longe | esperar */
        public String action;
        /**
         * Wait until (before the action): alvo_pousa (the target's knock-up ends in at most delta), alvo_no_chao (on the
         * ground for delta), marca_aplicada, reconjuracao_acertaria / acertaria (the recast / the cast of the step's
         * slot, or {@link #slot}, would hit by the prediction), controle_restante (at most delta), ataque_pronto, tempo
         * (delta seconds). Null = no wait.
         */
        public String until;
        /** The Δ of the wait (s). */
        public double delta;
        /** Calibratable Δ: the search may move it within these bounds. */
        public Double deltaMin;
        public Double deltaMax;
        /** The longest wait (s); then the step goes on anyway. */
        public double max = 2;
        /** The slot a recast wait looks at (null = the step's own). */
        public String slot;

        public ComboStep() {
        }

        public ComboStep(String action) {
            this.action = action;
        }

        public ComboStep(String action, String until, double delta) {
            this.action = action;
            this.until = until;
            this.delta = delta;
        }

        public boolean calibratable() {
            return deltaMin != null && deltaMax != null && deltaMax > deltaMin;
        }

        @Override
        public String toString() {
            return (until == null ? "" : "até " + until + (delta != 0 ? (delta > 0 ? " +" : " ") + delta : "") + " → ") + action;
        }
    }

    /** A combo of a champion: steps, when it may start, when it stops, and how it scored on the bench. */
    public static class Combo {
        public Long id;
        public String champion;
        public String name;
        /** descoberto (found by the search) | manual (typed by the user) */
        public String origin = "manual";
        public boolean active = true;
        /** Score on the bench (higher is better); orders the combos the AI may use. */
        public Double score;
        /** Build and minute it was found for (search), or null. */
        public String build;
        public Long buildId;
        public Double minute;
        public List<ComboStep> steps = new ArrayList<>();
        /** All must hold to start (facts of the observation). */
        public List<Condition> start = new ArrayList<>();
        /** Any of them stops it. */
        public List<Condition> abort = new ArrayList<>();
        /** The longest it may run (s). */
        public double maxTime = 6;
        /** Bench numbers of the last measure (damage, control, marks...). */
        public Map<String, Object> metrics = new LinkedHashMap<>();
        public String note;
        public String createdAt;
        public String updatedAt;
    }

    /** A combo run in a fight: when, how it ended, and its steps. */
    public static class ComboReport {
        public int fighter;
        public String combo;
        public double start;
        public Double end;
        /** done | aborted */
        public String outcome;
        public String reason;
        public List<Event> steps = new ArrayList<>();
        /**
         * What could be done when it ended (what a combo search may append): ready slots (pronto:S), open or coming
         * recasts (reconjurar:S), seconds to the next attack, the target's knock-up / control / mark left.
         */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public Map<String, Double> snapshot = new LinkedHashMap<>();
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
        /** When it reached its side's safe zone alive (null = never). */
        public Double escapedAt;
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
        /** Damage dealt by type after resistances (fisico, magico, verdadeiro). */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public Map<String, Double> damageByType = new LinkedHashMap<>();
        /** Spans [from, to] (s) it spent immobilized (stun, knock-up, displacement). */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public List<double[]> controlSpans = new ArrayList<>();
        /** Seconds left of each ability's cooldown at the end (0 = ready). */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public Map<String, Double> cooldownsLeft = new LinkedHashMap<>();
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
        /** Combos run, with their steps and how each ended. */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public List<ComboReport> combos = new ArrayList<>();
    }
}
