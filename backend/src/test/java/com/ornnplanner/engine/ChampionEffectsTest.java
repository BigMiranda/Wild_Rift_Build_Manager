package com.ornnplanner.engine;

import com.ornnplanner.engine.Model.EngineInput;
import com.ornnplanner.engine.Model.ItemDef;
import com.ornnplanner.engine.Model.MinutePoint;
import com.ornnplanner.engine.Model.ReferenceData;
import com.ornnplanner.engine.Model.StatLine;
import com.ornnplanner.seed.ChampionCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static com.ornnplanner.engine.Stats.ABILITY_POWER;
import static com.ornnplanner.engine.Stats.ARMOR;
import static com.ornnplanner.engine.Stats.ATTACK_DAMAGE;
import static com.ornnplanner.engine.Stats.MAGIC_RESIST;
import static com.ornnplanner.engine.Stats.MAX_HEALTH;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Champion passives / abilities from campeoes_7_3.yml, checked against values read in the game's training mode. */
class ChampionEffectsTest {

    private static final double EPS = 1e-6;
    private static ChampionCatalog champions;
    private static ReferenceData ref;

    @BeforeAll
    static void load() {
        champions = new ChampionCatalog(new ClassPathResource("seed/campeoes_7_3.yml"),
                new ClassPathResource("seed/precos_status.yml"));
        ref = new ReferenceData();
        double[] xp = {0, 280, 660, 1140, 1720, 2400, 3180, 4060, 5040, 6120, 7300, 8580, 9960, 11440, 13020};
        for (int i = 0; i < xp.length; i++) {
            ref.xpTable.put(i + 1, xp[i]);
        }
        ref.items.put(1L, item(1, StatLine.flat(ARMOR, 40)));
        ref.items.put(2L, item(2, StatLine.flat(MAX_HEALTH, 150)));
    }

    private static ItemDef item(long id, StatLine line) {
        ItemDef i = new ItemDef();
        i.id = id;
        i.name = "Item " + id;
        i.cost = 100;
        i.stats.add(line);
        return i;
    }

    /** Stats at minute 0 (level 1, or 15 with a huge XP rate), the given items bought at once. */
    private static Map<String, Double> stats(String code, List<Integer> order, Map<String, Boolean> cond, int level,
                                             Map<String, List<double[]>> rates, double minute, Long... items) {
        EngineInput in = new EngineInput();
        in.unit = champions.units().get(code);
        in.goldPerMin = 1e6;
        in.xpPerMin = level >= 15 ? 1e7 : 0;
        in.itemIds = List.of(items);
        for (ChampionCatalog.EffectItem e : champions.toItems(code, order, Map.of(), cond, rates, -1)) {
            in.runes.add(e.item);
            in.runeConditional.add(e.conditional);
        }
        double at = level >= 15 ? Math.max(1, minute) : minute;   // level 15 from minute 1 on
        in.matchEnd = at + 1;
        Model.TimelineResult r = new TimelineEngine(ref).run(in);
        MinutePoint p = r.series.stream().filter(m -> Math.abs(m.minute - at) < 1e-9).findFirst().orElseThrow();
        assertEquals(level, p.level);
        return p.total;
    }

    @Test
    void skillRanksFollowTheOrder() {
        assertArrayEquals(new int[] {1, 0, 0, 0}, ChampionCatalog.ranks(List.of(1, 2, 3), 1));
        assertArrayEquals(new int[] {1, 1, 1, 0}, ChampionCatalog.ranks(List.of(1, 2, 3), 3));
        assertArrayEquals(new int[] {2, 1, 1, 1}, ChampionCatalog.ranks(List.of(1, 2, 3), 5)); // ultimate at 5
        assertArrayEquals(new int[] {1, 1, 0, 0}, ChampionCatalog.ranks(List.of(2, 1, 3), 2));
        assertArrayEquals(new int[] {1, 4, 1, 1}, ChampionCatalog.ranks(List.of(2, 1, 3), 7));
        assertArrayEquals(new int[] {4, 4, 4, 3}, ChampionCatalog.ranks(List.of(3, 2, 1), 15));
        assertArrayEquals(new int[] {1, 0, 0, 0}, ChampionCatalog.ranks(List.of(9, 9, 9), 1)); // invalid -> 1 > 2 > 3
    }

