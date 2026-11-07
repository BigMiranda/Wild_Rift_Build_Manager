package com.ornnplanner.arena.guide;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ornnplanner.arena.EffectModel;
import com.ornnplanner.arena.EffectTestService;
import com.ornnplanner.arena.calc.BuildScore;
import com.ornnplanner.arena.calc.CalculatorService;
import com.ornnplanner.arena.discovery.DiscoveryModel;
import com.ornnplanner.arena.discovery.DiscoveryService;
import com.ornnplanner.arena.fight.BuildArchetypes;
import com.ornnplanner.engine.Model;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.repo.DiscoveryRepository;
import com.ornnplanner.service.PlannerService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The guides (Rodada 9, part 7): saving them ({@code build_guide}), reading one against a match (the enemies' builds, the
 * lane opponent, gold, minute), turning it into a purchase order by the planner's timeline, weighing it over its
 * branches, and suggesting one from the calculator's tree of both sides or from a discovery.
 */
@Service
public class GuideService {

    private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** A guide as the screens see it. */
    public static class View {
        public Long id;
        public String unitCode;
        public String name;
        public List<Guide.Node> nodes = new ArrayList<>();
        public String updatedAt;
        /** Goes to the public version's export (Rodada 11). */
        public boolean publico;
    }

    /** A match to read a guide against: builds (saved) of the enemies, the lane opponent among them, gold and minute. */
    public static class Match {
        public Long buildId;
        public BuildRepository.Build build;
        public List<Long> enemies = new ArrayList<>();
        public Long laneOpponent;
        public double goldDiff;
        public Double minute;
    }

    /** What a reading gives: the decisions on the way and the build they make. */
    public static class Reading {
        public List<Guide.Node> decisions = new ArrayList<>();
        public List<Long> order = new ArrayList<>();
        public BuildRepository.RunePage runePage;
        public Guide.Context context;
    }

    /** A guide's evaluation: each path's score and weight, and the weighted mean. */
    public static class Evaluation {
        public double score;
        public List<Map<String, Object>> paths = new ArrayList<>();
    }

    public static class Suggest {
        public String unitCode;
        public Long calcJobId;
        public Long discoveryJobId;
        /** Read the champion's newest stored discovery when no job is given. */
        public boolean latest = true;
    }

    private final DiscoveryRepository repo;
    private final BuildRepository builds;
    private final PlannerService planner;
    private final BuildScore scorer;
    private final BuildArchetypes archetypes;
    private final CalculatorService calc;
    private final DiscoveryService discovery;
    private final EffectTestService effect;

    public GuideService(DiscoveryRepository repo, BuildRepository builds, PlannerService planner, BuildScore scorer,
                        BuildArchetypes archetypes, CalculatorService calc, DiscoveryService discovery, EffectTestService effect) {
        this.repo = repo;
        this.builds = builds;
        this.planner = planner;
        this.scorer = scorer;
        this.archetypes = archetypes;
        this.calc = calc;
        this.discovery = discovery;
        this.effect = effect;
    }

    // ------------------------------------------------------------------ storage

    public List<View> list(String unit) {
        List<View> out = new ArrayList<>();
        repo.guides(unit).forEach(g -> out.add(view(g)));
        return out;
    }

