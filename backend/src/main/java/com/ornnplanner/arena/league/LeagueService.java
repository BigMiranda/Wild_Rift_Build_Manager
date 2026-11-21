package com.ornnplanner.arena.league;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.ArenaService;
import com.ornnplanner.arena.AutoBrain;
import com.ornnplanner.arena.Gym;
import com.ornnplanner.arena.ai.Policy;
import com.ornnplanner.arena.ai.PolicyRepository;
import com.ornnplanner.arena.draft.DraftModel;
import com.ornnplanner.arena.draft.MetaStats;
import com.ornnplanner.arena.fight.ChampionProfiles;
import com.ornnplanner.arena.fight.CombatCatalog;
import com.ornnplanner.arena.league.LeagueModel.Bout;
import com.ornnplanner.arena.league.LeagueModel.Matrix;
import com.ornnplanner.arena.progressao.ProgressionModel;
import com.ornnplanner.arena.progressao.ProgressionService;
import com.ornnplanner.arena.rift.RiftTemplates;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.seed.ChampionCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntConsumer;

/**
 * Rodada 21: the league's fights. Resolves the fixed validation bank ({@code ia_validacao_<patch>.yml}: the champions of
 * each class by the meta, the sheet's quality and the name), turns a {@link Bout} into a prepared {@link Gym.Episode}
 * (builds drawn by the natural progression at the minute, on the single lane or a lane of the full map with minions and
 * towers), measures an AI's {@link Matrix} against the tactical rules in the same fights, and keeps the matrices in table
 * {@code ai_validation}. The league's training ({@link LeagueTrainer}) plays its episodes through here too.
 */
@Service
public class LeagueService {

    private static final Logger log = LoggerFactory.getLogger(LeagueService.class);
    private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    public static final String TACTICAL = "tactical";

    private final ArenaService arena;
    private final CombatCatalog catalog;
    private final PolicyRepository policies;
    private final ChampionProfiles profiles;
    private final ChampionCatalog champions;
    private final ProgressionService progression;
    private final BuildRepository builds;
    private final MetaStats meta;
    private final JdbcTemplate jdbc;
    private final com.ornnplanner.seed.RuneCatalog runes;
    private final Map<String, Object> seed;
    private final LeagueModel.BankSettings bank = new LeagueModel.BankSettings();
    private volatile Gym gym;

    @SuppressWarnings("unchecked")
    public LeagueService(ArenaService arena, CombatCatalog catalog, PolicyRepository policies, ChampionProfiles profiles,
                         ChampionCatalog champions, @Lazy ProgressionService progression, BuildRepository builds, MetaStats meta,
                         JdbcTemplate jdbc, com.ornnplanner.seed.RuneCatalog runes,
                         @Value("${planner.seed.validation:classpath:seed/ia_validacao_7_3f.yml}") Resource file) {
        this.arena = arena;
        this.catalog = catalog;
        this.policies = policies;
        this.profiles = profiles;
        this.champions = champions;
        this.progression = progression;
        this.builds = builds;
        this.meta = meta;
        this.jdbc = jdbc;
        this.runes = runes;
        try (InputStream in = file.getInputStream()) {
            seed = new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + file, e);
        }
        ((List<Object>) seed.get("classes")).forEach(c -> bank.classes.add(String.valueOf(c)));
        bank.adversaries = num(seed.get("adversarios_por_classe"), 2).intValue();
        bank.players = num(seed.get("jogadores_por_classe"), 2).intValue();
        bank.minutes.clear();
        ((List<Object>) seed.get("minutos")).forEach(m -> bank.minutes.add(((Number) m).doubleValue()));
        bank.types = new ArrayList<>();
        ((List<Object>) seed.get("lutas")).forEach(t -> bank.types.add(String.valueOf(t)));
        bank.seeds = num(seed.get("sementes"), 3).intValue();
        bank.baseSeed = num(seed.get("semente_base"), 2111).longValue();
        ((Map<String, Object>) seed.getOrDefault("duracao", Map.of())).forEach((k, v) -> bank.duration.put(k, ((Number) v).doubleValue()));
        ((Map<String, Object>) seed.getOrDefault("estilo", Map.of())).forEach((k, v) -> bank.style.put(k, String.valueOf(v)));
        Map<String, Object> rota = section("rota");
        if (rota.get("rotas") instanceof List) {
            bank.lanes = new ArrayList<>();
            ((List<Object>) rota.get("rotas")).forEach(l -> bank.lanes.add(String.valueOf(l)));
        }
        Map<String, Object> dupla = section("dupla");
        bank.myAllyShift = num(dupla.get("aliado_meu"), 3).intValue();
        bank.theirAllyShift = num(dupla.get("aliado_deles"), 5).intValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(String key) {
        Object o = seed.get(key);
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static Number num(Object o, Number def) {
        return o instanceof Number ? (Number) o : def;
    }

    public LeagueModel.BankSettings bankSettings() {
        return bank;
    }

    public ArenaService arena() {
        return arena;
    }

    public PolicyRepository policies() {
        return policies;
    }

    public ChampionProfiles profiles() {
        return profiles;
    }

    /** The gym of the league's fights: the single lane and the full map. */
    public Gym gym() {
        Gym g = gym;
        if (g == null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) arena.kits().get("mapa");
            g = new Gym(catalog, map).withRift(arena.riftMap(), arena.riftRules());
            gym = g;
        }
        return g;
    }

