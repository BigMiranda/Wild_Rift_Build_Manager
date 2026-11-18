package com.ornnplanner.arena.draft;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.ArenaService;
import com.ornnplanner.arena.AutoBrain;
import com.ornnplanner.arena.discovery.CampaignService;
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
 * Rodada 20, Parte 4: the first encounter of the lane phase. Each side has one starting item (the shop's items that fit
 * the starting gold and are sold at the start: about 12), runes and spells known. The ally's champion with each start
 * fights the lane rival (revealed, or the most likely ones) with each start, at levels 1, 2 and 3 at the minutes of the
 * first waves, in the three lane styles (Rodada 14) as the ally's brain; the rival plays the standard one. Out: who wins
 * the first trade and the all-in at each level, the best start against what the rival most likely started (by the
 * progression model's first items), and the style the numbers recommend.
 */
@Service
public class FirstEncounterService {

    private final DraftCatalog catalog;
    private final PlannerService planner;
    private final ArenaService arena;
    private final CampaignService campaign;
    private final ProgressionService progression;
    private final DraftTablesService tables;
    private final DraftService draft;

    public FirstEncounterService(DraftCatalog catalog, PlannerService planner, ArenaService arena, @Lazy CampaignService campaign,
                                 @Lazy ProgressionService progression, DraftTablesService tables, DraftService draft) {
        this.catalog = catalog;
        this.planner = planner;
        this.arena = arena;
        this.campaign = campaign;
        this.progression = progression;
        this.tables = tables;
        this.draft = draft;
    }

    public static class Start {
        public long itemId;
        public String name;
        public int cost;
        /** The chance the rival started with it (the enemy's list only). */
        public Double probability;
    }

    /** One level: the first trade (health lead at a few seconds, -100..100) and the all-in race (0..100). */
    public static class Level {
        public int level;
        public double minute;
        public double trade;
        public double allIn;
    }

    /** An ally start, against the rival's starts weighted by their chance, in a style. */
    public static class Row {
        public long itemId;
        public String name;
        public String style;
        public List<Level> levels = new ArrayList<>();
        public double mean;
    }

    public static class Result {
        public String lane;
        public String champion;
        public List<String> partners = new ArrayList<>();
        public List<String> rivals = new ArrayList<>();
        /** Why these rivals: revelado | previsto | provavel. */
        public String rivalSource;
        public List<Start> allyStarts = new ArrayList<>();
        public List<Start> enemyStarts = new ArrayList<>();
        public boolean enemyStartKnown;
        public List<Row> rows = new ArrayList<>();
        public Row best;
        /** The recommended style and its reason key with values. */
        public String style;
        public DraftModel.Reason advice;
        public long fights;
        public double seconds;
        public double prepareSeconds;
        public String note;
    }

    /** The starting options: items that fit the starting gold and are sold at the start (support items for supports). */
    @SuppressWarnings("unchecked")
    public List<Start> starts(boolean support) {
        double gold = ((Number) catalog.section("primeiro_encontro").getOrDefault("ouro_inicial", 500)).doubleValue();
        List<Start> out = new ArrayList<>();
        for (Model.ItemDef d : planner.referenceData().items.values()) {
            if (d.cost > gold || d.cost <= 0 || d.evolvedFrom != null) {
                continue;
            }
            boolean prep = "preparacao".equals(d.section);
            if (!"basico".equals(d.section) && !prep) {
                continue;
            }
            if (prep && !support) {
                continue;
            }
            Start s = new Start();
            s.itemId = d.id;
            s.name = d.name;
            s.cost = d.cost;
            out.add(s);
        }
        out.sort(Comparator.comparing((Start s) -> s.name));
        return out;
    }

    /**
     * The chance of each start for a champion: the progression's first finished items (against the enemies), each
     * spreading its probability on the starts that are in its recipe tree; a little on every start.
     */
    List<Start> likelyStarts(String champion, List<String> enemies, boolean support) {
        List<Start> opts = starts(support);
        Map<Long, Double> p = new LinkedHashMap<>();
        opts.forEach(s -> p.put(s.itemId, 0.02));
        ProgressionService.Answer a = progression.answer(champion, List.of(), List.of(), enemies, support ? Boolean.TRUE : null, 1);
        Map<Long, Model.ItemDef> items = planner.referenceData().items;
        for (ProgressionService.Step st : a.next) {
            List<Long> tree = new ArrayList<>();
            collect(items.get(st.itemId), items, tree, 0);
            List<Long> hit = new ArrayList<>();
            for (Long id : tree) {
                if (p.containsKey(id) && !hit.contains(id)) {
                    hit.add(id);
                }
            }
            for (Long id : hit) {
                p.merge(id, st.probability / hit.size(), Double::sum);
            }
        }
        double sum = p.values().stream().mapToDouble(x -> x).sum();
        for (Start s : opts) {
            s.probability = p.get(s.itemId) / sum;
        }
        opts.sort(Comparator.comparingDouble((Start s) -> -s.probability).thenComparing(s -> s.name));
        return opts;
    }

    private static void collect(Model.ItemDef d, Map<Long, Model.ItemDef> items, List<Long> out, int depth) {
        if (d == null || depth > 4) {
            return;
        }
        for (Model.ComponentRef c : d.components) {
            out.add(c.itemId);
            collect(items.get(c.itemId), items, out, depth + 1);
        }
    }

    /** A player's build for the encounter: the standard one, with the runes and spells the user told. */
    BuildRepository.Build buildOf(String champion, LiveGameModel.Player p) {
        BuildRepository.Build base = tables.buildOf(champion);
        BuildRepository.Build b = LiveGameRepository.copy(base);
        b.id = null;
        if (p != null && p.runes != null && p.runes.keystone != null) {
            if (b.runePage == null) {
                b.runePage = new BuildRepository.RunePage();
            }
            b.runePage.keystone = p.runes.keystone;
            if (p.runes.primary != null) {
                b.runePage.primary = p.runes.primary;
                b.runePage.primaryRunes = new ArrayList<>(p.runes.primaryRunes);
            }
            if (p.runes.secondaryRune != null) {
                b.runePage.secondary = p.runes.secondary;
                b.runePage.secondaryRune = p.runes.secondaryRune;
            }
        }
        if (p != null && p.spells != null && p.spells.size() == 2) {
            b.spells = new ArrayList<>(p.spells);
        }
        return b;
    }

    /** The fighter of a champion holding one start, at a level and minute (brain: auto). */
    Arena.Input prepare(BuildRepository.Build b, long start, int level, double minute, int team) {
        ArenaModel.Slot me = new ArenaModel.Slot();
        me.team = team;
        me.build = b;
        me.minute = minute;
        me.matchSteps = 0;
        me.matchLevel = level;
        me.holdItems = new ArrayList<>(List.of(start));
        me.brain = AutoBrain.AUTO;
        ArenaModel.Slot dummy = new ArenaModel.Slot();
        dummy.team = 1 - team;
        dummy.dummy = new ArenaModel.Dummy();
        ArenaModel.Scenario sc = new ArenaModel.Scenario();
        sc.fighters = team == 0 ? List.of(me, dummy) : List.of(dummy, me);
        return arena.prepareSlots(sc).get(team == 0 ? 0 : 1);
    }

    @SuppressWarnings("unchecked")
    public Result run(LiveGameModel.Game g, String side, int index) {
        long t0 = System.nanoTime();
        Map<String, Object> cfg = catalog.section("primeiro_encontro");
        List<Number> levels = (List<Number>) cfg.getOrDefault("niveis", List.of(1, 2, 3));
        Map<Object, Object> minuteBy = (Map<Object, Object>) cfg.getOrDefault("minuto_por_nivel", Map.of());
        Map<String, Object> styles = (Map<String, Object>) cfg.getOrDefault("estilos", Map.of("padrao", "auto"));
        double tradeAt = ((Number) cfg.getOrDefault("troca_s", 4)).doubleValue();
        double allInFor = ((Number) cfg.getOrDefault("duracao_all_in", 25)).doubleValue();
        int starts = ((Number) cfg.getOrDefault("largadas", 2)).intValue();
        int enemyLimit = ((Number) cfg.getOrDefault("inimigos_mais_provaveis", 0)).intValue();

        LiveGameModel.Player me = g.team(side).players.get(index);
        if (me.champion == null) {
            throw new IllegalArgumentException("Escolha o campeão do cartão primeiro.");
        }
        String enemySide = g.enemyOf(side);
        Result r = new Result();
        r.lane = me.lane;
        r.champion = me.champion;
        boolean duo = "Dragão".equals(me.lane) || "Suporte".equals(me.lane);
        String partnerLane = "Dragão".equals(me.lane) ? "Suporte" : "Dragão";
        LiveGameModel.Player partner = duo ? byLane(g.team(side), partnerLane) : null;
        if (partner != null && partner.champion != null) {
            r.partners.add(partner.champion);
        }
        // the rival: revealed, else predicted by the user, else the draft's most likely
        LiveGameModel.Player rival = byLane(g.team(enemySide), me.lane);
        LiveGameModel.Player rivalPartner = duo ? byLane(g.team(enemySide), partnerLane) : null;
        List<String> enemies = g.team(enemySide).champions();
        if (rival != null && rival.champion != null) {
            r.rivals.add(rival.champion);
            r.rivalSource = "revelado";
        } else {
            String pred = predicted(g, enemySide, me.lane);
            if (pred != null) {
                r.rivals.add(pred);
                r.rivalSource = "previsto";
            } else {
                for (DraftModel.Scored x : draftLane(g, enemySide, me.lane)) {
                    if (r.rivals.size() < 3) {
                        r.rivals.add(x.champion);
                    }
                }
                r.rivalSource = "provavel";
            }
        }
        if (r.rivals.isEmpty()) {
            throw new IllegalArgumentException("Sem adversário de rota para medir.");
        }
        boolean support = "Suporte".equals(me.lane);
        r.allyStarts = starts(support);
        if (me.startItem != null) {
            r.allyStarts.removeIf(s -> s.itemId != me.startItem);
        }
        // the rival's starts: the one told, else the likely ones (of the first rival)
        String rv = r.rivals.get(0);
        if (rival != null && rival.startItem != null) {
            Start s = new Start();
            s.itemId = rival.startItem;
            Model.ItemDef d = planner.referenceData().items.get(rival.startItem);
            s.name = d == null ? String.valueOf(rival.startItem) : d.name;
            s.cost = d == null ? 0 : d.cost;
            s.probability = 1.0;
            r.enemyStarts.add(s);
            r.enemyStartKnown = true;
        } else {
            List<String> mine = g.team(side).champions();
            r.enemyStarts = likelyStarts(rv, mine, support);
            if (enemyLimit > 0 && r.enemyStarts.size() > enemyLimit) {
                r.enemyStarts = new ArrayList<>(r.enemyStarts.subList(0, enemyLimit));
                double sum = r.enemyStarts.stream().mapToDouble(s -> s.probability).sum();
                r.enemyStarts.forEach(s -> s.probability /= sum);
            }
        }

        // prepare: the ally per start and level (auto), its styles by brain; the rivals per start and level
        long p0 = System.nanoTime();
        BuildRepository.Build myBuild = buildOf(me.champion, me);
        Map<String, Arena.Input> ally = new LinkedHashMap<>();
        Map<String, Arena.Input> enemy = new LinkedHashMap<>();
        Map<Integer, Double> minuteOf = new LinkedHashMap<>();
        for (Number lv : levels) {
            Object m = minuteBy.get(lv.intValue());
            if (m == null) {
                m = minuteBy.get(String.valueOf(lv.intValue()));
            }
            minuteOf.put(lv.intValue(), m == null ? 0.5 + lv.intValue() * 0.8 : ((Number) m).doubleValue());
        }
        List<Arena.Input> partnerIn = new ArrayList<>();
        List<Arena.Input> rivalPartnerIn = new ArrayList<>();
        for (Number lvN : levels) {
            int lv = lvN.intValue();
            double minute = minuteOf.get(lv);
            for (Start s : r.allyStarts) {
                Arena.Input base = prepare(myBuild, s.itemId, lv, minute, 0);
                for (Map.Entry<String, Object> st : styles.entrySet()) {
                    String brain = String.valueOf(st.getValue());
                    ally.put(s.itemId + "|" + lv + "|" + st.getKey(), AutoBrain.AUTO.equals(brain) ? base : arena.withBrain(base, brain));
                }
            }
            for (String champ : r.rivals) {
                BuildRepository.Build eb = buildOf(champ, rival != null && champ.equals(rival.champion) ? rival : null);
                for (Start s : r.enemyStarts) {
                    enemy.put(champ + "|" + s.itemId + "|" + lv, prepare(eb, s.itemId, lv, minute, 1));
                }
            }
        }
        // the duo's partners hold their most likely start, at level 2 (they fight beside, not measured)
        if (duo && partner != null && partner.champion != null) {
            long ps = partner.startItem != null ? partner.startItem : likelyStarts(partner.champion, enemies, !support).get(0).itemId;
            partnerIn.add(prepare(buildOf(partner.champion, partner), ps, 2, minuteOf.getOrDefault(2, 1.4), 0));
        }
        if (duo && rivalPartner != null && rivalPartner.champion != null) {
            long ps = rivalPartner.startItem != null ? rivalPartner.startItem
                    : likelyStarts(rivalPartner.champion, g.team(side).champions(), !support).get(0).itemId;
            rivalPartnerIn.add(prepare(buildOf(rivalPartner.champion, rivalPartner), ps, 2, minuteOf.getOrDefault(2, 1.4), 1));
        }
        r.prepareSeconds = (System.nanoTime() - p0) / 1e9;

        // fight every combination
        List<String> keys = new ArrayList<>();
        List<Callable<double[]>> jobs = new ArrayList<>();
        for (Start a : r.allyStarts) {
            for (Number lvN : levels) {
                int lv = lvN.intValue();
                for (String style : styles.keySet()) {
                    Arena.Input x = ally.get(a.itemId + "|" + lv + "|" + style);
                    for (String champ : r.rivals) {
                        for (Start e : r.enemyStarts) {
                            Arena.Input y = enemy.get(champ + "|" + e.itemId + "|" + lv);
                            keys.add(a.itemId + "|" + lv + "|" + style + "|" + champ + "|" + e.itemId);
                            List<Arena.Input> sa = new ArrayList<>(List.of(x));
                            sa.addAll(partnerIn);
                            List<Arena.Input> sb = new ArrayList<>(List.of(y));
                            sb.addAll(rivalPartnerIn);
                            jobs.add(() -> fight(sa, sb, starts, tradeAt, allInFor));
                        }
                    }
                }
            }
        }
        List<double[]> done = ArenaService.parallel(jobs);
        r.fights = (long) jobs.size() * starts;
        Map<String, double[]> got = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            got.put(keys.get(i), done.get(i));
        }
        // weigh: the rivals equally, the rival's starts by their chance
        for (Start a : r.allyStarts) {
            for (String style : styles.keySet()) {
                Row row = new Row();
                row.itemId = a.itemId;
                row.name = a.name;
                row.style = style;
                double sum = 0;
                for (Number lvN : levels) {
                    int lv = lvN.intValue();
                    Level l = new Level();
                    l.level = lv;
                    l.minute = minuteOf.get(lv);
                    for (String champ : r.rivals) {
                        for (Start e : r.enemyStarts) {
                            double[] v = got.get(a.itemId + "|" + lv + "|" + style + "|" + champ + "|" + e.itemId);
                            double w = e.probability / r.rivals.size();
                            l.trade += w * v[0];
                            l.allIn += w * v[1];
                        }
                    }
                    sum += l.allIn;
                    row.levels.add(l);
                }
                row.mean = sum / levels.size();
                r.rows.add(row);
            }
        }
        r.rows.sort(Comparator.comparingDouble((Row x) -> -x.mean).thenComparing(x -> x.name).thenComparing(x -> x.style));
        // the best start with its best style (the rows are sorted by their mean all-in)
        r.best = r.rows.get(0);
        advise(r, styles.keySet());
        r.seconds = (System.nanoTime() - t0) / 1e9;
        return r;
    }

    /** The trade (the health lead at {@code tradeAt} seconds, -100..100) and the all-in race, mean over the starts. */
    private double[] fight(List<Arena.Input> a, List<Arena.Input> b, int starts, double tradeAt, double allIn) {
        double trade = 0;
        double race = 0;
        for (int k = 0; k < starts; k++) {
            double[] v = tables.raceWithTrade(a, b, k, allIn, tradeAt);
            race += v[0];
            trade += v[1];
        }
        return new double[] {trade / starts, race / starts};
    }

    /** The style the numbers say, worded from templates: pressing before the rival's level 2, farming to 3... */
    static void advise(Result r, java.util.Collection<String> styles) {
        Row best = r.best;
        double w1 = best.levels.size() > 0 ? best.levels.get(0).allIn : 50;
        double w2 = best.levels.size() > 1 ? best.levels.get(1).allIn : w1;
        double w3 = best.levels.size() > 2 ? best.levels.get(2).allIn : w2;
        String bestStyle = best.style;
        r.style = bestStyle;
        String rv = r.rivals.get(0);
        if (w1 >= 55 && w2 < 50) {
            r.advice = new DraftModel.Reason("pressioneAntes2", "rival", rv, "w1", round(w1), "w2", round(w2));
        } else if (w2 < 45 && w3 >= 50) {
            r.advice = new DraftModel.Reason("farmeAte3", "rival", rv, "w2", round(w2), "w3", round(w3));
        } else if (w1 >= 55 && w2 >= 55 && w3 >= 55) {
            r.advice = new DraftModel.Reason("ganhaTodos", "rival", rv, "w", round((w1 + w2 + w3) / 3));
        } else if (w1 <= 45 && w2 <= 45 && w3 <= 45) {
            r.advice = new DraftModel.Reason("perdeTodos", "rival", rv, "w", round((w1 + w2 + w3) / 3));
        } else {
            r.advice = new DraftModel.Reason("equilibrado", "rival", rv, "w1", round(w1), "w2", round(w2), "w3", round(w3));
        }
        r.advice.vars.put("style", bestStyle);
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static LiveGameModel.Player byLane(LiveGameModel.Team t, String lane) {
        for (LiveGameModel.Player p : t.players) {
            if (lane != null && lane.equals(p.lane)) {
                return p;
            }
        }
        return null;
    }

    private static String predicted(LiveGameModel.Game g, String side, String lane) {
        List<LiveGameModel.Player> ps = g.team(side).players;
        for (int i = 0; i < ps.size(); i++) {
            if (lane.equals(ps.get(i).lane)) {
                String p = g.predictions == null ? null : g.predictions.get(side + ":" + i);
                if (p != null && !p.isBlank()) {
                    return p;
                }
            }
        }
        return null;
    }

    /** The draft's most likely picks of a side for a lane (the lane alone: other candidates are skipped). */
    private List<DraftModel.Scored> draftLane(LiveGameModel.Game g, String side, String lane) {
        DraftModel.Board b = draft.board(g);
        DraftModel.Weights w = draft.weights();
        DraftModel m = draft.model(g, b, w);
        List<DraftModel.Scored> out = new ArrayList<>();
        for (DraftModel.Scored s : m.rank(b, side, 200)) {
            if (lane.equals(s.lane)) {
                out.add(s);
            }
        }
        return out;
    }
}
