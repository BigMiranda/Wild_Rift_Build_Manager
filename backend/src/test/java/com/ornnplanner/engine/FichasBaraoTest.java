package com.ornnplanner.engine;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel.FighterStats;
import com.ornnplanner.arena.ArenaModel.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rodada 18: the sheets of the other Baron lane champions in a real duel (the validator only reads the sheet): each
 * one is found, fights on autopilot against Ornn, deals damage with its abilities (Tryndamere's only damaging one is the E), and the duel is deterministic.
 */
class FichasBaraoTest {

    /** The champions sheeted in Rodada 18, in the order they were written. */
    static final List<String> RODADA_18 = List.of("Dr. Mundo", "Volibear", "Pantheon", "Urgot", "Cho'Gath", "Kennen", "Poppy",
            "Olaf", "Tryndamere", "Wukong", "Singed", "Jarvan IV", "Warwick", "Nautilus", "Gragas", "Maokai", "Amumu",
            "Yasuo", "Yone", "Akali", "Sylas", "Ambessa", "Diana", "Fizz", "Vladimir", "Zed", "Kassadin", "Ekko", "Graves",
            "Skarner", "Aurora", "Viego", "Jayce", "Gnar");

    /** Rodada 18, part 2: the other champions (every lane), in the order they were written. */
    static final List<String> PARTE_2 = List.of("Ahri", "Akshan", "Alistar", "Annie", "Ashe", "Aurelion Sol", "Bardo", "Blitzcrank",
            "Brand", "Braum", "Caitlyn", "Corki",
            "Draven", "Evelynn", "Ezreal", "Fiddlesticks", "Galio", "Hecarim", "Heimerdinger", "Hwei", "Janna", "Jhin", "Jinx", "Kai'Sa", "Kalista", "Karma",
            "Katarina", "Kayn", "Kha'Zix", "Kindred", "Kog'Maw", "Lee Sin", "Leona", "Lillia", "Lissandra", "Lucian", "Lulu", "Lux", "Master Yi", "Mel", "Milio");

    private static List<String> all() {
        List<String> out = new java.util.ArrayList<>(RODADA_18);
        out.addAll(PARTE_2);
        return out;
    }

    private static Result duel(String name) {
        Arena.Input a = ArenaScenarios.champion(name, 0, 1000, 0, 3000, 150, 90);
        Arena.Input b = ArenaScenarios.champion("Ornn", 1, 1900, 0, 3000, 150, 90);
        return new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).run(List.of(a, b), List.of(), 30);
    }

    @Test
    void everyNewSheetFightsWithItsAbilities() {
        StringBuilder report = new StringBuilder();
        for (String name : all()) {
            assertNotNull(ArenaScenarios.KITS.get(name), name + " has no sheet");
            FighterStats f = duel(name).fighters.get(0);
            report.append(name).append(": ").append(f.damageBySource).append('\n');
            assertTrue(f.dealt > 0, name + " dealt nothing");
            long abilities = f.damageBySource.keySet().stream().filter(k -> k.matches("^(1|2|3|R) · .*")).count();
            assertTrue(abilities >= 1, name + " hit with no ability: " + f.damageBySource);
        }
        System.out.print(report);
    }

    @Test
    void theNewSheetsAreDeterministic() {
        for (String name : all()) {
            Map<String, Double> one = duel(name).fighters.get(0).damageBySource;
            assertEquals(one, duel(name).fighters.get(0).damageBySource, name);
        }
    }
}