    // ------------------------------------------------------------------ champions

    /** The champions with a kit sheet, by name. */
    @SuppressWarnings("unchecked")
    public List<String> withSheet() {
        Map<String, Object> kits = (Map<String, Object>) arena.kits().get("campeoes");
        List<String> out = new ArrayList<>();
        for (Map<String, Object> c : champions.summaries()) {
            String name = String.valueOf(c.get("nome"));
            if (kits.containsKey(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /** The main class of a champion: the first of its main classes (null = none). */
    public String mainClass(String champion) {
        List<String> c = profiles.classesOf(champion);
        return c.isEmpty() ? null : c.get(0);
    }

    /** The sheet's quality letter (A..D; null = none). */
    @SuppressWarnings("unchecked")
    public String quality(String champion) {
        Object kit = ((Map<String, Object>) arena.kits().get("campeoes")).get(champion);
        Object q = kit instanceof Map ? ((Map<String, Object>) kit).get("qualidade") : null;
        return q == null ? null : String.valueOf(q);
    }

    /** A champion's presence in the meta (the most of its lanes, 0..1; 0 without an import). */
    public double presence(String champion) {
        Map<String, DraftModel.Meta> lanes = meta.of(null).get(champion);
        double best = 0;
        if (lanes != null) {
            for (DraftModel.Meta m : lanes.values()) {
                best = Math.max(best, m.presence);
            }
        }
        return best;
    }

    /** The champions of a class in the bank's order: meta presence, then the sheet's quality, then the name. */
    public List<String> ofClass(String cls) {
        List<String> out = new ArrayList<>();
        for (String c : withSheet()) {
            if (cls.equals(mainClass(c))) {
                out.add(c);
            }
        }
        out.sort(Comparator.<String>comparingDouble(c -> -presence(c))
                .thenComparing(c -> quality(c) == null ? "Z" : quality(c))
                .thenComparing(c -> c));
        return out;
    }

    /** The bank's adversaries: the first ones of each class (or the seed's fixed ones). */
    @SuppressWarnings("unchecked")
    public Map<String, List<String>> adversaries() {
        Map<String, Object> fixed = section("fixos");
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String cls : bank.classes) {
            List<String> list = new ArrayList<>();
            if (fixed.get(cls) instanceof List) {
                ((List<Object>) fixed.get(cls)).forEach(o -> list.add(String.valueOf(o)));
            } else {
                List<String> all = ofClass(cls);
                list.addAll(all.subList(0, Math.min(bank.adversaries, all.size())));
            }
            out.put(cls, list);
        }
        return out;
    }

    /** Who an AI plays in the bank: a champion's own AI its champion; a class AI the first ones of its class, in turn. */
    public List<String> players(String owner) {
        if (owner.startsWith(AutoBrain.CLASS_PREFIX)) {
            List<String> all = ofClass(owner.substring(AutoBrain.CLASS_PREFIX.length()));
            return new ArrayList<>(all.subList(0, Math.min(Math.max(1, bank.players), all.size())));
        }
        return List.of(owner);
    }

    /** The bank's bouts for an owner's players. */
    public List<Bout> bank(List<String> players) {
        return LeagueModel.bank(bank, adversaries(), players);
    }

    /** A fingerprint of the bank: the seed and the champions it resolved to (a change of meta changes it). */
    public String bankKey(List<String> players) {
        return sha(String.valueOf(seed) + adversaries() + players).substring(0, 16);
    }

    // ------------------------------------------------------------------ episodes

    private final Map<String, BuildRepository.Build> bases = new ConcurrentHashMap<>();

    /** A champion's base build (runes, spells, gold and experience): its first saved build, else a synthetic one. */
    public BuildRepository.Build base(String champion) {
        return bases.computeIfAbsent(champion.toLowerCase(), k -> {
            String unit = unitOf(champion);
            for (BuildRepository.Build b : builds.findBuildSummaries()) {
                if (unit.equals(b.unitCode)) {
                    BuildRepository.Build full = builds.findBuild(b.id).orElse(null);
                    if (full != null) {
                        return full;
                    }
                }
            }
            BuildRepository.Build b = new BuildRepository.Build();
            b.name = champion;
            b.unitCode = unit;
            b.goldPerMin = 700;
            b.xpPerMin = 700;
            b.runePage = new BuildRepository.RunePage();
            return b;
        });
    }

    /** Forgets the cached base builds (a saved build changed). */
    public void forgetBases() {
        bases.clear();
    }

    public String unitOf(String champion) {
        for (Map<String, Object> c : champions.summaries()) {
            if (champion.equalsIgnoreCase(String.valueOf(c.get("nome")))) {
                return String.valueOf(c.get("code"));
            }
        }
        return ChampionCatalog.code(champion);
    }

    /** The items a champion holds in a bout: one build drawn by the natural progression at the minute. */
    public List<Long> items(String champion, double minute, List<String> enemies, long sample, String archetype) {
        try {
            List<ProgressionModel.Sample> s = progression.sample(champion, List.of(), List.of(), enemies, null, archetype, minute, 1, sample);
            return s.isEmpty() ? List.of() : new ArrayList<>(s.get(0).items);
        } catch (RuntimeException e) {
            log.debug("no progression for {} at {}: {}", champion, minute, e.getMessage());
            return List.of();
        }
    }

    /** A slot of a champion holding the bout's items, at its minute, with a brain. */
    public ArenaModel.Slot slot(String champion, int team, Bout b, List<String> enemies, long sample, String brain) {
        BuildRepository.Build base = JSON.convertValue(base(champion), BuildRepository.Build.class);
        base.id = null;
        ArenaModel.Slot s = new ArenaModel.Slot();
        s.team = team;
        s.build = base;
        s.minute = b.minute;
        s.holdItems = items(champion, b.minute, enemies, sample, team == 0 && champion.equals(b.player) ? b.archetype : null);
        s.brain = brain;
        if (b.variedLoadout && team == 0 && champion.equals(b.player)) {
            vary(base, new Random(sample ^ 0x51EDL));
        }
        return s;
    }

    /** Training: other summoner spells of the Rift (the second one; the first stays) and another keystone. */
    private void vary(BuildRepository.Build b, Random rnd) {
        List<String> spells = new ArrayList<>();
        for (com.ornnplanner.seed.RuneCatalog.Spell sp : runes.catalog().spells) {
            if (sp.maps == null || sp.maps.contains("Rift")) {
                spells.add(sp.name);
            }
        }
        List<String> now = b.spells == null ? new ArrayList<>() : new ArrayList<>(b.spells);
        if (!spells.isEmpty()) {
            String other = spells.get(rnd.nextInt(spells.size()));
            if (now.size() >= 2) {
                if (!now.contains(other)) {
                    now.set(1, other);
                }
            } else if (!now.contains(other)) {
                now.add(other);
            }
            b.spells = now;
        }
        List<com.ornnplanner.seed.RuneCatalog.Rune> keys = runes.catalog().keystones;
        if (!keys.isEmpty()) {
            if (b.runePage == null) {
                b.runePage = new BuildRepository.RunePage();
            }
            b.runePage.keystone = keys.get(rnd.nextInt(keys.size())).name;
        }
    }

    /** The scenario of a bout: the player at index 0 (team 0), the others with {@code others} as their brain. */
    public ArenaModel.Scenario scenario(Bout b, String others) {
        ArenaModel.Scenario sc = new ArenaModel.Scenario();
        sc.name = "Liga: " + b;
        sc.duration = b.duration;
        Random rnd = new Random(b.sample);
        double jitter = rnd.nextInt(301) - 150;
        Map<String, Object> open = section("aberto");
        Map<String, Object> pair = section("dupla");
        Map<String, Object> chase = section("fuga");
        ArenaModel.Slot me = slot(b.player, 0, b, List.of(b.opponent), b.sample * 31 + 1, TACTICAL);
        ArenaModel.Slot opp = slot(b.opponent, 1, b, List.of(b.player), b.sample * 31 + 2, others);
        sc.fighters.add(me);
        sc.fighters.add(opp);
        switch (b.type) {
            case "rota": {
                double[][] at = RiftTemplates.laneDuel(arena.riftMap(), b.lane == null ? "meio" : b.lane,
                        num(section("rota").get("distancia"), 600).doubleValue());
                me.x = at[0][0];
                me.y = at[0][1];
                opp.x = at[1][0];
                opp.y = at[1][1];
                sc.rift = new ArenaModel.RiftOptions();
                sc.rift.minute = b.minute;
                sc.rift.minions = true;
                sc.rift.towers = true;
                sc.rift.jungle = false;
                sc.rift.plants = false;
                sc.rift.objectives = false;
                sc.rift.vision = false;
                break;
            }
            case "dupla": {
                double c = num(pair.get("centro"), 5000).doubleValue();
                double d = num(pair.get("distancia"), 600).doubleValue();
                me.x = c - d / 2;
                me.y = -120 + jitter / 3;
                opp.x = c + d / 2;
                opp.y = -120 - jitter / 3;
                if (b.myAlly != null) {
                    ArenaModel.Slot a = slot(b.myAlly, 0, b, List.of(b.opponent), b.sample * 31 + 3, others);
                    a.x = c - d / 2 - 60;
                    a.y = 120.0;
                    sc.fighters.add(a);
                }
                if (b.theirAlly != null) {
                    ArenaModel.Slot a = slot(b.theirAlly, 1, b, List.of(b.player), b.sample * 31 + 4, others);
                    a.x = c + d / 2 + 60;
                    a.y = 120.0;
                    sc.fighters.add(a);
                }
                break;
            }
            case "fuga": {
                double depth = num(chase.get("profundidade_zona"), 1200).doubleValue();
                double away = num(chase.get("distancia_da_zona"), 2600).doubleValue();
                double behind = num(chase.get("perseguidor_atras"), 350).doubleValue();
                double life = b.fleeHealth != null ? b.fleeHealth : num(chase.get("vida"), 0.4).doubleValue();
                double length = laneLength();
                sc.safeZone = new ArenaModel.SafeZone();
                sc.safeZone.depth = depth;
                sc.safeZone.damagePerSecond = num(chase.get("dano_da_torre"), 0).doubleValue();
                if (b.playerFlees) {
                    sc.safeZone.team = 0;
                    me.x = depth + away;
                    opp.x = me.x + behind;
                    me.startHealth = life;
                } else {
                    sc.safeZone.team = 1;
                    opp.x = length - depth - away;
                    me.x = opp.x - behind;
                    opp.startHealth = life;
                }
                me.y = jitter / 2;
                opp.y = jitter / 2;
                break;
            }
            default: {   // aberto
                double c = num(open.get("centro"), 5000).doubleValue();
                double d = num(open.get("distancia"), 600).doubleValue();
                double dy = num(open.get("desvio_y"), 150).doubleValue();
                me.x = c - d / 2;
                opp.x = c + d / 2;
                me.y = jitter * dy / 150;
                opp.y = -jitter * dy / 150;
            }
        }
        return sc;
    }

    @SuppressWarnings("unchecked")
    private double laneLength() {
        Map<String, Object> map = (Map<String, Object>) arena.kits().get("mapa");
        return map == null ? 10000 : num(map.get("comprimento"), 10000).doubleValue();
    }

    /** A bout prepared as an episode: the player at index 0 with the tactical rules, the others with {@code others}. */
    public Gym.Episode episode(Bout b, String others) {
        ArenaModel.Scenario sc = scenario(b, others);
        List<Arena.Input> inputs = arena.prepareSlots(sc);
        inputs.get(0).style = b.style;
        Gym.Episode e = new Gym.Episode(inputs, List.of(), b.duration, 0, new Policy[inputs.size()], others);
        e.safeZone = sc.safeZone;
        e.rift = sc.rift;
        e.tag = b;
        return e;
    }

    /** The episode with the player's brain set (a brain of {@link ArenaService#withBrain}: tactical, ai:&lt;id&gt;...). */
    public Gym.Episode withBrain(Gym.Episode e, String brain) {
        List<Arena.Input> in = new ArrayList<>(e.inputs);
        in.set(e.me, arena.withBrain(e.inputs.get(e.me), brain));
        Gym.Episode out = new Gym.Episode(in, e.actions, e.duration, e.me, e.policies, e.opponent);
        out.safeZone = e.safeZone;
        out.rift = e.rift;
        out.tag = e.tag;
        return out;
    }

    // ------------------------------------------------------------------ validation

    /** Hears a validation: fights done out of the total. */
    public interface Progress {
        void done(int fights, int total);

        default boolean cancelled() {
            return false;
        }
    }

    /**
     * An AI's matrix on the bank: each bout prepared once and fought with the brain and with the tactical rules (the same
     * starts and builds), on {@code threads} threads. Deterministic whatever the threads.
     */
    public Matrix validate(String brain, List<Bout> bouts, int threads, Progress progress) {
        java.util.function.Function<Arena.Input, Arena.Input> b = TACTICAL.equals(brain) ? null : in -> arena.withBrain(in, brain);
        return validateMany(java.util.Collections.singletonList(b), bouts, threads, progress).get(0);
    }

    /**
     * Several brains on the same bank in one pass: each bout prepared once, fought with the tactical rules and with each
     * brain (a function giving the player's input with its policy; null = the tactical rules themselves). Each matrix
     * carries the tactical numbers of the same fights.
     */
    public List<Matrix> validateMany(List<java.util.function.Function<Arena.Input, Arena.Input>> brains, List<Bout> bouts, int threads,
                                     Progress progress) {
        String others = String.valueOf(seed.getOrDefault("cerebro_outros", TACTICAL));
        int n = bouts.size();
        boolean[][] mine = new boolean[brains.size()][n];
        boolean[] tactical = new boolean[n];
        run(threads, n, i -> {
            Bout b = bouts.get(i);
            Gym.Episode e = episode(b, others);
            tactical[i] = LeagueModel.success(gym().play(e, null), b);
            for (int k = 0; k < brains.size(); k++) {
                java.util.function.Function<Arena.Input, Arena.Input> f = brains.get(k);
                mine[k][i] = f == null ? tactical[i] : LeagueModel.success(gym().play(withInput(e, f.apply(e.inputs.get(e.me))), null), b);
            }
        }, progress == null ? null : k -> progress.done(k, n), progress);
        List<Matrix> out = new ArrayList<>();
        for (int k = 0; k < brains.size(); k++) {
            out.add(LeagueModel.matrix(bouts, mine[k], tactical));
        }
        return out;
    }

    /** The episode with the player's input replaced (its brain, combos, style). */
    public static Gym.Episode withInput(Gym.Episode e, Arena.Input me) {
        List<Arena.Input> in = new ArrayList<>(e.inputs);
        in.set(e.me, me);
        Gym.Episode out = new Gym.Episode(in, e.actions, e.duration, e.me, e.policies, e.opponent);
        out.safeZone = e.safeZone;
        out.rift = e.rift;
        out.tag = e.tag;
        return out;
    }

    /** Runs {@code n} jobs on a pool, reporting how many are done; stops early when cancelled. */
    public static void run(int threads, int n, IntConsumer job, IntConsumer done, Progress cancel) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = i;
                fs.add(pool.submit(() -> {
                    if (cancel == null || !cancel.cancelled()) {
                        job.accept(k);
                    }
                }));
            }
            int count = 0;
            for (Future<?> f : fs) {
                f.get();
                count++;
                if (done != null) {
                    done.accept(count);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof RuntimeException ? (RuntimeException) e.getCause() : new IllegalStateException(e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Parallel calls, results in order. */
    public static <T> List<T> all(int threads, List<Callable<T>> calls) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        try {
            List<Future<T>> fs = new ArrayList<>();
            for (Callable<T> c : calls) {
                fs.add(pool.submit(c));
            }
            List<T> out = new ArrayList<>();
            for (Future<T> f : fs) {
                out.add(f.get());
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof RuntimeException ? (RuntimeException) e.getCause() : new IllegalStateException(e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The same AI on the scenario it was trained on (the overfitting it hides): the saved scenario of that name, its
     * slot of the owner's champion (a class AI: the first slot, with its players in turn holding the progression's build
     * at the slot's minute), with the starts moved as the training did, {@code fights} times; the AI's clean victories
     * against the tactical rules' in the same fights. Null when the scenario is gone.
     */
    public Map<String, Object> trainingScenario(PolicyRepository.Record r, int fights, int threads) {
        ArenaModel.Scenario sc = null;
        for (int pass = 0; pass < 2 && sc == null; pass++) {
            for (Map<String, Object> m : arena.list()) {
                String name = String.valueOf(m.get("name"));
                // exact, else a saved scenario whose name starts the record's (a chain's "<cenário> · <versões>")
                if (r.scenario != null && (pass == 0 ? r.scenario.equals(name) : r.scenario.startsWith(name + " "))) {
                    sc = arena.find(((Number) m.get("id")).longValue());
                    break;
                }
            }
        }
        if (sc == null) {
            return null;
        }
        boolean ofClass = r.champion.startsWith(AutoBrain.CLASS_PREFIX);
        List<String> players = players(r.champion);
        Object trained = ofClass ? section("jogadores_do_treino").get(r.champion.substring(AutoBrain.CLASS_PREFIX.length())) : null;
        if (trained instanceof List) {
            players = new ArrayList<>();
            for (Object o : (List<?>) trained) {
                players.add(String.valueOf(o));
            }
        }
        final List<String> who = players;
        int slot = 0;
        if (!ofClass) {
            List<Arena.Input> base = arena.prepareSlots(sc);
            slot = -1;
            for (int k = 0; k < base.size(); k++) {
                if (base.get(k).f.champion.equalsIgnoreCase(r.champion)) {
                    slot = k;
                    break;
                }
            }
            if (slot < 0) {
                return null;
            }
        }
        final ArenaModel.Scenario scenario = sc;
        final int me = slot;
        List<Callable<double[]>> calls = new ArrayList<>();
        for (int i = 0; i < fights; i++) {
            int k = i;
            calls.add(() -> {
                ArenaModel.Scenario c = JSON.convertValue(scenario, ArenaModel.Scenario.class);
                Random rnd = new Random(LeagueModel.mix(bank.baseSeed, "cenario", r.id, k));
                double shift = rnd.nextInt(3001) - 1500;
                double gap = rnd.nextInt(501) - 200;
                for (ArenaModel.Slot s : c.fighters) {
                    s.brain = s.brain == null || AutoBrain.AUTO.equals(s.brain) ? TACTICAL : s.brain;
                }
                if (ofClass) {
                    Bout b = new Bout();
                    b.player = who.get(k % who.size());
                    ArenaModel.Slot s = c.fighters.get(me);
                    b.minute = s.minute == null ? 15 : s.minute;
                    ArenaModel.Slot mine = slot(b.player, s.team, b, List.of(), LeagueModel.mix(7, b.player, k), TACTICAL);
                    mine.x = s.x;
                    mine.y = s.y;
                    c.fighters.set(me, mine);
                }
                c.fighters.get(me).brain = TACTICAL;
                List<Arena.Input> in = arena.prepareSlots(c);
                int team = in.get(me).f.team;
                for (Arena.Input x : in) {
                    double side = x.f.team == team ? -1 : 1;
                    x.x = Math.max(300, Math.min(9700, x.x + shift + side * gap / 2));
                    x.y = Math.max(-450, Math.min(450, x.y + rnd.nextInt(601) - 300));
                }
                Gym.Episode e = new Gym.Episode(in, c.actions == null ? List.of() : c.actions, Math.max(5, c.duration), me,
                        new Policy[in.size()], "cenário");
                e.safeZone = c.safeZone;
                ArenaModel.Result rt = gym().play(e, null);
                ArenaModel.Result ra = gym().play(withBrain(e, "ai:" + r.id), null);
                double ft = com.ornnplanner.arena.ArenaScore.fitness(rt, r.objective, me, team);
                double fa = com.ornnplanner.arena.ArenaScore.fitness(ra, r.objective, me, team);
                return new double[] {com.ornnplanner.arena.ArenaTraining.cleanWin(ra, me, team) ? 1 : 0,
                        com.ornnplanner.arena.ArenaTraining.cleanWin(rt, me, team) ? 1 : 0, ra.winner == team ? 1 : 0, rt.winner == team ? 1 : 0,
                        fa > ft + 1e-9 ? 1 : Math.abs(fa - ft) <= 1e-9 ? 0.5 : 0};
            });
        }
        double[] sum = new double[5];
        for (double[] x : all(threads, calls)) {
            for (int i = 0; i < sum.length; i++) {
                sum[i] += x[i];
            }
        }
        double n = Math.max(1, fights);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cenario", scenario.name);
        out.put("lutas", fights);
        out.put("exito", 100 * sum[0] / n);
        out.put("tatico", 100 * sum[1] / n);
        out.put("vitoria", 100 * sum[2] / n);
        out.put("vitoriaTatico", 100 * sum[3] / n);
        // what its training measured: its objective's fitness better than the tactical rules' in the same fight
        out.put("melhorQueTatico", 100 * sum[4] / n);
        return out;
    }

    // ------------------------------------------------------------------ the table

    /** A saved matrix. */
    public static class Saved {
        public Long id;
        public Long policyId;
        public String owner;
        public String champion;
        public String bank;
        public double mean;
        public double tacticalMean;
        public Matrix matrix;
        public String sheetHash;
        public String gamePatch;
        public Double seconds;
        public String createdAt;
    }

    public Saved save(Long policyId, String owner, String champion, String bankKey, Matrix m, double seconds) {
        Saved s = new Saved();
        s.policyId = policyId;
        s.owner = owner;
        s.champion = champion;
        s.bank = bankKey;
        s.mean = m.mean;
        s.tacticalMean = m.tacticalMean;
        s.matrix = m;
        s.sheetHash = "*".equals(champion) ? null : sheetHash(champion);
        s.gamePatch = policies.gamePatch();
        s.seconds = seconds;
        s.createdAt = Instant.now().toString();
        try {
            jdbc.update("INSERT INTO ai_validation (policy_id, owner, champion, bank, mean, tactical_mean, matrix, sheet_hash, game_patch, "
                            + "seconds, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)", policyId, owner, champion, bankKey, m.mean,
                    m.tacticalMean, JSON.writeValueAsString(m), s.sheetHash, s.gamePatch, seconds, s.createdAt);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        s.id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return s;
    }

    /** The newest matrix of an AI (null policy = the tactical rules) played by a champion ("*" = a class AI's rotation). */
    public Saved latest(Long policyId, String champion) {
        List<Saved> l = jdbc.query("SELECT * FROM ai_validation WHERE policy_id IS ? AND champion = ? COLLATE NOCASE ORDER BY id DESC LIMIT 1",
                (rs, i) -> row(rs), policyId, champion);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Every saved matrix, newest first, without the cells. */
    public List<Saved> list() {
        return jdbc.query("SELECT * FROM ai_validation ORDER BY id DESC", (rs, i) -> {
            Saved s = row(rs);
            s.matrix = null;
            return s;
        });
    }

    private static Saved row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Saved s = new Saved();
        s.id = rs.getLong("id");
        long p = rs.getLong("policy_id");
        s.policyId = rs.wasNull() ? null : p;
        s.owner = rs.getString("owner");
        s.champion = rs.getString("champion");
        s.bank = rs.getString("bank");
        s.mean = rs.getDouble("mean");
        s.tacticalMean = rs.getDouble("tactical_mean");
        try {
            s.matrix = JSON.readValue(rs.getString("matrix"), Matrix.class);
        } catch (Exception e) {
            s.matrix = null;
        }
        s.sheetHash = rs.getString("sheet_hash");
        s.gamePatch = rs.getString("game_patch");
        double sec = rs.getDouble("seconds");
        s.seconds = rs.wasNull() ? null : sec;
        s.createdAt = rs.getString("created_at");
        return s;
    }

    /** A hash of a champion's kit sheet: a new sheet means its AI is trained again. */
    @SuppressWarnings("unchecked")
    public String sheetHash(String champion) {
        Object kit = ((Map<String, Object>) arena.kits().get("campeoes")).get(champion);
        return kit == null ? null : sha(String.valueOf(kit)).substring(0, 16);
    }

    static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) {
                b.append(String.format("%02x", x));
            }
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
