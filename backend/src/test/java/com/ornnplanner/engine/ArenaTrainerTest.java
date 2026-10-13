package com.ornnplanner.engine;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaScore;
import com.ornnplanner.arena.Gym;
import com.ornnplanner.arena.ai.Network;
import com.ornnplanner.arena.ai.NeuralPolicy;
import com.ornnplanner.arena.ai.Policy;
import com.ornnplanner.arena.ai.ScriptedPolicy;
import com.ornnplanner.arena.ai.TacticalPolicy;
import com.ornnplanner.arena.ai.Trainer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Learned policies: the network, the evolution strategy and an offline training of Darius against Vayne. */
class ArenaTrainerTest {

    private static final Gym GYM = new Gym(ArenaScenarios.CATALOG, ArenaScenarios.MAP);
    private static final List<String> PRIORITY = List.of("R", "3", "1", "2");

    /**
     * Darius (blue, controlled) against Vayne on script or tactics, from varied starts. Darius' attack damage makes the
     * duel close: on script, Vayne survives with a sliver of health.
     */
    private static List<Gym.Episode> episodes(long seed, int count) {
        Random rnd = new Random(seed);
        List<Gym.Episode> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double x = 2000 + rnd.nextInt(5000);
            double gap = 600 + rnd.nextInt(700);
            double y1 = rnd.nextInt(800) - 400;
            double y2 = rnd.nextInt(800) - 400;
            boolean tactical = i % 2 == 1;
            Arena.Input darius = ArenaScenarios.champion("Darius", 0, x, y1, 2800, 300, 90);
            Arena.Input vayne = ArenaScenarios.champion("Vayne", 1, x + gap, y2, 2800, 160, 90);
            Policy[] policies = {null, tactical ? new TacticalPolicy(PRIORITY) : new ScriptedPolicy(PRIORITY)};
            out.add(new Gym.Episode(List.of(darius, vayne), List.of(), 30, 0, policies, tactical ? "tactical" : "script"));
        }
        return out;
    }

    private static Network train(Trainer.Config cfg, List<Gym.Episode> pool, List<Trainer.Generation> log) {
        Trainer.Evaluator eval = (net, e) -> {
            Gym.Episode ep = pool.get(e);
            return ArenaScore.fitness(GYM.play(ep, new NeuralPolicy(net, PRIORITY)), "win_fast", ep.me, ep.team());
        };
        return new Trainer(cfg).train(Network.zeros(Network.DEFAULT_LAYERS), pool.size(), eval, log::add);
    }

    @Test
    void networkForwardShapesAndZeros() {
        Network net = Network.zeros(Network.DEFAULT_LAYERS);
        assertTrue(net.size() < 5000, "weights: " + net.size());
        double[] out = net.forward(new double[Network.DEFAULT_LAYERS[0]]);
        assertEquals(Network.DEFAULT_LAYERS[Network.DEFAULT_LAYERS.length - 1], out.length);
        for (double o : out) {
            assertEquals(0, o, 1e-12);
        }
    }

    @Test
    void sameSeedSameWeightsWhateverTheThreads() {
        List<Gym.Episode> pool = episodes(7, 6);
        Trainer.Config a = new Trainer.Config();
        a.population = 6;
        a.generations = 2;
        a.episodesPerGeneration = 3;
        a.threads = 1;
        Trainer.Config b = new Trainer.Config();
        b.population = 6;
        b.generations = 2;
        b.episodesPerGeneration = 3;
        b.threads = 8;
        assertArrayEquals(train(a, pool, new ArrayList<>()).weights(), train(b, pool, new ArrayList<>()).weights());
    }

    @Test
    void zeroNetworkPlaysLikeTheTacticalAutopilot() {
        // Residual over the tactical policy: untrained, it should do as well as the tactical autopilot.
        Gym.Report r = GYM.compare(episodes(3, 12), new NeuralPolicy(Network.zeros(Network.DEFAULT_LAYERS), PRIORITY),
                new TacticalPolicy(PRIORITY), "win_fast");
        assertEquals(r.referenceWinRate, r.winRate, 0.2);
    }

    /** Phase 3 goal: after an offline training, better than the scripted autopilot in at least 60% of new starts. */
    @Test
    void trainedDariusBeatsTheScriptOnValidation() {
        List<Gym.Episode> pool = episodes(11, 24);
        List<Gym.Episode> validation = episodes(99, 24);   // other starts, never trained on
        Trainer.Config cfg = new Trainer.Config();
        cfg.population = 32;
        cfg.generations = 30;
        cfg.episodesPerGeneration = 12;
        cfg.seed = 5;
        List<Trainer.Generation> log = new ArrayList<>();
        long t0 = System.nanoTime();
        Network net = train(cfg, pool, log);
        double seconds = (System.nanoTime() - t0) / 1e9;
        NeuralPolicy ai = new NeuralPolicy(net, PRIORITY);
        Gym.Report vsScript = GYM.compare(validation, ai, new ScriptedPolicy(PRIORITY), "win_fast");
        Gym.Report vsTactical = GYM.compare(validation, ai, new TacticalPolicy(PRIORITY), "win_fast");
        System.out.printf("training: %d generations in %.1fs; mean fitness %.0f -> %.0f%n", log.size(), seconds,
                log.get(0).mean, log.get(log.size() - 1).mean);
        System.out.printf("validation vs script: better in %.0f%%, wins %.0f%% (script %.0f%%)%n", vsScript.better * 100,
                vsScript.winRate * 100, vsScript.referenceWinRate * 100);
        System.out.printf("validation vs tactical: better in %.0f%%, wins %.0f%% (tactical %.0f%%)%n", vsTactical.better * 100,
                vsTactical.winRate * 100, vsTactical.referenceWinRate * 100);
        assertTrue(vsScript.better >= 0.6, "better than the script in " + vsScript.better);
    }
}
