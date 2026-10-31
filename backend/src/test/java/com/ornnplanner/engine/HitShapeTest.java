package com.ornnplanner.engine;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel.Action;
import com.ornnplanner.arena.ArenaModel.Result;
import com.ornnplanner.arena.ArenaModel.Use;
import com.ornnplanner.arena.fight.HitShape;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every form hits and misses at its limits, by one test (the target's circle), and the sheets' zones work in a fight. */
class HitShapeTest {

    private static final double R = 80;

    /** A target of radius R just touching at (x, y) is hit; one step farther along (dx, dy) is not. */
    private static void edge(HitShape s, double x, double y, double dx, double dy) {
        assertTrue(s.hits(x, y, R), s.form + " touching");
        assertFalse(s.hits(x + dx, y + dy, R), s.form + " past the edge");
    }

    @Test
    void circle() {
        edge(HitShape.circle(300), 380, 0, 1, 0);
        assertEquals(0, HitShape.circle(300).gap(100, 100));
    }

    @Test
    void ringHitsOnlyBetweenItsRadii() {
        HitShape ring = HitShape.ring(240, 460);
        edge(ring, 540, 0, 1, 0);
        edge(ring, 160, 0, -1, 0);
        assertTrue(ring.hits(350, 0, 0));
        assertFalse(ring.hits(0, 0, R));
    }

    @Test
    void sectorHasStraightEdgesAndAnInnerRadius() {
        HitShape s = HitShape.sector(Math.toRadians(90), 500, 0);
        edge(s, 580, 0, 1, 0);
        // a target beside the straight edge, at 45 degrees: it touches along the edge's normal
        double c = Math.cos(Math.toRadians(45));
        edge(s, 300 * c - R * c, 300 * c + R * c, -1, 1);
        assertFalse(s.hits(-200, 0, R));
        HitShape annular = HitShape.sector(Math.toRadians(90), 500, 200);
        edge(annular, 120, 0, -1, 0);
    }

    @Test
    void rectangleFromTheBaseOrTheCenter() {
        HitShape base = HitShape.rectangle(600, 200, false);
        edge(base, 680, 0, 1, 0);
        edge(base, -80, 0, -1, 0);
        edge(base, 300, 180, 0, 1);
        HitShape center = HitShape.rectangle(600, 200, true);
        edge(center, 380, 0, 1, 0);
        edge(center, -380, 0, -1, 0);
    }

    @Test
    void capsuleHasRoundEnds() {
        HitShape c = HitShape.capsule(600, 200, false);
        edge(c, 780, 0, 1, 0);
        edge(c, 300, 180, 0, 1);
        double round = (100 + R) * Math.sqrt(0.5);
        edge(c, 600 + round, round, 1, 1);
        // the corner a rectangle would have is out of a capsule
        assertFalse(c.hits(600 + 170, 170, R));
    }

    @Test
    void polygonIsTestedInsideAndByItsEdges() {
        HitShape p = HitShape.polygon(new double[][] {{0, -100}, {400, -100}, {400, 100}, {0, 100}});
        assertTrue(p.hits(200, 0, 0));
        edge(p, 480, 0, 1, 0);
        edge(p, 200, 180, 0, 1);
    }

    @Test
    void chainFollowsItsPoints() {
        HitShape c = HitShape.chain(new double[][] {{0, 0}, {300, 0}, {300, 300}}, 100);
        edge(c, 150, 130, 0, 1);
        edge(c, 430, 150, 1, 0);
        assertFalse(c.hits(100, 250, R));
    }

    @Test
    void theWorldTestTurnsWithTheCast() {
        HitShape r = HitShape.rectangle(600, 100, false);
        assertTrue(r.hitsAt(1000, 1000, Math.PI / 2, 1000, 1500, 0));
        assertFalse(r.hitsAt(1000, 1000, 0, 1000, 1500, 0));
    }

    @Test
    void outlinesCloseEveryFormAndARingHasAHole() {
        for (HitShape s : List.of(HitShape.circle(100), HitShape.sector(1, 100, 50), HitShape.capsule(100, 20, true),
                HitShape.rectangle(10, 10, false))) {
            assertEquals(1, s.outline().size(), s.form);
        }
        assertEquals(2, HitShape.ring(50, 100).outline().size());
    }

    // ------------------------------------------------------------------ the sheets

    private static Action cast(int fighter, double t, String slot) {
        return ArenaScenarios.order(fighter, t, "ability", slot, 1);
    }

    private static Result decimate(double gap) {
        Arena.Input darius = ArenaScenarios.champion("Darius", 0, 2000, 0, 3000, 200, 80);
        Arena.Input other = ArenaScenarios.champion("Ornn", 1, 2000 + gap, 0, 3000, 100, 80);
        darius.autoAbilities = false;
        other.autoAbilities = false;
        other.kit = new LinkedHashMap<>(other.kit);
        other.f.ranks = new int[4];
        return new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).run(new ArrayList<>(List.of(darius, other)),
                List.of(cast(0, 0.1, "1")), 1.2);
    }

    private static double bleedOn(Result r) {
        return r.fighters.get(0).events.getOrDefault("cc_aplicado:lentidao", 0.0)
                + r.fighters.get(0).events.getOrDefault("zona:1:lâmina", 0.0);
    }

    @Test
    void decimateBleedsOnTheBladeAndNotOnTheHandle() {
        Result blade = decimate(400);
        Result handle = decimate(100);
        assertEquals(1, bleedOn(blade));
        assertEquals(0, bleedOn(handle));
        assertEquals(1, handle.fighters.get(0).events.getOrDefault("zona:1:cabo", 0.0));
        Use bladeUse = blade.fighters.get(0).uses.get(0);
        Use handleUse = handle.fighters.get(0).uses.get(0);
        assertEquals(Map.of("lâmina", 1), bladeUse.zones);
        assertEquals(Map.of("cabo", 1), handleUse.zones);
        double b = bladeUse.damage;
        double h = handleUse.damage;
        assertTrue(h < b * 0.5, "handle " + h + " blade " + b);
    }

    @Test
    void aTargetTakesOnlyTheFirstZoneItIsIn() {
        // at 200 the body touches both the ring and the circle: the ring comes first in the sheet
        Result both = decimate(200);
        assertEquals(1, both.fighters.get(0).events.getOrDefault("zona:1:lâmina", 0.0));
        assertEquals(0, both.fighters.get(0).events.getOrDefault("zona:1:cabo", 0.0));
    }

    @Test
    void zonesAreDeterministic() {
        assertEquals(ArenaScenarios.fingerprint(decimate(400)), ArenaScenarios.fingerprint(decimate(400)));
    }

    @Test
    void zonesAreDrawnWithTheSameGeometry() {
        Result r = decimate(400);
        long zones = r.frames.stream().flatMap(f -> f.shapes.stream()).filter(s -> "zone".equals(s.kind)).count();
        assertEquals(2, zones, "the ring and the circle, in the frame after the cast");
    }

    /** A synthetic sheet with zones in time. */
    private static Map<String, Object> timed(Map<String, Object> extra) {
        Map<String, Object> z = new LinkedHashMap<>();
        z.put("forma", "circulo");
        z.put("raio", 150);
        Map<String, Object> dano = new LinkedHashMap<>();
        dano.put("tipo", "verdadeiro");
        dano.put("base", 100);
        Map<String, Object> ab = new LinkedHashMap<>();
        ab.put("nome", "Teste");
        ab.put("forma", "zonas");
        ab.put("recarga", 100);
        ab.put("dano", dano);
        ab.put("zonas", List.of(z));
        ab.putAll(extra);
        return ab;
    }

    private static Result timedFight(Map<String, Object> ability, double gap, double seconds) {
        Arena.Input a = ArenaScenarios.champion("Darius", 0, 2000, 0, 3000, 0, 80);
        Arena.Input b = ArenaScenarios.champion("Ornn", 1, 2000 + gap, 0, 3000, 0, 80);
        a.autoAbilities = false;
        b.autoAbilities = false;
        b.f.ranks = new int[4];
        Map<String, Object> kit = new LinkedHashMap<>(a.kit);
        Map<String, Object> abilities = new LinkedHashMap<>();
        abilities.put("1", ability);
        kit.put("habilidades", abilities);
        a.kit = kit;
        return new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).run(new ArrayList<>(List.of(a, b)), List.of(cast(0, 0.1, "1")), seconds);
    }

    private static double dealt(Result r) {
        return r.fighters.get(0).damageBySource.getOrDefault("1 · Teste", 0.0);
    }

    @Test
    void aDelayedZoneLandsLaterAndItsTargetCanBeGone() {
        assertEquals(100, dealt(timedFight(timed(Map.of("atraso", 0.5)), 100, 1.2)), 1e-6);
        assertEquals(0, dealt(timedFight(timed(Map.of("atraso", 0.5)), 100, 0.4)), 1e-6);
    }

    @Test
    void aZoneThatStaysHitsAtEachInterval() {
        assertEquals(400, dealt(timedFight(timed(Map.of("duracao", 1.5, "intervalo", 0.5)), 100, 3)), 1e-6);
    }

    @Test
    void aTravellingZoneHitsOnceWhenItArrives() {
        Map<String, Object> ab = timed(Map.of("move", Map.of("velocidade", 1000, "distancia", 1500)));
        assertEquals(0, dealt(timedFight(ab, 800, 0.4)), 1e-6);
        assertEquals(100, dealt(timedFight(ab, 800, 2)), 1e-6);
    }

    @Test
    void aGrowingZoneReachesOnlyAtItsSize() {
        Map<String, Object> ab = timed(Map.of("expande", Map.of("de", 0.1, "em", 1)));
        assertEquals(0, dealt(timedFight(ab, 200, 0.5)), 1e-6);
        assertEquals(100, dealt(timedFight(ab, 200, 1.5)), 1e-6);
    }
}
