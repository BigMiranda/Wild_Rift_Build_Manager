package com.ornnplanner.arena.draft;

import com.ornnplanner.arena.fight.ChampionProfiles;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rodada 20, Parte 3: the draft's lists for a followed match: for the next pick of each side, the 5 most likely enemy
 * picks (prediction) and the 5 best for the ally (recommendation), the bans (the 5 that threaten the ally most) and the
 * tree of scenarios (beam 5, a branch expanded on demand), from {@link DraftModel} over the profiles, the imported meta
 * and the measured tables.
 */
@Service
public class DraftService {

    private final DraftCatalog catalog;
    private final ChampionProfiles profiles;
    private final MetaStats meta;
    private final DraftTablesService tables;
    private final CounterMechanics mechanics;

    public DraftService(DraftCatalog catalog, ChampionProfiles profiles, MetaStats meta, DraftTablesService tables, CounterMechanics mechanics) {
        this.catalog = catalog;
        this.profiles = profiles;
        this.meta = meta;
        this.tables = tables;
        this.mechanics = mechanics;
    }

    /** What the draft reads of every champion (the profiles: classes, damage, toughness, control, range, sustain). */
    public static List<DraftModel.Champ> champs(ChampionProfiles profiles) {
        List<ChampionProfiles.Profile> all = profiles.all();
        List<Double> durab = new ArrayList<>();
        for (ChampionProfiles.Profile p : all) {
            durab.add(durability(p));
        }
        durab.sort(Double::compare);
        double front = durab.isEmpty() ? 0 : durab.get((int) Math.floor(durab.size() * 0.7));
        List<DraftModel.Champ> out = new ArrayList<>();
        for (ChampionProfiles.Profile p : all) {
            DraftModel.Champ c = new DraftModel.Champ();
            c.name = p.name;
            c.lanes = new ArrayList<>(p.lanes);
            c.subclasses = new ArrayList<>(p.subclasses);
            c.classes = new ArrayList<>(p.mainClasses);
            c.physical = p.physical + p.trueDamage / 2;
            c.magic = p.magic + p.trueDamage / 2;
            c.frontline = durability(p) >= front;
            int strong = 0;
            for (Map.Entry<String, Integer> e : p.control.entrySet()) {
                if (!"lentidao".equals(e.getKey())) {
                    strong += e.getValue();
                }
            }
            c.control = strong > 0;
            c.initiation = c.control && p.mobility > 0;
            c.longRange = p.attackRange >= 500;
            c.healShield = p.heals + p.shields > 0;
            boolean early = p.mainClasses.contains("Lutador") || p.mainClasses.contains("Assassino");
            boolean late = p.mainClasses.contains("Atirador") || p.mainClasses.contains("Mago");
            c.early = early && !late ? 1 : late && !early ? -1 : 0;
            c.logPower = Math.log(Math.max(1, p.attackDps + p.abilityDps) * durability(p));
            out.add(c);
        }
        return out;
    }

    /**
     * Rodada 20.b: the champions with their lane chances: the meta's presence share in each lane (the seed's lanes the
     * meta does not show get a small weight), else the seed's order; the possible lanes are the seed's plus the meta's
     * above the seed's minimum share.
     */
    public static List<DraftModel.Champ> champs(ChampionProfiles profiles, Map<String, Map<String, DraftModel.Meta>> meta, DraftModel.Weights w,
                                                double minShare, double unseenSeedLane) {
        List<DraftModel.Champ> out = champs(profiles);
        for (DraftModel.Champ c : out) {
            Map<String, DraftModel.Meta> m = meta == null ? Map.of() : meta.getOrDefault(c.name, Map.of());
            double total = 0;
            for (DraftModel.Meta x : m.values()) {
                total += Math.max(0, x.presence);
            }
            c.possibleLanes = new ArrayList<>(c.lanes);
            if (total <= 0) {
                c.lanePrior = DraftModel.seedPrior(c.lanes, w.seedLaneOrder);
                continue;
            }
            Map<String, Double> prior = new LinkedHashMap<>();
            for (Map.Entry<String, DraftModel.Meta> e : m.entrySet()) {
                double share = Math.max(0, e.getValue().presence) / total;
                prior.put(e.getKey(), share);
                if (share >= minShare && !c.possibleLanes.contains(e.getKey())) {
                    c.possibleLanes.add(e.getKey());
                }
            }
            for (String l : c.lanes) {
                prior.merge(l, unseenSeedLane, Math::max);
            }
            double sum = prior.values().stream().mapToDouble(d -> d).sum();
            prior.replaceAll((k, v) -> v / sum);
            c.lanePrior = prior;
        }
        return out;
    }

