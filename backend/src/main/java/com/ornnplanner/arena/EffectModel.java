package com.ornnplanner.arena;

import com.ornnplanner.repo.BuildRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Types of the effectiveness reports ({@code seed/efetividade_<patch>.yml}): test scenarios fought in the arena by
 * test ragdolls (attackers and allies), what is measured, and the requests of the screens.
 */
public final class EffectModel {
    private EffectModel() {
    }

    /** A test ragdoll: an attacker or an ally. No champion: its role decides only its damage pattern and position. */
    public static class Bot {
        public String name;
        /** atirador | mago | lutador | assassino | tanque | suporte */
        public String role = "lutador";
        public double health = 8000;
        public double armor = 60;
        public double magicResist = 60;
        public double attackDamage = 100;
        /** Attacks per second before the items' attack speed. */
        public double attackSpeed = 1;
        public double moveSpeed = 350;
        /** Null = the role's. */
        public Double attackRange;
        /** Other stats on top (engine names: Ability Power, % Critical Rate, penetrations, % Lifesteal...). */
        public Map<String, Double> stats = new LinkedHashMap<>();
        /** Shop items it wears (ids). */
        public List<Long> items = new ArrayList<>();
        /** Its ability pulses; null = the role's pattern. */
        public List<ArenaModel.Pulse> pulses;

        public Bot copy() {
            Bot b = new Bot();
            b.name = name;
            b.role = role;
            b.health = health;
            b.armor = armor;
            b.magicResist = magicResist;
            b.attackDamage = attackDamage;
            b.attackSpeed = attackSpeed;
            b.moveSpeed = moveSpeed;
            b.attackRange = attackRange;
            b.stats = new LinkedHashMap<>(stats == null ? Map.of() : stats);
            b.items = new ArrayList<>(items == null ? List.of() : items);
            if (pulses != null) {
                b.pulses = new ArrayList<>();
                for (ArenaModel.Pulse p : pulses) {
                    b.pulses.add(p.copy());
                }
            }
            return b;
        }
    }

    /** A role's default: attack range, where it stands (frente | meio | tras) and its ability pulses. */
    public static class Role {
        public String key;
        public double attackRange;
        public String position = "frente";
        public List<ArenaModel.Pulse> pulses = new ArrayList<>();
    }

    /** A test scenario: the build tested against 1 to 5 attackers, with 0 to 4 allies, for a duration. */
    public static class Test {
        public Long id;
        /** Preset key (null = the user's). */
        public String key;
        public String name;
        public String note;
        /** 1x1 | equipe */
        public String group = "1x1";
        public List<Bot> attackers = new ArrayList<>();
        public List<Bot> allies = new ArrayList<>();
        public double duration = 60;
        public boolean preset;
        /**
         * The attackers grow with the build's minute (their base stats and pulses, by the seed's rule; items stay). Off
         * by default: an attacker has its fixed stats whatever the minute tested, so builds at different minutes compare.
         */
        public boolean scalesWithMinute;
    }

    /** One stage of a sweep: a dimension of the reference attacker set to a value. */
    public static class Stage {
        public String dimension;
        public double value;
    }

    /** A sweep of the complete report: one attribute of the reference attacker over 5 stages. */
    public static class Sweep {
        public String key;
        public String name;
        public List<Double> stages = new ArrayList<>();
    }

    /** One scenario of a report, with its weight. key: a preset key, or "u:&lt;id&gt;" for a scenario of the user. */
    public static class Entry {
        public String key;
        public double weight = 1;
    }

    /** A report's settings: its scenarios with weights and (complete) its sweeps. */
    public static class Report {
        /** basico | completo */
        public String kind;
        public String name;
        public List<Entry> tests = new ArrayList<>();
        public List<Sweep> sweeps = new ArrayList<>();
        /** The seed's default (nothing saved by the user). */
        public boolean isDefault;
    }

    /**
     * How the build is held in a run: as it is (build); without an item (sem); with an item reduced to its flat stats
     * (so_status: passives and actives off); with an item's gold in pure stats (ouro, the mix of {@code mix}); or with
     * another item in the purchase at {@code slot} (candidato).
     */
    public static class Variant {
        public String kind = "build";
        public Long itemId;
        /** Shares of the gold (stat -> 0..1) for "ouro". */
        public Map<String, Double> mix;
        /** Index in the build's steps of the purchase replaced, and the candidate item (candidato). */
        public Integer slot;
        public Long candidateId;
        /** Candidate as a new purchase (no slot): right after this minute; null = after the last purchase. */
        public Double atMinute;
    }