    public View get(long id) {
        DiscoveryRepository.GuideRow g = repo.guide(id);
        if (g == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return view(g);
    }

    public View save(View v) {
        if (v.unitCode == null || v.unitCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uma guia é de um campeão.");
        }
        DiscoveryRepository.GuideRow g = new DiscoveryRepository.GuideRow();
        g.id = v.id;
        g.unitCode = v.unitCode;
        g.name = v.name == null || v.name.isBlank() ? "Guia" : v.name.trim();
        g.tree = write(v.nodes);
        g.publico = v.publico;
        return view(repo.saveGuide(g));
    }

    public void delete(long id) {
        repo.deleteGuide(id);
    }

    private static View view(DiscoveryRepository.GuideRow g) {
        View v = new View();
        v.id = g.id;
        v.unitCode = g.unitCode;
        v.name = g.name;
        v.updatedAt = g.updatedAt;
        v.publico = g.publico;
        try {
            v.nodes = JSON.readValue(g.tree, new TypeReference<List<Guide.Node>>() { });
        } catch (Exception e) {
            v.nodes = new ArrayList<>();
        }
        return v;
    }

    // ------------------------------------------------------------------ reading

    /** One enemy from a saved build: champion, class, the build's archetype, its damage and finished items. */
    public Guide.Enemy enemy(BuildRepository.Build b) {
        Guide.Enemy e = new Guide.Enemy();
        e.champion = scorer.championOf(b);
        e.championClass = scorer.classOf(b);
        BuildArchetypes.Archetype a = archetypes.of(b, null);
        e.archetype = a == null ? null : a.key;
        Map<Long, Model.ItemDef> items = planner.referenceData().items;
        Map<String, Double> prices = planner.referenceData().statPrices;
        double phys = 0;
        double mag = 0;
        for (BuildRepository.Step st : b.steps) {
            Model.ItemDef d = st.itemId == null ? null : items.get(st.itemId);
            if (d == null) {
                continue;
            }
            if ("aprimorado".equals(d.section) && !d.boots()) {
                e.items.add(d.id);
            }
            for (Model.StatLine l : d.stats) {
                double g = (l.value == null ? 0 : l.value) * prices.getOrDefault(l.type, 0.0);
                if ("Ability Power".equals(l.type) || l.type != null && l.type.contains("Magic Penetration")) {
                    mag += g;
                } else if ("Attack Damage".equals(l.type) || l.type != null && (l.type.contains("Armor Penetration") || l.type.contains("Critical"))) {
                    phys += g;
                }
            }
        }
        e.damage = phys + mag < 1 ? null : phys > 2 * mag ? "fisico" : mag > 2 * phys ? "magico" : "misto";
        return e;
    }

    public Guide.Context context(Match m) {
        Guide.Context ctx = new Guide.Context();
        for (long id : m.enemies) {
            BuildRepository.Build b = find(id);
            Guide.Enemy e = enemy(b);
            ctx.enemies.add(e);
            if (m.laneOpponent != null && m.laneOpponent == id) {
                ctx.laneOpponent = e;
            }
        }
        ctx.goldDiff = m.goldDiff;
        ctx.minute = m.minute == null ? 0 : m.minute;
        return ctx;
    }

    /**
     * A guide read against a match, as a purchase order: the decisions are taken one at a time, each with the minute the
     * planner's timeline reaches with the ones before (so "a partir dos 10 min" turns when the build gets there).
     */
    public Reading resolve(View g, Match m) {
        BuildRepository.Build base = m.build != null ? m.build : find(m.buildId);
        Guide.Context ctx = context(m);
        Reading r = new Reading();
        r.context = ctx;
        double start = ctx.minute;
        for (int guard = 0; guard < 20; guard++) {
            BuildRepository.Build b = withOrder(base, r.order);
            ctx.minute = Math.max(start, lastMinute(b));
            List<Guide.Node> path = Guide.read(g.nodes, ctx);
            List<Guide.Node> buys = new ArrayList<>();
            for (Guide.Node n : path) {
                if ("runas".equals(n.decision)) {
                    r.runePage = n.runePage;
                } else if (n.itemId != null) {
                    buys.add(n);
                }
            }
            if (buys.size() <= r.order.size()) {
                r.decisions = path;
                break;
            }
            r.order.add(buys.get(r.order.size()).itemId);
            r.decisions = path;
        }
        return r;
    }

    public BuildRepository.Build withOrderPublic(BuildRepository.Build base, List<Long> order) {
        return withOrder(base, order);
    }

    /** The base build with this order (and nothing else changed). */
    BuildRepository.Build withOrder(BuildRepository.Build base, List<Long> order) {
        BuildRepository.Build b = JSON.convertValue(base, BuildRepository.Build.class);
        b.id = null;
        b.steps = new ArrayList<>();
        for (long id : order) {
            b.steps.add(new BuildRepository.Step(id, true));
        }
        return b;
    }

    private double lastMinute(BuildRepository.Build b) {
        if (b.steps.isEmpty()) {
            return 0;
        }
        double m = 0;
        for (Model.TimelineStep s : planner.calculate(b).steps) {
            if (!s.ignored) {
                m = s.minute;
            }
        }
        return m;
    }

    /**
     * A guide's worth: each path through its conditions becomes a build (its decisions in order, its page of runes), the
     * robust class score measures it, and the paths weigh by the frequency of their conditions in the scenarios.
     */
    public Evaluation evaluate(View g, BuildRepository.Build base) {
        Evaluation ev = new Evaluation();
        double sum = 0;
        double w = 0;
        for (Guide.Path p : Guide.paths(g.nodes)) {
            List<Long> order = new ArrayList<>();
            BuildRepository.RunePage page = null;
            for (Guide.Node n : p.decisions) {
                if ("runas".equals(n.decision)) {
                    page = n.runePage;
                } else if (n.itemId != null && !order.contains(n.itemId)) {
                    order.add(n.itemId);
                }
            }
            BuildRepository.Build b = withOrder(base, order);
            if (page != null) {
                b.runePage = page;
            }
            double s = order.isEmpty() ? 0 : scorer.robust(b, null).total;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("choices", p.choices);
            row.put("order", order);
            row.put("weight", p.weight);
            row.put("score", s);
            ev.paths.add(row);
            sum += p.weight * s;
            w += p.weight;
        }
        ev.score = w > 0 ? sum / w : 0;
        return ev;
    }

    // ------------------------------------------------------------------ suggestions

    /**
     * A suggested guide. From the calculator's tree of both sides: "if the opponent rushes X, our best answer to it;
     * otherwise the maximin line". From a discovery: "2 or more enemies with high magic damage → the found build best
     * against the magic scenarios; 2 or more with physical → the best against the physical ones; otherwise the robust
     * standard build". The scenarios are magic or physical by their attackers' roles (the seed's), never by name.
     */
    public View suggest(Suggest s) {
        View v = new View();
        v.unitCode = s.unitCode;
        if (s.calcJobId != null) {
            CalculatorService.Answer a = calc.job(s.calcJobId).answer;
            if (a == null || a.matrix == null || a.rows.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A calculadora precisa da árvore dos dois lados.");
            }
            v.name = "Sugerida pela árvore dos dois lados";
            List<Guide.Node> chain = v.nodes;
            java.util.Set<Long> seen = new java.util.HashSet<>();
            for (int c = 0; c < a.answers.size(); c++) {
                long first = a.answers.get(c).order.get(0);
                if (!seen.add(first)) {
                    continue;
                }
                int best = 0;
                for (int r = 1; r < a.matrix.length; r++) {
                    if (a.matrix[r][c] > a.matrix[best][c]) {
                        best = r;
                    }
                }
                CalculatorService.TreeRow row = rowOf(a, best);
                Guide.Node cond = condition("item_adversario", null, null);
                cond.cond.itemId = first;
                cond.note = "o adversário rusha " + a.answers.get(c).names.get(0);
                cond.then = decisions(row.ours.order);
                chain.add(cond);
                chain = cond.otherwise;
            }
            CalculatorService.TreeRow safe = a.maximin == null ? a.rows.get(0) : rowOf(a, a.maximin);
            chain.addAll(decisions(safe.ours.order));
            return v;
        }
        DiscoveryModel.Result res = null;
        if (s.discoveryJobId != null) {
            res = discovery.job(s.discoveryJobId).result;
        } else if (s.latest && s.unitCode != null) {
            DiscoveryRepository.DiscoveryRow row = repo.latestDone(s.unitCode, patch());
            if (row != null && row.result != null) {
                try {
                    Map<String, Object> m = JSON.readValue(row.result, new TypeReference<Map<String, Object>>() { });
                    res = JSON.convertValue(m.get("discovery"), DiscoveryModel.Result.class);
                } catch (Exception e) {
                    res = null;
                }
            }
        }
        if (res == null || res.standard == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rode a Descoberta (ou a calculadora com a árvore) deste campeão antes.");
        }
        v.name = "Sugerida pela Descoberta";
        java.util.Set<String> magic = new java.util.HashSet<>();
        java.util.Set<String> physical = new java.util.HashSet<>();
        effect.presets().forEach((key, t) -> {
            boolean allMagic = !t.attackers.isEmpty() && t.attackers.stream().allMatch(b -> "mago".equals(b.role));
            boolean allPhysical = !t.attackers.isEmpty() && t.attackers.stream().allMatch(b -> b.attackDamage > 0 && !"mago".equals(b.role));
            if (allMagic) {
                magic.add(BuildScore.DEFENSIVE + "|" + key);
            } else if (allPhysical) {
                physical.add(BuildScore.DEFENSIVE + "|" + key);
            }
        });
        DiscoveryModel.KitView bestMagic = bestOn(res, magic);
        DiscoveryModel.KitView bestPhysical = bestOn(res, physical);
        Guide.Node m = condition("inimigos_dano", "magico", 2);
        m.then = kitDecisions(bestMagic == null ? res.standard : bestMagic);
        Guide.Node p = condition("inimigos_dano", "fisico", 2);
        p.then = kitDecisions(bestPhysical == null ? res.standard : bestPhysical);
        p.otherwise = kitDecisions(res.standard);
        m.otherwise.add(p);
        v.nodes.add(m);
        return v;
    }

    private String patch() {
        return discovery.patch();
    }

    private static CalculatorService.TreeRow rowOf(CalculatorService.Answer a, int matrixRow) {
        return a.rows.stream().filter(r -> r.matrixRow == matrixRow).findFirst().orElse(a.rows.get(0));
    }

    /** The found build with the best mean points on these scenarios (any minute). */
    private static DiscoveryModel.KitView bestOn(DiscoveryModel.Result res, java.util.Set<String> scenarios) {
        DiscoveryModel.KitView best = null;
        double top = Double.NEGATIVE_INFINITY;
        List<DiscoveryModel.KitView> all = new ArrayList<>(res.ranked);
        all.addAll(res.niches.values());
        for (DiscoveryModel.KitView k : all) {
            double sum = 0;
            int n = 0;
            for (Map.Entry<String, Double> e : k.readings.entrySet()) {
                String partKey = e.getKey().substring(e.getKey().indexOf('|') + 1);
                if (scenarios.contains(partKey)) {
                    sum += e.getValue();
                    n++;
                }
            }
            if (n > 0 && sum / n > top) {
                top = sum / n;
                best = k;
            }
        }
        return best;
    }

    private static Guide.Node condition(String type, String value, Integer count) {
        Guide.Node n = new Guide.Node();
        n.kind = "condicao";
        n.cond = new Guide.Condition();
        n.cond.type = type;
        n.cond.value = value;
        n.cond.count = count;
        return n;
    }

    private List<Guide.Node> decisions(List<Long> order) {
        List<Guide.Node> out = new ArrayList<>();
        Map<Long, Model.ItemDef> items = planner.referenceData().items;
        for (long id : order) {
            Guide.Node n = new Guide.Node();
            Model.ItemDef d = items.get(id);
            n.decision = d != null && d.boots() ? "bota" : d != null && !"aprimorado".equals(d.section) ? "componente" : "item";
            n.itemId = id;
            out.add(n);
        }
        return out;
    }

    private List<Guide.Node> kitDecisions(DiscoveryModel.KitView k) {
        List<Long> order = k.order != null && !k.order.isEmpty() ? k.order : new ArrayList<>(k.items);
        List<Guide.Node> out = decisions(order);
        if (k.runePage != null) {
            Guide.Node r = new Guide.Node();
            r.decision = "runas";
            r.runePage = k.runePage;
            out.add(0, r);
        }
        return out;
    }

    private BuildRepository.Build find(Long id) {
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha uma build.");
        }
        return builds.findBuild(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Build " + id + " não existe."));
    }

    static String write(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
