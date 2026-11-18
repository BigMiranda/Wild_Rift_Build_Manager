package com.ornnplanner.arena.draft;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rodada 20, Parte 3: the draft's notes and the tree of scenarios on synthetic champions. */
class DraftModelTest {

    static final List<String> LANES = List.of("Barão", "Selva", "Meio", "Dragão", "Suporte");
    static final String BLUE = LiveGameModel.BLUE;
    static final String RED = LiveGameModel.RED;

    /** 8 champions per lane, named by lane and number, with growing power; a few with two lanes. */
    static List<DraftModel.Champ> champs() {
        List<DraftModel.Champ> out = new ArrayList<>();
        for (String lane : LANES) {
            for (int i = 0; i < 8; i++) {
                DraftModel.Champ c = new DraftModel.Champ();
                c.name = lane + i;
                c.lanes.add(lane);
                if (i == 7) {
                    c.lanes.add("Meio".equals(lane) ? "Barão" : "Meio");
                }
                c.subclasses.add("Sub" + (i % 3));
                c.physical = i % 2 == 0 ? 0.9 : 0.1;
                c.magic = 1 - c.physical;
                c.frontline = i % 4 == 0;
                c.control = i % 3 == 0;
                c.initiation = i == 3;
                c.longRange = i == 5;
                c.healShield = i == 6;
                c.logPower = 10 + i * 0.1;
                out.add(c);
            }
        }
        return out;
    }

    /** Measured: in each lane, champion k beats the one before it (k-1) clearly; else not measured. */
    static final DraftModel.Matchup MEASURED = (a, b, lane) -> {
        int ia = Integer.parseInt(a.replaceAll("\\D", ""));
        int ib = Integer.parseInt(b.replaceAll("\\D", ""));
        if (!a.startsWith(lane) || !b.startsWith(lane)) {
            return null;
        }
        return ia == (ib + 1) % 8 ? Double.valueOf(0.9) : ib == (ia + 1) % 8 ? Double.valueOf(-0.9) : null;
    };

    static DraftModel model() {
        return new DraftModel(champs(), Map.of(), MEASURED, new DraftModel.Weights(), LANES);
    }

    @Test
    void theBeamNeverComputesMoreThanFivePerStep() {
        DraftModel m = model();
        DraftModel.Board b = new DraftModel.Board();
        List<String> order = List.of(BLUE, RED, RED, BLUE, BLUE, RED, RED, BLUE, BLUE, RED);
        DraftModel.Node root = m.expand(b, List.of(), order);
        assertEquals(BLUE, root.side);
        assertEquals(5, root.children.size());
        assertEquals(5, m.expanded);
        // expanding one child computes only its 5 children, never the whole tree
        DraftModel.Scored first = root.children.get(0);
        DraftModel.Node child = m.expand(b, List.of(new DraftModel.Pick(BLUE, first.champion, first.lane, true)), order);
        assertEquals(RED, child.side);
        assertTrue(child.children.size() <= 5);
        assertEquals(10, m.expanded);
        DraftModel.Scored second = child.children.get(0);
        DraftModel.Node grand = m.expand(b, List.of(new DraftModel.Pick(BLUE, first.champion, first.lane, true),
                new DraftModel.Pick(RED, second.champion, second.lane, true)), order);
        assertEquals(RED, grand.side, "the second red pick of the snake");
        assertEquals(15, m.expanded);
    }

    @Test
    void fixingAPredictionChangesTheLists() {
        DraftModel m = model();
        DraftModel.Board b = new DraftModel.Board();
        b.picks.add(new DraftModel.Pick(BLUE, "Selva0", "Selva", false));
        List<DraftModel.Scored> before = m.rank(b, BLUE, 5);
        // "acho que vão de Barão3": the ally's Barão answer is the one that beats it (Barão4)
        DraftModel.Board fixed = b.with(new DraftModel.Pick(RED, "Barão3", "Barão", true));
        List<DraftModel.Scored> after = m.rank(fixed, BLUE, 5);
        assertNotEquals(DraftModel.names(before), DraftModel.names(after));
        assertEquals("Barão4", after.get(0).champion);
        assertTrue(after.get(0).reasons.stream().anyMatch(r -> "ganhaRota".equals(r.key) && Boolean.TRUE.equals(r.vars.get("predicted"))));
        // and the enemy's prediction no longer offers the predicted one
        assertFalse(DraftModel.names(m.rank(fixed, RED, 40)).contains("Barão3"));
    }

    @Test
    void fearlessRemovesTheUsedOnes() {
        DraftModel m = model();
        DraftModel.Board b = new DraftModel.Board();
        List<String> all = DraftModel.names(m.rank(b, BLUE, 100));
        assertTrue(all.contains("Barão7"));
        b.unavailable.put(BLUE, new LinkedHashSet<>(List.of("Barão7", "Meio7")));
        List<String> blue = DraftModel.names(m.rank(b, BLUE, 100));
        assertFalse(blue.contains("Barão7"));
        assertFalse(blue.contains("Meio7"));
        // the scope "proprio": the other side may still pick them
        assertTrue(DraftModel.names(m.rank(b, RED, 100)).contains("Barão7"));
    }

    @Test
    void aFullLaneGetsNoCandidates() {
        DraftModel m = model();
        DraftModel.Board b = new DraftModel.Board();
        b.picks.add(new DraftModel.Pick(BLUE, "Barão0", "Barão", false));
        for (DraftModel.Scored s : m.rank(b, BLUE, 100)) {
            assertNotEquals("Barão", s.lane, s.champion);
        }
        // a two-lane champion still comes, in its other lane
        assertTrue(m.rank(b, BLUE, 100).stream().anyMatch(s -> "Meio7".equals(s.champion) && "Barão".equals(s.lane)) == false);
        // the other side's Barão stays open
        assertTrue(m.rank(b, RED, 100).stream().anyMatch(s -> "Barão".equals(s.lane)));
        // all five lanes full: nothing
        for (String lane : List.of("Selva", "Meio", "Dragão", "Suporte")) {
            b.picks.add(new DraftModel.Pick(BLUE, lane + "0", lane, false));
        }
        assertTrue(m.rank(b, BLUE, 5).isEmpty());
    }

    @Test
    void theBansAreTheOtherSidesBest() {
        DraftModel m = model();
        DraftModel.Board b = new DraftModel.Board();
        b.picks.add(new DraftModel.Pick(BLUE, "Barão3", "Barão", false));
        List<DraftModel.Scored> bans = m.threats(b, BLUE, 5);
        assertEquals(DraftModel.names(m.rank(b, RED, 5)), DraftModel.names(bans));
        assertEquals("Barão4", bans.get(0).champion, "the one that beats the ally's pick");
    }
}
