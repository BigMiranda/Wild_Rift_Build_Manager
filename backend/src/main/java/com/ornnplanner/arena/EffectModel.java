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
    }

    /** What a fight says about the build tested. */
    public static class Measures {
        /** Seconds until the build died; estimated from the health it lost when it survived. */
        public double timeToDeath;
        public boolean died;
        public boolean estimated;
        /** Raw damage taken until it died (before resistances and reductions): the effective health against them. */
        public double effectiveHealth;
        public double rawTaken;
        /** Damage after resistances and reductions, before shields. */
        public double taken;
        public double mitigatedPct;
        public double shieldAbsorbed;
        public double healed;
        public double healLost;
        /** Damage the build dealt to the attackers (returned). */
        public double dealt;
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
        /** timeToDeath / reference timeToDeath x 100. */
        public Double index;
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