    /** Rodada 20.b: each champion's possible lanes (the seed's plus the meta's above the minimum), for the screen's prefill. */
    public Map<String, List<String>> possibleLanes(String elo) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        DraftModel.Weights w = weights();
        for (DraftModel.Champ c : champs(profiles, meta.of(elo), w, minShare(), unseenSeedLane())) {
            out.put(c.name, c.possibleLanes);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> lanesCfg() {
        return (Map<String, Object>) catalog.section("nota").getOrDefault("rotas", Map.of());
    }

    double minShare() {
        return num(lanesCfg().get("presenca_minima"), 0.10);
    }

    double unseenSeedLane() {
        return num(lanesCfg().get("sem_meta_na_rota"), 0.05);
    }

    /** The screen shows "?" when no lane of a pick passes this. */
    public double unsureBelow() {
        return num(lanesCfg().get("incerto_abaixo"), 0.6);
    }

    private static double durability(ChampionProfiles.Profile p) {
        return Math.max(1, p.healthLv15) * (1 + (p.armorLv15 + p.magicResistLv15) / 200);
    }

    @SuppressWarnings("unchecked")
    DraftModel.Weights weights() {
        Map<String, Object> nota = catalog.section("nota");
        Map<String, Object> p = (Map<String, Object>) nota.getOrDefault("pesos", Map.of());
        Map<String, Object> comp = (Map<String, Object>) nota.getOrDefault("composicao", Map.of());
        Map<String, Object> m = (Map<String, Object>) nota.getOrDefault("meta", Map.of());
        DraftModel.Weights w = new DraftModel.Weights();
        w.meta = num(p.get("meta"), w.meta);
        w.lane = num(p.get("rota"), w.lane);
        w.matchup = num(p.get("matchup"), w.matchup);
        w.composition = num(p.get("composicao"), w.composition);
        w.counter = num(p.get("contra"), w.counter);
        w.flex = num(p.get("flex"), w.flex);
        Map<String, Object> fx = (Map<String, Object>) nota.getOrDefault("flex", Map.of());
        w.flexMin = num(fx.get("presenca_real"), w.flexMin);
        Map<String, Object> ct = (Map<String, Object>) nota.getOrDefault("contra", Map.of());
        w.counterMechanics = num(ct.get("mecanicas"), w.counterMechanics);
        w.counterFullAfter = (int) num(ct.get("cheio_com"), w.counterFullAfter);
        w.counterTop = num(ct.get("mais_forte"), w.counterTop);
        w.banCounter = num(ct.get("banimentos"), w.banCounter);
        w.metaPresence = num(m.get("presenca"), w.metaPresence);
        w.metaBan = num(m.get("banimento"), w.metaBan);
        w.metaWin = num(m.get("vitoria"), w.metaWin);
        w.metaWinScale = num(m.get("vitoria_escala"), w.metaWinScale);
        w.magicMin = num(comp.get("dano_magico_min"), w.magicMin);
        w.physicalMin = num(comp.get("dano_fisico_min"), w.physicalMin);
        w.frontline = (int) num(comp.get("linha_de_frente"), w.frontline);
        w.control = (int) num(comp.get("controle"), w.control);
        w.initiation = (int) num(comp.get("iniciacao"), w.initiation);
        w.longRange = (int) num(comp.get("alcance_longo"), w.longRange);
        w.healShield = (int) num(comp.get("cura_escudo"), w.healShield);
        w.scaling = num(comp.get("escalonamento"), w.scaling);
        w.beam = (int) num(nota.get("feixe"), w.beam);
        Map<String, Object> lc = (Map<String, Object>) nota.getOrDefault("rotas", Map.of());
        if (lc.get("ordem_seed") instanceof List) {
            List<Object> o = (List<Object>) lc.get("ordem_seed");
            w.seedLaneOrder = new double[o.size()];
            for (int i = 0; i < o.size(); i++) {
                w.seedLaneOrder[i] = num(o.get(i), 0.05);
            }
        }
        w.userLaneMin = num(lc.get("marcada_minimo"), w.userLaneMin);
        w.undefinedLane = num(lc.get("indefinido"), w.undefinedLane);
        w.laneFitMin = num(lc.get("encaixe_minimo"), w.laneFitMin);
        Map<String, Object> seedCal = (Map<String, Object>) nota.getOrDefault("calibracao", Map.of());
        seedCal.forEach((k, v) -> w.calibration.put(k, num(v, 1)));
        // the measured calibration only when it predicts out of sample (else the seed's)
        Map<String, Double> info = tables.calibrationInfo();
        if (info.getOrDefault("correlacao_validada", 0.0) >= num(nota.get("calibracao_minima"), 0.3)) {
            w.calibration.putAll(tables.calibration());
        }
        return w;
    }

    private static double num(Object o, double def) {
        return o instanceof Number ? ((Number) o).doubleValue() : def;
    }

    /**
     * The board of a game: the picks (Rodada 20.b: with their possible lanes and the lane fixed on the loading screen, if
     * any), the predictions fixed on empty cards, what each side cannot pick.
     */
    public DraftModel.Board board(LiveGameModel.Game g) {
        catalog.upgrade(g);
        DraftModel.Board b = new DraftModel.Board();
        for (String side : List.of(LiveGameModel.BLUE, LiveGameModel.RED)) {
            List<LiveGameModel.Player> ps = g.team(side).players;
            for (int i = 0; i < ps.size(); i++) {
                LiveGameModel.Player p = ps.get(i);
                DraftModel.Pick pick = null;
                if (p.champion != null && !p.champion.isBlank()) {
                    pick = new DraftModel.Pick(side, p.champion, p.lane, false);
                    pick.lanes = p.lanes == null ? null : new ArrayList<>(p.lanes);
                } else {
                    String pred = g.predictions == null ? null : g.predictions.get(side + ":" + i);
                    if (pred != null && !pred.isBlank()) {
                        pick = new DraftModel.Pick(side, pred, p.lane, true);
                    }
                }
                if (pick != null) {
                    pick.slot = i;
                    b.picks.add(pick);
                }
            }
            b.order = pickOrder(g);
            Set<String> off = new LinkedHashSet<>(catalog.unavailable(g, side));
            b.unavailable.put(side, off);
        }
        return b;
    }

    /** The model of a request: profiles, the meta of the game's elo, the tables (the duo where both partners are known). */
    DraftModel model(LiveGameModel.Game g, DraftModel.Board board, DraftModel.Weights w) {
        Map<String, String> sideOf = new LinkedHashMap<>();
        Map<String, String> laneOf = new LinkedHashMap<>();
        for (DraftModel.Pick p : board.picks) {
            sideOf.put(p.champion, p.side);
        }
        DraftModel.Matchup m = (a, b, lane) -> {
            if ("Dragão".equals(lane) || "Suporte".equals(lane)) {
                // the duo: the partners of a (its side: the board's, or the other of b's) and of b
                String bs = sideOf.get(b);
                String as = sideOf.getOrDefault(a, bs == null ? null : LiveGameModel.BLUE.equals(bs) ? LiveGameModel.RED : LiveGameModel.BLUE);
                String partnerLane = "Dragão".equals(lane) ? "Suporte" : "Dragão";
                String pa = as == null ? null : laneOf.get(as + "|" + partnerLane);
                String pb = bs == null ? null : laneOf.get(bs + "|" + partnerLane);
                if (pa != null && pb != null) {
                    String da = "Dragão".equals(lane) ? a + "+" + pa : pa + "+" + a;
                    String db = "Dragão".equals(lane) ? b + "+" + pb : pb + "+" + b;
                    Double d = tables.advantage(da, db, DraftTablesService.DUO);
                    if (d != null) {
                        return d;
                    }
                }
            }
            return tables.advantage(a, b, lane);
        };
        Map<String, Map<String, DraftModel.Meta>> mt = meta.of(g.elo);
        DraftModel model = new DraftModel(champs(profiles, mt, w, minShare(), unseenSeedLane()), mt, m, w, catalog.lanes())
                .withPunish(punish());
        // Rodada 20.b: the duo's partners by the most likely assignment of each team
        for (String side : List.of(LiveGameModel.BLUE, LiveGameModel.RED)) {
            List<DraftModel.Pick> ps = board.of(side);
            List<String> likely = model.likelyLanes(board, side);
            for (int i = 0; i < ps.size(); i++) {
                laneOf.put(side + "|" + likely.get(i), ps.get(i).champion);
            }
        }
        return model;
    }

    /** Rodada 20.b, Parte 3: the mechanics that punish (contra_mecanicas_7_3f.yml), as the model reads them. */
    DraftModel.Punish punish() {
        // computed once in the background: until then the counter-pick risk reads only the measured matchup
        Map<String, CounterMechanics.Traits> t = mechanics.traitsIfReady();
        if (t == null) {
            return null;
        }
        return new DraftModel.Punish() {
            @Override
            public double of(String b, String a) {
                return mechanics.punish(t, b, a);
            }

            @Override
            public String why(String b, String a) {
                List<CounterMechanics.Why> w = mechanics.why(t, b, a);
                return w.isEmpty() ? null : w.get(0).vulnerability;
            }
        };
    }

    /** Rodada 20.b, Parte 3: the mechanics' table for the screen (vulnerabilities, punishments, each champion's traits). */
    public Map<String, Object> mechanicsInfo() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("vulnerabilidades", mechanics.vulnerabilities());
        Map<String, Object> ps = new LinkedHashMap<>();
        mechanics.punishments().forEach((k, v) -> ps.put(k, Map.of("nome", v.getOrDefault("nome", k), "nome_en", v.getOrDefault("nome_en", k))));
        out.put("punicoes", ps);
        out.put("campeoes", mechanics.traits());
        return out;
    }

