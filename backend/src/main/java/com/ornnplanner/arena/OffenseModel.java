package com.ornnplanner.arena;

import com.ornnplanner.repo.BuildRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Types of the offensive effectiveness (Rodada 9, part 1b; {@code ofensivo} in {@code seed/efetividade_<patch>.yml}):
 * the build attacks a target ragdoll that does not attack, and the measure is how well it kills each profile.
 */
public final class OffenseModel {
    private OffenseModel() {
    }

    /** A target ragdoll: how much it lasts and how it avoids, heals or shields. It never attacks (but a light retaliation). */
    public static class Target {
        public Long id;
        /** Preset key (null = the user's). */
        public String key;
        public String name;
        public String note;
        public boolean preset;
        public double health = 3000;
        public double armor = 50;
        public double magicResist = 50;
        public double moveSpeed = 350;
        /** parado | anda (zigzag across the lane) | foge (away from its attacker) */
        public String behavior = "parado";
        /** Healing of this % of its max health per second (from the first hit). */
        public double healPct;
        /** A shield of this much, renewed every {@link #shieldEvery} s, lasting {@link #shieldDuration} s (0 = none). */
        public double shield;
        public double shieldEvery = 1;
        public double shieldDuration = 1.2;
        /** Armor and magic resist grow to this over {@link #growSeconds} from the first hit, and stay (null = fixed). */
        public Double growTo;
        public double growSeconds = 5;
        /** A dash every this many seconds (0 = none), of {@link #dashDistance}, in a direction drawn by the start's seed. */
        public double dashEvery;
        public double dashDistance = 300;
        /** Physical damage per second back to its attacker, so "when hit" effects start (0 = none). */
        public double retaliate;

        public Target copy() {
            Target t = new Target();
            t.id = id;
            t.key = key;
            t.name = name;
            t.note = note;
            t.preset = preset;
            t.health = health;
            t.armor = armor;
            t.magicResist = magicResist;
            t.moveSpeed = moveSpeed;
            t.behavior = behavior;
            t.healPct = healPct;
            t.shield = shield;
            t.shieldEvery = shieldEvery;
            t.shieldDuration = shieldDuration;
            t.growTo = growTo;
            t.growSeconds = growSeconds;
            t.dashEvery = dashEvery;
            t.dashDistance = dashDistance;
            t.retaliate = retaliate;
            return t;
        }
    }

    /** One target of a report, with its weight. key: a preset key, or "u:&lt;id&gt;" for a target of the user. */
    public static class Entry {
        public String key;
        public double weight = 1;
    }

    /** The offensive report's settings: its targets with weights and its sweeps. */
    public static class Report {
        public String name;
        public List<Entry> targets = new ArrayList<>();
        public List<EffectModel.Sweep> sweeps = new ArrayList<>();
        public boolean isDefault;
    }

    /** One run asked for: a target (given, preset key or the user's), an optional sweep stage and a variant of the build. */
    public static class Job {
        public String tag;
        public Target target;
        public String targetKey;
        public EffectModel.Stage stage;
        public EffectModel.Variant variant;
    }

    /** A batch of runs of one build at one minute. */
    public static class Request {
        public Long buildId;
        public BuildRepository.Build build;
        public Double minute;
        public List<Job> jobs = new ArrayList<>();
        /** como_joga (one start) | largadas (the mean of several starts: distance, side and the dashes' seed). */
        public String readMode;
        public Integer starts;
    }

    /** What a fight says about the build attacking. */
    public static class Measures {
        public boolean killed;
        /** Seconds to kill, or the whole fight when it did not. */
        public double timeToKill;
        /**
         * Time to kill without the steps of the last blow: the target's health over the net damage rate (overkill
         * included), so two builds that kill on the same attack still compare. What the index divides.
         */
        public double smoothTime;
        /** Share of the target's health left at the end (0 when killed). */
        public double healthLeftPct;
        /** Health removed per second: after resistances, shields and healing. */
        public double effectiveDps;
        /** Raw damage dealt to the target, and what resistances, shields and the target's healing took from it. */
        public double raw;
        public double lostToResist;
        public double lostToShield;
        public double lostToHeal;
        public double lostToResistPct;
        public double lostToShieldPct;
        public double lostToHealPct;
        /** Aimed abilities cast and that hit; basic attacks started and that landed. */
        public double aimedCast;
        public double aimedHit;
        public double attacks;
        public double attacksHit;
        /** Seconds with the target in attack reach. */
        public double inRange;
        public double fightLength;
    }

    /** The answer to one job: the build's measures, the reference's, and the offensive index. */
    public static class Outcome {
        public String tag;
        public String targetName;
        public Measures build;
        public Measures reference;
        /**
         * Reference's time to kill ÷ the build's × 100; when either does not kill in the fight, the build's effective
         * damage rate ÷ the reference's × 100 ({@link #byRate}). 100 = as the champion with no items; higher is better.
         */
        public Double index;
        public boolean byRate;
        public String readMode;
        public String error;
    }

    /** The screens' metadata: presets, the user's targets, the sweeps, the gold mixes and the report. */
    public static class Meta {
        public Map<String, Target> presets;
        public List<Target> userTargets;
        public List<EffectModel.Sweep> sweeps;
        public List<Map<String, Double>> mixes;
        public Report report;
        public double duration;
    }
}
