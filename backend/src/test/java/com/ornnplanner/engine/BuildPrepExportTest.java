package com.ornnplanner.engine;

import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.repo.BuildRepository.Build;
import com.ornnplanner.repo.BuildRepository.RunePage;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Copying a build's rune page (with its settings) and summoner spells to other builds. */
class BuildPrepExportTest {

    private static BuildRepository repo(Path dir) {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("t.db"));
        ds.setDriverClassName("org.sqlite.JDBC");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        return new BuildRepository(new JdbcTemplate(ds));
    }

    private static Build build(long folder, String name, String keystone, List<String> spells) {
        Build b = new Build();
        b.folderId = folder;
        b.name = name;
        b.unitCode = "ORNN";
        b.goldPerMin = 400;
        b.xpPerMin = 500;
        b.runePage = new RunePage();
        b.runePage.primary = "Determinação";
        b.runePage.secondary = "Dominação";
        b.runePage.keystone = keystone;
        b.spells = spells;
        b.note = "nota de " + name;
        return b;
    }

    @Test
    void copiesRunesWithTheirSettingsAndSpellsLeavingTheRest(@TempDir Path dir) {
        BuildRepository repo = repo(dir);
        long folder = repo.insertFolder("Pasta");
        long target = repo.insertBuild(build(folder, "alvo", "Aftershock", List.of("Flash", "Curar")));
        RunePage page = build(folder, "x", "Grasp of the Undying", List.of()).runePage;
        page.primaryRunes = new java.util.ArrayList<>(java.util.Arrays.asList("Demolish", null, "Overgrowth"));
        page.options.put("Grasp of the Undying", 3);
        page.conditional.put("Overgrowth", false);

        assertTrue(repo.updatePrep(target, page, List.of("Flash", "Incendiar")));
        Build after = repo.findBuild(target).orElseThrow();
        assertEquals("Grasp of the Undying", after.runePage.keystone);
        assertEquals(Integer.valueOf(3), after.runePage.options.get("Grasp of the Undying"));
        assertEquals(Boolean.FALSE, after.runePage.conditional.get("Overgrowth"));
        assertEquals(List.of("Flash", "Incendiar"), after.spells);
        assertEquals("alvo", after.name);
        assertEquals("nota de alvo", after.note);
    }

    @Test
    void skillPlanReplacesOrderAndLevelsKeepingTheOtherChoices(@TempDir Path dir) {
        BuildRepository repo = repo(dir);
        long folder = repo.insertFolder("Pasta");
        Build b = build(folder, "alvo", "Aftershock", List.of("Flash"));
        b.championSetup = new BuildRepository.ChampionSetup();
        b.championSetup.skillLevels = List.of("1", "2", "3", "1", "R", "1", "1", "2", "R", "2", "2", "3", "R", "3", "3");
        b.championSetup.options.put("Efeito", 2);
        long target = repo.insertBuild(b);
        long empty = repo.insertBuild(build(folder, "vazia", "Aftershock", List.of()));

        assertTrue(repo.updateSkillPlan(target, List.of(3, 1, 2), null));
        Build after = repo.findBuild(target).orElseThrow();
        assertEquals(List.of(3, 1, 2), after.championSetup.skillOrder);
        assertEquals(null, after.championSetup.skillLevels);
        assertEquals(Integer.valueOf(2), after.championSetup.options.get("Efeito"));

        List<String> plan = List.of("2", "1", "3", "2", "R", "2", "2", "1", "R", "1", "1", "3", "R", "3", "3");
        assertTrue(repo.updateSkillPlan(empty, List.of(2, 1, 3), plan));
        assertEquals(plan, repo.findBuild(empty).orElseThrow().championSetup.skillLevels);
        assertFalse(repo.updateSkillPlan(9999, List.of(1, 2, 3), null));
    }

    @Test
    void itemSetupMergesByKeyKeepingTheOthers(@TempDir Path dir) {
        BuildRepository repo = repo(dir);
        long folder = repo.insertFolder("Pasta");
        Build b = build(folder, "alvo", "Aftershock", List.of());
        b.itemSetup = new BuildRepository.ItemSetup();
        b.itemSetup.conditional.put("Égide|Escudo", false);
        b.itemSetup.conditional.put("Outro|Efeito", false);
        BuildRepository.RatePeriod period = new BuildRepository.RatePeriod();
        period.perMinute = 2;
        b.itemSetup.rates.put("Égide|Acúmulos", List.of(period));
        long target = repo.insertBuild(b);

        BuildRepository.ItemSetup patch = new BuildRepository.ItemSetup();
        patch.conditional.put("Égide|Escudo", true);
        patch.options.put("Égide|Escudo", 3);
        patch.rates.put("Égide|Acúmulos", null);
        assertTrue(repo.mergeItemSetup(target, patch));
        BuildRepository.ItemSetup after = repo.findBuild(target).orElseThrow().itemSetup;
        assertEquals(Boolean.TRUE, after.conditional.get("Égide|Escudo"));
        assertEquals(Integer.valueOf(3), after.options.get("Égide|Escudo"));
        assertEquals(Boolean.FALSE, after.conditional.get("Outro|Efeito"));
        assertFalse(after.rates.containsKey("Égide|Acúmulos"));
        assertFalse(repo.mergeItemSetup(9999, patch));
    }

    @Test
    void onlySpellsKeepsTheRunes(@TempDir Path dir) {
        BuildRepository repo = repo(dir);
        long folder = repo.insertFolder("Pasta");
        long target = repo.insertBuild(build(folder, "alvo", "Aftershock", List.of("Flash", "Curar")));
        assertTrue(repo.updatePrep(target, null, List.of("Fantasma", "Flash")));
        Build after = repo.findBuild(target).orElseThrow();
        assertEquals("Aftershock", after.runePage.keystone);
        assertEquals(List.of("Fantasma", "Flash"), after.spells);
        assertFalse(repo.updatePrep(9999, null, List.of("Flash")));
    }
}
