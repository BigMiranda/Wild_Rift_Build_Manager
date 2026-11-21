package com.ornnplanner.arena.league;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.ArenaTraining;
import com.ornnplanner.arena.AutoBrain;
import com.ornnplanner.arena.Gym;
import com.ornnplanner.arena.ai.ActionSpace;
import com.ornnplanner.arena.ai.Network;
import com.ornnplanner.arena.ai.NeuralPolicy;
import com.ornnplanner.arena.ai.Observation;
import com.ornnplanner.arena.ai.Policy;
import com.ornnplanner.arena.ai.PolicyRepository;
import com.ornnplanner.arena.ai.Trainer;
import com.ornnplanner.arena.league.LeagueModel.Bout;
import com.ornnplanner.arena.league.LeagueModel.Matrix;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Rodada 21, part 2: training in a league. The {@link Trainer} (evolution strategy) is the same; the episodes are not
 * one scenario's any more but a pool drawn anew each generation ({@code treino_liga_<patch>.yml}): opponents of every
 * class (the validation bank's left out), minutes 3 to 25 by a curriculum (early lane duels first, then 2 x 2 and
 * chases), builds by the natural progression (some pulled to another archetype), varied spells and keystones, and a
 * requested style whose reward ({@link StyleReward}) is the fitness. Opponents play with the tactical rules, the
 * accepted AIs of their champion or class, or the hall: the last versions of the AI being trained and its own weights of
 * a few generations before.
 *
 * <p>A new version replaces the one in use only if it is better on the whole validation matrix ({@link LeagueModel#accept}).
 * Deterministic: the same request (seed, budget, saved AIs) gives the same weights whatever the threads.
 */
@Service
public class LeagueTrainer {

    /** The seed's settings. */
    public static class Settings {
        public long baseSeed = 7301;
        public int poolEpisodes = 12;
        public double minMinute = 3;
        public double maxMinute = 25;
        public double earlyMinutes = 10;
        public boolean excludeBank = true;
        public double wrongBuild = 0.2;
        public double variedLoadout = 0.3;
        /** The health the one who flees starts with in a training chase: drawn in this range. */
        public double fleeHealthMin = 0.3;
        public double fleeHealthMax = 1.0;
        public String minQuality = "C";
        public Map<String, Double> typesStart = new LinkedHashMap<>();
        public Map<String, Double> typesEnd = new LinkedHashMap<>();
        public Map<String, Double> opponents = new LinkedHashMap<>();
        public int hallVersions = 4;
        public int selfPlayEvery = 5;
        public String allies = "tactical";
        public Map<String, Map<String, Double>> stylesByType = new LinkedHashMap<>();
        public Map<String, Map<String, Double>> rewards = new LinkedHashMap<>();
        public double acceptMeanAbove = 0;
        public double acceptWorstCell = 15;
        public Map<String, Map<String, Budget>> budgets = new LinkedHashMap<>();
        public int selectionFights = 24;
        public int selectionEvery = 2;
        public List<String> archetypes = new ArrayList<>();
        /** melhor | atual | zero: where a class AI starts. */
        public String classStart = "melhor";
        /** Share of the cores the mass campaign uses alone, and with the builds campaign running. */
        public double workersAlone = 1.0;
        public double workersShared = 0.5;
        /** How many trained owners of each kind the estimate is measured on. */
        public int estimateSample = 3;
    }

    /** A training budget. */
    public static class Budget {
        public int generations = 20;
        public int population = 16;
        public int episodes = 8;

        public Budget() {
        }

        public Budget(int generations, int population, int episodes) {
            this.generations = generations;
            this.population = population;
            this.episodes = episodes;
        }
    }

    /** What to train. */
    public static class Request {
        /** A champion, or class:&lt;Classe&gt;. */
        public String owner;
        /** The champions it plays in training (a class AI: its class in turn). */
        public List<String> players = new ArrayList<>();
        /** The champions it plays in validation (null = the bank's for the owner). */
        public List<String> validationPlayers;
        /** Weights it starts from (null = zeros: the tactical rules) and what they are. */
        public Network start;
        public Long parentId;
        public String startLabel;
        /** The AI in use it must beat on the matrix (null = the tactical rules), as the player's input with its brain. */
        public Function<Arena.Input, Arena.Input> incumbent;
        public Long incumbentId;
        public String incumbentLabel;
        public Budget budget = new Budget();
        public long seed = 1;
        public int threads = Runtime.getRuntime().availableProcessors();
        /** Bouts of the validation (null = the whole bank). */
        public List<Bout> validation;
        /** The pool's settings may be narrowed for a test (null = the seed's). */
        public Settings settings;
        /** Save the version (false: a dry run, e.g. a test that only measures). */
        public boolean save = true;
    }

    /** One generation as a job shows it. */
    public static class Point {
        public int generation;
        public double mean;
        public double best;
        /** The weights' mean reward on the selection fights, and the tactical rules' (when measured). */
        public Double selection;
        public Double selectionTactical;
    }

    public static class Result {
        public PolicyRepository.Record policy;
        public Network network;
        public Matrix matrix;
        public Matrix incumbentMatrix;
        public LeagueModel.Acceptance acceptance;
        public int generations;
        public int bestGeneration;
        public double trainSeconds;
        public double validationSeconds;
        public List<String> opponents = new ArrayList<>();
        public List<Point> curve = new ArrayList<>();
        public boolean cancelled;
    }

    /** Hears a training. */
    public interface Listener {
        default void message(String text) {
        }

        default void point(Point p) {
        }

        default void validation(int done, int total) {
        }

        default boolean cancelled() {
            return false;
        }
    }

    private final LeagueService league;
    private final PolicyRepository policies;
    private final Settings settings = new Settings();

    @SuppressWarnings("unchecked")
    public LeagueTrainer(LeagueService league, PolicyRepository policies,
                         @Value("${planner.seed.league:classpath:seed/treino_liga_7_3f.yml}") Resource file) {
        this.league = league;
        this.policies = policies;
        Map<String, Object> root;
        try (InputStream in = file.getInputStream()) {
            root = new Yaml().load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + file, e);
        }
        Settings s = settings;
        s.baseSeed = num(root.get("semente_base"), 7301).longValue();
        Map<String, Object> pool = map(root.get("pool"));
        s.poolEpisodes = num(pool.get("episodios"), 12).intValue();
        List<Object> minutes = (List<Object>) pool.getOrDefault("minutos", List.of(3, 25));
        s.minMinute = ((Number) minutes.get(0)).doubleValue();
        s.maxMinute = ((Number) minutes.get(1)).doubleValue();
        s.excludeBank = !Boolean.FALSE.equals(pool.get("exclui_banco"));
        s.wrongBuild = num(pool.get("build_errada"), 0.2).doubleValue();
        s.variedLoadout = num(pool.get("feiticos_variados"), 0.3).doubleValue();
        s.minQuality = String.valueOf(pool.getOrDefault("qualidade_minima", "C"));
        List<Object> flee = (List<Object>) pool.getOrDefault("vida_na_fuga", List.of(0.3, 1.0));
        s.fleeHealthMin = ((Number) flee.get(0)).doubleValue();
        s.fleeHealthMax = ((Number) flee.get(1)).doubleValue();
        Map<String, Object> cur = map(root.get("curriculo"));
        s.typesStart = doubles(cur.get("inicio"));
        s.typesEnd = doubles(cur.get("fim"));
        s.earlyMinutes = num(cur.get("minutos_cedo"), 10).doubleValue();
        s.opponents = doubles(root.get("adversarios"));
        Map<String, Object> hall = map(root.get("salao"));
        s.hallVersions = num(hall.get("versoes"), 4).intValue();
        s.selfPlayEvery = num(hall.get("self_play_a_cada"), 5).intValue();
        s.allies = String.valueOf(root.getOrDefault("aliados", "tactical"));
        map(root.get("estilos_por_tipo")).forEach((k, v) -> s.stylesByType.put(k, doubles(v)));
        map(root.get("recompensas")).forEach((k, v) -> s.rewards.put(k, doubles(v)));
        Map<String, Object> acc = map(root.get("aceite"));
        s.acceptMeanAbove = num(acc.get("media_acima"), 0).doubleValue();
        s.acceptWorstCell = num(acc.get("pior_celula"), 15).doubleValue();
        map(root.get("orcamentos")).forEach((name, v) -> {
            Map<String, Budget> byKind = new LinkedHashMap<>();
            map(v).forEach((kind, b) -> {
                Map<String, Object> m = map(b);
                byKind.put(kind, new Budget(num(m.get("geracoes"), 20).intValue(), num(m.get("populacao"), 16).intValue(),
                        num(m.get("episodios"), 8).intValue()));
            });
            s.budgets.put(name, byKind);
        });
        Map<String, Object> sel = map(root.get("selecao"));
        s.selectionFights = num(sel.get("lutas"), 24).intValue();
        s.selectionEvery = num(sel.get("a_cada"), 2).intValue();
        ((List<Object>) root.getOrDefault("arquetipos", List.of())).forEach(a -> s.archetypes.add(String.valueOf(a)));
        s.classStart = String.valueOf(root.getOrDefault("inicio_classe", "melhor"));
        Map<String, Object> workers = map(root.get("trabalhadores"));
        s.workersAlone = num(workers.get("sozinha"), 1.0).doubleValue();
        s.workersShared = num(workers.get("com_campanha_de_builds"), 0.5).doubleValue();
        s.estimateSample = num(map(root.get("estimativa")).get("amostra"), 3).intValue();
        for (Map<String, Double> w : s.rewards.values()) {
            w.keySet().forEach(term -> {
                if (!TERMS.contains(term)) {
                    throw new IllegalStateException("treino_liga: termo de recompensa desconhecido: " + term);
                }
            });
        }
    }

    static final List<String> TERMS = List.of("vitoria", "limpa", "rapidez", "dano", "vida", "viveu", "tempo_vivo", "chegou",
            "chegou_cedo", "presos", "controle", "ouro", "vantagem");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static Map<String, Double> doubles(Object o) {
        Map<String, Double> out = new LinkedHashMap<>();
        map(o).forEach((k, v) -> out.put(k, ((Number) v).doubleValue()));
        return out;
    }

    private static Number num(Object o, Number def) {
        return o instanceof Number ? (Number) o : def;
    }

    public Settings settings() {
        return settings;
    }

    /** A copy of the seed's settings (a test narrows its own). */
    public Settings copySettings() {
        return new com.fasterxml.jackson.databind.ObjectMapper().convertValue(settings, Settings.class);
    }

    public Budget budget(String name, String kind) {
        Map<String, Budget> b = settings.budgets.get(name);
        Budget out = b == null ? null : b.get(kind);
        return out == null ? new Budget() : out;
    }

    /** The reward weights of a style (padrao for an unknown one). */
    public Map<String, Double> weights(Settings s, String style) {
        Map<String, Double> w = s.rewards.get(style);
        return w != null ? w : s.rewards.getOrDefault(LeagueModel.DEFAULT_STYLE, Map.of("vitoria", 1.0));
    }

    // ------------------------------------------------------------------ the pool

    /** The champions the league draws opponents and allies from: with a sheet of the minimum quality, by main class. */
    public Map<String, List<String>> opponentsByClass(Settings s) {
        Set<String> bank = new HashSet<>();
        if (s.excludeBank) {
            league.adversaries().values().forEach(bank::addAll);
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String c : league.withSheet()) {
            String q = league.quality(c);
            String cls = league.mainClass(c);
            if (cls == null || bank.contains(c) || q == null || q.compareTo(s.minQuality) > 0) {
                continue;
            }
            out.computeIfAbsent(cls, k -> new ArrayList<>()).add(c);
        }
        return out;
    }

    /** The curriculum's fight type weights at a share {@code t} of the training (0 = start, 1 = end). */
    static Map<String, Double> typeWeights(Settings s, double t) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String type : LeagueModel.TYPES) {
            double a = s.typesStart.getOrDefault(type, 0.0);
            double b = s.typesEnd.getOrDefault(type, 0.0);
            out.put(type, a + (b - a) * t);
        }
        return out;
    }

    /**
     * The bouts of a generation's pool (or of the selection set): drawn from the generation's number, so a training is
     * the same whatever the threads. {@code t} is how far the curriculum has gone.
     */
    public List<Bout> pool(Settings s, String owner, List<String> players, Map<String, List<String>> opponents, int count,
                           double t, long seedValue) {
        Random rnd = new Random(seedValue);
        List<String> classes = new ArrayList<>(opponents.keySet());
        Map<String, Double> types = typeWeights(s, t);
        double hi = s.earlyMinutes + (s.maxMinute - s.earlyMinutes) * t;
        List<String> lanes = league.bankSettings().lanes;
        List<Bout> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Bout b = new Bout();
            b.player = players.get(rnd.nextInt(players.size()));
            String cls = classes.get(rnd.nextInt(classes.size()));
            List<String> pick = opponents.get(cls);
            b.opponent = pick.get(rnd.nextInt(pick.size()));
            b.opponentClass = cls;
            b.type = LeagueModel.weighted(types, rnd);
            b.minute = Math.round(2 * (s.minMinute + rnd.nextDouble() * (Math.max(s.minMinute, hi) - s.minMinute))) / 2.0;
            b.seed = i;
            b.sample = rnd.nextLong() & Long.MAX_VALUE;
            b.style = LeagueModel.weighted(s.stylesByType.getOrDefault(b.type, Map.of(LeagueModel.DEFAULT_STYLE, 1.0)), rnd);
            b.duration = league.bankSettings().duration.getOrDefault(b.type, 40.0);
            boolean coin = rnd.nextBoolean();   // drawn always: the draws after it never depend on the style
            b.playerFlees = "fugir".equals(b.style) || coin && !"agressivo".equals(b.style);
            b.lane = lanes.get(rnd.nextInt(lanes.size()));
            if ("dupla".equals(b.type)) {
                b.myAlly = any(opponents, rnd, b.player, b.opponent);
                b.theirAlly = any(opponents, rnd, b.player, b.opponent, b.myAlly);
            }
            if (!s.archetypes.isEmpty() && rnd.nextDouble() < s.wrongBuild) {
                b.archetype = s.archetypes.get(rnd.nextInt(s.archetypes.size()));
            }
            b.variedLoadout = rnd.nextDouble() < s.variedLoadout;
            double h = s.fleeHealthMin + rnd.nextDouble() * (s.fleeHealthMax - s.fleeHealthMin);
            b.fleeHealth = "fuga".equals(b.type) ? Math.round(h * 20) / 20.0 : null;
            out.add(b);
        }
        return out;
    }

    private static String any(Map<String, List<String>> opponents, Random rnd, String... avoid) {
        List<String> all = new ArrayList<>();
        opponents.values().forEach(all::addAll);
        for (int k = 0; k < 20; k++) {
            String c = all.get(rnd.nextInt(all.size()));
            if (!List.of(avoid).contains(c)) {
                return c;
            }
        }
        return all.get(0);
    }

    // ------------------------------------------------------------------ opponents' brains

    /** The newest accepted, compatible AI of an owner (null = none). */
    PolicyRepository.Record newestAccepted(String owner) {
        for (PolicyRepository.Record r : policies.list(owner)) {
            if (r.accepted && r.compatible() && r.champion.equalsIgnoreCase(owner)) {
                return r;
            }
        }
        return null;
    }

    /** An opponent's AI: its champion's own accepted one, else its class's (null = none: the tactical rules). */
    PolicyRepository.Record aiOf(String champion, Map<String, Object> cache) {
        Object c = cache.computeIfAbsent(champion.toLowerCase(), k -> {
            PolicyRepository.Record r = newestAccepted(champion);
            if (r == null) {
                String cls = league.mainClass(champion);
                r = cls == null ? null : newestAccepted(AutoBrain.CLASS_PREFIX + cls);
            }
            return r == null ? Boolean.FALSE : policies.find(r.id).orElse(null);
        });
        return c instanceof PolicyRepository.Record ? (PolicyRepository.Record) c : null;
    }

    /** An input playing a saved AI's weights directly (any champion: the networks are not by champion). */
    static Arena.Input playing(Arena.Input in, Policy p) {
        Arena.Input c = in.copy();
        c.policy = p;
        c.combos = List.of();
        return c;
    }

    // ------------------------------------------------------------------ training

    public Result train(Request q, Listener l) {
        Settings s = q.settings != null ? q.settings : settings;
        Result out = new Result();
        long t0 = System.nanoTime();
        l.message("preparando a liga");
        Map<String, List<String>> opponents = opponentsByClass(s);
        if (opponents.isEmpty()) {
            throw new IllegalStateException("A liga não tem adversários (fichas da qualidade mínima).");
        }
        Map<String, Object> aiCache = new ConcurrentHashMap<>();
        // the hall: the last versions of this AI, and its own weights of a few generations before (self-play)
        List<PolicyRepository.Record> hall = new ArrayList<>();
        for (PolicyRepository.Record r : policies.list(q.owner)) {
            if (r.compatible() && r.champion.equalsIgnoreCase(q.owner) && hall.size() < s.hallVersions) {
                policies.find(r.id).ifPresent(hall::add);
            }
        }
        AtomicReference<Network> self = new AtomicReference<>(leagueStart(q.start));
        hall.forEach(h -> out.opponents.add("salão: v" + h.version));
        out.opponents.add(0, "tático");
        out.opponents.add(1, "IAs dos adversários (campeão, senão classe)");
        out.opponents.add("salão: ela mesma de " + s.selfPlayEvery + " gerações atrás");

        Trainer.Config cfg = new Trainer.Config();
        cfg.population = Math.max(4, Math.min(128, q.budget.population));
        cfg.generations = Math.max(1, q.budget.generations);
        cfg.episodesPerGeneration = Math.max(1, q.budget.episodes);
        cfg.seed = LeagueModel.mix(s.baseSeed, q.owner, q.seed);
        cfg.threads = Math.max(1, q.threads);
        int poolSize = cfg.episodesPerGeneration;
        String others = s.allies;

        // the selection set: the curriculum's end mix, the tactical rules for everyone else, prepared once
        List<Bout> selBouts = pool(s, q.owner, q.players, opponents, s.selectionFights, 1.0,
                LeagueModel.mix(s.baseSeed, q.owner, q.seed, "selecao"));
        List<Gym.Episode> selection = LeagueService.all(cfg.threads, toCalls(selBouts, b -> league.episode(b, LeagueService.TACTICAL)));
        double[] selTactical = scores(selection, null, s, cfg.threads);
        double selTacticalMean = mean(selTactical);

        List<Gym.Episode>[] current = new List[] {List.of()};
        Network start = leagueStart(q.start);
        Network[] best = {start};
        double[] bestSel = {Double.NEGATIVE_INFINITY};
        int[] bestGen = {0};
        {
            double first = mean(scores(selection, start, s, cfg.threads));
            bestSel[0] = first;
            Point p = new Point();
            p.generation = 0;
            p.selection = first;
            p.selectionTactical = selTacticalMean;
            out.curve.add(p);
            l.point(p);
        }
        l.message("treinando em liga");
        int[] done = {0};
        new Trainer(cfg).train(start, poolSize, new Trainer.Evaluator() {
            @Override
            public void generationStart(int gen) {
                double t = cfg.generations <= 1 ? 1 : (gen - 1) / (double) (cfg.generations - 1);
                long sv = LeagueModel.mix(s.baseSeed, q.owner, q.seed, "geracao", gen);
                List<Bout> bouts = pool(s, q.owner, q.players, opponents, poolSize, t, sv);
                Random rnd = new Random(sv ^ 0x2F0E);
                List<String> brains = new ArrayList<>();
                for (int i = 0; i < bouts.size(); i++) {
                    brains.add(LeagueModel.weighted(s.opponents, rnd));
                }
                int hallPick = rnd.nextInt(hall.size() + 1);
                Network snapshot = self.get();
                List<java.util.concurrent.Callable<Gym.Episode>> calls = new ArrayList<>();
                for (int i = 0; i < bouts.size(); i++) {
                    Bout b = bouts.get(i);
                    String kind = brains.get(i);
                    int k = i;
                    calls.add(() -> {
                        Gym.Episode e = league.episode(b, others);
                        List<Arena.Input> in = new ArrayList<>(e.inputs);
                        for (int j = 0; j < in.size(); j++) {
                            Arena.Input x = in.get(j);
                            if (x.f.team == 0) {
                                continue;
                            }
                            if ("ias".equals(kind)) {
                                PolicyRepository.Record r = aiOf(x.f.champion, aiCache);
                                if (r != null) {
                                    in.set(j, playing(x, r.policy(x.priority)));
                                }
                            } else if ("salao".equals(kind)) {
                                int h = (hallPick + k) % (hall.size() + 1);
                                Policy p = h < hall.size() ? hall.get(h).policy(x.priority) : NeuralPolicy.everyStep(snapshot, x.priority);
                                in.set(j, playing(x, p));
                            }
                        }
                        in.get(0).combos = List.of();
                        Gym.Episode ep = new Gym.Episode(in, e.actions, e.duration, 0, e.policies, kind);
                        ep.safeZone = e.safeZone;
                        ep.rift = e.rift;
                        ep.tag = b;
                        return ep;
                    });
                }
                current[0] = LeagueService.all(cfg.threads, calls);
            }

            @Override
            public double evaluate(Network candidate, int episode) {
                Gym.Episode e = current[0].get(episode);
                Bout b = (Bout) e.tag;
                ArenaModel.Result r = league.gym().play(e, NeuralPolicy.everyStep(candidate, e.inputs.get(0).priority));
                return StyleReward.score(r, 0, 0, b.duration, weights(s, b.style));
            }
        }, new Trainer.Listener() {
            @Override
            public void generation(Trainer.Generation g) {
                done[0] = g.index;
                Point p = new Point();
                p.generation = g.index;
                p.mean = g.mean;
                p.best = g.best;
                if (g.index % Math.max(1, s.selectionEvery) == 0 || g.index == cfg.generations) {
                    double sel = mean(scores(selection, g.center, s, cfg.threads));
                    p.selection = sel;
                    p.selectionTactical = selTacticalMean;
                    if (sel > bestSel[0] + 1e-9) {
                        bestSel[0] = sel;
                        best[0] = g.center;
                        bestGen[0] = g.index;
                    }
                }
                if (s.selfPlayEvery > 0 && g.index % s.selfPlayEvery == 0) {
                    self.set(g.center);
                }
                out.curve.add(p);
                l.point(p);
            }

            @Override
            public boolean cancelled() {
                return l.cancelled();
            }
        });
        out.generations = done[0];
        out.bestGeneration = bestGen[0];
        out.network = best[0];
        out.trainSeconds = (System.nanoTime() - t0) / 1e9;
        if (l.cancelled()) {
            out.cancelled = true;
            return out;
        }

        // validation on the bank: the new weights, the AI in use and the tactical rules in the same fights
        l.message("validando no banco");
        long t1 = System.nanoTime();
        List<String> vPlayers = q.validationPlayers != null ? q.validationPlayers : league.players(q.owner);
        List<Bout> bank = q.validation != null ? q.validation : league.bank(vPlayers);
        Network chosen = best[0];
        List<Function<Arena.Input, Arena.Input>> brains = new ArrayList<>();
        brains.add(in -> playing(in, NeuralPolicy.everyStep(chosen, in.priority)));
        if (q.incumbent != null) {
            brains.add(q.incumbent);
        }
        List<Matrix> ms = league.validateMany(brains, bank, cfg.threads, new LeagueService.Progress() {
            @Override
            public void done(int fights, int total) {
                l.validation(fights, total);
            }

            @Override
            public boolean cancelled() {
                return l.cancelled();
            }
        });
        out.validationSeconds = (System.nanoTime() - t1) / 1e9;
        if (l.cancelled()) {
            out.cancelled = true;
            return out;
        }
        out.matrix = ms.get(0);
        out.incumbentMatrix = q.incumbent != null ? ms.get(1) : null;
        out.acceptance = LeagueModel.accept(out.matrix, out.incumbentMatrix, s.acceptMeanAbove, s.acceptWorstCell);
        out.acceptance.against = q.incumbent == null ? "tatico" : q.incumbentLabel != null ? q.incumbentLabel : "atual";
        if (!q.save) {
            return out;
        }
        PolicyRepository.Record r = new PolicyRepository.Record();
        r.champion = q.owner;
        r.objective = "liga";
        r.fitness = out.matrix.mean;
        r.winrate = out.matrix.mean / 100;
        r.winrateScript = null;
        r.betterVsTactical = null;
        r.betterVsScript = null;
        r.accepted = out.acceptance.accepted;
        r.generations = out.generations;
        r.population = cfg.population;
        r.seed = q.seed;
        r.scenario = String.format(Locale.ROOT, "Liga (%s): êxito %.1f%%, Tático %.1f%%", q.owner, out.matrix.mean, out.matrix.tacticalMean);
        r.obsSchema = Observation.SCHEMA_VERSION;
        r.actions = ActionSpace.SIZE;
        r.parentId = q.parentId;
        r.weights = PolicyRepository.Weights.trained(chosen, NeuralPolicy.BASE_BONUS);
        r.weights.interval = 0.0;   // decides every step: zero weights are exactly the tactical rules
        r.level = ArenaTraining.LEVEL_ALONE;
        out.policy = policies.insert(r);
        String champion = q.owner.startsWith(AutoBrain.CLASS_PREFIX) ? "*" : q.owner;
        String key = league.bankKey(vPlayers);
        league.save(out.policy.id, q.owner, champion, key, out.matrix, out.validationSeconds);
        if (q.incumbentId != null && out.incumbentMatrix != null) {
            LeagueService.Saved old = league.latest(q.incumbentId, champion);
            if (old == null || !key.equals(old.bank)) {
                league.save(q.incumbentId, q.incumbentLabel == null ? q.owner : q.incumbentLabel, champion, key, out.incumbentMatrix,
                        out.validationSeconds);
            }
        }
        return out;
    }

    /**
     * Where a league training starts: zero weights, or a saved network given the style's direct inputs (zero, so it
     * plays as it did) when it has none.
     */
    static Network leagueStart(Network start) {
        if (start == null) {
            return Network.league();
        }
        if (start.skip() > 0) {
            return start;
        }
        int[] layers = start.layers();
        int outputs = layers[layers.length - 1];
        int skip = Observation.STYLES.size();
        double[] w = java.util.Arrays.copyOf(start.weights(), Network.count(layers) + skip * outputs);
        return new Network(layers, w, skip);
    }

    private static <T> List<java.util.concurrent.Callable<T>> toCalls(List<Bout> bouts, Function<Bout, T> f) {
        List<java.util.concurrent.Callable<T>> out = new ArrayList<>();
        for (Bout b : bouts) {
            out.add(() -> f.apply(b));
        }
        return out;
    }

    /** The style reward of each episode with these weights (null = the tactical rules as prepared). */
    private double[] scores(List<Gym.Episode> eps, Network net, Settings s, int threads) {
        List<java.util.concurrent.Callable<Double>> calls = new ArrayList<>();
        for (Gym.Episode e : eps) {
            calls.add(() -> {
                Bout b = (Bout) e.tag;
                Gym.Episode ep = e;
                if (net != null) {
                    Arena.Input me = playing(e.inputs.get(0), NeuralPolicy.everyStep(net, e.inputs.get(0).priority));
                    ep = LeagueService.withInput(e, me);
                }
                ArenaModel.Result r = league.gym().play(ep, null);
                return StyleReward.score(r, 0, 0, b.duration, weights(s, b.style));
            });
        }
        List<Double> r = LeagueService.all(threads, calls);
        double[] out = new double[r.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = r.get(i);
        }
        return out;
    }

    private static double mean(double[] x) {
        double s = 0;
        for (double v : x) {
            s += v;
        }
        return x.length == 0 ? 0 : s / x.length;
    }
}
