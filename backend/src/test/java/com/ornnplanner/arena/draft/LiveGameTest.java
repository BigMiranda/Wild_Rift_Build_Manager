package com.ornnplanner.arena.draft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rodada 20, Parte 1: the followed match (table live_game) and the draft formats of draft_7_3f.yml. */
class LiveGameTest {

    private static DraftCatalog catalog() {
        return new DraftCatalog(new ClassPathResource("seed/draft_7_3f.yml"));
    }

    @Test
    void theUsersThreeFormatsAndTheirSteps() {
        DraftCatalog c = catalog();
        assertEquals(List.of("ranqueada", "campeonato", "campeonato_fearless"), List.copyOf(c.formats().keySet()));
        for (DraftCatalog.Format f : c.formats().values()) {
            assertEquals(20, f.turns.size(), f.key);
            for (String side : List.of(LiveGameModel.BLUE, LiveGameModel.RED)) {
                assertEquals(5, f.bansPerSide(side), f.key + " " + side);
                assertEquals(5, f.turns.stream().filter(t -> "escolha".equals(t.action) && t.side.equals(side)).count(), f.key + " " + side);
            }
            assertTrue(f.source.startsWith("USU"), "the user's order: " + f.key);
        }
        // ranked: 10 blind bans at once, then the snake 1-2-2-2-2-1
        DraftCatalog.Format r = c.format("ranqueada");
        assertTrue(r.bansRepeat);
        assertFalse(r.banSkip);
        assertTrue(r.turns.subList(0, 10).stream().allMatch(t -> "ban".equals(t.action) && t.simultaneous));
        assertEquals("ABBAABBAAB", sides(r.turns.subList(10, 20)));
        // championship: bans 1-2-2-1, picks 1-2-2-1, bans 1-2-1, picks 1-2-1 (the second phase starts with red: confirmed)
        DraftCatalog.Format ch = c.format("campeonato");
        assertFalse(ch.bansRepeat);
        assertTrue(ch.banSkip);
        assertFalse(ch.fearless);
        assertEquals("ban:ABBAAB|escolha:ABBAAB|ban:BAAB|escolha:BAAB", blocks(ch.turns));
        assertTrue(ch.turns.subList(12, 20).stream().allMatch(t -> t.phase == 2 && t.source == null), "phase 2 confirmed by the user");
        assertTrue(ch.turns.subList(0, 12).stream().allMatch(t -> t.phase == 1 && t.source == null));
        // the Fearless: the same steps
        DraftCatalog.Format fe = c.format("campeonato_fearless");
        assertTrue(fe.fearless);
        assertEquals(blocks(ch.turns), blocks(fe.turns));
    }

    private static String sides(List<DraftCatalog.Turn> ts) {
        StringBuilder b = new StringBuilder();
        ts.forEach(t -> b.append(LiveGameModel.BLUE.equals(t.side) ? 'A' : 'B'));
        return b.toString();
    }

    /** The turns as runs of one action: "ban:ABBA|escolha:..." (A = blue). */
    private static String blocks(List<DraftCatalog.Turn> ts) {
        StringBuilder b = new StringBuilder();
        String last = null;
        for (DraftCatalog.Turn t : ts) {
            if (!t.action.equals(last)) {
                b.append(last == null ? "" : "|").append(t.action).append(':');
                last = t.action;
            }
            b.append(LiveGameModel.BLUE.equals(t.side) ? 'A' : 'B');
        }
        return b.toString();
    }

