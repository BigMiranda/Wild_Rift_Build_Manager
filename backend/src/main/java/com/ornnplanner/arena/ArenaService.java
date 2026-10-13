package com.ornnplanner.arena;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ornnplanner.arena.ArenaModel.Scenario;
import com.ornnplanner.arena.ArenaModel.Slot;
import com.ornnplanner.arena.ai.NeuralPolicy;
import com.ornnplanner.arena.ai.PolicyRepository;
import com.ornnplanner.arena.ai.TacticalPolicy;
import com.ornnplanner.combat.CombatCatalog;
import com.ornnplanner.combat.CombatService;
import com.ornnplanner.combat.CombatSimulator.Fighter;
import com.ornnplanner.combat.CombatSimulator.ItemEffect;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.seed.RuneCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrated matchups: runs a scenario on the arena (each champion from a saved build at a minute, with its ability
 * sheet from {@code kits_<patch>.yml}) and stores scenarios (table arena_scenario, as JSON).
 */
@Service
public class ArenaService {

    /** Up to 5 per side, 6 with clones (Wukong). */
    public static final int MAX_TEAM = 6;
    public static final double MAX_DURATION = 180;

    /** Cooldowns of item actives the combat file does not carry (s). */
    private static final Map<String, Double> ITEM_COOLDOWNS = Map.of(
            "Ampulheta de Zhonya", 90.0, "Armaguarda da Caçadora", 150.0, "Placa Gargolítica", 60.0,
            "Medalhão dos Solari de Ferro", 60.0, "Bênção de Mikael", 120.0, "Cimitarra Mercurial", 90.0,
            "Bandana de Mercúrio", 90.0);

