package com.ornnplanner.engine;

import com.ornnplanner.arena.calc.BuildScore;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.service.PlannerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The class score of a build (Rodada 9, part 1), on the seed's catalog. */
@SpringBootTest(properties = {"server.port=0"})
class BuildScoreTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        String db = Files.createTempDirectory("build-score").resolve("t.db").toString();
        r.add("planner.db-path", () -> db);
    }

    @Autowired
    BuildScore scorer;
    @Autowired
    PlannerService planner;

    private final Map<String, Long> ids = new LinkedHashMap<>();

    @BeforeEach
    void names() {
        planner.itemViews().forEach(i -> ids.put(i.name, i.id));
    }

    private BuildRepository.Build build(String unit, String... items) {
        BuildRepository.Build b = new BuildRepository.Build();
        b.name = "teste " + unit;
        b.unitCode = unit;
        b.goldPerMin = 800;
        b.xpPerMin = 700;
        b.steps = new ArrayList<>();
        for (String i : items) {
            b.steps.add(new BuildRepository.Step(ids.get(i), true));
        }
        return b;
    }

    @Test
    void theWeightsFollowTheClassAndTheArchetype() {
        Map<String, Double> tank = scorer.weights("Tanque", null);
        assertEquals(1.0, tank.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
        assertTrue(tank.get(BuildScore.DEFENSIVE) > tank.get(BuildScore.OFFENSIVE));
        Map<String, Double> marksman = scorer.weights("Atirador", null);
        assertEquals(1.0, marksman.get(BuildScore.OFFENSIVE), 1e-9);
        Map<String, Double> fighterAsTank = scorer.weights("Lutador", "tanque");
        assertTrue(fighterAsTank.get(BuildScore.DEFENSIVE) > scorer.weights("Lutador", null).get(BuildScore.DEFENSIVE));
    }

    @Test
    void thePercentileAndTheOffensivePoints() {
        assertEquals(3.0, BuildScore.percentile(List.of(10.0, 2.0, 5.0, 4.0, 3.0, 8.0), 0.2), 1e-9);
        assertEquals(3.5, BuildScore.percentile(List.of(10.0, 2.0, 5.0, 4.0, 3.0, 8.0), 0.3), 1e-9);
        assertEquals(50, BuildScore.offensivePoints(200), 1e-9);
        assertEquals(0, BuildScore.offensivePoints(100), 1e-9);
        assertTrue(BuildScore.offensivePoints(80) < 0);
    }

    /** The measure the calculator lacked: a tank scores more with tank items, a marksman with damage items. */
    @Test
    void eachClassIsRewardedForItsOwnItems() {
        String[] tankItems = {"Égide de Fogo Solar", "Armadura de Espinhos", "Presságio de Randuin"};
        String[] damage = {"Gume do Infinito", "Lembranças do Lorde Dominik", "Rancor de Serylda"};
        BuildScore.Score ornnTank = scorer.score(build("ORNN", tankItems), 20);
        BuildScore.Score ornnDamage = scorer.score(build("ORNN", damage), 20);
        assertEquals("Tanque", ornnTank.championClass);
        assertTrue(ornnTank.total > ornnDamage.total, "Ornn: tank " + ornnTank.total + " vs damage " + ornnDamage.total);
        BuildScore.Score vayneTank = scorer.score(build("VAYNE", tankItems), 20);
        BuildScore.Score vayneDamage = scorer.score(build("VAYNE", damage), 20);
        assertEquals("Atirador", vayneDamage.championClass);
        assertTrue(vayneDamage.total > vayneTank.total, "Vayne: damage " + vayneDamage.total + " vs tank " + vayneTank.total);
        System.out.printf("SCORE Ornn tanque %.1f (def %.1f, ofe %.1f, p20 %.1f) | dano %.1f%n", ornnTank.total,
                ornnTank.parts.get(BuildScore.DEFENSIVE), ornnTank.parts.getOrDefault(BuildScore.OFFENSIVE, 0.0), ornnTank.p20, ornnDamage.total);
        System.out.printf("SCORE Vayne dano %.1f (ofe %.1f, piso %.1f) | tanque %.1f%n", vayneDamage.total,
                vayneDamage.parts.get(BuildScore.OFFENSIVE), vayneDamage.floorPenalty, vayneTank.total);
    }

    @Test
    void theRobustScoreBeatsAReferenceSometimes() {
        BuildRepository.Build a = build("ORNN", "Égide de Fogo Solar", "Armadura de Espinhos");
        BuildScore.Robust r = scorer.robust(a, build("ORNN", "Gume do Infinito"));
        assertTrue(r.minutes.size() >= 3);
        assertTrue(r.beatsReference != null && r.beatsReference > 50, "beats " + r.beatsReference);
    }
}