    @Test
    void nextTurnFollowsTheChampionshipOrderAndASkippedBanCounts() {
        DraftCatalog c = catalog();
        LiveGameModel.Game g = new LiveGameModel.Game();
        g.version = LiveGameModel.VERSION;
        g.mode = "campeonato";
        DraftCatalog.Turn t = c.next(g);
        assertEquals("ban", t.action);
        assertEquals(LiveGameModel.BLUE, t.side);
        // blue does not ban: "sem banimento" counts as done
        g.blue.bans.add(LiveGameModel.NO_BAN);
        t = c.next(g);
        assertEquals(LiveGameModel.RED, t.side);
        assertEquals(0, t.index);
        assertTrue(c.unavailable(g, LiveGameModel.RED).isEmpty(), "a skipped ban bans nobody");
        g.red.bans.add("Zed");
        assertEquals(LiveGameModel.RED, c.next(g).side, "red bans twice");
        g.red.bans.add("Ahri");
        g.blue.bans.add("Lux");
        g.blue.bans.add("Jinx");
        g.red.bans.add(LiveGameModel.NO_BAN);
        t = c.next(g);
        assertEquals("escolha", t.action);
        assertEquals(LiveGameModel.BLUE, t.side);
        g.blue.players.get(0).champion = "Ornn";
        t = c.next(g);
        assertEquals(LiveGameModel.RED, t.side);
        assertEquals(0, t.index);
        g.red.players.get(0).champion = "Darius";
        g.red.players.get(1).champion = "Ahri2";
        g.blue.players.get(1).champion = "Lee Sin";
        g.blue.players.get(2).champion = "Lux2";
        g.red.players.get(2).champion = "Thresh";
        t = c.next(g);
        assertEquals("ban", t.action, "the second phase of bans");
        assertEquals(LiveGameModel.RED, t.side);
        assertEquals(3, t.index);
        assertEquals(2, t.phase);
    }

    @Test
    void theChampionshipForbidsRepeatedBansAndTheRankedAllowsThem() {
        DraftCatalog c = catalog();
        LiveGameModel.Game g = new LiveGameModel.Game();
        g.version = LiveGameModel.VERSION;
        g.mode = "campeonato";
        g.blue.bans.add("Zed");
        assertEquals("banido", c.cannotBan(g, LiveGameModel.RED, 0, "Zed"));
        assertNull(c.cannotBan(g, LiveGameModel.BLUE, 0, "Zed"), "the same slot may keep it");
        assertNull(c.cannotBan(g, LiveGameModel.RED, 0, LiveGameModel.NO_BAN), "skipping is always allowed");
        g.red.players.get(0).champion = "Darius";
        assertEquals("escolhido", c.cannotBan(g, LiveGameModel.BLUE, 1, "Darius"));
        g.mode = "ranqueada";
        assertNull(c.cannotBan(g, LiveGameModel.RED, 0, "Zed"), "blind bans may repeat");
        assertEquals("escolhido", c.cannotBan(g, LiveGameModel.BLUE, 1, "Darius"));
    }

    @Test
    void fearlessBansTheTenOfEveryEarlierGame() {
        DraftCatalog c = catalog();
        LiveGameModel.Game g1 = new LiveGameModel.Game();
        g1.version = LiveGameModel.VERSION;
        g1.mode = "campeonato_fearless";
        g1.id = 7L;
        g1.name = "Final";
        g1.blue.name = "Azuis";
        for (int i = 0; i < 5; i++) {
            g1.blue.players.get(i).champion = "A" + i;
            g1.red.players.get(i).champion = "B" + i;
        }
        LiveGameModel.Game g2 = c.nextInSeries(g1);
        assertEquals("Final — jogo 2", g2.name);
        assertEquals("Azuis", g2.blue.name);
        assertEquals(1, g2.series.size());
        assertEquals(7L, g2.series.get(0).gameId);
        assertEquals(10, c.fearlessUsed(g2, LiveGameModel.BLUE).size());
        assertEquals(c.fearlessUsed(g2, LiveGameModel.BLUE), c.fearlessUsed(g2, LiveGameModel.RED), "always both teams");
        assertTrue(g2.blue.champions().isEmpty(), "the board starts empty");
        for (int i = 0; i < 5; i++) {
            g2.blue.players.get(i).champion = "C" + i;
            g2.red.players.get(i).champion = "D" + i;
        }
        LiveGameModel.Game g3 = c.nextInSeries(g2);
        assertEquals("Final — jogo 3", g3.name);
        assertEquals(20, c.fearlessUsed(g3, LiveGameModel.RED).size(), "game 3: 20 banned before the draft");
        assertTrue(c.unavailable(g3, LiveGameModel.BLUE).containsAll(List.of("A0", "B4", "C2", "D3")));
        assertEquals("fearless", c.cannotBan(g3, LiveGameModel.BLUE, 0, "B4"));
        // a plain championship ignores a series
        g3.mode = "campeonato";
        assertTrue(c.fearlessUsed(g3, LiveGameModel.BLUE).isEmpty());
    }

