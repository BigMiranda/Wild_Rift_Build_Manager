package com.ornnplanner.engine;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.AutoBrain;
import com.ornnplanner.arena.Gym;
import com.ornnplanner.arena.ai.ActionSpace;
import com.ornnplanner.arena.ai.Network;
import com.ornnplanner.arena.ai.NeuralPolicy;
import com.ornnplanner.arena.ai.Observation;
import com.ornnplanner.arena.ai.PolicyRepository;
import com.ornnplanner.arena.league.LeagueModel;
import com.ornnplanner.arena.league.LeagueService;
import com.ornnplanner.arena.league.LeagueTrainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rodada 21, part 2: the league's training on the seed's catalog (a new database). */
@SpringBootTest(properties = {"server.port=0"})
class LeagueTrainingTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        String db = Files.createTempDirectory("league").resolve("t.db").toString();
        r.add("planner.db-path", () -> db);
    }

    @Autowired
    LeagueService league;
    @Autowired
    LeagueTrainer trainer;
    @Autowired
    PolicyRepository policies;
    @Autowired
    JdbcTemplate jdbc;

    static final int THREADS = Math.max(2, Runtime.getRuntime().availableProcessors());

    /** A saved AI of the schema 3 layout (241 inputs), random weights. */
    private PolicyRepository.Record schema3(String owner, long seed) {
        int[] layers = {Observation.V3_SIZE, 16, ActionSpace.SIZE};
        double[] w = new double[Network.count(layers)];
        Random rnd = new Random(seed);
        for (int i = 0; i < w.length; i++) {
            w[i] = rnd.nextGaussian() * 0.05;
        }
        PolicyRepository.Record r = new PolicyRepository.Record();
        r.champion = owner;
        r.objective = "win_fast";
        r.accepted = true;
        r.obsSchema = 3;
        r.actions = ActionSpace.SIZE;
        r.scenario = "teste";
        r.weights = PolicyRepository.Weights.trained(new Network(layers, w), NeuralPolicy.BASE_BONUS);
        return policies.insert(r);
    }

    @Test
    void schema3AiStillLoadsAndReadsEveryStyleAsPadrao() {
        PolicyRepository.Record saved = schema3("Garen", 5);
        PolicyRepository.Record r = policies.find(saved.id).orElseThrow();
        assertTrue(r.compatible());
        assertEquals(3, r.migratedFrom);
        Network net = r.weights.network();
        assertEquals(Observation.SIZE, net.layers()[0]);
        Network old = saved.weights.network();
        double[] v = new double[Observation.SIZE];
        Random rnd = new Random(9);
        for (int i = 0; i < Observation.V3_SIZE; i++) {
            v[i] = rnd.nextDouble() * 2 - 1;
        }
        double[] padrao = net.forward(v);
        assertArrayEquals(old.forward(java.util.Arrays.copyOf(v, Observation.V3_SIZE)), padrao, 1e-12);
        for (int k = 0; k < Observation.STYLES.size(); k++) {
            double[] styled = v.clone();
            styled[Observation.V3_SIZE + k] = 1;
            assertArrayEquals(padrao, net.forward(styled), 1e-12, "estilo " + Observation.STYLES.get(k));
        }
    }

    /** A small bank: one seed and one minute of each cell (7 classes x 2 x 4 types = 56 fights). */
    private List<LeagueModel.Bout> smallBank(String owner) {
        List<LeagueModel.Bout> out = new ArrayList<>();
        for (LeagueModel.Bout b : league.bank(league.players(owner))) {
            if (b.seed == 0 && b.minute == 12) {
                out.add(b);
            }
        }
        return out;
    }

    @Test
    void aClassLeagueAiNeverReplacesABetterOne() {
        String cls = "Lutador";
        String owner = AutoBrain.CLASS_PREFIX + cls;
        jdbc.update("DELETE FROM arena_policy WHERE champion = ?", owner);
        PolicyRepository.Record incumbent = schema3(owner, 11);
        PolicyRepository.Record full = policies.find(incumbent.id).orElseThrow();
        LeagueTrainer.Request q = new LeagueTrainer.Request();
        q.owner = owner;
        q.players = league.ofClass(cls);
        q.budget = new LeagueTrainer.Budget(3, 8, 6);
        q.seed = 3;
        q.threads = THREADS;
        q.validation = smallBank(owner);
        q.incumbent = in -> {
            Arena.Input c = in.copy();
            c.policy = full.policy(c.priority);
            c.combos = List.of();
            return c;
        };
        q.incumbentId = incumbent.id;
        q.incumbentLabel = owner;
        LeagueTrainer.Result r = trainer.train(q, new LeagueTrainer.Listener() { });
        assertNotNull(r.matrix);
        assertNotNull(r.incumbentMatrix);
        assertEquals(56, r.matrix.fights);
        LeagueModel.Acceptance a = r.acceptance;
        assertNotNull(a.reason);
        double limit = trainer.settings().acceptWorstCell;
        if (a.accepted) {
            // accepted: better on average than the AI in use, and no cell worse than the limit
            assertTrue(r.matrix.mean > r.incumbentMatrix.mean, a.reason);
            for (LeagueModel.Cell c : r.matrix.cells) {
                LeagueModel.Cell old = r.incumbentMatrix.cell(c.opponentClass, c.type);
                assertTrue(c.success >= old.success - limit - 1e-9, c.opponentClass + " " + c.type);
            }
        } else {
            assertFalse(r.policy.accepted, "recusada fica salva sem aceite");
        }
        // saved in the league's layout, with its matrix and the incumbent's
        PolicyRepository.Record saved = policies.find(r.policy.id).orElseThrow();
        assertEquals("liga", saved.objective);
        assertEquals(Observation.SCHEMA_VERSION, saved.obsSchema);
        assertEquals(a.accepted, saved.accepted);
        assertNotNull(league.latest(r.policy.id, "*"));
        assertNotNull(league.latest(incumbent.id, "*"));
        System.out.printf("liga %s: %.1f%% (atual %.1f%%, Tático %.1f%%) -> %s%n", owner, r.matrix.mean, r.incumbentMatrix.mean,
                r.matrix.tacticalMean, a.reason);
    }

    /** A league network with zero weights decides every step on the tactical rules: the same fights, the same results. */
    @Test
    void zeroLeagueNetworkPlaysExactlyLikeTheTacticalRules() {
        String player = league.ofClass("Mago").get(0);
        Network zero = Network.league();
        LeagueModel.Matrix m = league.validateMany(List.of(in -> {
            Arena.Input c = in.copy();
            c.policy = NeuralPolicy.everyStep(zero, c.priority);
            return c;
        }), smallBank(player), THREADS, null).get(0);
        assertEquals(m.tacticalMean, m.mean, 1e-9);
        for (LeagueModel.Cell c : m.cells) {
            assertEquals(c.tactical, c.success, 1e-9, c.opponentClass + " " + c.type);
        }
    }

    @Test
    void trainingIsDeterministicAcrossThreads() {
        String player = league.ofClass("Tanque").get(0);
        LeagueTrainer.Settings s = trainer.copySettings();
        LeagueTrainer.Request q = new LeagueTrainer.Request();
        q.owner = player;
        q.players = List.of(player);
        q.budget = new LeagueTrainer.Budget(2, 4, 3);
        q.seed = 7;
        q.settings = s;
        q.save = false;
        q.validation = smallBank(player).subList(0, 4);
        q.threads = 1;
        double[] a = trainer.train(q, new LeagueTrainer.Listener() { }).network.weights();
        q.threads = THREADS;
        double[] b = trainer.train(q, new LeagueTrainer.Listener() { }).network.weights();
        assertArrayEquals(a, b, 0);
    }

    @Test
    void fleeStyleReachesTheTowerMoreThanPadraoFromTheSamePlace() {
        String player = league.ofClass("Lutador").get(0);
        LeagueTrainer.Settings s = trainer.copySettings();
        // only chases, half asked to flee and half the standard style
        s.typesStart = new LinkedHashMap<>(Map.of("fuga", 1.0));
        s.typesEnd = new LinkedHashMap<>(Map.of("fuga", 1.0));
        Map<String, Double> styles = new LinkedHashMap<>();
        styles.put("fugir", 0.5);
        styles.put("padrao", 0.5);
        s.stylesByType.put("fuga", styles);
        s.opponents = new LinkedHashMap<>(Map.of("tatico", 1.0));
        LeagueTrainer.Request q = new LeagueTrainer.Request();
        q.owner = player;
        q.players = List.of(player);
        q.budget = new LeagueTrainer.Budget(40, 16, 8);
        q.seed = 21;
        q.settings = s;
        q.save = false;
        q.threads = THREADS;
        q.validation = smallBank(player).subList(0, 2);
        LeagueTrainer.Result r = trainer.train(q, new LeagueTrainer.Listener() { });
        Network net = r.network;
        // the same places: the player at full health, a fresh rival behind it, asked to flee or to play its standard way
        // (with full health the standard way fights; at 40% both styles are right to run)
        List<LeagueModel.Bout> places = trainer.pool(s, player, List.of(player), trainer.opponentsByClass(s), 16, 1.0, 99);
        int[] reached = new int[2];
        for (LeagueModel.Bout b : places) {
            b.playerFlees = true;
            b.fleeHealth = 1.0;
            b.variedLoadout = false;
            b.archetype = null;
            for (int k = 0; k < 2; k++) {
                b.style = k == 0 ? "fugir" : "padrao";
                Gym.Episode e = league.episode(b, LeagueService.TACTICAL);
                Arena.Input me = e.inputs.get(0).copy();
                me.policy = NeuralPolicy.everyStep(net, me.priority);
                me.combos = List.of();
                me.style = b.style;
                var res = league.gym().play(LeagueService.withInput(e, me), null);
                reached[k] += res.fighters.get(0).escapedAt != null && res.fighters.get(0).deathTime == null ? 1 : 0;
            }
        }
        // what is possible from those places: always walking away, and the tactical rules
        int[] other = new int[2];
        double[] padraoScore = new double[3];
        for (LeagueModel.Bout b : places) {
            b.style = "fugir";
            Gym.Episode e = league.episode(b, LeagueService.TACTICAL);
            Arena.Input away = e.inputs.get(0).copy();
            away.policy = (o, mask) -> com.ornnplanner.arena.ai.Policy.Decision.of(mask.valid(ActionSpace.RETREAT) ? ActionSpace.RETREAT : ActionSpace.IDLE);
            var ra = league.gym().play(LeagueService.withInput(e, away), null);
            other[0] += ra.fighters.get(0).escapedAt != null && ra.fighters.get(0).deathTime == null ? 1 : 0;
            var rt = league.gym().play(e, null);
            other[1] += rt.fighters.get(0).escapedAt != null && rt.fighters.get(0).deathTime == null ? 1 : 0;
            Map<String, Double> w = trainer.weights(s, "padrao");
            padraoScore[0] += com.ornnplanner.arena.league.StyleReward.score(ra, 0, 0, b.duration, w) / 16;
            padraoScore[1] += com.ornnplanner.arena.league.StyleReward.score(rt, 0, 0, b.duration, w) / 16;
        }
        System.out.printf("recompensa padrão: recuar %.0f, Tático %.0f%n", padraoScore[0], padraoScore[1]);
        System.out.printf("fugir chegou %d de 16; padrão %d (melhor geração %d); só recuar %d; Tático %d%n", reached[0], reached[1],
                r.bestGeneration, other[0], other[1]);
        assertTrue(reached[0] > reached[1], "fugir " + reached[0] + " x padrão " + reached[1]);
    }
}
