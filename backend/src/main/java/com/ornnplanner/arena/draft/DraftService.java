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

    public DraftService(DraftCatalog catalog, ChampionProfiles profiles, MetaStats meta, DraftTablesService tables) {
        this.catalog = catalog;
        this.profiles = profiles;
        this.meta = meta;
        this.tables = tables;
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

    /** The board of a game: the picks (each on its card's lane), the predictions fixed on empty cards, what each side cannot pick. */
    public DraftModel.Board board(LiveGameModel.Game g) {
        DraftModel.Board b = new DraftModel.Board();
        for (String side : List.of(LiveGameModel.BLUE, LiveGameModel.RED)) {
            List<LiveGameModel.Player> ps = g.team(side).players;
            for (int i = 0; i < ps.size(); i++) {
                LiveGameModel.Player p = ps.get(i);
                if (p.champion != null && !p.champion.isBlank()) {
                    b.picks.add(new DraftModel.Pick(side, p.champion, p.lane, false));
                } else {
                    String pred = g.predictions == null ? null : g.predictions.get(side + ":" + i);
                    if (pred != null && !pred.isBlank()) {
                        b.picks.add(new DraftModel.Pick(side, pred, p.lane, true));
                    }
                }
            }
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
            laneOf.put(p.side + "|" + p.lane, p.champion);
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
        return new DraftModel(champs(profiles), meta.of(g.elo), m, w, catalog.lanes());
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
        return s;
    }
}
