package com.ornnplanner.arena.progressao;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ornnplanner.arena.fight.ChampionProfiles;
import com.ornnplanner.arena.fight.CombatCatalog;
import com.ornnplanner.arena.guide.Guide;
import com.ornnplanner.arena.guide.GuideService;
import com.ornnplanner.engine.Model.ComponentRef;
import com.ornnplanner.engine.Model.ItemDef;
import com.ornnplanner.engine.Model.StatLine;
import com.ornnplanner.engine.ShopRuleCheck;
import com.ornnplanner.engine.TimelineEngine;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.repo.DiscoveryRepository;
import com.ornnplanner.seed.ChampionCatalog;
import com.ornnplanner.service.PlannerService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rodada 19: the natural progression of builds ({@link ProgressionModel}) over what the database knows: the Discovery's
 * builds of every stored campaign, the saved builds, the meta builds and the guides' paths; the shop's items with their
 * stats, effects and recipes; each champion's subclasses and usual archetype. The model is rebuilt when the sources
 * change.
 */
@Service
public class ProgressionService {

    private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final DiscoveryRepository discovery;
    private final BuildRepository builds;
    private final PlannerService planner;
    private final CombatCatalog combat;
    private final ChampionProfiles profiles;
    private final ChampionCatalog champions;
    private final GuideService guides;
    private final JdbcTemplate jdbc;
    private final Map<String, Object> seed;
    private ProgressionModel model;
    private String fingerprint;
    /** When the sources were last looked at: at most every few seconds (the Atlas asks thousands of times a guide). */
    private long checkedAt;
    private static final long CHECK_EVERY_NANOS = 5_000_000_000L;

