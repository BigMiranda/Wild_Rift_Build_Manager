package com.ornnplanner.arena.draft;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.ArenaService;
import com.ornnplanner.arena.AutoBrain;
import com.ornnplanner.arena.EffectModel;
import com.ornnplanner.arena.EffectTestService;
import com.ornnplanner.arena.progressao.ProgressionService;
import com.ornnplanner.engine.Model;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.service.PlannerService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Rodada 20, Parte 5: the likely builds during the match. As the user tells purchases, each champion shows the 10 most
 * likely complete builds by the progression model (Rodada 19), conditioned on the items and components bought, the
 * runes (through the champion's build) and both teams. Ordered by importance for the near context: how much the next
 * item changes the fights that come next (the duel with the lane rival now and at the minute it closes, by the gold
 * told; and the Basic Effectiveness gained), with the probability beside. The ally side gets the same list and the
 * recommended answer: the next item of each ally against the enemy's likely builds.
 */
@Service
public class LiveBuildsService {

    private final ProgressionService progression;
    private final PlannerService planner;
    private final ArenaService arena;
    private final DraftTablesService tables;
    private final FirstEncounterService encounter;
    private final EffectTestService effect;

    public LiveBuildsService(@Lazy ProgressionService progression, PlannerService planner, ArenaService arena, DraftTablesService tables,
                             FirstEncounterService encounter, @Lazy EffectTestService effect) {
        this.progression = progression;
        this.planner = planner;
        this.arena = arena;
        this.tables = tables;
        this.encounter = encounter;
        this.effect = effect;
    }

    /** One step of a likely build: the item, its chance, its estimated minute. */
    public static class Step {
        public long itemId;
        public String name;
        public double probability;
        public double minute;
        /** The duel with the lane rival with the build up to this item, at its minute (0..100); null = not measured. */
        public Double duel;
    }

    public static class Build {
        public double probability;
        public List<Step> steps = new ArrayList<>();
        /** How much the next item changes the duel with the lane rival (points of race; + = stronger). */
        public Double duelChange;
        /** The Basic Effectiveness gained with the next item (points of durability). */
        public Double effectChange;
        /** The importance for the near context (the size of what it changes). */
        public double importance;
        /** From which item (1-based) of this build the duel turns (null = it does not turn in the steps measured). */
        public Integer turnsAt;
        /** The side the duel turns to: ganha (the champion starts winning) | perde. */
        public String turnsTo;
    }

    public static class Result {
        public String champion;
        public String side;
        public String rival;
        public double minute;
        /** How the minutes were estimated: ouro (the gold told) | ritmo (the default pace). */
        public String clock;
        public double pace;
        public Double duelNow;
        /** The rival's most likely next item (it grows by its own progression in the later steps). */
        public String rivalNext;
        public List<Build> builds = new ArrayList<>();
        /** Ally side: the next item recommended for each ally against the enemy's likely builds. */
        public List<Map<String, Object>> answers = new ArrayList<>();
        public double seconds;
    }

    /** The game's minute now (the clock set plus the wall time since, while running). */
    static double minuteOf(LiveGameModel.Game g) {
        LiveGameModel.Clock c = g.clock;
        if (c == null) {
            return 0;
        }
        double s = c.seconds + (c.paused || c.setAt == null ? 0 : (System.currentTimeMillis() - c.setAt) / 1000.0);
        return s / 60;
    }

    public Result run(LiveGameModel.Game g, String side, int index, boolean measure) {
        long t0 = System.nanoTime();
        LiveGameModel.Player p = g.team(side).players.get(index);
        if (p.champion == null) {
            throw new IllegalArgumentException("Escolha o campeão do cartão primeiro.");
        }
        String other = g.enemyOf(side);
        Result r = new Result();
        r.champion = p.champion;
        r.side = side;
        r.minute = Math.max(0, minuteOf(g));
        LiveGameModel.Player rival = laneRival(g, other, p.lane);
        r.rival = rival == null ? null : rival.champion;
        Map<Long, Model.ItemDef> items = planner.referenceData().items;
        // the clock: the gold told (earned so far) gives the pace; else the progression's default pace
        double paid = cost(p.items, items) + cost(p.components, items);
        if (p.gold != null && r.minute >= 1) {
            r.pace = Math.max(200, (p.gold - 500) / r.minute);
            r.clock = "ouro";
        } else {
            r.pace = 700;
            r.clock = "ritmo";
        }
        double earned = p.gold != null ? p.gold : 500 + r.pace * r.minute;
        boolean support = "Suporte".equals(p.lane);
        ProgressionService.Answer a = progression.answer(p.champion, p.items, p.components, g.team(other).champions(), support ? Boolean.TRUE : null, 10);
        for (ProgressionService.ContinuationView c : a.continuations) {
            Build b = new Build();
            b.probability = c.probability;
            double spent = Math.max(paid, 0);
            List<Long> held = new ArrayList<>(p.items);
            List<Long> comps = new ArrayList<>(p.components);
            for (ProgressionService.Step s : c.steps) {
                Step st = new Step();
                st.itemId = s.itemId;
                st.name = s.name;
                st.probability = s.probability;
                Model.ItemDef d = items.get(s.itemId);
                double need = d == null ? 0 : d.cost - componentsPaid(d, comps, items);
                spent += need;
                st.minute = Math.max(r.minute, r.minute + (spent - earned) / r.pace);
                // the boots of tier 3 wait for the shop (the progression's minutes already apply it)
                if (s.minute != null && r.clock.equals("ritmo")) {
                    st.minute = Math.max(st.minute, s.minute);
                }
                held.add(s.itemId);
                comps = new ArrayList<>();
                b.steps.add(st);
            }
            r.builds.add(b);
        }
        if (measure && rival != null && rival.champion != null) {
            measureDuels(r, p, rival, items);
        }
        if (measure) {
            measureEffect(r, p);
        }
        for (Build b : r.builds) {
            b.importance = Math.abs(b.duelChange == null ? 0 : b.duelChange) + 0.5 * Math.abs(b.effectChange == null ? 0 : b.effectChange);
        }
        r.builds.sort(Comparator.comparingDouble((Build b) -> -b.importance).thenComparingDouble(b -> -b.probability));
        if (measure && side.equals(g.allySide == null ? LiveGameModel.BLUE : g.allySide)) {
            answers(r, g, side);
        }
        r.seconds = (System.nanoTime() - t0) / 1e9;
        return r;
    }

    private static double cost(List<Long> ids, Map<Long, Model.ItemDef> items) {
        double s = 0;
        for (Long id : ids == null ? List.<Long>of() : ids) {
            Model.ItemDef d = items.get(id);
            s += d == null ? 0 : d.cost;
        }
        return s;
    }

    /** What the components held already paid of an item (each counted once). */
    private static double componentsPaid(Model.ItemDef d, List<Long> comps, Map<Long, Model.ItemDef> items) {
        List<Long> left = new ArrayList<>(comps);
        double paid = 0;
        for (Model.ComponentRef c : d.components) {
            for (int k = 0; k < c.quantity; k++) {
                if (left.remove(c.itemId)) {
                    Model.ItemDef x = items.get(c.itemId);
                    paid += x == null ? 0 : x.cost;
                }
            }
        }
        return paid;
    }

    private static LiveGameModel.Player laneRival(LiveGameModel.Game g, String side, String lane) {
        for (LiveGameModel.Player x : g.team(side).players) {
            if (lane != null && lane.equals(x.lane) && x.champion != null) {
                return x;
            }
        }
        return null;
    }

    /** A champion holding exactly these items at a minute (its standard build's level clock and runes). */
    Arena.Input fighter(String champion, LiveGameModel.Player p, List<Long> hold, double minute, int team) {
        BuildRepository.Build b = encounter.buildOf(champion, p);
        ArenaModel.Slot me = new ArenaModel.Slot();
        me.team = team;
        me.build = b;
        me.minute = Math.max(1, minute);
        me.holdItems = new ArrayList<>(hold);
        me.brain = com.ornnplanner.arena.BrainDefaults.of("builds_provaveis");
        ArenaModel.Slot dummy = new ArenaModel.Slot();
        dummy.team = 1 - team;
        dummy.dummy = new ArenaModel.Dummy();
        ArenaModel.Scenario sc = new ArenaModel.Scenario();
        sc.fighters = team == 0 ? List.of(me, dummy) : List.of(dummy, me);
        return arena.prepareSlots(sc).get(team == 0 ? 0 : 1);
    }

    /**
     * The duel with the lane rival now and after each of the first 3 items of each build, at each item's minute (the
     * rival grown by its own most likely continuation by then). A prefix shared by several builds is fought once.
     */
    private void measureDuels(Result r, LiveGameModel.Player p, LiveGameModel.Player rival, Map<Long, Model.ItemDef> items) {
        List<Long> nowHold = new ArrayList<>(p.items);
        nowHold.addAll(p.components);
        List<Long> rivalHold = new ArrayList<>(rival.items);
        rivalHold.addAll(rival.components);
        // the rival grows too: its most likely continuation, each item at its default-pace minute
        ProgressionService.Answer ra = progression.answer(rival.champion, rival.items, rival.components, List.of(p.champion), null, 1);
        List<ProgressionService.Step> rivalNext = ra.continuations.isEmpty() ? List.of() : ra.continuations.get(0).steps;
        java.util.function.DoubleFunction<List<Long>> rivalAt = m -> {
            List<Long> h = new ArrayList<>(rival.items);
            boolean grew = false;
            for (ProgressionService.Step st : rivalNext) {
                if (st.minute != null && st.minute <= m) {
                    h.add(st.itemId);
                    grew = true;
                }
            }
            if (!grew) {
                h.addAll(rival.components);
            }
            return h;
        };
        r.rivalNext = rivalNext.isEmpty() ? null : rivalNext.get(0).name;
        Map<String, Callable<Double>> jobs = new LinkedHashMap<>();
        Map<String, Arena.Input[]> pairs = new LinkedHashMap<>();
        pairs.put("now", new Arena.Input[] {fighter(p.champion, p, nowHold, r.minute, 0), fighter(rival.champion, rival, rivalHold, r.minute, 1)});
        for (Build b : r.builds) {
            List<Long> hold = new ArrayList<>(p.items);
            for (int i = 0; i < Math.min(3, b.steps.size()); i++) {
                Step st = b.steps.get(i);
                hold.add(st.itemId);
                String key = hold + "@" + Math.round(st.minute * 2);
                if (!pairs.containsKey(key)) {
                    pairs.put(key, new Arena.Input[] {fighter(p.champion, p, hold, st.minute, 0), fighter(rival.champion, rival, rivalAt.apply(st.minute), st.minute, 1)});
                }
            }
        }
        pairs.forEach((k, v) -> jobs.put(k, () -> {
            double s = 0;
            for (int i = 0; i < 2; i++) {
                s += tables.raceWithTrade(List.of(v[0]), List.of(v[1]), i, 30, -1)[0];
            }
            return s / 2;
        }));
        List<String> keys = new ArrayList<>(jobs.keySet());
        List<Double> out = ArenaService.parallel(new ArrayList<>(jobs.values()));
        Map<String, Double> got = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            got.put(keys.get(i), out.get(i));
        }
        r.duelNow = got.get("now");
        for (Build b : r.builds) {
            List<Long> hold = new ArrayList<>(p.items);
            for (int i = 0; i < Math.min(3, b.steps.size()); i++) {
                Step st = b.steps.get(i);
                hold.add(st.itemId);
                st.duel = got.get(hold + "@" + Math.round(st.minute * 2));
                if (b.turnsAt == null && st.duel != null && r.duelNow != null && (st.duel - 50) * (r.duelNow - 50) < 0) {
                    b.turnsAt = i + 1;
                    b.turnsTo = st.duel > 50 ? "ganha" : "perde";
                }
            }
            if (!b.steps.isEmpty() && b.steps.get(0).duel != null && r.duelNow != null) {
                b.duelChange = b.steps.get(0).duel - r.duelNow;
            }
        }
    }

    /** The Basic Effectiveness (the 1x1 scenarios' durability) gained with each distinct next item, at its minute. */
    private void measureEffect(Result r, LiveGameModel.Player p) {
        Map<Long, Double> gain = new LinkedHashMap<>();
        List<String> tests = new ArrayList<>();
        effect.presets().forEach((k, t) -> {
            if ("1x1".equals(t.group) && tests.size() < 4) {
                tests.add(k);
            }
        });
        if (tests.isEmpty()) {
            return;
        }
        for (Build b : r.builds) {
            if (b.steps.isEmpty() || gain.containsKey(b.steps.get(0).itemId)) {
                continue;
            }
            Step next = b.steps.get(0);
            double before = durability(p, List.of(), next.minute, tests);
            double after = durability(p, List.of(next.itemId), next.minute, tests);
            gain.put(next.itemId, after - before);
        }
        for (Build b : r.builds) {
            if (!b.steps.isEmpty()) {
                b.effectChange = gain.get(b.steps.get(0).itemId);
            }
        }
    }

    private double durability(LiveGameModel.Player p, List<Long> extra, double minute, List<String> tests) {
        BuildRepository.Build b = LiveGameRepository.copy(encounter.buildOf(p.champion, p));
        b.id = null;
        b.steps = new ArrayList<>();
        for (Long id : p.items) {
            b.steps.add(new BuildRepository.Step(id, true));
        }
        for (Long id : p.components) {
            b.steps.add(new BuildRepository.Step(id, true));
        }
        for (Long id : extra) {
            b.steps.add(new BuildRepository.Step(id, true));
        }
        b.goldPerMin = 1e6;   // everything held at the minute; the level keeps the build's clock
        double s = 0;
        for (String t : tests) {
            EffectModel.Measures m = effect.fightWith(b, Math.max(1, minute), t, Map.of(), null);
            s += m.durability;
        }
        return s / tests.size();
    }

    /**
     * The ally side's answer: for each ally with a champion, its next item (the progression's most likely ones, 3) that
     * does best in the duel against its lane rival holding the rival's most likely next item.
     */
    private void answers(Result r, LiveGameModel.Game g, String side) {
        String other = g.enemyOf(side);
        for (int i = 0; i < g.team(side).players.size(); i++) {
            LiveGameModel.Player ally = g.team(side).players.get(i);
            LiveGameModel.Player rival = laneRival(g, other, ally.lane);
            if (ally.champion == null || rival == null) {
                continue;
            }
            ProgressionService.Answer mine = progression.answer(ally.champion, ally.items, ally.components, g.team(other).champions(), null, 1);
            ProgressionService.Answer theirs = progression.answer(rival.champion, rival.items, rival.components, g.team(side).champions(), null, 1);
            if (mine.next.isEmpty()) {
                continue;
            }
            List<Long> rivalHold = new ArrayList<>(rival.items);
            if (!theirs.next.isEmpty()) {
                rivalHold.add(theirs.next.get(0).itemId);
            }
            double minute = Math.max(r.minute + 3, 5);
            Arena.Input y = fighter(rival.champion, rival, rivalHold, minute, 1);
            List<Callable<double[]>> jobs = new ArrayList<>();
            List<ProgressionService.Step> cands = mine.next.subList(0, Math.min(3, mine.next.size()));
            for (ProgressionService.Step c : cands) {
                List<Long> hold = new ArrayList<>(ally.items);
                hold.add(c.itemId);
                Arena.Input x = fighter(ally.champion, ally, hold, minute, 0);
                jobs.add(() -> tables.raceWithTrade(List.of(x), List.of(y), 0, 30, -1));
            }
            List<double[]> out = ArenaService.parallel(jobs);
            int best = 0;
            for (int k = 1; k < out.size(); k++) {
                if (out.get(k)[0] > out.get(best)[0]) {
                    best = k;
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", i);
            m.put("champion", ally.champion);
            m.put("rival", rival.champion);
            m.put("rivalNext", theirs.next.isEmpty() ? null : theirs.next.get(0).name);
            m.put("itemId", cands.get(best).itemId);
            m.put("name", cands.get(best).name);
            m.put("probability", cands.get(best).probability);
            m.put("duel", out.get(best)[0]);
            m.put("minute", minute);
            List<Map<String, Object>> alt = new ArrayList<>();
            for (int k = 0; k < cands.size(); k++) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("name", cands.get(k).name);
                x.put("itemId", cands.get(k).itemId);
                x.put("duel", out.get(k)[0]);
                alt.add(x);
            }
            m.put("candidates", alt);
            r.answers.add(m);
        }
    }
}
