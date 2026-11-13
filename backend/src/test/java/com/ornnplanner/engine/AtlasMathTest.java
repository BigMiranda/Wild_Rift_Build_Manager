package com.ornnplanner.engine;

import com.ornnplanner.arena.atlas.AtlasMath;
import com.ornnplanner.arena.atlas.AtlasModel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Rodada 16, the atlas's arithmetic: a measured set predicted with weight 1 gives back its race at the order that
 * covers it; the interactions are what the set does beyond its parts.
 */
class AtlasMathTest {

    private static Map<String, Double> races() {
        Map<String, Double> r = new LinkedHashMap<>();
        r.put("", 50.0);
        r.put("A:x", 60.0);
        r.put("A:y", 55.0);
        r.put("B:z", 40.0);
        r.put(AtlasMath.setKey(List.of("A:x", "A:y")), 70.0);      // +5 beyond the notes
        r.put(AtlasMath.setKey(List.of("A:x", "B:z")), 45.0);      // -5
        r.put(AtlasMath.setKey(List.of("A:y", "B:z")), 45.0);      // 0
        r.put(AtlasMath.setKey(List.of("A:x", "A:y", "B:z")), 62.0);
        return r;
    }

    private static Map<String, Double> all(String... keys) {
        Map<String, Double> w = new LinkedHashMap<>();
        for (String k : keys) {
            w.put(k, 1.0);
        }
        return w;
    }

    @Test
    void theNotesAreWhatEachOrderAdds() {
        Map<String, Double> r = races();
        assertEquals(10, AtlasMath.note1(r, 50, "A:x"), 1e-9);
        assertEquals(5, AtlasMath.note2(r, 50, "A:x", "A:y"), 1e-9);
        assertEquals(-5, AtlasMath.note2(r, 50, "B:z", "A:x"), 1e-9);
        // 62 - (70 + 45 + 45) + (60 + 55 + 40) - 50
        assertEquals(7, AtlasMath.note3(r, 50, "A:x", "A:y", "B:z"), 1e-9);
        assertNull(AtlasMath.note2(r, 50, "A:x", "A:w"));
    }

    @Test
    void aMeasuredSetIsGivenBackAtItsOrder() {
        Map<String, Double> r = races();
        assertEquals(70, AtlasMath.predict(r, 50, all("A:x", "A:y"), 2), 1e-9);
        assertEquals(65, AtlasMath.predict(r, 50, all("A:x", "A:y"), 1), 1e-9);
        assertEquals(62, AtlasMath.predict(r, 50, all("A:x", "A:y", "B:z"), 3), 1e-9);
        assertEquals(55, AtlasMath.predict(r, 50, all("A:x", "A:y", "B:z"), 1), 1e-9);
    }

    @Test
    void weightsScaleEachTermByTheirProduct() {
        Map<String, Double> r = races();
        Map<String, Double> w = new LinkedHashMap<>();
        w.put("A:x", 0.5);
        w.put("A:y", 1.0);
        // 50 + 0.5 x 10 + 1 x 5 + 0.5 x 1 x 5
        assertEquals(62.5, AtlasMath.predict(r, 50, w, 2), 1e-9);
    }

    @Test
    void factorsShrinkEachOrder() {
        Map<String, Double> r = races();
        // 50 + (10 + 5 - 10) + 0.5 x (5 - 5 + 0) + 0 x 7
        assertEquals(55, AtlasMath.predict(r, 50, all("A:x", "A:y", "B:z"), new double[] {1, 0.5, 0}), 1e-9);
        // a quarter of the pair x-y: 50 + 15 + 0.25 x 5
        assertEquals(66.25, AtlasMath.predict(r, 50, all("A:x", "A:y"), new double[] {1, 0.25, 0}), 1e-9);
        // on the logit scale a measured set at its order comes back (within the ends kept off)
        Map<String, Double> lg = AtlasMath.logits(r);
        assertEquals(70, AtlasMath.predictLogit(lg, AtlasMath.logit(50), all("A:x", "A:y"), 2), 1e-9);
    }

