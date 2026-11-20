package com.ornnplanner.arena.draft;

import com.ornnplanner.arena.discovery.CampaignService;
import com.ornnplanner.arena.fight.ChampionProfiles;
import com.ornnplanner.repo.BuildRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rodada 20.b, Parte 4: the loading screen's spells and runes for an ally, even without the lane fixed: the best for
 * each likely lane (the seed's rules, {@code draft_7_3f.yml › carregamento}, over the champion's standard page and
 * spells), the one weighted by the lanes' chances and the most robust (the best in the worst of the likely lanes). EST:
 * there is no measured spell or rune per lane yet.
 */
@Service
public class LoadoutService {

    /** A recommendation: two spells and a keystone (the rest of the page is the champion's standard page). */
    public static class Pick {
        public String lane;
        public double probability;
        public List<String> spells = new ArrayList<>();
        public String keystone;
        /** The keystone is the champion's own (its standard page) or the lane's rule for its class. */
        public boolean ownKeystone;
        public double score;
    }

    public static class Result {
        public String champion;
        public String side;
        public int index;
        /** The lane chances it read (the fixed lane alone when fixed). */
        public Map<String, Double> lanes = new LinkedHashMap<>();
        public List<Pick> byLane = new ArrayList<>();
        public Pick weighted;
        public Pick robust;
        /** The champion's standard page and spells (campaign, else its first saved build). */
        public BuildRepository.RunePage standardPage;
        public List<String> standardSpells = new ArrayList<>();
        public String source = "EST";
    }

    private final DraftCatalog catalog;
    private final DraftService draft;
    private final ChampionProfiles profiles;
    private final CampaignService campaign;
    private final DraftTablesService tables;

    public LoadoutService(DraftCatalog catalog, DraftService draft, ChampionProfiles profiles, @Lazy CampaignService campaign,
                          DraftTablesService tables) {
        this.catalog = catalog;
        this.draft = draft;
        this.profiles = profiles;
        this.campaign = campaign;
        this.tables = tables;
    }

    /** The loading recommendation for a card of a game (its lane chances from the draft's distribution). */
    public Result run(LiveGameModel.Game g, String side, int index) {
        LiveGameModel.Player p = g.team(side).players.get(index);
        if (p.champion == null || p.champion.isBlank()) {
            throw new IllegalArgumentException("Escolha o campeão do cartão primeiro.");
        }
        Map<String, Double> lanes = new LinkedHashMap<>();
        if (p.lane != null) {
            lanes.put(p.lane, 1.0);
        } else {
            DraftService.Suggestions s = draft.suggest(g, List.of());
            for (Map<String, Object> x : s.lanes.get(side).picks) {
                if (Integer.valueOf(index).equals(x.get("slot"))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Double> d = (Map<String, Double>) x.get("lanes");
                    lanes.putAll(d);
                }
            }
        }
        BuildRepository.Build std = campaign.standardBuild(tables.unitOf(p.champion));
        Result r = recommend(catalog.section("carregamento"), lanes, std.spells, std.runePage == null ? null : std.runePage.keystone,
                profiles.classesOf(p.champion));
        r.champion = p.champion;
        r.side = side;
        r.index = index;
        r.standardPage = std.runePage;
        r.standardSpells = std.spells;
        return r;
    }

    /**
     * Pure: the recommendation for lane chances, the champion's own spells and keystone and its main classes, by the
     * seed's rules.
     */
    @SuppressWarnings("unchecked")
    public static Result recommend(Map<String, Object> cfg, Map<String, Double> lanes, List<String> ownSpells, String ownKeystone,
                                   List<String> classes) {
        Map<String, Object> byLane = (Map<String, Object>) cfg.getOrDefault("feiticos_por_rota", Map.of());
        List<Number> order = (List<Number>) cfg.getOrDefault("pontos_por_ordem", List.of(1, 0.7, 0.5));
        double ownBonus = num(cfg.get("bonus_proprio"), 0.4);
        Map<String, Object> onlyIn = (Map<String, Object>) cfg.getOrDefault("so_na_rota", Map.of());
        Map<String, Object> keystones = (Map<String, Object>) cfg.getOrDefault("fundamentais_por_rota", Map.of());
        double robustMin = num(cfg.get("robusta_minimo"), 0.2);
        Result r = new Result();
        r.lanes.putAll(lanes);
        // every pair any likely lane lists, and the champion's own
        Set<List<String>> pairs = new LinkedHashSet<>();
        for (String lane : lanes.keySet()) {
            for (Object o : (List<Object>) byLane.getOrDefault(lane, List.of())) {
                pairs.add(sorted((List<String>) o));
            }
        }
        List<String> own = ownSpells == null || ownSpells.size() < 2 ? null : sorted(ownSpells);
        if (own != null) {
            pairs.add(own);
        }
        Map<List<String>, Map<String, Double>> score = new LinkedHashMap<>();
        for (List<String> pair : pairs) {
            Map<String, Double> per = new LinkedHashMap<>();
            for (String lane : lanes.keySet()) {
                per.put(lane, pairScore(pair, lane, byLane, order, onlyIn, own, ownBonus));
            }
            score.put(pair, per);
        }
        List<Map.Entry<String, Double>> ls = new ArrayList<>(lanes.entrySet());
        ls.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        for (Map.Entry<String, Double> e : ls) {
            String lane = e.getKey();
            Pick k = new Pick();
            k.lane = lane;
            k.probability = e.getValue();
            List<String> best = null;
            for (List<String> pair : pairs) {
                if (best == null || score.get(pair).get(lane) > score.get(best).get(lane)) {
                    best = pair;
                }
            }
            if (best != null) {
                k.spells = laneOrder(best, lane, byLane);
                k.score = score.get(best).get(lane);
            }
            keystone(k, lane, ownKeystone, classes, keystones);
            r.byLane.add(k);
        }
        // weighted: the pair with the best expected score; robust: the best worst case among the likely lanes
        List<String> wBest = null;
        double wScore = -1;
        List<String> rBest = null;
        double rScore = -1;
        for (List<String> pair : pairs) {
            double ev = 0;
            double worst = Double.MAX_VALUE;
            for (Map.Entry<String, Double> e : lanes.entrySet()) {
                double s = score.get(pair).get(e.getKey());
                ev += e.getValue() * s;
                if (e.getValue() >= robustMin) {
                    worst = Math.min(worst, s);
                }
            }
            if (ev > wScore + 1e-12) {
                wScore = ev;
                wBest = pair;
            }
            if (worst != Double.MAX_VALUE && worst > rScore + 1e-12) {
                rScore = worst;
                rBest = pair;
            }
        }
        String main = ls.isEmpty() ? null : ls.get(0).getKey();
        r.weighted = summary(wBest, wScore, main, r.byLane, byLane);
        r.robust = summary(rBest == null ? wBest : rBest, rBest == null ? wScore : rScore, main, r.byLane, byLane);
        // the keystone that serves the most likely lanes: weighted by chance; robust = one that suits every likely lane
        r.weighted.keystone = weightedKeystone(r.byLane, 0);
        r.robust.keystone = weightedKeystone(r.byLane, robustMin);
        r.weighted.ownKeystone = ownKeystone != null && ownKeystone.equals(r.weighted.keystone);
        r.robust.ownKeystone = ownKeystone != null && ownKeystone.equals(r.robust.keystone);
        return r;
    }