    /** What an index is measured against: the same champion with no items (proprio), another build, or a folder's mean. */
    public static class Reference {
        public String kind = "proprio";
        public Long buildId;
        public Long folderId;
    }

    /**
     * How a reading treats the build's decisions. FIXED: the decisions of the build as it is, in time, replayed in every
     * variant. STARTS: the mean of several starts (distance and side). AS_PLAYED: each variant decides for itself.
     */
    public static final String FIXED = "fixo";
    public static final String STARTS = "largadas";
    public static final String AS_PLAYED = "como_joga";
    public static final java.util.List<String> READ_MODES = java.util.List.of(FIXED, STARTS, AS_PLAYED);

    /** One run asked for: a scenario (given, preset key or the user's id), an optional sweep stage and a variant. */
    public static class Job {
        /** Free tag the screen uses to put the answer back in place. */
        public String tag;
        public Test test;
        public String testKey;
        public Stage stage;
        public Variant variant;
    }

    /** A batch of runs of one build at one minute. */
    public static class Request {
        public Long buildId;
        /** The planner's draft, instead of {@link #buildId}. */
        public BuildRepository.Build build;
        public Double minute;
        public Reference reference;
        public List<Job> jobs = new ArrayList<>();
        /** {@link #AS_PLAYED} when null: a report shows the build as it plays. The per-item screens ask for {@link #FIXED}. */
        public String readMode;
        /** Starts of {@link #STARTS} (null = 8). */
        public Integer starts;
    }

    /** What a fight says about the build tested. */
    public static class Measures {
        /** Seconds until the build died, or the whole fight when it did not ({@link #died} false): never an estimate. */
        public double timeToDeath;
        public boolean died;
        /** Damage after mitigation, minus the healing and shields it got, per second of the fight (below 0: it sustains). */
        public double netRate;
        /** The same in % of its max health per second (100 / time to die when it died). */
        public double healthRate;
        /** What it heals beyond what it takes, per second (0 unless it sustains). */
        public double surplus;
        /**
         * Seconds its health lasts at the net rate: its time to die when it died, at most 5x the fight's duration. What
         * the per-item readings and the measured passives compare (they need one number also when nobody dies).
         */
        public double durability;
        /** Raw damage taken until it died (before resistances and reductions): the effective health against them. */
        public double effectiveHealth;
        public double rawTaken;
        /** Damage after resistances and reductions, before shields. */
        public double taken;
        public double mitigatedPct;
        public double shieldAbsorbed;
        public double healed;
        public double healLost;
        /** Healing that found its health full. */
        public double healOver;
        /** Damage the build dealt to the attackers (returned), and that by how it got there. */
        public double dealt;
        public double returnedThorns;
        public double returnedAuras;
        public double returnedHits;
        /** Healing of the attackers, and what the build's Grievous Wounds cut from it. */
        public double enemyHealed;
        public double enemyHealCut;
        /** Seconds of crowd control it applied to them. */
        public double controlApplied;
        /** With allies: share of the enemies' raw damage the build took, and the allies' mean time alive. */
        public Double absorbedShare;
        public Double alliesAlive;
        public double fightLength;
        public double maxHealth;
    }

    /** The answer to one job: the build's measures, the reference's and the index. */
    public static class Outcome {
        public String tag;
        public String testName;
        public Measures build;
        public Measures reference;
        /** The reference's net rate over the build's x 100, at most {@link #cap}; null when the reference sustains and it does not. */
        public Double index;
        /** The cap of an index, and whether this one hit it. */
        public double cap;
        public boolean capped;
        /** The build heals at least what it takes: its index is the cap (marked), not a number of its own. */
        public boolean sustains;
        /** por_vida (in % of each one's max health) | bruta (in damage per second): which rate the index compared. */
        public String rateMode;
        /** The reading mode these measures came from. */
        public String readMode;
        /** Candidate runs: whether the candidate is held at the minute (bought by then). */
        public Boolean held;
        public String error;
    }

    /** An item of the build at the minute, with its cost (the per-item readings). */
    public static class HeldItem {
        public long id;
        public String name;
        public int cost;
        /** Index in the build's steps of the purchase that brought it (null = an assumed component). */
        public Integer slot;
    }
}