    public ProgressionService(DiscoveryRepository discovery, BuildRepository builds, PlannerService planner, CombatCatalog combat,
                              ChampionProfiles profiles, ChampionCatalog champions, @Lazy GuideService guides, JdbcTemplate jdbc,
                              @Value("${planner.seed.progression:classpath:seed/progressao_7_3f.yml}") Resource file) {
        this.discovery = discovery;
        this.builds = builds;
        this.planner = planner;
        this.combat = combat;
        this.profiles = profiles;
        this.champions = champions;
        this.guides = guides;
        this.jdbc = jdbc;
        try (InputStream in = file.getInputStream()) {
            this.seed = new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + file, e);
        }
    }

    // ------------------------------------------------------------------ the model

    /** The model over the database as it is now (rebuilt when a source changed). */
    public synchronized ProgressionModel model() {
        long now = System.nanoTime();
        if (model != null && now - checkedAt < CHECK_EVERY_NANOS) {
            return model;
        }
        checkedAt = now;
        String fp = fingerprint();
        if (model == null || !fp.equals(fingerprint)) {
            model = new ProgressionModel(items(), trajectories(), settings());
            fingerprint = fp;
        }
        return model;
    }

    /** What changes when a source changes: counts and the last update of each table. */
    private String fingerprint() {
        StringBuilder sb = new StringBuilder();
        for (String sql : List.of(
                "SELECT COUNT(*) || '/' || COALESCE(MAX(updated_at), '') FROM build_discovery WHERE status = 'feito'",
                "SELECT COUNT(*) || '/' || COALESCE(MAX(updated_at), '') FROM build",
                "SELECT COUNT(*) || '/' || COALESCE(MAX(created_at), '') FROM meta_build",
                "SELECT COUNT(*) || '/' || COALESCE(MAX(updated_at), '') FROM build_guide")) {
            try {
                sb.append(jdbc.queryForObject(sql, String.class)).append(';');
            } catch (RuntimeException e) {
                sb.append("-;");
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    ProgressionModel.Settings settings() {
        ProgressionModel.Settings s = new ProgressionModel.Settings();
        if (seed.get("fontes") instanceof Map) {
            s.sources = new LinkedHashMap<>();
            ((Map<String, Object>) seed.get("fontes")).forEach((k, v) -> s.sources.put(k, num(v, 1)));
        }
        Map<String, Object> mix = (Map<String, Object>) seed.getOrDefault("mistura", Map.of());
        s.champion = num(mix.get("campeao"), s.champion);
        s.championItems = num(mix.get("campeao_itens"), s.championItems);
        s.subclass = num(mix.get("subclasse"), s.subclass);
        s.archetype = num(mix.get("arquetipo"), s.archetype);
        s.uniform = num(mix.get("uniforme"), s.uniform);
        s.overlapPower = num(seed.get("sobreposicao_expoente"), s.overlapPower);
        s.component = num(seed.get("componente"), s.component);
        if (seed.get("ritmos") instanceof Map) {
            s.paces = new LinkedHashMap<>();
            ((Map<String, Object>) seed.get("ritmos")).forEach((k, v) -> s.paces.put(k, num(v, 700)));
        }
        if (seed.get("pesos_ritmo") instanceof Map) {
            s.paceWeights = new LinkedHashMap<>();
            ((Map<String, Object>) seed.get("pesos_ritmo")).forEach((k, v) -> s.paceWeights.put(k, num(v, 0)));
        }
        s.startingGold = TimelineEngine.STARTING_GOLD;
        ShopRuleCheck rule = planner.shopRuleCheck();
        s.upgradedBootsMinute = rule == null ? 0 : rule.upgradedBootsMinute;
        s.beam = (int) num(seed.get("feixe"), s.beam);
        s.branches = (int) num(seed.get("ramos"), s.branches);
        s.composition = new ArrayList<>();
        for (Object o : (List<Object>) seed.getOrDefault("composicao", List.of())) {
            Map<String, Object> m = (Map<String, Object>) o;
            ProgressionModel.Rule r = new ProgressionModel.Rule();
            r.name = str(m.get("nome"));
            Map<String, Object> when = (Map<String, Object>) m.getOrDefault("quando", Map.of());
            r.magicMin = when.get("dano_magico") == null ? null : num(when.get("dano_magico"), 0);
            r.physicalMin = when.get("dano_fisico") == null ? null : num(when.get("dano_fisico"), 0);
            r.healsMin = when.get("curas") == null ? null : (int) num(when.get("curas"), 0);
            r.tanksMin = when.get("tanques") == null ? null : (int) num(when.get("tanques"), 0);
            ((Map<String, Object>) m.getOrDefault("status", Map.of())).forEach((k, v) -> r.stats.put(k, num(v, 1)));
            ((Map<String, Object>) m.getOrDefault("efeitos", Map.of())).forEach((k, v) -> r.effects.put(k, num(v, 1)));
            s.composition.add(r);
        }
        return s;
    }

    /** How many continuations the API gives by default. */
    public int defaultContinuations() {
        return (int) num(seed.get("continuacoes"), 5);
    }

    /** The shop's finished items and boots (tier 2 and 3), as the model sees them. */
    Map<Long, ProgressionModel.Item> items() {
        Map<Long, ItemDef> all = planner.referenceData().items;
        Set<Long> support = planner.supportItems();
        Map<Long, ProgressionModel.Item> out = new LinkedHashMap<>();
        for (ItemDef d : all.values()) {
            boolean finalBoots = d.boots() && ("tier_medio".equals(d.section) || "aprimorado".equals(d.section));
            boolean finalItem = !d.boots() && "aprimorado".equals(d.section);
            if (d.id <= 0 || !finalBoots && !finalItem) {
                continue;
            }
            ProgressionModel.Item it = new ProgressionModel.Item();
            it.id = d.id;
            it.name = d.name;
            it.cost = d.cost;
            it.boots = d.boots();
            it.upgradedBoots = ShopRuleCheck.upgradedBoots(d);
            it.support = support.contains(d.id);
            it.group = d.group;
            it.exclusive = d.exclusiveGroups == null ? new ArrayList<>() : new ArrayList<>(d.exclusiveGroups);
            for (StatLine l : d.stats) {
                if (l.passive == null && l.ratio == null && !l.conditional && l.perMinute == null && l.value != null && l.value != 0) {
                    it.stats.merge(l.type, l.value, Double::sum);
                }
            }
            for (Map<String, Object> e : combat.effects(d.name)) {
                if (e.get("tipo") != null) {
                    it.effects.add(String.valueOf(e.get("tipo")));
                }
            }
            flatten(d, 1, all, it.components, 0);
            out.put(it.id, it);
        }
        return out;
    }

    private static void flatten(ItemDef d, int times, Map<Long, ItemDef> all, Map<Long, Integer> out, int depth) {
        if (depth > 6 || d.components == null) {
            return;
        }
        for (ComponentRef c : d.components) {
            out.merge(c.itemId, c.quantity * times, Integer::sum);
            ItemDef sub = all.get(c.itemId);
            if (sub != null) {
                flatten(sub, c.quantity * times, all, out, depth + 1);
            }
        }
    }

    /** Every known build, in purchase order, of every source. */
    @SuppressWarnings("unchecked")
    List<ProgressionModel.Trajectory> trajectories() {
        Map<Long, ProgressionModel.Item> items = items();
        Map<String, Double> w = settings().sources;
        Map<String, Object> disc = (Map<String, Object>) seed.getOrDefault("descoberta", Map.of());
        List<ProgressionModel.Trajectory> out = new ArrayList<>();
        // the Discovery: every stored campaign
        for (DiscoveryRepository.DiscoveryRow row : discovery.discoveries(null)) {
            if (!"feito".equals(row.status) || row.result == null) {
                continue;
            }
            Map<String, Object> res;
            try {
                res = JSON.readValue(row.result, Map.class);
            } catch (Exception e) {
                continue;
            }
            Map<String, Object> d = (Map<String, Object>) res.get("discovery");
            if (d == null) {
                continue;
            }
            String champ = row.champion != null ? row.champion : nameOf(row.unitCode);
            Set<String> seen = new LinkedHashSet<>();
            List<Object[]> entries = new ArrayList<>();
            if (d.get("standard") instanceof Map) {
                entries.add(new Object[] {d.get("standard"), num(disc.get("padrao"), 2)});
            }
            for (Object o : d.get("ranked") instanceof List ? (List<Object>) d.get("ranked") : List.of()) {
                entries.add(new Object[] {o, num(disc.get("ranqueada"), 1)});
            }
            if (d.get("niches") instanceof Map) {
                for (Object o : ((Map<String, Object>) d.get("niches")).values()) {
                    entries.add(new Object[] {o, num(disc.get("nicho"), 0.6)});
                }
            }
            for (Object[] e : entries) {
                if (!(e[0] instanceof Map)) {
                    continue;
                }
                Map<String, Object> kit = (Map<String, Object>) e[0];
                List<Long> seq = kitSequence(kit, items);
                if (seq.size() < 2 || !seen.add(seq.toString())) {
                    continue;
                }
                ProgressionModel.Trajectory t = trajectory(champ, "descoberta", w.getOrDefault("descoberta", 1.0) * (double) e[1], seq);
                t.archetype = str(kit.get("archetype"));
                t.label = row.campaign;
                out.add(t);
            }
        }
        // the saved builds
        for (BuildRepository.Build s : builds.findBuildSummaries()) {
            BuildRepository.Build b = builds.findBuild(s.id).orElse(null);
            if (b == null || b.unitCode == null) {
                continue;
            }
            List<Long> seq = new ArrayList<>();
            for (BuildRepository.Step st : b.steps) {
                if (st.itemId != null && !st.sell() && items.containsKey(st.itemId) && !seq.contains(st.itemId)) {
                    seq.add(st.itemId);
                }
            }
            if (seq.size() >= 2) {
                ProgressionModel.Trajectory t = trajectory(nameOf(b.unitCode), "salvas", w.getOrDefault("salvas", 1.0), seq);
                t.label = b.name;
                out.add(t);
            }
        }
        // the meta builds: no order, the boots after the first item
        for (DiscoveryRepository.MetaBuild m : discovery.metaBuilds(null)) {
            List<Long> seq = new ArrayList<>();
            for (Long id : m.items) {
                if (items.containsKey(id) && !seq.contains(id)) {
                    seq.add(id);
                }
            }
            if (m.boots != null && items.containsKey(m.boots) && !seq.contains(m.boots)) {
                seq.add(Math.min(1, seq.size()), m.boots);
            }
            if (seq.size() >= 2) {
                ProgressionModel.Trajectory t = trajectory(nameOf(m.unitCode), "meta", w.getOrDefault("meta", 1.0), seq);
                t.label = m.source;
                out.add(t);
            }
        }
        // the guides' paths, each with its frequency
        for (GuideService.View g : guides.list(null)) {
            for (Guide.Path p : Guide.paths(g.nodes)) {
                List<Long> seq = new ArrayList<>();
                for (Guide.Node n : p.decisions) {
                    if (n.itemId != null && items.containsKey(n.itemId) && !seq.contains(n.itemId)) {
                        seq.add(n.itemId);
                    }
                }
                if (seq.size() >= 2 && p.weight > 0) {
                    ProgressionModel.Trajectory t = trajectory(nameOf(g.unitCode), "guias", w.getOrDefault("guias", 1.0) * p.weight, seq);
                    t.label = g.name;
                    out.add(t);
                }
            }
        }
        // a saved or meta build takes its champion's usual archetype (the Discovery's)
        Map<String, String> usual = usualArchetypes(out);
        for (ProgressionModel.Trajectory t : out) {
            if (t.archetype == null) {
                t.archetype = usual.get(t.champion);
            }
        }
        return out;
    }

    /** A Discovery kit's purchases in order: its finished items and boots as the order has them, then any left out. */
    @SuppressWarnings("unchecked")
    static List<Long> kitSequence(Map<String, Object> kit, Map<Long, ProgressionModel.Item> items) {
        Set<Long> mine = new LinkedHashSet<>();
        for (Object o : kit.get("items") instanceof List ? (List<Object>) kit.get("items") : List.of()) {
            mine.add(((Number) o).longValue());
        }
        if (kit.get("boots") instanceof Number) {
            mine.add(((Number) kit.get("boots")).longValue());
        }
        List<Long> seq = new ArrayList<>();
        for (Object o : kit.get("order") instanceof List ? (List<Object>) kit.get("order") : List.of()) {
            long id = ((Number) o).longValue();
            if (mine.contains(id) && items.containsKey(id) && !seq.contains(id)) {
                seq.add(id);
            }
        }
        for (Long id : mine) {
            if (items.containsKey(id) && !seq.contains(id)) {
                seq.add(id);
            }
        }
        return seq;
    }

    private ProgressionModel.Trajectory trajectory(String champ, String source, double weight, List<Long> seq) {
        ProgressionModel.Trajectory t = new ProgressionModel.Trajectory();
        t.champion = champ;
        t.source = source;
        t.weight = weight;
        t.items = seq;
        t.subclasses = champ == null ? List.of() : profiles.subclassesOf(champ);
        return t;
    }

    /** Each champion's most weighted archetype among its builds that have one. */
    static Map<String, String> usualArchetypes(Collection<ProgressionModel.Trajectory> ts) {
        Map<String, Map<String, Double>> by = new LinkedHashMap<>();
        for (ProgressionModel.Trajectory t : ts) {
            if (t.archetype != null && t.champion != null) {
                by.computeIfAbsent(t.champion, k -> new LinkedHashMap<>()).merge(t.archetype, t.weight, Double::sum);
            }
        }
        Map<String, String> out = new HashMap<>();
        by.forEach((c, m) -> m.entrySet().stream().max(Map.Entry.<String, Double>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .ifPresent(e -> out.put(c, e.getKey())));
        return out;
    }

    // ------------------------------------------------------------------ contexts

    /** A champion's name from its unit code or name (as given when unknown). */
    public String nameOf(String unitOrName) {
        if (unitOrName == null) {
            return null;
        }
        return champions.find(unitOrName.toUpperCase()).map(m -> String.valueOf(m.get("nome")))
                .orElseGet(() -> champions.find(ChampionCatalog.code(unitOrName)).map(m -> String.valueOf(m.get("nome"))).orElse(unitOrName));
    }

    /** The context of a champion holding these items and components, against these enemies (names or unit codes). */
    public ProgressionModel.Context context(String champion, List<Long> held, List<Long> components, List<String> enemies, Boolean support) {
        return context(model(), champion, held, components, enemies, support);
    }

    ProgressionModel.Context context(ProgressionModel m, String champion, List<Long> held, List<Long> components, List<String> enemies,
                                     Boolean support) {
        ProgressionModel.Context c = new ProgressionModel.Context();
        c.champion = nameOf(champion);
        c.subclasses = profiles.subclassesOf(c.champion);
        c.held = new ArrayList<>();
        for (Long id : held == null ? List.<Long>of() : held) {
            if (m.item(id) != null && !c.held.contains(id)) {
                c.held.add(id);
            }
        }
        c.components = components == null ? new ArrayList<>() : new ArrayList<>(components);
        c.enemies = composition(enemies);
        c.archetype = archetypeOf(c.champion, c.subclasses, m.known());
        boolean ownSupport = m.known().stream().anyMatch(t -> c.champion != null && c.champion.equalsIgnoreCase(t.champion)
                && t.items.stream().anyMatch(id -> m.item(id) != null && m.item(id).support));
        c.support = support != null ? support : ownSupport || c.held.stream().anyMatch(id -> m.item(id).support);
        return c;
    }

    /** The champion's usual archetype; with no build of its own, the usual one of its subclass. */
    static String archetypeOf(String champion, List<String> subclasses, List<ProgressionModel.Trajectory> known) {
        Map<String, Double> own = new LinkedHashMap<>();
        Map<String, Double> alike = new LinkedHashMap<>();
        for (ProgressionModel.Trajectory t : known) {
            if (t.archetype == null) {
                continue;
            }
            if (champion != null && champion.equalsIgnoreCase(t.champion)) {
                own.merge(t.archetype, t.weight, Double::sum);
            } else if (t.subclasses.stream().anyMatch(subclasses::contains)) {
                alike.merge(t.archetype, t.weight, Double::sum);
            }
        }
        Map<String, Double> m = own.isEmpty() ? alike : own;
        return m.entrySet().stream().max(Map.Entry.<String, Double>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).orElse(null);
    }

    /** The enemies as the composition rules read them, from their profiles. */
    public ProgressionModel.Composition composition(List<String> enemies) {
        ProgressionModel.Composition c = new ProgressionModel.Composition();
        for (String e : enemies == null ? List.<String>of() : enemies) {
            if (e == null || e.isBlank()) {
                continue;
            }
            ChampionProfiles.Profile p = profiles.of(nameOf(e.trim()));
            if (p == null) {
                continue;
            }
            c.enemies++;
            c.magic += p.magic;
            c.physical += p.physical;
            if (p.heals > 0) {
                c.heals++;
            }
            if (p.mainClasses.contains("Tanque")) {
                c.tanks++;
            }
        }
        if (c.enemies > 0) {
            c.magic /= c.enemies;
            c.physical /= c.enemies;
        }
        return c;
    }

    // ------------------------------------------------------------------ the API's answers

    /** One item with its probability (and, in a continuation, its minute at each pace). */
    public static class Step {
        public long itemId;
        public String name;
        public Integer cost;
        public double probability;
        public Double minute;
        public Double minuteBehind;
        public Double minuteAhead;
    }

    public static class ContinuationView {
        public double probability;
        public List<Step> steps = new ArrayList<>();
    }

    public static class Answer {
        public String champion;
        public String archetype;
        public List<String> subclasses = new ArrayList<>();
        public List<Step> held = new ArrayList<>();
        public List<Step> components = new ArrayList<>();
        public ProgressionModel.Composition enemies;
        public boolean support;
        /** Known builds of the champion the model reads (by source). */
        public Map<String, Integer> known = new LinkedHashMap<>();
        /** The next item's distribution (its most likely first; the whole sums to 1). */
        public List<Step> next = new ArrayList<>();
        public List<ContinuationView> continuations = new ArrayList<>();
        /** The probability the continuations shown cover together. */
        public double mass;
        public Map<String, Double> paces = new LinkedHashMap<>();
    }

    /** The next item and the k most likely continuations to a complete build. */
    public Answer answer(String champion, List<Long> held, List<Long> components, List<String> enemies, Boolean support, Integer k) {
        return answer(champion, held, components, enemies, support, null, k);
    }

    /** As above, leaning to an archetype (a key of the classification; null = the champion's usual one). */
    public Answer answer(String champion, List<Long> held, List<Long> components, List<String> enemies, Boolean support,
                         String archetype, Integer k) {
        ProgressionModel m = model();
        ProgressionModel.Context ctx = context(m, champion, held, components, enemies, support);
        if (archetype != null) {
            ctx.archetype = archetype;
        }
        Answer a = new Answer();
        a.champion = ctx.champion;
        a.archetype = ctx.archetype;
        a.subclasses = ctx.subclasses;
        a.enemies = ctx.enemies;
        a.support = ctx.support;
        a.paces = m.settings().paces;
        ctx.held.forEach(id -> a.held.add(step(m, id, 1)));
        ctx.components.forEach(id -> {
            ItemDef d = planner.referenceData().items.get(id);
            Step s = new Step();
            s.itemId = id;
            s.name = d == null ? String.valueOf(id) : d.name;
            s.cost = d == null ? null : d.cost;
            a.components.add(s);
        });
        for (ProgressionModel.Trajectory t : m.known()) {
            if (ctx.champion != null && ctx.champion.equalsIgnoreCase(t.champion)) {
                a.known.merge(t.source, 1, Integer::sum);
            }
        }
        m.next(ctx).forEach((id, p) -> a.next.add(step(m, id, p)));
        for (ProgressionModel.Continuation c : m.continuations(ctx, k == null || k <= 0 ? defaultContinuations() : k)) {
            ContinuationView v = new ContinuationView();
            v.probability = c.probability;
            a.mass += c.probability;
            for (int i = 0; i < c.items.size(); i++) {
                Step s = step(m, c.items.get(i), c.stepProbability.get(i));
                s.minute = at(c.minutes.get("padrao"), i);
                s.minuteBehind = at(c.minutes.get("atras"), i);
                s.minuteAhead = at(c.minutes.get("frente"), i);
                v.steps.add(s);
            }
            a.continuations.add(v);
        }
        return a;
    }

    private static Double at(List<Double> l, int i) {
        return l == null || i >= l.size() ? null : l.get(i);
    }

    private static Step step(ProgressionModel m, long id, double p) {
        ProgressionModel.Item it = m.item(id);
        Step s = new Step();
        s.itemId = id;
        s.name = it == null ? String.valueOf(id) : it.name;
        s.cost = it == null ? null : it.cost;
        s.probability = p;
        return s;
    }

    /** n builds of the champion drawn at a minute, from what it holds (the Atlas's other side). */
    public List<ProgressionModel.Sample> sample(String champion, List<Long> held, List<Long> components, List<String> enemies,
                                                Boolean support, double minute, int n, long seedValue) {
        return sample(champion, held, components, enemies, support, null, minute, n, seedValue);
    }

    /** As above, leaning to an archetype (null = the champion's usual one). */
    public List<ProgressionModel.Sample> sample(String champion, List<Long> held, List<Long> components, List<String> enemies,
                                                Boolean support, String archetype, double minute, int n, long seedValue) {
        ProgressionModel.Context ctx = context(champion, held, components, enemies, support);
        if (archetype != null) {
            ctx.archetype = archetype;
        }
        return model().sample(ctx, minute, Math.max(1, Math.min(500, n)), seedValue);
    }

    /** The leave-one-out check over the champions asked (all the known ones when empty). */
    public ProgressionModel.Check validate(List<String> championsAsked) {
        ProgressionModel m = model();
        List<String> names = new ArrayList<>();
        for (String c : championsAsked == null ? List.<String>of() : championsAsked) {
            if (c != null && !c.isBlank()) {
                names.add(nameOf(c.trim()));
            }
        }
        Map<String, ProgressionModel.Context> made = new HashMap<>();
        return m.leaveOneOut(names, t -> made.computeIfAbsent(t.champion, c -> context(m, c, List.of(), List.of(), List.of(), null)).copy());
    }

    private static double num(Object o, double def) {
        return o instanceof Number ? ((Number) o).doubleValue() : def;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