    /** Rodada 20.b: a team's lane distribution for the screen: each pick's lane chances and each lane's chance of being open. */
    public static class LaneView {
        /** Board order (slot = the card; -1 = a hypothetical pick of the tree). */
        public List<Map<String, Object>> picks = new ArrayList<>();
        public Map<String, Double> open = new LinkedHashMap<>();
        public boolean relaxed;
    }

    static LaneView laneView(DraftModel model, DraftModel.Board b, String side) {
        LaneAssignment.Result a = model.assignment(b, side);
        LaneView v = new LaneView();
        List<DraftModel.Pick> ps = b.of(side);
        List<String> lanes = model.lanes();
        for (int i = 0; i < ps.size(); i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slot", ps.get(i).slot);
            m.put("champion", ps.get(i).champion);
            m.put("predicted", ps.get(i).predicted);
            m.put("fixed", ps.get(i).lane);
            Map<String, Double> d = new LinkedHashMap<>();
            for (int l = 0; l < lanes.size(); l++) {
                if (a.marginal[i][l] >= 0.005) {
                    d.put(lanes.get(l), Math.round(a.marginal[i][l] * 1000) / 1000.0);
                }
            }
            m.put("lanes", d);
            m.put("likely", lanes.get(a.best[i]));
            v.picks.add(m);
        }
        for (int l = 0; l < lanes.size(); l++) {
            v.open.put(lanes.get(l), Math.round(a.open[l] * 1000) / 1000.0);
        }
        v.relaxed = a.relaxed;
        return v;
    }

