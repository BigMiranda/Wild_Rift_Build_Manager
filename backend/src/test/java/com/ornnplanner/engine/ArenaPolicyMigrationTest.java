package com.ornnplanner.engine;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ai.Network;
import com.ornnplanner.arena.ai.NeuralPolicy;
import com.ornnplanner.arena.ai.Policy;
import com.ornnplanner.arena.ai.PolicyRepository;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A learned policy of an older layout (observation schema 2, 85 inputs, 32 actions) keeps playing exactly as it did:
 * fights with a fixed random v2 network as the brain must match the fingerprints recorded with that layout
 * ({@code arena/golden-ai-v2.txt}). Recorded with the schema 2 code (commit 8a9f24b); recorded again with
 * -Darena.golden.write=true only when the fight rules change on purpose (Rodada 5: the length of Ornn's elemental), never
 * because of the migration itself.
 */
class ArenaPolicyMigrationTest {

    private static final Path GOLDEN = Paths.get("src/test/resources/arena/golden-ai-v2.txt");
    static final int[] V2_LAYERS = {85, 16, 32};

    /** The fixed v2 network: seeded gaussian weights, large enough to differ from the tactical base often. */
    static Network v2Network(long seed) {
        Random rnd = new Random(seed);
        double[] w = new double[Network.count(V2_LAYERS)];
        for (int i = 0; i < w.length; i++) {
            w[i] = rnd.nextGaussian() * 0.5;
        }
        return new Network(V2_LAYERS, w);
    }

    /** Fights with the v2 brain on fighter 0 (and on fighter 1 with another seed), without frames. */
    static List<String> fingerprints(java.util.function.BiFunction<Network, List<String>, Policy> load) {
        List<String> out = new ArrayList<>();
        for (String name : List.of("duel Ornn x Darius", "duel Vayne x Renekton", "duel K'Sante x Rumble", "3v3")) {
            ArenaScenarios.Scenario s = ArenaScenarios.all().stream().filter(x -> x.name.equals(name)).findFirst().orElseThrow();
            List<Arena.Input> in = new ArrayList<>();
            List<Arena.Input> base = s.inputs.get();
            for (int i = 0; i < base.size(); i++) {
                Arena.Input c = base.get(i).copy();
                if (i == 0 || i == base.size() - 1) {
                    c.policy = load.apply(v2Network(7 + i), c.priority);
                }
                in.add(c);
            }
            out.add(ArenaScenarios.fingerprint(new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).withoutFrames()
                    .run(in, s.actions.get(), s.duration)) + "\t" + name);
        }
        return out;
    }

    /** A version saved with schema 2 (85 inputs, 32 actions, no base: the tactical rules of then), read as now. */
    static Policy loaded(Network v2, List<String> priority) {
        PolicyRepository.Weights w = PolicyRepository.Weights.of(v2, NeuralPolicy.BASE_BONUS);
        return PolicyRepository.upgrade(w, 2, 32).policy(priority);
    }

    @Test
    void aV2PolicyMigratedPlaysAsItDid() throws Exception {
        if (Boolean.getBoolean("arena.golden.write")) {
            Files.write(GOLDEN, fingerprints(ArenaPolicyMigrationTest::loaded), StandardCharsets.UTF_8);
            return;
        }
        // first recorded with the schema 2 code (commit 8a9f24b), where the network ran as it was
        List<String> golden = Files.readAllLines(GOLDEN, StandardCharsets.UTF_8);
        assertEquals(golden, fingerprints(ArenaPolicyMigrationTest::loaded));
    }

    @Test
    void migrationMapsInputsAndOutputsAndAddsZeros() {
        Network v2 = v2Network(3);
        PolicyRepository.Weights up = PolicyRepository.upgrade(PolicyRepository.Weights.of(v2, 0.5), 2, 32);
        Network now = up.network();
        assertEquals(com.ornnplanner.arena.ai.Observation.SIZE, now.layers()[0]);
        assertEquals(com.ornnplanner.arena.ai.ActionSpace.SIZE, now.layers()[2]);
        // any input: the old features in their place, the new ones anything -> the same scores on the old actions
        Random rnd = new Random(1);
        for (int n = 0; n < 20; n++) {
            double[] in = new double[now.layers()[0]];
            for (int i = 0; i < in.length; i++) {
                in[i] = rnd.nextDouble() * 2 - 1;
            }
            double[] old = v2.forward(java.util.Arrays.copyOf(in, 85));
            double[] out = now.forward(in);
            for (int a = 0; a < old.length; a++) {
                assertEquals(old[a], out[a], 1e-12);
            }
            for (int a = old.length; a < out.length; a++) {
                assertEquals(com.ornnplanner.arena.ai.ActionSpace.NEW_ACTION_BIAS, out[a], 1e-12);
            }
        }
        assertEquals(null, up.base, "an old version keeps the tactical rules of then");
    }
}
