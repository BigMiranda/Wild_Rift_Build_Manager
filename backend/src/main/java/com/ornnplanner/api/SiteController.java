package com.ornnplanner.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ornnplanner.arena.ArenaService;
import com.ornnplanner.publico.PublicExporter;
import com.ornnplanner.publico.PublicMode;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.repo.DiscoveryRepository;
import com.ornnplanner.seed.ChampionCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The static site (Rodada 11.b), on the local app only: what the generation script reads ({@code /info}, over a copy
 * of the exported database) and the state of each step of feeding the site (Admin › Catálogo › "Site público").
 */
@RestController
@RequestMapping("/api/site")
@Profile("!" + PublicMode.PROFILE)
public class SiteController {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Bytes of one build's precomputed files (timelines, effectiveness, abilities at the key minutes), measured in part 2. */
    static final long BYTES_PER_BUILD = 160_000;
    /** The catalog, the icons and the page itself. */
    static final long BYTES_FIXED = 8_000_000;

    private final JdbcTemplate jdbc;
    private final DiscoveryRepository discoveries;
    private final BuildRepository builds;
    private final ChampionCatalog champions;
    private final ArenaService arena;
    private final PublicExporter exporter;
    private final String siteDir;

    public SiteController(JdbcTemplate jdbc, DiscoveryRepository discoveries, BuildRepository builds, ChampionCatalog champions,
                          ArenaService arena, PublicExporter exporter, @Value("${planner.site.dir:../site-publico}") String siteDir) {
        this.jdbc = jdbc;
        this.discoveries = discoveries;
        this.builds = builds;
        this.champions = champions;
        this.arena = arena;
        this.exporter = exporter;
        this.siteDir = siteDir;
    }

    /**
     * What the generation script reads first: the patch, the export's record (null on a database that was not
     * exported), and every champion with a finished discovery of this patch or a build in a public folder.
     */
    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("patch", champions.patch());
        Map<String, String> export = new LinkedHashMap<>();
        try {
            jdbc.query("SELECT key, value FROM public_export ORDER BY key", rs -> {
                export.put(rs.getString(1), rs.getString(2));
            });
            m.put("export", export);
        } catch (DataAccessException e) {
            m.put("export", null);
        }
        m.put("champions", championsOfTheSite());
        return m;
    }

    /** The champions of the site: unit code → {champion, discovery row id or null, public builds}. */
    private List<Map<String, Object>> championsOfTheSite() {
        String patch = champions.patch();
        Map<String, Map<String, Object>> by = new TreeMap<>();
        Set<Long> open = new TreeSet<>();
        builds.findFolders().stream().filter(f -> f.publico).forEach(f -> open.add(f.id));
        boolean exported = !open.isEmpty() && builds.findFolders().stream().allMatch(f -> f.publico);
        for (DiscoveryRepository.DiscoveryRow r : discoveries.discoveries(null)) {
            if ("feito".equals(r.status) && patch.equals(r.patch)) {
                Map<String, Object> c = by.computeIfAbsent(r.unitCode, u -> entry(u));
                DiscoveryRepository.DiscoveryRow latest = discoveries.latestDone(r.unitCode, patch);
                c.put("discovery", latest == null ? null : latest.id);
                c.put("champion", r.champion);
            }
        }
        for (BuildRepository.Build b : builds.findBuildSummaries()) {
            if (open.contains(b.folderId) && !com.ornnplanner.seed.ReferenceSeeder.RAGDOLL.equals(b.unitCode)) {
                Map<String, Object> c = by.computeIfAbsent(b.unitCode, u -> entry(u));
                c.put("publicBuilds", ((Integer) c.get("publicBuilds")) + 1);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(by.values());
        out.forEach(c -> c.put("exported", exported));
        return out;
    }

    private Map<String, Object> entry(String unit) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("unitCode", unit);
        c.put("champion", null);
        c.put("discovery", null);
        c.put("publicBuilds", 0);
        return c;
    }

    /** The state of each step of feeding the site, for the Admin screen. */
    @GetMapping("/status")
    @SuppressWarnings("unchecked")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        String patch = champions.patch();
        m.put("patch", patch);
        // 1. the campaigns: how many champions have a finished discovery, how many have a good sheet
        Map<String, Object> kits = (Map<String, Object>) arena.kits().getOrDefault("campeoes", Map.of());
        List<String> good = new ArrayList<>();
        kits.forEach((name, k) -> {
            Object q = k instanceof Map ? ((Map<String, Object>) k).get("qualidade") : null;
            if (q != null && List.of("A", "B").contains(String.valueOf(q))) {
                good.add(name);
            }
        });
        Set<String> done = new TreeSet<>();
        int running = 0;
        for (DiscoveryRepository.DiscoveryRow r : discoveries.discoveries(null)) {
            if (patch.equals(r.patch) && "feito".equals(r.status)) {
                done.add(r.champion == null ? r.unitCode : r.champion);
            } else if (patch.equals(r.patch) && List.of("pendente", "rodando").contains(r.status)) {
                running++;
            }
        }
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM unit WHERE code <> ?", Integer.class, com.ornnplanner.seed.ReferenceSeeder.RAGDOLL);
        m.put("championsTotal", total == null ? 0 : total);
        m.put("goodSheets", good);
        m.put("discovered", new ArrayList<>(done));
        m.put("queued", running);
        // 2. what is marked public
        List<String> folders = new ArrayList<>();
        Set<Long> open = new TreeSet<>();
        builds.findFolders().stream().filter(f -> f.publico).forEach(f -> {
            folders.add(f.name);
            open.add(f.id);
        });
        long publicBuilds = builds.findBuildSummaries().stream().filter(b -> open.contains(b.folderId)).count();
        m.put("publicFolders", folders);
        m.put("publicBuilds", publicBuilds);
        Integer guides = jdbc.queryForObject("SELECT COUNT(*) FROM build_guide WHERE publico = 1", Integer.class);
        m.put("publicGuides", guides == null ? 0 : guides);
        // 3. the last export (its report next to the database)
        Path out = exporter.defaultOut();
        Path report = out.resolveSibling(out.getFileName().toString().replaceAll("\\.db$", "") + "-relatorio.json");
        m.put("exportFile", out.toString());
        m.put("export", readJson(report));
        // 4. the generated site (its manifest), and the size estimate
        Path site = Path.of(siteDir).toAbsolutePath().normalize();
        m.put("siteDir", site.toString());
        JsonNode manifest = readJson(site.resolve("api").resolve("manifest.json"));
        m.put("site", manifest);
        long buildsOfTheSite = publicBuilds;
        for (String c : done) {
            buildsOfTheSite += 5;   // the standard, about three niches and a discovery
        }
        m.put("estimatedBytes", BYTES_FIXED + buildsOfTheSite * BYTES_PER_BUILD);
        return m;
    }

    private static JsonNode readJson(Path p) {
        try {
            return Files.exists(p) ? JSON.readTree(Files.readString(p)) : null;
        } catch (IOException e) {
            return null;
        }
    }
}