    @Test
    void anAmountSpreadsOnTheTwoLevels() {
        assertArrayEquals(new double[] {0.5, 0}, AtlasMath.statWeights(30, 60, 120), 1e-9);
        assertArrayEquals(new double[] {0.5, 0.5}, AtlasMath.statWeights(90, 60, 120), 1e-9);
        assertArrayEquals(new double[] {0, 1.5}, AtlasMath.statWeights(180, 60, 120), 1e-9);
        assertEquals(1, AtlasMath.correlation(List.of(1.0, 2.0, 3.0), List.of(2.0, 4.0, 6.0)), 1e-9);
    }

    @Test
    void theTermsSplitTheSumByOrderAndSide() {
        Map<String, Double> r = races();
        Map<String, Double> w = new LinkedHashMap<>(all("A:x", "A:y", "B:z"));
        w.put("A:y", 0.5);
        double[] t = AtlasMath.terms(r, 50, w);
        // notes of A: 10 + 0.5 x 5; of B: -10; pairs: 0.5 x 5 - 5 + 0; triple: 0.5 x 7
        assertArrayEquals(new double[] {12.5, -10, -2.5, 3.5}, t, 1e-9);
        assertEquals(50 + t[0] + t[1] + t[2] + t[3], AtlasMath.predict(r, 50, w, 3), 1e-9);
    }

    @Test
    void thePartialIsWhatAWeightAddsExactly() {
        Map<String, Double> r = races();
        Map<String, Double> without = all("A:y", "B:z");
        double[] d = AtlasMath.partial(r, 50, without, "A:x");
        // note 10; pairs with y (+5) and z (-5); the triple 7
        assertArrayEquals(new double[] {10, 0, 7}, d, 1e-9);
        Map<String, Double> with = new LinkedHashMap<>(without);
        with.put("A:x", 0.6);
        double before = AtlasMath.predict(r, 50, without, 3);
        assertEquals(before + 0.6 * (d[0] + d[1] + d[2]), AtlasMath.predict(r, 50, with, 3), 1e-9);
        // the condition itself in the set is left out
        assertArrayEquals(d, AtlasMath.partial(r, 50, with, "A:x"), 1e-9);
    }

    @Test
    void ridgeFindsTheCoefficientsAndShrinksWithThePenalty() {
        double[][] x = new double[40][];
        double[] y = new double[40];
        for (int i = 0; i < 40; i++) {
            x[i] = new double[] {i % 7, (i * 3) % 5, 1};
            y[i] = 2 * x[i][0] - 1.5 * x[i][1] + 0.25;
        }
        assertArrayEquals(new double[] {2, -1.5, 0.25}, AtlasMath.ridge(x, y, new double[] {0, 0, 0}), 1e-9);
        double[] shrunk = AtlasMath.ridge(x, y, new double[] {1000, 1000, 1000});
        assertEquals(true, Math.abs(shrunk[0]) < 2 && Math.abs(shrunk[1]) < 1.5);
    }

    @Test
    void theModelsGradientMatchesItsLogit() {
        Map<String, Double> lg = AtlasMath.logits(races());
        double lb = AtlasMath.logit(50);
        AtlasModel.BuildModel m = new AtlasModel.BuildModel();
        m.coef = new double[] {0.8, 0.9, 0.5, 0.25};
        m.theta.put("A:x", -0.2);
        Map<String, Double> without = all("A:y", "B:z");
        Map<String, Double> with = new LinkedHashMap<>(without);
        with.put("A:x", 1.0);
        double g = AtlasMath.modelGrad(lg, lb, without, "A:x", m);
        assertEquals(AtlasMath.modelLogit(lg, lb, without, m) + g, AtlasMath.modelLogit(lg, lb, with, m), 1e-9);
        // whole coefficients and no correction: the logit prediction with every order
        AtlasModel.BuildModel whole = new AtlasModel.BuildModel();
        whole.coef = new double[] {1, 1, 1, 1};
        assertEquals(AtlasMath.predictLogit(lg, lb, with, 3), AtlasMath.expit(AtlasMath.modelLogit(lg, lb, with, whole)), 1e-9);
    }
}
