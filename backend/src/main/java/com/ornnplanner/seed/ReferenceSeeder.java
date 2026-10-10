package com.ornnplanner.seed;

import com.ornnplanner.engine.Model.ForgeTier;
import com.ornnplanner.engine.Model.StatGrowth;
import com.ornnplanner.engine.Model.UnitProfile;
import com.ornnplanner.engine.Stats;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.repo.CatalogRepository;
import com.ornnplanner.repo.ReferenceRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Seeds reference data on startup, only for tables that are still empty, so edits made in the admin screen
 * survive restarts.
 */
@Component
public class ReferenceSeeder implements ApplicationRunner {

    public static final String ORNN = "ORNN";
    public static final String RAGDOLL = "RAGDOLL";

    /**
     * Cumulative XP to reach each level. ESTIMATE: Wild Rift has no reliable public table; these are the first
     * 15 levels of the LoL PC curve (280 XP for level 2, +100 per level after that). Editable in the admin screen.
     */
    static final double[] DEFAULT_XP = {0, 280, 660, 1140, 1720, 2400, 3180, 4060, 5040, 6120, 7300, 8580, 9960,
            11440, 13020};

    private final CatalogRepository catalog;
    private final ReferenceRepository reference;
    private final BuildRepository builds;
    private final CatalogImporter importer;
    private final SchemaManager schema;
    private final ChampionCatalog champions;

    public ReferenceSeeder(CatalogRepository catalog, ReferenceRepository reference, BuildRepository builds,
                           CatalogImporter importer, SchemaManager schema,
                           ChampionCatalog champions) {
        this.catalog = catalog;
        this.reference = reference;
        this.builds = builds;
        this.importer = importer;
        this.schema = schema;
        this.champions = champions;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (catalog.countItems() == 0 || catalog.countStatDefs() == 0 || schema.isCatalogReimportNeeded()) {
            importer.importAll(false);
        }
        if (reference.findUnit(ORNN).isEmpty()) {
            reference.upsertUnit(ornn());
        }
        if (reference.findUnit(RAGDOLL).isEmpty()) {
            UnitProfile r = new UnitProfile();
            r.code = RAGDOLL;
            r.name = "Boneco de pano";
            r.livingForge = false;
            reference.upsertUnit(r); // no stats: always defined by the user, per build
        }
        // Champions measured in the training mode, refreshed from the seed file on every start (only Ornn's base stats
        // are editable in the Admin); Ornn keeps his own calibrated profile (ornn()).
        champions.units().forEach((code, u) -> {
            if (!ORNN.equals(code)) {
                reference.upsertUnit(u);
            }
        });
        if (reference.findForgeTiers().isEmpty()) {
            reference.replaceForgeTiers(List.of(
                    new ForgeTier(1, 0.07), new ForgeTier(7, 0.12), new ForgeTier(10, 0.17), new ForgeTier(13, 0.22)));
        }
        if (reference.findXpTable().isEmpty()) {
            NavigableMap<Integer, Double> xp = new TreeMap<>();
            for (int i = 0; i < DEFAULT_XP.length; i++) {
                xp.put(i + 1, DEFAULT_XP[i]);
            }
            reference.replaceXpTable(xp);
        }
        if (builds.findFolders().isEmpty()) {
            builds.insertFolder("Geral");
        }
    }

    /**
     * Ornn, Wild Rift, measured in game (stats tab at levels 1 and 15, 03/10/2026): growth = (level 15 - level 1) / 14.
     * value(L) = base + growth * (L - 1). Health and AD come out exact (132 and 4 per level), confirming linear growth;
     * the other fractions come from the in-game rounding. Armor and MR: the level 15 tooltip (05/10/2026) shows base
     * 116 / 68; the earlier totals (120 / 71 at 15, 48 / 42 at 1) included a rune, so level 1 is taken as 44 / 39 with
     * the same growth (to be confirmed with a level 1 tooltip). Move speed was not measurable (548 at both levels).
     */
    static UnitProfile ornn() {
        UnitProfile u = new UnitProfile();
        u.code = ORNN;
        u.name = "Ornn";
        u.livingForge = true;
        u.stats.put(Stats.MAX_HEALTH, new StatGrowth(690, 132));
        u.stats.put(Stats.MAX_MANA, new StatGrowth(380, 60));
        u.stats.put(Stats.HEALTH_REGEN, new StatGrowth(17, 17.0 / 14));
        u.stats.put(Stats.MANA_REGEN, new StatGrowth(12, 12.0 / 14));
        u.stats.put(Stats.ARMOR, new StatGrowth(44, 72.0 / 14));
        u.stats.put(Stats.MAGIC_RESIST, new StatGrowth(39, 29.0 / 14));
        u.stats.put(Stats.ATTACK_DAMAGE, new StatGrowth(62, 4));
        u.stats.put(Stats.ABILITY_POWER, new StatGrowth(0, 0));
        u.stats.put(Stats.ABILITY_HASTE, new StatGrowth(0, 0));
        u.stats.put(Stats.MOVE_SPEED, new StatGrowth(345, 0));
        return u;
    }
}