    private final CombatService combat;
    private final CombatCatalog catalog;
    private final BuildRepository builds;
    private final RuneCatalog runes;
    private final JdbcTemplate jdbc;
    private final PolicyRepository policies;
    private final Map<String, Object> kits = new LinkedHashMap<>();
    private final Map<String, Object> map = new LinkedHashMap<>();
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @SuppressWarnings("unchecked")
    public ArenaService(CombatService combat, CombatCatalog catalog, BuildRepository builds, RuneCatalog runes,
                        JdbcTemplate jdbc, PolicyRepository policies, @Value("${planner.seed.kits:classpath:seed/kits_7_3.yml}") Resource kitsFile) {
        this.combat = combat;
        this.catalog = catalog;
        this.builds = builds;
        this.runes = runes;
        this.jdbc = jdbc;
        this.policies = policies;
        try (InputStream in = kitsFile.getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            kits.putAll((Map<String, Object>) root.get("campeoes"));
            map.putAll((Map<String, Object>) root.get("mapa"));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + kitsFile, e);
        }
    }

    /** The ability sheets and the map, for the screen (ranges to draw, shapes, sources). */
    public Map<String, Object> kits() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mapa", map);
        out.put("campeoes", kits);
        return out;
    }

    public ArenaModel.Result run(Scenario sc) {
        List<Arena.Input> inputs = prepare(sc);
        return new Arena(catalog, map).run(inputs, sc.actions == null ? List.of() : sc.actions, duration(sc));
    }

    static double duration(Scenario sc) {
        return Math.max(5, Math.min(MAX_DURATION, sc.duration <= 0 ? 60 : sc.duration));
    }

    // ------------------------------------------------------------------ automatic search

    public static class SearchRequest {
        public Scenario scenario;
        /** The champion whose timeline is searched (index in the scenario). */
        public int fighter;
        /** win_fast | damage | survive | team */
        public String objective = "win_fast";
        public int iterations = 300;
        public long seed = 1;
    }

    public static class SearchResult {
        public List<ArenaModel.Action> actions = new ArrayList<>();
        public double score;
        public double baseline;
        public int tried;
        public int improved;
        /** Best score after each improvement: {iteration, score}. */
        public List<double[]> progress = new ArrayList<>();
        public String summary;
        public String baselineSummary;
    }

    /**
     * Trial and error on one champion's timeline: from the scenario's orders, each try changes them (adds an order at
     * a random second, removes one, moves one, swaps two, changes its ability), runs the fight without frames and keeps
     * the change when the objective improves (on a tie, the shorter timeline). Deterministic for a seed.
     */
    public SearchResult search(SearchRequest req) {
        Scenario sc = req.scenario;
        if (sc == null || req.fighter < 0 || req.fighter >= sc.fighters.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha o campeão da busca.");
        }
        List<Arena.Input> inputs = prepare(sc);
        double duration = duration(sc);
        int me = req.fighter;
        int team = sc.fighters.get(me).team;
        Arena.Input mine = inputs.get(me);
        List<String> choices = new ArrayList<>();
        for (String slot : List.of("1", "2", "3", "R")) {
            int i = "R".equals(slot) ? 3 : Integer.parseInt(slot) - 1;
            if (mine.f.ranks[i] > 0) {
                choices.add("ability:" + slot);
            }
        }
        if (mine.kit != null && mine.kit.get("habilidades") instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) mine.kit.get("habilidades")).entrySet()) {
                if (e.getValue() instanceof Map && ((Map<?, ?>) e.getValue()).get("reconjurar") != null
                        && choices.contains("ability:" + e.getKey())) {
                    choices.add("recast:" + e.getKey());
                }
            }
        }
        mine.activeCooldowns.keySet().forEach(n -> choices.add("active:" + n));
        if (choices.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Este campeão não tem o que ordenar.");
        }
        Integer enemy = sc.fighters.get(me).target;
        for (int i = 0; enemy == null && i < sc.fighters.size(); i++) {
            if (sc.fighters.get(i).team != team) {
                enemy = i;
            }
        }
        List<ArenaModel.Action> others = new ArrayList<>();
        List<ArenaModel.Action> best = new ArrayList<>();
        for (ArenaModel.Action a : sc.actions == null ? List.<ArenaModel.Action>of() : sc.actions) {
            (a.fighter == me ? best : others).add(copy(a));
        }
        java.util.Random rnd = new java.util.Random(req.seed);
        SearchResult out = new SearchResult();
        ArenaModel.Result base = simulate(inputs, others, best, duration);
        double bestScore = score(base, req.objective, me, team);
        out.baseline = bestScore;
        out.baselineSummary = summary(base, me, team);
        out.progress.add(new double[] {0, bestScore});
        int iterations = Math.max(1, Math.min(3000, req.iterations));
        for (int it = 1; it <= iterations; it++) {
            List<ArenaModel.Action> cand = mutate(best, choices, me, enemy, duration, rnd);
            double s = score(simulate(inputs, others, cand, duration), req.objective, me, team);
            out.tried++;
            if (s > bestScore + 1e-9 || Math.abs(s - bestScore) <= 1e-9 && cand.size() < best.size()) {
                if (s > bestScore + 1e-9) {
                    out.improved++;
                    out.progress.add(new double[] {it, s});
                }
                bestScore = s;
                best = cand;
            }
        }
        best.sort(java.util.Comparator.comparingDouble(a -> a.time));
        out.actions = best;
        out.score = bestScore;
        out.summary = summary(simulate(inputs, others, best, duration), me, team);
        return out;
    }

    private ArenaModel.Result simulate(List<Arena.Input> inputs, List<ArenaModel.Action> others,
                                       List<ArenaModel.Action> mine, double duration) {
        List<ArenaModel.Action> all = new ArrayList<>(others);
        all.addAll(mine);
        return new Arena(catalog, map).withoutFrames().run(inputs, all, duration);
    }

    /** Higher is better (see {@link ArenaScore}). */
    private static double score(ArenaModel.Result r, String objective, int me, int team) {
        return ArenaScore.score(r, objective, me, team);
    }

    private static String summary(ArenaModel.Result r, int me, int team) {
        ArenaModel.FighterStats f = r.fighters.get(me);
        String outcome = r.winner == team ? "vitória em " + String.format(java.util.Locale.ROOT, "%.1f", r.duration) + "s"
                : r.winner < 0 ? "sem vencedor em " + String.format(java.util.Locale.ROOT, "%.0f", r.duration) + "s"
                : "derrota em " + String.format(java.util.Locale.ROOT, "%.1f", r.duration) + "s";
        return (outcome + "; " + f.champion + " causou " + Math.round(f.dealt) + " de dano e terminou com "
                + Math.round(Math.max(0, f.health)) + " de Vida").replace('.', ',');
    }

    private static List<ArenaModel.Action> mutate(List<ArenaModel.Action> base, List<String> choices, int me, Integer enemy,
                                                  double duration, java.util.Random rnd) {
        List<ArenaModel.Action> c = new ArrayList<>();
        for (ArenaModel.Action a : base) {
            c.add(copy(a));
        }
        int kind = c.isEmpty() ? 0 : rnd.nextInt(6);
        switch (kind) {
            case 0:
            case 1: {   // add an order at a random second
                String ch = choices.get(rnd.nextInt(choices.size()));
                ArenaModel.Action a = new ArenaModel.Action();
                a.fighter = me;
                a.time = Math.round(rnd.nextDouble() * duration * 0.8 * 10) / 10.0;
                String[] p = ch.split(":", 2);
                a.type = "active".equals(p[0]) ? (SPELLS.contains(p[1]) ? "spell" : "item") : p[0];
                if ("ability".equals(a.type) || "recast".equals(a.type)) {
                    a.slot = p[1];
                } else {
                    a.name = p[1];
                }
                a.target = "recast".equals(a.type) ? null : enemy;
                c.add(a);
                break;
            }
            case 2:     // remove one
                c.remove(rnd.nextInt(c.size()));
                break;
            case 3: {   // move one by up to 3s
                ArenaModel.Action a = c.get(rnd.nextInt(c.size()));
                a.time = Math.max(0, Math.min(duration, Math.round((a.time + (rnd.nextDouble() * 6 - 3)) * 10) / 10.0));
                break;
            }
            case 4: {   // swap the times of two
                ArenaModel.Action a = c.get(rnd.nextInt(c.size()));
                ArenaModel.Action b = c.get(rnd.nextInt(c.size()));
                double t = a.time;
                a.time = b.time;
                b.time = t;
                break;
            }
            default: {  // change the ability of one
                ArenaModel.Action a = c.get(rnd.nextInt(c.size()));
                if ("ability".equals(a.type)) {
                    List<String> slots = new ArrayList<>();
                    for (String ch : choices) {
                        if (ch.startsWith("ability:")) {
                            slots.add(ch.substring(8));
                        }
                    }
                    a.slot = slots.get(rnd.nextInt(slots.size()));
                } else {
                    a.time = Math.max(0, Math.round((a.time + 0.5) * 10) / 10.0);
                }
                break;
            }
        }
        return c;
    }

    private static final List<String> SPELLS = List.of("Flash", "Incendiar", "Exaustão", "Barreira", "Curar", "Fantasma",
            "Purificar", "Golpear", "Teleporte");

    private static ArenaModel.Action copy(ArenaModel.Action a) {
        ArenaModel.Action b = new ArenaModel.Action();
        b.fighter = a.fighter;
        b.time = a.time;
        b.type = a.type;
        b.slot = a.slot;
        b.name = a.name;
        b.target = a.target;
        b.x = a.x;
        b.y = a.y;
        b.charge = a.charge;
        return b;
    }

    /** The arena's champions for a scenario (builds resolved once; a search reuses them for every try). */
    @SuppressWarnings("unchecked")
    List<Arena.Input> prepare(Scenario sc) {

        if (sc.fighters == null || sc.fighters.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O cenário não tem campeões.");
        }
        long blue = sc.fighters.stream().filter(f -> f.team == 0).count();
        long red = sc.fighters.size() - blue;
        if (blue == 0 || red == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cada lado precisa de ao menos 1 campeão.");
        }
        if (blue > MAX_TEAM || red > MAX_TEAM) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No máximo " + MAX_TEAM + " por lado.");
        }
        List<Arena.Input> inputs = new ArrayList<>();
        int[] perTeam = new int[2];
        int[] ofTeam = {(int) blue, (int) red};
        for (Slot slot : sc.fighters) {
            if (slot.buildId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha uma build para cada campeão.");
            }
            BuildRepository.Build build = builds.findBuild(slot.buildId).orElseThrow(
                    () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Build " + slot.buildId + " não existe."));
            CombatService.Combatant c = new CombatService.Combatant();
            c.buildId = slot.buildId;
            c.minute = slot.minute;
            c.activations = slot.activations;
            // The champion's name decides its sheet; the sheet's form effects are switched by the arena itself.
            Map<String, Object> kit = (Map<String, Object>) kits.get(combat.championName(c));
            Set<String> forms = new LinkedHashSet<>();
            if (kit != null && kit.get("efeitos_de_forma") instanceof Map) {
                forms.addAll(((Map<String, Object>) kit.get("efeitos_de_forma")).keySet());
            }
            CombatService.ArenaBuild ab = combat.arenaBuild(c, forms);
            Fighter f = ab.fighter;
            f.team = slot.team;
            Arena.Input in = new Arena.Input();
            in.f = f;
            in.triggers = ab.triggers;
            in.build = build.name;
            in.kit = kit;
            in.forms = forms.isEmpty() ? new LinkedHashMap<>() : combat.formDeltas(c, forms);
            in.target = slot.target;
            in.autoAbilities = slot.autoAbilities;
            if (slot.priority != null && !slot.priority.isEmpty()) {
                in.priority = new ArrayList<>(slot.priority);
            }
            in.policy = policy(slot.brain, in);
            in.baseAttackSpeed = f.baseAttackSpeed > 0 ? f.baseAttackSpeed : 0.7;
            double[] start = start(slot.team, perTeam[slot.team]++, ofTeam[slot.team]);
            in.x = slot.x != null ? slot.x : start[0];
            in.y = slot.y != null ? slot.y : start[1];
            for (String item : f.items.keySet()) {
                Double cd = ITEM_COOLDOWNS.get(item);
                for (ItemEffect e : f.effects) {
                    if (e.item.equals(item) && "ativo".equals(e.kind())) {
                        cd = ((Number) e.p.getOrDefault("recarga", 60)).doubleValue();
                    }
                }
                if (cd != null) {
                    in.activeCooldowns.put(item, cd);
                }
            }
            for (String spell : f.spells) {
                runes.catalog().spells.stream().filter(s -> s.name.equals(spell) && s.cooldown != null)
                        .findFirst().ifPresent(s -> in.activeCooldowns.put(spell, s.cooldown.doubleValue()));
            }
            inputs.add(in);
        }
        return inputs;
    }

    /**
     * The policy of a brain: script (null), tactical, or ai:&lt;id&gt; (a saved learned policy of that champion, with
     * weights of the current observation and action layout).
     */
    com.ornnplanner.arena.ai.Policy policy(String brain, Arena.Input in) {
        if (brain == null || brain.isBlank() || "script".equals(brain)) {
            return null;
        }
        if ("tactical".equals(brain)) {
            return new TacticalPolicy(in.priority);
        }
        if (brain.startsWith("ai:")) {
            long id;
            try {
                id = Long.parseLong(brain.substring(3));
            } catch (NumberFormatException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cérebro desconhecido: " + brain + ".");
            }
            PolicyRepository.Record r = policies.find(id).orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "A IA " + id + " não existe mais."));
            if (!r.compatible()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A IA v" + r.version + " de " + r.champion
                        + " foi treinada para outra versão da arena; treine de novo.");
            }
            if (!r.champion.equalsIgnoreCase(in.f.champion)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A IA v" + r.version + " é de " + r.champion
                        + ", não de " + in.f.champion + ".");
            }
            return new NeuralPolicy(r.weights.network(), in.priority, r.weights.bonus);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cérebro desconhecido: " + brain + ".");
    }

    /** Default start: next to the side's Nexus, side by side. */
    private double[] start(int team, int k, int count) {
        List<?> nexus = (List<?>) map.get(team == 0 ? "nexus_azul" : "nexus_vermelho");
        double x = ((Number) nexus.get(0)).doubleValue();
        double y = ((Number) nexus.get(1)).doubleValue() + (k - (count - 1) / 2.0) * 160;
        return new double[] {x, y};
    }

    // ------------------------------------------------------------------ stored scenarios

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT id, name, updated_at AS updatedAt FROM arena_scenario ORDER BY name COLLATE NOCASE");
    }

    public Scenario find(long id) {
        List<String> data = jdbc.queryForList("SELECT data FROM arena_scenario WHERE id = ?", String.class, id);
        if (data.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        try {
            Scenario sc = JSON.readValue(data.get(0), Scenario.class);
            sc.id = id;
            return sc;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cenário " + id + " inválido", e);
        }
    }

    public Scenario save(Scenario sc) {
        String name = sc.name == null || sc.name.isBlank() ? "Cenário" : sc.name.trim();
        String now = Instant.now().toString();
        String data;
        try {
            sc.name = name;
            data = JSON.writeValueAsString(sc);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
        if (sc.id == null) {
            KeyHolder kh = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO arena_scenario (name, data, created_at, updated_at) VALUES (?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, name);
                ps.setString(2, data);
                ps.setString(3, now);
                ps.setString(4, now);
                return ps;
            }, kh);
            sc.id = kh.getKey().longValue();
        } else if (jdbc.update("UPDATE arena_scenario SET name = ?, data = ?, updated_at = ? WHERE id = ?",
                name, data, now, sc.id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return sc;
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM arena_scenario WHERE id = ?", id);
    }
}