    /**
     * Rodada 20.b: a copy of the game where every picked (or predicted) card has a lane: the fixed one, else the one of
     * the most likely whole assignment of its team. The per-player panels (first encounter, likely builds, tips) read
     * lanes from it, so a pick in an unexpected lane only moves the distribution.
     */
    public LiveGameModel.Game resolved(LiveGameModel.Game g) {
        LiveGameModel.Game c = LiveGameRepository.read(LiveGameRepository.write(g));
        DraftModel.Board b = board(c);
        DraftModel model = model(c, b, weights());
        for (String side : List.of(LiveGameModel.BLUE, LiveGameModel.RED)) {
            List<DraftModel.Pick> ps = b.of(side);
            List<String> likely = model.likelyLanes(b, side);
            for (int i = 0; i < ps.size(); i++) {
                LiveGameModel.Player p = c.team(side).players.get(ps.get(i).slot);
                if (p.lane == null) {
                    p.lane = likely.get(i);
                }
            }
        }
        return c;
    }

    /** The picks in the order of the game's format (sides only). */
    List<String> pickOrder(LiveGameModel.Game g) {
        List<String> out = new ArrayList<>();
        for (DraftCatalog.Turn t : catalog.format(g.mode).turns) {
            if ("escolha".equals(t.action)) {
                out.add(t.side);
            }
        }
        return out;
    }