    @SuppressWarnings("unchecked")
    private static double pairScore(List<String> pair, String lane, Map<String, Object> byLane, List<Number> order,
                                    Map<String, Object> onlyIn, List<String> own, double ownBonus) {
        for (String spell : pair) {
            Object only = onlyIn.get(spell);
            if (only != null && !only.equals(lane)) {
                return 0;
            }
        }
        for (Map.Entry<String, Object> e : onlyIn.entrySet()) {
            // a lane whose own spell is missing (the jungle without Smite) is no fit
            if (e.getValue().equals(lane) && !pair.contains(e.getKey())) {
                return 0;
            }
        }
        double s = 0;
        List<Object> list = (List<Object>) byLane.getOrDefault(lane, List.of());
        for (int i = 0; i < list.size(); i++) {
            if (sorted((List<String>) list.get(i)).equals(pair)) {
                s = i < order.size() ? order.get(i).doubleValue() : order.get(order.size() - 1).doubleValue() * 0.8;
                break;
            }
        }
        if (s == 0) {
            // a pair the lane does not list: half the points of each spell the lane's pairs use
            Set<String> used = new LinkedHashSet<>();
            for (Object o : list) {
                used.addAll((List<String>) o);
            }
            for (String spell : pair) {
                s += used.contains(spell) ? 0.25 : 0;
            }
        }
        if (own != null && own.equals(pair) && s > 0) {
            s += ownBonus;
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static void keystone(Pick k, String lane, String own, List<String> classes, Map<String, Object> keystones) {
        Map<String, Object> byClass = (Map<String, Object>) keystones.get(lane);
        if (byClass == null || byClass.isEmpty()) {
            k.keystone = own;
            k.ownKeystone = own != null;
            return;
        }
        Set<String> fit = new LinkedHashSet<>();
        for (Object l : byClass.values()) {
            fit.addAll((List<String>) l);
        }
        if (own != null && fit.contains(own)) {
            k.keystone = own;
            k.ownKeystone = true;
            return;
        }
        for (String c : classes == null ? List.<String>of() : classes) {
            List<String> l = (List<String>) byClass.get(c);
            if (l != null && !l.isEmpty()) {
                k.keystone = l.get(0);
                return;
            }
        }
        k.keystone = own != null ? own : ((List<String>) byClass.values().iterator().next()).get(0);
        k.ownKeystone = own != null;
    }

    private static String weightedKeystone(List<Pick> byLane, double min) {
        Map<String, Double> votes = new LinkedHashMap<>();
        for (Pick p : byLane) {
            if (p.keystone != null && p.probability >= min) {
                votes.merge(p.keystone, p.probability, Double::sum);
            }
        }
        if (votes.isEmpty()) {
            return byLane.isEmpty() ? null : byLane.get(0).keystone;
        }
        return votes.entrySet().stream().max(Comparator.comparingDouble(Map.Entry::getValue)).get().getKey();
    }

    private static Pick summary(List<String> pair, double score, String lane, List<Pick> byLane, Map<String, Object> byLaneCfg) {
        Pick p = new Pick();
        p.lane = lane;
        p.spells = pair == null ? new ArrayList<>() : laneOrder(pair, lane, byLaneCfg);
        p.score = Math.round(score * 1000) / 1000.0;
        p.probability = 1;
        return p;
    }

    /** The pair in the order the lane lists it (the screen shows "Golpear + Flash"), else as given. */
    @SuppressWarnings("unchecked")
    private static List<String> laneOrder(List<String> pair, String lane, Map<String, Object> byLane) {
        for (Object o : (List<Object>) byLane.getOrDefault(lane, List.of())) {
            List<String> l = (List<String>) o;
            if (sorted(l).equals(pair)) {
                return new ArrayList<>(l);
            }
        }
        return new ArrayList<>(pair);
    }

    private static List<String> sorted(List<String> l) {
        List<String> out = new ArrayList<>(l);
        out.sort(String::compareTo);
        return out;
    }

    private static double num(Object o, double def) {
        return o instanceof Number ? ((Number) o).doubleValue() : def;
    }
}
