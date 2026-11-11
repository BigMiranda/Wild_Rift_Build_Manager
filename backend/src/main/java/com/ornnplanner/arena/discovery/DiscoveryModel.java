package com.ornnplanner.arena.discovery;

import com.ornnplanner.repo.BuildRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Types of the build discovery (Rodada 9, parts 2 to 6): what is asked, a job, and what it found. */
public final class DiscoveryModel {
    private DiscoveryModel() {
    }

    public static final String QUICK = "rapido";
    public static final String COMPLETE = "completo";
    /** The objective (Rodada 13): the best builds (the niches and the discoveries are the "novidade" part) | novelty. */
    public static final String BEST = "melhores";
    public static final String NOVELTY = "novidade";

    /** What a discovery asks: the build that gives champion, runes, spells and gold; the budget; the search's knobs. */
    public static class Request {
        public Long buildId;
        public BuildRepository.Build build;
        /** rapido (minutes) | completo (hours, a campaign). */
        public String budget = QUICK;
        /**
         * melhores: discoveries only from the ranking, and only with enough known builds to measure novelty against |
         * novidade: from the ranking and the niches' best, as before Rodada 13.
         */
        public String objective = BEST;
        public long seed = 1;
        /** The niches' axes: arquetipo | dano | durabilidade | passivas. */
        public List<String> axes = new ArrayList<>(List.of("arquetipo", "dano", "durabilidade"));
        /** Target archetype ("deixar livre" = null): only kits whose archetype matches rank first. */
        public String archetype;
        /** Search the runes and the order of the finalists (part 4). */
        public boolean runes = true;
        public boolean order = true;
        /** Start from the saved builds of the champion (and the meta ones): seeds and the start's bar (part 4b). */
        public boolean seedsFromSaved = true;
        /** Items never / always in the kits. */
        public List<Long> forbidden = new ArrayList<>();
        public List<Long> required = new ArrayList<>();
        /** The budget's numbers set by hand (tests, the campaigns' measured budget); null = by {@link #budget}. */
        public Budget custom;
    }

    /** The numbers of a budget. */
    public static class Budget {
        public int shortlist = 30;
        public int perGroup = 2;
        public int jokers = 6;
        public Funnel.Config search = new Funnel.Config();
        public int orderBudget = 240;

        public static Budget of(String name) {
            Budget b = new Budget();
            if (COMPLETE.equals(name)) {
                b.search.population = 16;
                b.search.generations = 10;
                b.search.children = 32;
                b.search.fullPerGeneration = 8;
                b.search.confirm = 4;
                b.search.synergyPairs = 20;
                b.orderBudget = 2000;
            } else {
                b.shortlist = 24;
                b.jokers = 4;
                b.search.population = 8;
                b.search.generations = 3;
                b.search.children = 16;
                b.search.fullPerGeneration = 4;
                b.search.confirm = 2;
                b.search.synergyPairs = 6;
                b.orderBudget = 240;
            }
            return b;
        }

        /** Full batteries, quick fights and order measures a run takes at most. */
        public long[] counts() {
            Funnel.Config c = search;
            long full = c.population + (long) c.generations * c.fullPerGeneration + c.confirm + 3L * c.synergyPairs;
            long quick = (long) c.generations * Math.max(c.fullPerGeneration * 2, c.children / 2 + c.randomChildren);
            return new long[] {full, quick, orderBudget};
        }
    }

