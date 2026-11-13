package com.ornnplanner.arena.atlas;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rodada 16, the interaction atlas: conditions (stats, item passives, keystones, runes, spells) measured on their own,
 * in pairs and in triples, in a closed fight between two champions (the run's fixed context), so a guide can say which
 * conditions pay against the other side and rebuild that answer from any moment fixed ahead (items rushed, runes).
 */
public final class AtlasModel {

    private AtlasModel() {
    }

    /** What a run measures: its fixed context (the two champions, the mode, the minutes) and how deep it goes. */
    public static class Config {
        public String name;
        /** x1 (the only mode for now: the two champions alone in the lane). */
        public String mode = "x1";
        /** Side A and side B: champion unit codes. */
        public String a;
        public String b;
        public List<Double> minutes = new ArrayList<>(List.of(15.0));
        /** Dimensions measured: 1, 2, 3. */
        public List<Integer> dims = new ArrayList<>(List.of(1, 2, 3));
        public double duration = 60;
        /** Fights per measure, from different starting distances. */
        public int starts = 2;
        /** Pairs among the most relevant conditions of each side by the one-dimension notes (0 = all of them). */
        public int top2d;
        /** Triples among this many most relevant conditions of each side. */
        public int top3d = 12;
        /** Random sets of conditions fought to check the predictions of 1, 2 and 3 dimensions. */
        public int validation = 150;
        /**
         * Whole builds fought for real (real items on both sides, sometimes a keystone, a rune or a spell swapped): the
         * data the model of whole builds is fitted on.
         */
        public int builds = 600;
        /**
         * Rounds of refinement after the whole builds: the guide's own assembled builds (from nothing fixed and from
         * random first items) and their neighbours are fought, then the model is fitted again with them, so it learns
         * where the guide's choices lead (the builds it overrates are the ones it picks).
         */
        public int refineRounds = 2;
        public int refineBuilds = 120;
        /** Condition sources: status, passiva, fundamental, runa, feitico. */
        public List<String> sources = new ArrayList<>(List.of("status", "passiva", "fundamental", "runa", "feitico"));
        public long seed = 1;
    }

    /** One condition a side can have. */
    public static class Condition {
        /** status:&lt;stat&gt;:&lt;level&gt; | passiva:&lt;itemId&gt; | fundamental:&lt;name&gt; | runa:&lt;name&gt; | feitico:&lt;name&gt; */
        public String key;
        public String source;
        public String name;
        /** Two conditions sharing a tag never go together (two keystones, two levels of a stat, two Spellblades...). */
        public List<String> exclusive = new ArrayList<>();
        public Long itemId;
        public String stat;
        public Double amount;
        public Integer level;
        public String rune;
        public String tree;
        public Integer row;
        public String spell;
    }

    /** The conditions of one side at a minute, and its base (what the conditions are added to or swapped from). */
    public static class Side {
        public String unit;
        public String champion;
        public String baseName;
        public String keystone;
        public List<String> runes = new ArrayList<>();
        public List<String> spells = new ArrayList<>();
        public List<Condition> conditions = new ArrayList<>();
    }

    /** One minute's measures. Keys of a set: sorted side keys ("A:status:Armor:1|B:passiva:12") joined by "|". */
    public static class MinuteResult {
        public double minute;
        public Side a = new Side();
        public Side b = new Side();
        /** The race (side A's view, 0..100) with no condition on either side. */
        public double base;
        public Map<String, Double> races = new LinkedHashMap<>();
        /**
         * How well each order predicts sets it never measured: {order, correlation, mean absolute error, sets, scale}
         * (scale 0 = points, 1 = logit).
         */
        public List<double[]> validation = new ArrayList<>();
        /** The validation's sets and their real races (kept apart: the prediction never reads them). */
        public Map<String, Double> validationRaces = new LinkedHashMap<>();
        /** The scale the guide predicts on, the one that predicted the validation best: pontos | logit. */
        public String scale = "logit";
        /**
         * The factors on the notes, the pairs and the triples that predict the validation best, fitted by two-fold cross
         * validation (fitted on half the sets, judged on the other half): the measures are noisy and the race saturates,
         * so whole interactions overcorrect. Null = whole terms up to the order asked.
         */
        public double[] factors;
        /** The fitted prediction on the validation: {cross-validated mean absolute error, correlation}. */
        public double[] fitted;
        /** The races as log-odds, for a guide on the logit scale (computed on demand, never stored). */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public transient Map<String, Double> logits;
        @com.fasterxml.jackson.annotation.JsonIgnore
        public transient boolean useLogit;
        /** The whole builds fought, with their real races. */
        public List<BuildSample> builds = new ArrayList<>();
        /**
         * Each refinement round: {round, builds, mean absolute error, mean bias (predicted minus real), correlation} of
         * the model on the round's builds before it saw them: how far it is from the builds the guide assembles.
         */
        public List<double[]> refinement = new ArrayList<>();
        /** The model fitted on them (null = none measured). */
        public BuildModel model;
        /** Each status and passive condition's mean gain over the measured builds (side key -> points, its side's view). */
        public Map<String, Double> inBuilds = new LinkedHashMap<>();
        public int fights;
        public double seconds;
        /** Seconds preparing the fighters (one thread) and fighting (in parallel). */
        public double prepareSeconds;
        public double fightSeconds;
    }

    /** A whole build of each side and its real race (side A's view). */
    public static class BuildSample {
        public Fixed a = new Fixed();
        public Fixed b = new Fixed();
        /** sorteada (any item of the shop) | guiada (among the empty guide's best items per gold) | refino (the guide's own builds and neighbours) */
        public String kind;
        public double race;
    }

    /**
     * The model of whole builds, in log-odds: logit(race) = logit(base) + coef . {notes of A, notes of B, pairs, triples}
     * + the sum of each condition's correction times its weight. Fitted by ridge regression on the measured builds:
     * the coefficients shrink (or grow) each order as a whole, the corrections fix what the notes miss about a condition
     * inside a build (diminishing returns, what only pays with other items).
     */
    public static class BuildModel {
        public double[] coef;
        public Map<String, Double> theta = new LinkedHashMap<>();
        /** The penalty on the corrections the cross validation picked (null = no corrections). */
        public Double lambda;
        public int samples;
        /**
         * Each prediction's error on the measured builds: name -> {mean absolute error, correlation}. The model's by
         * five-fold cross validation (fitted without the builds it is judged on).
         */
        public Map<String, double[]> errors = new LinkedHashMap<>();
    }

    public static class Result {
        public List<MinuteResult> minutes = new ArrayList<>();
    }

    /** A run as stored. */
    public static class Run {
        public Long id;
        public String name;
        public Config config;
        /** pendente | rodando | feito | erro | cancelado */
        public String status;
        public String stage;
        public int done;
        public int total;
        public String error;
        public Result result;
        public String createdAt;
        public String updatedAt;
    }

    /** What a side has fixed: items held, runes and spells, and conditions by key. */
    public static class Fixed {
        public List<Long> items = new ArrayList<>();
        public String keystone;
        public List<String> runes = new ArrayList<>();
        public String spell;
        public List<String> conditions = new ArrayList<>();
    }

    /** The guide's question: the run, the minute, and what each side has fixed. */
    public static class GuideRequest {
        public Double minute;
        public Fixed a = new Fixed();
        public Fixed b = new Fixed();
        /** Highest order of interaction the prediction uses (1, 2 or 3); 0 = the factors the validation fitted. */
        public int order;
        /** pontos | logit; null = the run's (the better on its validation). */
        public String scale;
        /** The side the improvement by fights works on: A | B. */
        public String side;
    }

    /** A ranked option of a side (a condition, an item, a rune, a spell): what it adds to that side's race. */
    public static class Option {
        public String key;
        public String name;
        public Long itemId;
        public Integer cost;
        public double gain;
        public Double perThousand;
        /** Conditions it brings, with their share (stats by amount, passives whole). */
        public Map<String, Double> parts;
    }

    public static class SideGuide {
        public String champion;
        /** The side's race as predicted with what is fixed (its own view: 50 = even). */
        public double race;
        public List<Option> conditions = new ArrayList<>();
        public List<Option> items = new ArrayList<>();
        public List<Option> keystones = new ArrayList<>();
        public List<Option> runes = new ArrayList<>();
        public List<Option> spells = new ArrayList<>();
        /** The build the notes assemble from what is fixed: items in order, then the runes and the spell. */
        public List<Option> build = new ArrayList<>();
        public String buildKeystone;
        public List<String> buildRunes = new ArrayList<>();
        public String buildSpell;
        public double buildRace;
    }

    /** Two builds fought for real at the run's minute: the real race (side A's view) next to the prediction. */
    public static class Confirmation {
        public double minute;
        public double real;
        public double predicted;
        /** Each start's race. */
        public List<Double> starts = new ArrayList<>();
        /** Half the width of the real race's 95% interval (1.96 standard errors of the mean over the starts). */
        public double margin;
    }

    /** A condition that changes another's value when they go together (a measured pair). */
    public static class Partner {
        public String key;
        public String name;
        /** The pair's interaction in the reading side's view (points). */
        public double value;
    }

    /** One status or passive condition of a side: how much it is worth in each reading, and with whom. */
    public static class ConditionRow {
        public String key;
        public String name;
        public String source;
        public Long itemId;
        public String stat;
        public Double amount;
        public Integer level;
        /** Its note alone, over the base with nothing else (points, its side's view). */
        public Double alone;
        /** Its mean gain added to the measured whole builds (by the model). */
        public Double inBuilds;
        /** Its gain over what is fixed now on both sides. */
        public double here;
        /** The gold of a stat condition (null for a passive). */
        public Double gold;
        /** Own conditions that make it worth more / less (synergy / overlap). */
        public List<Partner> synergies = new ArrayList<>();
        public List<Partner> overlaps = new ArrayList<>();
        /** The other side's conditions that cancel it / that it punishes. */
        public List<Partner> counters = new ArrayList<>();
        public List<Partner> punishes = new ArrayList<>();
    }

    public static class SideReport {
        public String champion;
        /** The side's race with what is fixed (its own view). */
        public double race;
        public List<ConditionRow> rows = new ArrayList<>();
    }

    /** The favorable and unfavorable status and passive conditions of each side, from what is fixed. */
    public static class ConditionReport {
        public double minute;
        /** Whether the readings come from the model of whole builds (else from the notes with the fitted factors). */
        public boolean model;
        public SideReport a = new SideReport();
        public SideReport b = new SideReport();
    }

    /** One swap the real fights kept. */
    public static class Step {
        public String from;
        public String to;
        /** The side's real race before and after (its view). */
        public double before;
        public double after;
    }

    /** A side's build improved by real fights against the other side's assembled build. */
    public static class Improvement {
        public String side;
        public String champion;
        /** The assembled build it started from, the other side's build, and the improved build (with its item names). */
        public Fixed start;
        public Fixed against;
        public Fixed build;
        public List<String> names = new ArrayList<>();
        public List<Step> steps = new ArrayList<>();
        /** Real races (the side's view): the start's over the improvement's starts, the end's over the confirmation's. */
        public double startReal;
        public double endReal;
        public double endMargin;
        /** The model's predictions of the start (before) and of the end (after it learned from the fights). */
        public double startPredicted;
        public double endPredicted;
        public int fights;
        /** Builds fought that joined the run's whole builds. */
        public int learned;
    }

    public static class Guide {
        public double minute;
        /** 0 = the factors the validation fitted. */
        public int order;
        public String scale;
        public double[] factors;
        /** Side A's race predicted with only 1, up to 2 and up to 3 dimensions (how much the interactions change it). */
        public Map<Integer, Double> byOrder = new LinkedHashMap<>();
        public SideGuide a = new SideGuide();
        public SideGuide b = new SideGuide();
    }
}