    @Test
    void malphiteThunderclapIsPercentOfTotalArmor() {
        // In game: Malphite level 1, W rank 1 learned: Armor 51 -> 64.
        assertEquals(51 * 1.25, stats("MALPHITE", List.of(2, 1, 3), Map.of(), 1, Map.of(), 0).get(ARMOR), EPS);
        assertEquals(51.0, stats("MALPHITE", List.of(1, 2, 3), Map.of(), 1, Map.of(), 0).get(ARMOR), EPS); // W not learned
        assertEquals((51 + 40) * 1.25, stats("MALPHITE", List.of(2, 1, 3), Map.of(), 1, Map.of(), 0, 1L).get(ARMOR), EPS);
        assertEquals(123 * 1.40, stats("MALPHITE", List.of(1, 2, 3), Map.of(), 15, Map.of(), 0).get(ARMOR), EPS);
    }

    @Test
    void poppyAndRammusMatchTheGame() {
        // Poppy level 1 with W: 46 -> 52 Armor, 36 -> 41 MR (12% of total).
        Map<String, Double> poppy = stats("POPPY", List.of(2, 1, 3), Map.of(), 1, Map.of(), 0);
        assertEquals(46 * 1.12, poppy.get(ARMOR), EPS);
        assertEquals(36 * 1.12, poppy.get(MAGIC_RESIST), EPS);
        Map<String, Double> low = stats("POPPY", List.of(2, 1, 3), Map.of("Presença Inabalável", true), 1, Map.of(), 0);
        assertEquals(46 * 1.24, low.get(ARMOR), EPS);             // doubled under 40% health
        // Rammus level 1, W active: Armor 42 -> 94, MR 40 -> 67 ((42 + 30) x 1.3, (40 + 10) x 1.35).
        Map<String, Double> off = stats("RAMMUS", List.of(2, 1, 3), Map.of(), 1, Map.of(), 0);
        assertEquals(42.0, off.get(ARMOR), EPS);                  // conditional: off by default
        Map<String, Double> on = stats("RAMMUS", List.of(2, 1, 3), Map.of("Bola Curva Defensiva", true), 1, Map.of(), 0);
        assertEquals(93.6, on.get(ARMOR), EPS);
        assertEquals(67.5, on.get(MAGIC_RESIST), EPS);
    }

    @Test
    void kSanteAllOutLosesHealthAndBonusResistances() {
        Map<String, Double> normal = stats("KSANTE", List.of(1, 2, 3), Map.of(), 15, Map.of(), 0, 1L, 2L);
        Map<String, Double> allOut = stats("KSANTE", List.of(1, 2, 3), Map.of("Forma Irrestrita", true), 15, Map.of(), 0, 1L, 2L);
        assertEquals(113 + 40, normal.get(ARMOR), EPS);
        assertEquals(113 + 40 * 0.25, allOut.get(ARMOR), EPS);
        assertEquals((2704 + 150) * 0.7, allOut.get(MAX_HEALTH), EPS);
    }

    @Test
    void pykeConvertsBonusHealthToAttackDamage() {
        Map<String, Double> s = stats("PYKE", List.of(1, 2, 3), Map.of(), 1, Map.of(), 0, 2L);
        Map<String, Double> bare = stats("PYKE", List.of(1, 2, 3), Map.of(), 1, Map.of(), 0);
        assertEquals(bare.get(MAX_HEALTH), s.get(MAX_HEALTH), EPS);
        assertEquals(bare.get(ATTACK_DAMAGE) + 150 / 14.0, s.get(ATTACK_DAMAGE), 1e-3); // 1/14 written as 0.0714286
    }

    @Test
    void alternateFormsAndStacks() {
        // Gnar: the base is Mini-Gnar's (in game: 600 health at level 1, 1832 at 15); Mega-Gnar adds 100-900.
        assertEquals(600.0, stats("GNAR", List.of(1, 2, 3), Map.of(), 1, Map.of(), 0).get(MAX_HEALTH), EPS);
        assertEquals(1832 + 900.0, stats("GNAR", List.of(1, 2, 3), Map.of("Mega-Gnar", true), 15, Map.of(), 0).get(MAX_HEALTH), EPS);
        // Jayce: Cannon base (48 armor at level 1), the Hammer form is on by default (+5).
        assertEquals(53.0, stats("JAYCE", List.of(1, 2, 3), Map.of(), 1, Map.of(), 0).get(ARMOR), EPS);
        assertEquals(48.0, stats("JAYCE", List.of(1, 2, 3), Map.of("Martelo de Mercúrio", false), 1, Map.of(), 0).get(ARMOR), EPS);
        // Veigar: 3 stacks per minute -> +30 AP at 10:00.
        Map<String, List<double[]>> rate = Map.of("Poder Maligno Fenomenal", List.<double[]>of(new double[] {0, 3}));
        Map<String, Double> veigar = stats("VEIGAR", List.of(1, 2, 3), Map.of(), 1, rate, 10);
        assertEquals(30.0, veigar.get(ABILITY_POWER), EPS);
        assertTrue(champions.effects("ORNN").isEmpty());
    }
}