    /** A kit as the screen shows it. */
    public static class KitView {
        public List<Long> items = new ArrayList<>();
        public List<String> names = new ArrayList<>();
        public Long boots;
        public String bootsName;
        /** The order the kit is bought in (ids), as the planner's timeline reads it. */
        public List<Long> order = new ArrayList<>();
        public double score;
        public Double mean;
        public Double p20;
        public Double confirmed;
        public Double quick;
        public String origin;
        public int generation;
        public String niche;
        public String archetype;
        /** Points of the build at each minute of the robust score (minute -> total). */
        public Map<String, Double> curve = new LinkedHashMap<>();
        /** Share of the scenario-minutes where it beats the best known build (null = none known). */
        public Double beatsKnown;
        /** Distance to the closest known build (part 5): 1 = shares nothing, 0 = the same. */
        public Double novelty;
        public String closestKnown;
        public BuildRepository.RunePage runePage;
        /** Versatility (part 4b): the worst and the low percentile over the scenarios, and the mixed score. */
        public Double worst;
        public Double versatile;
        /** The order search's reading (part 4): early importance, which component first, what the runes changed. */
        public List<Map<String, Object>> importance = new ArrayList<>();
        public List<Map<String, Object>> componentFirst = new ArrayList<>();
        public List<String> runeChanges = new ArrayList<>();
        /**
         * Where the rune page came from (Rodada 13): busca (searched for this kit) | padrao (the standard build's, not
         * searched for this kit) | null (the base build's, as the kit was measured).
         */
        public String runesFrom;
        /** The pace it was measured with when not the base build's (a support build: Rodada 13). */
        public Double goldPerMin;
        public Double xpPerMin;
        /**
         * The order was searched by the calculator; cut = its budget ran out before a whole order. A cut order keeps the
         * calculator's best start ({@code orderPrefix} purchases) and the screening's order after it (Rodada 13).
         */
        public boolean orderSearched;
        public boolean orderCut;
        public int orderPrefix;
        /**
         * Rodada 13: the score with the kit's own runes and order (a saved build as it is saved; a published kit after its
         * runes and order were searched). The comparisons with the known builds that decide what is published use it.
         */
        public Double finalScore;
        /** The saved build a known view is (null for a meta build or a found kit). */
        public Long buildId;
        /** A known build's name (a saved build, or a meta build with its source). */
        public String label;
        public String keystone;
        /** The scenario-minutes' points (part 5 compares them with the known builds'). */
        public Map<String, Double> readings = new LinkedHashMap<>();
    }

    public static class SynergyView {
        public long a;
        public long b;
        public String nameA;
        public String nameB;
        public double value;
        public String explain;
    }

    /** A "discovery" (part 5): a new build that does at least as well as the best known, explained. */
    public static class Finding {
        public KitView kit;
        public String against;
        public List<String> wins = new ArrayList<>();
        public List<String> losses = new ArrayList<>();
        public List<String> swaps = new ArrayList<>();
        public String synergy;
        public String text;
        /** Held in the confirmation (starts the search did not see). */
        public boolean confirmed;
    }

    public static class Result {
        public String champion;
        public String championClass;
        /** The sheet's quality (A..D, null = none): C, D or none = low confidence. */
        public String quality;
        public boolean lowConfidence;
        public String budget;
        public String objective;
        public List<Map<String, Object>> shortlist = new ArrayList<>();
        /** The robust default build: the best mean whose P20 is acceptable. */
        public KitView standard;
        /** The best support build (Rodada 13), ranked apart: it plays with the support pace. Null = none. */
        public KitView support;
        public List<KitView> ranked = new ArrayList<>();
        public Map<String, KitView> niches = new LinkedHashMap<>();
        public List<SynergyView> synergies = new ArrayList<>();
        public List<Finding> discoveries = new ArrayList<>();
        public List<KitView> known = new ArrayList<>();
        public Double surrogateCorrelation;
        public int quickRuns;
        public int fullRuns;
        public int confirmRuns;
        public int orderMeasures;
        /** Branches cut by their start (part 4b). */
        public int prunedByStart;
        public double seconds;
        public boolean stopped;
        public List<String> notes = new ArrayList<>();
    }

    public static class Job {
        public long id;
        public String status = "running";
        public String stage = "triagem";
        public int done;
        public int total;
        public String error;
        public Result result;
        public double estimateSeconds;
        public volatile boolean stop;
    }

    public static class Estimate {
        public long full;
        public long quick;
        public long order;
        public double seconds;
        public int candidates;
    }
}
