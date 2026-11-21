package com.ornnplanner.engine;

import com.ornnplanner.arena.league.LeagueModel;
import com.ornnplanner.arena.league.LeagueModel.Bout;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rodada 21: the validation bank, the matrix and the acceptance rule (pure, no fights). */
class LeagueModelTest {

    static LeagueModel.BankSettings settings() {
        LeagueModel.BankSettings s = new LeagueModel.BankSettings();
        s.classes = List.of("Tanque", "Lutador", "Assassino", "Mago", "Atirador", "Controlador", "Especialista");
        s.minutes = List.of(6.0, 12.0, 20.0);
        s.seeds = 3;
        s.style.put("fuga", "fugir");
        return s;
    }

    static Map<String, List<String>> adversaries() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (String c : settings().classes) {
            m.put(c, List.of(c + "A", c + "B"));
        }
        return m;
    }

    @Test
    void bankHas504FightsOf18PerCell() {
        List<Bout> bank = LeagueModel.bank(settings(), adversaries(), List.of("Eu1", "Eu2"));
        assertEquals(7 * 2 * 3 * 4 * 3, bank.size());
        Map<String, Integer> cells = new LinkedHashMap<>();
        for (Bout b : bank) {
            cells.merge(b.opponentClass + "|" + b.type, 1, Integer::sum);
            assertEquals("fuga".equals(b.type) ? "fugir" : "padrao", b.style);
            if ("dupla".equals(b.type)) {
                assertNotNull(b.myAlly);
                assertNotNull(b.theirAlly);
                assertNotEquals(b.opponent, b.myAlly);
            }
        }
        assertEquals(28, cells.size());
        cells.values().forEach(n -> assertEquals(18, n));
        // the class AI's players take turns
        assertTrue(bank.stream().anyMatch(b -> b.player.equals("Eu1")));
        assertTrue(bank.stream().anyMatch(b -> b.player.equals("Eu2")));
    }

    @Test
    void bankIsDeterministic() {
        List<Bout> a = LeagueModel.bank(settings(), adversaries(), List.of("Eu"));
        List<Bout> b = LeagueModel.bank(settings(), adversaries(), List.of("Eu"));
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).key(), b.get(i).key());
        }
        assertNotEquals(a.get(0).sample, a.get(1).sample);
    }

    static LeagueModel.Matrix matrix(double[] mine, double[] tactical) {
        List<Bout> bouts = new ArrayList<>();
        List<Boolean> m = new ArrayList<>();
        List<Boolean> t = new ArrayList<>();
        String[] types = {"rota", "aberto"};
        for (int c = 0; c < mine.length; c++) {
            for (int i = 0; i < 10; i++) {
                Bout b = new Bout();
                b.opponentClass = "C" + c;
                b.type = types[c % 2];
                bouts.add(b);
                m.add(i < mine[c] * 10);
                t.add(i < tactical[c] * 10);
            }
        }
        boolean[] mm = new boolean[m.size()];
        boolean[] tt = new boolean[t.size()];
        for (int i = 0; i < mm.length; i++) {
            mm[i] = m.get(i);
            tt[i] = t.get(i);
        }
        return LeagueModel.matrix(bouts, mm, tt);
    }

    @Test
    void matrixCellsAndMeans() {
        LeagueModel.Matrix m = matrix(new double[] {0.5, 1.0}, new double[] {0.3, 0.6});
        assertEquals(2, m.cells.size());
        assertEquals(50, m.cell("C0", "rota").success, 1e-9);
        assertEquals(30, m.cell("C0", "rota").tactical, 1e-9);
        assertEquals(75, m.mean, 1e-9);
        assertEquals(45, m.tacticalMean, 1e-9);
        assertEquals(30, m.gap(), 1e-9);
    }

    @Test
    void acceptanceNeedsTheMeanAndEveryCell() {
        LeagueModel.Matrix old = matrix(new double[] {0.5, 0.5}, new double[] {0.3, 0.3});
        // better on average, but one cell 20 points worse: refused with a limit of 15
        LeagueModel.Matrix worse = matrix(new double[] {0.3, 1.0}, new double[] {0.3, 0.3});
        LeagueModel.Acceptance a = LeagueModel.accept(worse, old, 0, 15);
        assertFalse(a.accepted, a.reason);
        assertEquals(-20, a.worstCell, 1e-9);
        // with a looser limit it passes
        assertTrue(LeagueModel.accept(worse, old, 0, 25).accepted);
        // the same mean is not above
        assertFalse(LeagueModel.accept(old, old, 0, 15).accepted);
        // with no version in use, against the tactical numbers of its own fights
        LeagueModel.Acceptance first = LeagueModel.accept(old, null, 0, 15);
        assertTrue(first.accepted, first.reason);
        assertEquals("tatico", first.against);
    }
}
