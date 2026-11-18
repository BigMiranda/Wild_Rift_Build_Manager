package com.ornnplanner.arena.draft;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaService;
import com.ornnplanner.arena.discovery.CampaignService;
import com.ornnplanner.arena.fight.ChampionProfiles;
import com.ornnplanner.arena.progressao.ProgressionService;
import com.ornnplanner.engine.Stats;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Rodada 20, Parte 6: tips on how to play against each enemy, for the selected ally, the lane rival first. No invented
 * text: every tip is a template (a key the screen words in pt or en) filled with measured numbers, and says where it came
 * from: an Arena measure (who wins the trade now and after each one's next item, and at which level it turns), the
 * sheet (control, dashes, ranges and cooldowns of the key abilities at the current rank) or the Rodada 14 guide (the
 * lane style that led most against the rival's subclass in the campaign).
 */
@Service
public class TipsService {

    /** Control effects of a sheet's ability that make a trade window (slows alone do not). */
    static final List<String> HARD = List.of("atordoa", "arremessa", "enraiza", "puxa", "silencia", "amedronta", "suprime",
            "provoca", "encanta", "adormece", "polimorfa", "afasta");

    public static class Tip {
        public String enemy;
        /** janela | kit | guia */
        public String kind;
        public String key;
        public Map<String, Object> vars = new LinkedHashMap<>();
        /** arena | ficha | guia */
        public String source;
        /** The sheet's quality (A..D) for the kit tips. */
        public String quality;
        /** The sheet's numbers are partly PC values or estimates (geometry EST): the tip may be off. */
        public boolean estimated;

        Tip(String enemy, String kind, String key, String source, Object... kv) {
            this.enemy = enemy;
            this.kind = kind;
            this.key = key;
            this.source = source;
            for (int i = 0; i + 1 < kv.length; i += 2) {
                vars.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        }
    }

    public static class Result {
        public String champion;
        public List<String> enemies = new ArrayList<>();
        public List<Tip> tips = new ArrayList<>();
        public double seconds;
    }

    private final ArenaService arena;
    private final LiveBuildsService builds;
    private final FirstEncounterService encounter;
    private final DraftTablesService tables;
    private final ProgressionService progression;
    private final ChampionProfiles profiles;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public TipsService(ArenaService arena, LiveBuildsService builds, FirstEncounterService encounter, DraftTablesService tables,
                       @Lazy ProgressionService progression, ChampionProfiles profiles,
                       org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.arena = arena;
        this.builds = builds;
        this.encounter = encounter;
        this.tables = tables;
        this.progression = progression;
        this.profiles = profiles;
    }

    /**
     * The tips for an ally card against the chosen enemies (card indexes of the other side; empty = the lane rival first,
     * then the others).
     */
    public Result run(LiveGameModel.Game g, String side, int index, List<Integer> against) {
        long t0 = System.nanoTime();
        LiveGameModel.Player me = g.team(side).players.get(index);
        if (me.champion == null) {
            throw new IllegalArgumentException("Escolha o campeão do cartão primeiro.");
        }
        String other = g.enemyOf(side);
        List<LiveGameModel.Player> them = g.team(other).players;
        List<Integer> order = new ArrayList<>();
        if (against != null && !against.isEmpty()) {
            order.addAll(against);
        } else {
            for (int i = 0; i < them.size(); i++) {
                if (them.get(i).champion != null && me.lane != null && me.lane.equals(them.get(i).lane)) {
                    order.add(i);
                }
            }
            for (int i = 0; i < them.size(); i++) {
                if (them.get(i).champion != null && !order.contains(i)) {
                    order.add(i);
                }
            }
        }
        Result r = new Result();
        r.champion = me.champion;
        double minute = LiveBuildsService.minuteOf(g);
        for (int i : order) {
            LiveGameModel.Player e = them.get(i);
            if (e.champion == null) {
                continue;
            }
            r.enemies.add(e.champion);
            windows(r, g, me, e, minute);
            kit(r, me, e, minute);
            guide(r, me, e);
        }
        r.seconds = (System.nanoTime() - t0) / 1e9;
        return r;
    }

    // ------------------------------------------------------------------ windows of strength (Arena)

    private double duel(Arena.Input a, Arena.Input b) {
        double s = 0;
        for (int k = 0; k < 2; k++) {
            s += tables.raceWithTrade(List.of(a), List.of(b), k, 30, -1)[0];
        }
        return s / 2;
    }

    /**
     * Who wins now and after each one's next item (the progression's most likely), and where it turns; before the first
     * items (under 4 minutes and nothing bought), levels 1 to 3 with each one's start.
     */
    private void windows(Result r, LiveGameModel.Game g, LiveGameModel.Player me, LiveGameModel.Player e, double minute) {
        boolean early = minute < 4 && me.items.isEmpty() && e.items.isEmpty();
        if (early) {
            long myStart = me.startItem != null ? me.startItem : encounter.likelyStarts(me.champion, List.of(e.champion), "Suporte".equals(me.lane)).get(0).itemId;
            long theirStart = e.startItem != null ? e.startItem : encounter.likelyStarts(e.champion, List.of(me.champion), "Suporte".equals(e.lane)).get(0).itemId;
            List<Callable<Double>> jobs = new ArrayList<>();
            double[] minutes = {0.5, 1.4, 2.2};
            for (int lv = 1; lv <= 3; lv++) {
                Arena.Input a = encounter.prepare(encounter.buildOf(me.champion, me), myStart, lv, minutes[lv - 1], 0);
                Arena.Input b = encounter.prepare(encounter.buildOf(e.champion, e), theirStart, lv, minutes[lv - 1], 1);
                jobs.add(() -> duel(a, b));
            }
            List<Double> v = ArenaService.parallel(jobs);
            Integer turns = null;
            for (int lv = 1; lv <= 3; lv++) {
                r.tips.add(new Tip(e.champion, "janela", "nivel", "arena", "n", lv, "who", who(v.get(lv - 1)), "v", round(v.get(lv - 1))));
                if (lv > 1 && turns == null && (v.get(lv - 1) - 50) * (v.get(lv - 2) - 50) < 0) {
                    turns = lv;
                }
            }
            if (turns != null) {
                r.tips.add(new Tip(e.champion, "janela", "viraNivel", "arena", "n", turns, "who", who(v.get(turns - 1))));
            }
            return;
        }
        ProgressionService.Answer mine = progression.answer(me.champion, me.items, me.components, List.of(e.champion), null, 1);
        ProgressionService.Answer theirs = progression.answer(e.champion, e.items, e.components, List.of(me.champion), null, 1);
        ProgressionService.Step myNext = mine.next.isEmpty() ? null : mine.next.get(0);
        ProgressionService.Step theirNext = theirs.next.isEmpty() ? null : theirs.next.get(0);
        double myAt = minuteOfNext(mine, minute);
        double theirAt = minuteOfNext(theirs, minute);
        List<Long> mineNow = held(me);
        List<Long> theirsNow = held(e);
        Arena.Input a0 = builds.fighter(me.champion, me, mineNow, minute, 0);
        Arena.Input b0 = builds.fighter(e.champion, e, theirsNow, minute, 1);
        List<Callable<Double>> jobs = new ArrayList<>();
        jobs.add(() -> duel(a0, b0));
        if (myNext != null) {
            List<Long> h = new ArrayList<>(me.items);
            h.add(myNext.itemId);
            Arena.Input a1 = builds.fighter(me.champion, me, h, myAt, 0);
            Arena.Input b1 = builds.fighter(e.champion, e, theirsNow, myAt, 1);
            jobs.add(() -> duel(a1, b1));
        }
        if (theirNext != null) {
            List<Long> h = new ArrayList<>(e.items);
            h.add(theirNext.itemId);
            Arena.Input a2 = builds.fighter(me.champion, me, mineNow, theirAt, 0);
            Arena.Input b2 = builds.fighter(e.champion, e, h, theirAt, 1);
            jobs.add(() -> duel(a2, b2));
        }
        List<Double> v = ArenaService.parallel(jobs);
        double now = v.get(0);
        r.tips.add(new Tip(e.champion, "janela", "agora", "arena", "m", round(minute), "who", who(now), "v", round(now)));
        int k = 1;
        if (myNext != null) {
            double x = v.get(k++);
            r.tips.add(new Tip(e.champion, "janela", "depoisMeu", "arena", "item", myNext.name, "m", round(myAt), "who", who(x), "v", round(x),
                    "turns", (x - 50) * (now - 50) < 0));
        }
        if (theirNext != null) {
            double x = v.get(k);
            r.tips.add(new Tip(e.champion, "janela", "depoisDele", "arena", "item", theirNext.name, "m", round(theirAt), "who", who(x), "v", round(x),
                    "turns", (x - 50) * (now - 50) < 0));
        }
    }

    private static List<Long> held(LiveGameModel.Player p) {
        List<Long> h = new ArrayList<>(p.items);
        h.addAll(p.components);
        return h;
    }

    /** The minute a champion's next item closes: the progression's (default pace), not before now. */
    private static double minuteOfNext(ProgressionService.Answer a, double now) {
        if (a.continuations.isEmpty() || a.continuations.get(0).steps.isEmpty() || a.continuations.get(0).steps.get(0).minute == null) {
            return now + 3;
        }
        return Math.max(now + 1, a.continuations.get(0).steps.get(0).minute);
    }

    static String who(double race) {
        return race >= 55 ? "voce" : race <= 45 ? "ele" : "empate";
    }

    // ------------------------------------------------------------------ threats of the kit (the sheet)

    @SuppressWarnings("unchecked")
    private void kit(Result r, LiveGameModel.Player me, LiveGameModel.Player e, double minute) {
        Map<String, Object> kits = (Map<String, Object>) arena.kits().get("campeoes");
        Map<String, Object> sheet = kits == null ? null : (Map<String, Object>) kits.get(e.champion);
        if (sheet == null) {
            return;
        }
        String quality = sheet.get("qualidade") == null ? null : String.valueOf(sheet.get("qualidade"));
        // the rank and haste now: the enemy's fighter at this minute with what it holds
        Arena.Input in = builds.fighter(e.champion, e, held(e), Math.max(1, minute), 1);
        int[] ranks = in.f.ranks;
        Map<String, Double> total = in.f.total;
        Map<String, Object> abilities = (Map<String, Object>) sheet.getOrDefault("habilidades", Map.of());
        Map<String, Object> mobility = (Map<String, Object>) sheet.getOrDefault("mobilidade", Map.of());
        int added = 0;
        for (String slot : List.of("1", "2", "3", "R")) {
            Map<String, Object> ab = (Map<String, Object>) abilities.get(slot);
            if (ab == null) {
                continue;
            }
            int idx = "R".equals(slot) ? 3 : Integer.parseInt(slot) - 1;
            int rank = ranks == null || idx >= ranks.length ? 1 : Math.max(1, ranks[idx]);
            if (ranks != null && idx < ranks.length && ranks[idx] == 0) {
                continue;   // not learned yet
            }
            Double cd = rankValue(ab.get("recarga"), rank);
            double factor = Stats.cooldownFactor(s -> total.getOrDefault(s, 0.0), slot);
            Double cdNow = cd == null ? null : cd * factor;
            List<String> cc = control(ab);
            Double range = number(ab.get("alcance"));
            boolean dash = mobility.containsKey(slot) || Boolean.TRUE.equals(ab.get("salta"));
            String name = String.valueOf(ab.getOrDefault("nome", slot));
            boolean est = estimated(ab);
            Tip t = null;
            if (!cc.isEmpty() && cdNow != null) {
                t = new Tip(e.champion, "kit", "janelaHabilidade", "ficha", "key", slot, "name", name, "cc", cc.get(0), "cd", round(cdNow), "rank", rank);
                r.tips.add(mark(t, quality, est));
                if (range != null && range >= 300) {
                    r.tips.add(mark(new Tip(e.champion, "kit", "foraDoAlcance", "ficha", "key", slot, "name", name, "cc", cc.get(0),
                            "range", (int) Math.round(range)), quality, est));
                }
                added++;
            } else if (dash && cdNow != null) {
                Object kind = mobility.getOrDefault(slot, "salto");
                r.tips.add(mark(new Tip(e.champion, "kit", "avanco", "ficha", "key", slot, "name", name, "mob", String.valueOf(kind),
                        "range", range == null ? null : (int) Math.round(range), "cd", round(cdNow)), quality, est));
                added++;
            } else if ("R".equals(slot) && cdNow != null) {
                r.tips.add(mark(new Tip(e.champion, "kit", "ultimate", "ficha", "name", name, "cd", round(cdNow), "rank", rank), quality, est));
                added++;
            }
            if (added >= 4) {
                break;
            }
        }
    }

    private static Tip mark(Tip t, String quality, boolean est) {
        t.quality = quality;
        t.estimated = est || "C".equals(quality) || "D".equals(quality);
        return t;
    }

    /** The ability's hard control effects (its own and its zones'), in the order of HARD. */
    @SuppressWarnings("unchecked")
    static List<String> control(Map<String, Object> ab) {
        List<Map<String, Object>> effs = new ArrayList<>();
        if (ab.get("efeitos") instanceof Map) {
            effs.add((Map<String, Object>) ab.get("efeitos"));
        }
        if (ab.get("zonas") instanceof List) {
            for (Object z : (List<Object>) ab.get("zonas")) {
                if (z instanceof Map && ((Map<String, Object>) z).get("efeitos") instanceof Map) {
                    effs.add((Map<String, Object>) ((Map<String, Object>) z).get("efeitos"));
                }
            }
        }
        List<String> out = new ArrayList<>();
        for (String h : HARD) {
            for (Map<String, Object> e : effs) {
                if (e.containsKey(h) && !out.contains(h)) {
                    out.add(h);
                }
            }
        }
        return out;
    }

    /** A sheet's numbers came partly from PC or are estimates ("fontes" says PC or EST; or a "lacuna"). */
    static boolean estimated(Map<String, Object> ab) {
        String f = String.valueOf(ab.getOrDefault("fontes", ""));
        return f.contains("EST") || f.contains("PC") || ab.containsKey("lacuna");
    }

    @SuppressWarnings("unchecked")
    static Double rankValue(Object v, int rank) {
        if (v instanceof List) {
            List<Object> l = (List<Object>) v;
            if (l.isEmpty()) {
                return null;
            }
            return number(l.get(Math.min(l.size(), rank) - 1));
        }
        return number(v);
    }

    static Double number(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : null;
    }

    // ------------------------------------------------------------------ the Rodada 14 guide (campaign lane styles)

    private void guide(Result r, LiveGameModel.Player me, LiveGameModel.Player e) {
        // the newest finished campaign of the champion that measured the lane styles (a later discovery alone has none)
        List<String> found = jdbc.queryForList("SELECT result FROM build_discovery WHERE unit_code = ? AND status = 'feito' "
                + "AND result LIKE '%\"styles\":[{%' ORDER BY id DESC LIMIT 1", String.class, tables.unitOf(me.champion));
        CampaignService.ChampionResult res;
        try {
            res = found.isEmpty() ? null : JSON.readValue(found.get(0), CampaignService.ChampionResult.class);
        } catch (Exception ex) {
            res = null;
        }
        if (res == null || res.styles == null || res.styles.isEmpty()) {
            return;
        }
        List<String> subs = profiles.subclassesOf(e.champion);
        // the rival itself when the campaign measured it, else its subclass
        for (CampaignService.LaneStyles l : res.styles) {
            if (e.champion.equals(l.rival) && l.best != null) {
                r.tips.add(new Tip(e.champion, "guia", "estiloRival", "guia", "style", l.best, "lead", round(l.leads.getOrDefault(l.best, 0.0))));
                return;
            }
        }
        Map<String, double[]> acc = new LinkedHashMap<>();
        List<String> rivals = new ArrayList<>();
        for (CampaignService.LaneStyles l : res.styles) {
            if (l.subclasses.stream().anyMatch(subs::contains)) {
                rivals.add(l.rival);
                l.leads.forEach((st, v) -> {
                    double[] a = acc.computeIfAbsent(st, k -> new double[2]);
                    a[0] += v;
                    a[1]++;
                });
            }
        }
        String best = null;
        double top = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, double[]> x : acc.entrySet()) {
            double m = x.getValue()[0] / x.getValue()[1];
            if (m > top) {
                top = m;
                best = x.getKey();
            }
        }
        if (best != null) {
            r.tips.add(new Tip(e.champion, "guia", "estiloSubclasse", "guia", "style", best, "lead", round(top),
                    "sub", subs.isEmpty() ? "" : subs.get(0), "rivals", String.join(", ", rivals)));
        }
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