    @Test
    void oldGamesOpenAsTheNearestFormatWithANotice() {
        DraftCatalog c = catalog();
        LiveGameModel.Game old = new LiveGameModel.Game();
        old.mode = "campeonato";
        old.blue.players.get(0).champion = "Ornn";
        old.blue.players.get(0).lane = "Barão";
        old.blue.players.get(3).lane = "Dragão";
        List<String> notes = c.upgrade(old);
        assertEquals("campeonato", old.mode);
        assertTrue(notes.contains("campeonato"));
        assertEquals("Barão", old.blue.players.get(0).lane, "a picked card keeps its lane as fixed");
        assertNull(old.blue.players.get(3).lane, "an empty card's lane is open now");
        assertEquals(LiveGameModel.VERSION, old.version);
        assertTrue(c.upgrade(old).isEmpty(), "once upgraded, nothing more");

        LiveGameModel.Game fr = new LiveGameModel.Game();
        fr.mode = "ranqueada";
        fr.fearless = true;
        fr.fearlessScope = "proprio";
        LiveGameModel.SeriesGame s = new LiveGameModel.SeriesGame();
        s.blue.add("Ornn");
        s.red.add("Darius");
        fr.series.add(s);
        notes = c.upgrade(fr);
        assertEquals("campeonato_fearless", fr.mode);
        assertTrue(notes.containsAll(List.of("fearlessDaRanqueada", "fearlessProprio")));
        assertTrue(c.unavailable(fr, LiveGameModel.BLUE).containsAll(List.of("Ornn", "Darius")), "both teams now");

        LiveGameModel.Game odd = new LiveGameModel.Game();
        odd.mode = "torneio_x";
        assertEquals(List.of("formatoDesconhecido", "rotasLivres"), c.upgrade(odd));
        assertEquals("ranqueada", odd.mode);
    }

    @Test
    void gameRoundTripsThroughTheTable(@TempDir Path dir) {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("t.db"));
        ds.setDriverClassName("org.sqlite.JDBC");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        LiveGameRepository repo = new LiveGameRepository(new JdbcTemplate(ds));
        LiveGameModel.Game g = new LiveGameModel.Game();
        g.name = "Final";
        g.fearless = true;
        g.blue.players.get(0).champion = "Ornn";
        g.blue.players.get(0).spells = List.of("Flash", "Teleporte");
        g.blue.players.get(0).items.add(1L);
        g.red.players.get(0).champion = "Darius";
        LiveGameModel.Event e = new LiveGameModel.Event();
        e.t = 450;
        e.kind = "dragao";
        e.side = LiveGameModel.BLUE;
        e.detail = "infernal";
        g.events.add(e);
        long id = repo.save(g).id;
        LiveGameModel.Game back = repo.find(id).orElseThrow();
        assertEquals("Final", back.name);
        assertTrue(back.fearless);
        assertEquals("Ornn", back.blue.players.get(0).champion);
        assertEquals(List.of("Flash", "Teleporte"), back.blue.players.get(0).spells);
        assertNull(back.red.players.get(0).lane, "Rodada 20.b: a lane stays open until the loading screen");
        assertEquals(5, back.red.players.size());
        assertEquals("infernal", back.events.get(0).detail);
        assertNull(back.blue.players.get(1).champion);
        back.name = "Final 2";
        repo.save(back);
        assertEquals(1, repo.list().size());
        assertEquals("Final 2", repo.list().get(0).get("name"));
        repo.delete(id);
        assertTrue(repo.list().isEmpty());
    }
}