    public static class Suggestions {
        public DraftCatalog.Turn next;
        public String ally;
        public String enemy;
        public List<DraftModel.Scored> predictions = new ArrayList<>();
        public List<DraftModel.Scored> recommendations = new ArrayList<>();
        public List<DraftModel.Scored> bans = new ArrayList<>();
        public DraftModel.Node node;
        public Map<String, Object> meta;
        public Map<String, Double> calibration;
        public Map<String, Double> calibrationInfo;
        /** The measured calibration is in the weights (its validated correlation passed the seed's minimum). */
        public boolean calibrationApplied;
        public int measuredRows;
        public DraftModel.Weights weights;
        /** Rodada 20.b: each side's lane distribution (after the branch's picks). */
        public Map<String, LaneView> lanes = new LinkedHashMap<>();
        /** Rodada 20.b: the screen shows "?" below this. */
        public double unsureBelow;
        /** Rodada 20.b: the mechanics that punish are in the counter-pick risk (false while they are computed). */
        public boolean mechanics;
    }

    /**
     * The lists for a game, after the branch {@code path} (hypothetical picks, in order): the enemy's 5 most likely next
     * picks, the ally's 5 best, the 5 bans, and the node of the branch (the beam's children of the side that picks next).
     */
    public Suggestions suggest(LiveGameModel.Game g, List<DraftModel.Pick> path) {
        DraftModel.Board base = board(g);
        DraftModel.Board b = base;
        for (DraftModel.Pick p : path == null ? List.<DraftModel.Pick>of() : path) {
            b = b.with(p);
        }
        DraftModel.Weights w = weights();
        DraftModel model = model(g, b, w);
        Suggestions s = new Suggestions();
        s.next = catalog.next(g);
        s.ally = g.allySide == null ? LiveGameModel.BLUE : g.allySide;
        s.enemy = g.enemyOf(s.ally);
        int k = w.beam;
        s.predictions = model.rank(b, s.enemy, k);
        s.recommendations = model.rank(b, s.ally, k);
        s.bans = model.threats(b, s.ally, k);
        s.node = model.expand(base, path == null ? List.of() : path, pickOrder(g));
        s.meta = meta.info();
        s.calibration = tables.calibration();
        s.calibrationInfo = tables.calibrationInfo();
        s.calibrationApplied = !w.calibration.isEmpty() && !s.calibration.isEmpty();
        s.measuredRows = tables.status().rows;
        s.weights = w;
        s.lanes.put(LiveGameModel.BLUE, laneView(model, b, LiveGameModel.BLUE));
        s.lanes.put(LiveGameModel.RED, laneView(model, b, LiveGameModel.RED));
        s.unsureBelow = unsureBelow();
        s.mechanics = mechanics.traitsIfReady() != null;
        return s;
    }
}
