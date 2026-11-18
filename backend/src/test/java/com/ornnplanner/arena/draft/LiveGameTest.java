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
    void formatsHaveFiveBansAndFivePicksPerSide() {
        DraftCatalog c = catalog();
        assertTrue(c.formats().containsKey("ranqueada"));
        assertTrue(c.formats().containsKey("campeonato"));
        for (DraftCatalog.Format f : c.formats().values()) {
            for (String side : List.of(LiveGameModel.BLUE, LiveGameModel.RED)) {
                assertEquals(5, f.bansPerSide(side), f.key + " " + side);
                assertEquals(5, f.turns.stream().filter(t -> "escolha".equals(t.action) && t.side.equals(side)).count(), f.key + " " + side);
            }
            assertTrue(f.source.startsWith("EST"), "the order is not the official one: " + f.key);
        }
    }

    @Test
    void nextTurnFollowsTheOrder() {
        DraftCatalog c = catalog();
        LiveGameModel.Game g = new LiveGameModel.Game();
        g.mode = "campeonato";
        DraftCatalog.Turn t = c.next(g);
        assertEquals("ban", t.action);
        assertEquals(LiveGameModel.BLUE, t.side);
        g.blue.bans.add("Zed");
        assertEquals(LiveGameModel.RED, c.next(g).side);
        for (int i = 0; i < 2; i++) {
            g.red.bans.add("A" + i);
            g.blue.bans.add("B" + i);
        }
        g.red.bans.add("C");
        t = c.next(g);
        assertEquals("escolha", t.action);
        assertEquals(LiveGameModel.BLUE, t.side);
        g.blue.players.get(0).champion = "Ornn";
        t = c.next(g);
        assertEquals(LiveGameModel.RED, t.side);
        assertEquals(0, t.index);
    }

    @Test
    void fearlessRemovesTheSeriesChampions() {
        DraftCatalog c = catalog();
        LiveGameModel.Game g = new LiveGameModel.Game();
        LiveGameModel.SeriesGame s = new LiveGameModel.SeriesGame();
        s.blue.add("Ornn");
        s.red.add("Darius");
        g.series.add(s);
        assertFalse(c.unavailable(g, LiveGameModel.BLUE).contains("Ornn"), "not a Fearless series");
        g.fearless = true;
        g.fearlessScope = "ambos";
        assertTrue(c.unavailable(g, LiveGameModel.BLUE).contains("Darius"));
        assertTrue(c.unavailable(g, LiveGameModel.RED).contains("Ornn"));
        g.fearlessScope = "proprio";
        assertTrue(c.unavailable(g, LiveGameModel.BLUE).contains("Ornn"));
        assertFalse(c.unavailable(g, LiveGameModel.BLUE).contains("Darius"));
        assertTrue(c.unavailable(g, LiveGameModel.RED).contains("Darius"));
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
        assertEquals("Barão", back.red.players.get(0).lane);
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
