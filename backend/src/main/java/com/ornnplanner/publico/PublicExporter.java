package com.ornnplanner.publico;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ornnplanner.arena.EffectModel;
import com.ornnplanner.arena.EffectTestService;
import com.ornnplanner.arena.ai.PolicyRepository;
import com.ornnplanner.engine.Model;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.seed.ChampionCatalog;
import com.ornnplanner.seed.ReferenceSeeder;
import com.ornnplanner.service.PlannerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Exports the read-only database of the public version (Rodada 11, part 2): the current patch's catalog, the builds of
 * the folders marked public, what was calculated (discovery and campaigns, the basic effectiveness of the public
 * builds, the public guides, the AIs the automatic brain picks, with their weights only) and nothing else. The copy is
 * made by SQLite ({@code VACUUM INTO}), trimmed, indexed for the public reads and vacuumed again, so no deleted row
 * survives in a free page. Only Java and SQLite: no new dependency.
 */
@Component
@Profile("!" + PublicMode.PROFILE)
public class PublicExporter {

    private static final Logger log = LoggerFactory.getLogger(PublicExporter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Tables the public version never reads: dropped. */
    static final List<String> DROPPED = List.of("arena_dummy", "arena_guideline", "macro_policy");
    /** Columns no public read touches: dropped (table → columns). */
    static final Map<String, List<String>> DROPPED_COLUMNS = Map.of("build", List.of("include_conditional"));

    /** What came out. */
    public static class Report {
        public String exportedAt;
        public String patch;
        public String file;
        public long sizeBytes;
        public double seconds;
        public List<String> folders = new ArrayList<>();
        public List<String> builds = new ArrayList<>();
        public List<String> guides = new ArrayList<>();
        public List<String> scenarios = new ArrayList<>();
        public int effects;
        public Map<String, Long> counts = new TreeMap<>();
        public List<String> excluded = new ArrayList<>();
    }

    private final JdbcTemplate jdbc;
    private final BuildRepository builds;
    private final PlannerService planner;
    private final EffectTestService effect;
    private final PolicyRepository policies;
    private final ChampionCatalog champions;
    private final String defaultOut;

    public PublicExporter(JdbcTemplate jdbc, BuildRepository builds, PlannerService planner, EffectTestService effect,
                          PolicyRepository policies, ChampionCatalog champions,
                          @Value("${planner.export.out:./data/publico/ornn-planner-publico.db}") String defaultOut) {
        this.jdbc = jdbc;
        this.builds = builds;
        this.planner = planner;
        this.effect = effect;
        this.policies = policies;
        this.champions = champions;
        this.defaultOut = defaultOut;
    }

    public Path defaultOut() {
        return Path.of(defaultOut).toAbsolutePath().normalize();
    }

    public synchronized Report export() {
        return export(defaultOut());
    }

    public synchronized Report export(Path out) {
        long t0 = System.nanoTime();
        Report rep = new Report();
        rep.exportedAt = Instant.now().toString();
        rep.patch = champions.patch();
        rep.file = out.toString();

        // 1. what is public: the marked folders' builds, the marked guides, the scenarios made only of them
        Set<Long> folderIds = new LinkedHashSet<>();
        for (BuildRepository.Folder f : builds.findFolders()) {
            if (f.publico) {
                folderIds.add(f.id);
                rep.folders.add(f.name);
            }
        }
        if (folderIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Nenhuma pasta marcada como pública: marque ao menos uma em Builds (🌐) antes de exportar.");
        }
        List<BuildRepository.Build> publicBuilds = new ArrayList<>();
        for (BuildRepository.Build s : builds.findBuildSummaries()) {
            if (folderIds.contains(s.folderId)) {
                builds.findBuild(s.id).ifPresent(publicBuilds::add);
            }
        }
        Set<Long> buildIds = publicBuilds.stream().map(b -> b.id).collect(Collectors.toCollection(LinkedHashSet::new));
        publicBuilds.forEach(b -> rep.builds.add(b.name));
        Map<Long, String> scenarios = publicScenarios(buildIds, rep);
        PublicScrubber scrubber = scrubber(publicBuilds, buildIds);

        // 2. calculated before the copy, on the full app: the basic effectiveness of each public build
        List<Object[]> effects = basicEffects(publicBuilds);
        rep.effects = effects.size();

        // 3. the AIs the automatic brain may pick (and the ones the public scenarios name): weights only
        Set<Long> keptPolicies = keptPolicies(scenarios.values());

        try {
            Files.createDirectories(out.toAbsolutePath().getParent());
            Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
            Files.deleteIfExists(tmp);
            jdbc.execute("VACUUM INTO '" + tmp.toAbsolutePath().toString().replace("'", "''") + "'");
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + tmp.toAbsolutePath())) {
                trim(c, folderIds, buildIds, scenarios.keySet(), keptPolicies, rep);
                scrub(c, scrubber, rep);
                write(c, effects, rep);
                indexes(c);
                try (Statement st = c.createStatement()) {
                    st.execute("VACUUM");
                }
                counts(c, rep);
            }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
            rep.sizeBytes = Files.size(out);
            rep.seconds = Math.round((System.nanoTime() - t0) / 1e7) / 100.0;
            writeReport(out, rep);
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("A exportação falhou: " + e.getMessage(), e);
        }
        log.info("Exportação pública: {} build(s) de {} pasta(s), {} bytes, {} s → {}", rep.builds.size(), rep.folders.size(),
                rep.sizeBytes, rep.seconds, out);
        return rep;
    }

    // ------------------------------------------------------------------ what goes out

    /** Scenarios whose every fighter is a ragdoll or a public build (at least one build): id → data. */
    private Map<Long, String> publicScenarios(Set<Long> buildIds, Report rep) {
        Map<Long, String> out = new LinkedHashMap<>();
        jdbc.query("SELECT id, name, data FROM arena_scenario ORDER BY id", rs -> {
            try {
                JsonNode fighters = JSON.readTree(rs.getString("data")).path("fighters");
                boolean any = false;
                boolean ok = fighters.size() > 0;
                for (JsonNode f : fighters) {
                    if (f.hasNonNull("buildId")) {
                        any = true;
                        ok &= buildIds.contains(f.get("buildId").asLong());
                    } else if (!f.hasNonNull("dummy")) {
                        ok = false;
                    }
                }
                if (ok && any) {
                    out.put(rs.getLong("id"), rs.getString("data"));
                    rep.scenarios.add(rs.getString("name"));
                }
            } catch (JsonProcessingException e) {
                // unreadable: stays out
            }
        });
        return out;
    }

    /** [build id, minute, report JSON] of the basic report at each public build's last purchase. */
    private List<Object[]> basicEffects(List<BuildRepository.Build> list) {
        List<Object[]> out = new ArrayList<>();
        EffectModel.Report report = effect.report("basico");
        for (BuildRepository.Build b : list) {
            if (ReferenceSeeder.RAGDOLL.equals(b.unitCode) || report.tests.isEmpty()) {
                continue;
            }
            try {
                Model.TimelineResult t = planner.calculate(b);
                if (t.steps.isEmpty()) {
                    continue;
                }
                double minute = Math.round(t.steps.get(t.steps.size() - 1).minute * 100) / 100.0;
                EffectModel.Request req = new EffectModel.Request();
                req.buildId = b.id;
                req.minute = minute;
                for (EffectModel.Entry e : report.tests) {
                    EffectModel.Job j = new EffectModel.Job();
                    j.tag = e.key;
                    j.testKey = e.key;
                    req.jobs.add(j);
                }
                List<EffectModel.Outcome> outcomes = effect.evaluate(req);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("buildId", b.id);
                row.put("name", b.name);
                row.put("unitCode", b.unitCode);
                row.put("minute", minute);
                double sum = 0;
                double w = 0;
                List<Map<String, Object>> tests = new ArrayList<>();
                List<EffectModel.Grade> grades = null;
                for (int i = 0; i < outcomes.size(); i++) {
                    EffectModel.Outcome o = outcomes.get(i);
                    double weight = report.tests.get(i).weight;
                    if (o.index != null && Double.isFinite(o.index)) {
                        sum += weight * o.index;
                        w += weight;
                    }
                    if (o.grades != null) {
                        grades = o.grades;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("key", report.tests.get(i).key);
                    m.put("name", o.testName);
                    m.put("weight", weight);
                    m.put("index", o.index);
                    m.put("grade", o.grade);
                    m.put("error", o.error);
                    tests.add(m);
                }
                Double index = w > 0 ? sum / w : null;
                row.put("index", index);
                row.put("grade", index != null && grades != null ? EffectTestService.gradeOf(index, grades) : null);
                row.put("tests", tests);
                out.add(new Object[]{b.id, minute, JSON.writeValueAsString(row)});
            } catch (RuntimeException | JsonProcessingException e) {
                log.warn("Efetividade da build pública {} não calculada: {}", b.name, e.getMessage());
            }
        }
        return out;
    }

    /**
     * The policies the automatic brain may pick: the ones with a fight in the history and the best saved one of each
     * owner (champion or class), as {@code AutoBrain} reads them; plus those a public scenario names ("ai:12").
     */
    private Set<Long> keptPolicies(java.util.Collection<String> scenarioData) {
        Map<Long, PolicyRepository.Record> usable = new LinkedHashMap<>();
        policies.list(null).stream().filter(PolicyRepository.Record::compatible).forEach(p -> usable.put(p.id, p));
        Set<Long> keep = new LinkedHashSet<>();
        for (Long id : jdbc.queryForList("SELECT DISTINCT policy_id FROM ai_result WHERE policy_id IS NOT NULL", Long.class)) {
            if (usable.containsKey(id)) {
                keep.add(id);
            }
        }
        Map<String, PolicyRepository.Record> best = new LinkedHashMap<>();
        for (PolicyRepository.Record p : usable.values()) {
            String owner = p.champion.toLowerCase(java.util.Locale.ROOT);
            PolicyRepository.Record b = best.get(owner);
            if (b == null || better(p, b)) {
                best.put(owner, p);
            }
        }
        best.values().forEach(p -> keep.add(p.id));
        for (String data : scenarioData) {
            try {
                for (JsonNode f : JSON.readTree(data).path("fighters")) {
                    String brain = f.path("brain").asText("");
                    if (brain.startsWith("ai:")) {
                        keep.add(Long.parseLong(brain.substring(3)));
                    }
                }
            } catch (JsonProcessingException | NumberFormatException e) {
                // a scenario that does not name an AI
            }
        }
        return keep;
    }

    /** {@code AutoBrain.bestSaved}'s order: accepted, winning its own scenario, newest version. */
    private static boolean better(PolicyRepository.Record a, PolicyRepository.Record b) {
        if (a.accepted != b.accepted) {
            return a.accepted;
        }
        boolean wa = Boolean.TRUE.equals(a.winsScenario);
        boolean wb = Boolean.TRUE.equals(b.winsScenario);
        if (wa != wb) {
            return wa;
        }
        return a.version > b.version;
    }

    /** What the private builds are called (Rodada 11.b, part 1): every build of the summary, the public ones with items. */
    private PublicScrubber scrubber(List<BuildRepository.Build> publicBuilds, Set<Long> publicIds) {
        Map<Long, BuildRepository.Build> full = new LinkedHashMap<>();
        publicBuilds.forEach(b -> full.put(b.id, b));
        List<PublicScrubber.BuildRef> all = new ArrayList<>();
        for (BuildRepository.Build s : builds.findBuildSummaries()) {
            BuildRepository.Build b = full.getOrDefault(s.id, s);
            List<Long> items = new ArrayList<>();
            if (b.steps != null) {
                b.steps.stream().filter(x -> x.itemId != null).forEach(x -> items.add(x.itemId));
            }
            all.add(new PublicScrubber.BuildRef(s.id, s.name, s.unitCode, items));
        }
        List<String> privateFolders = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (BuildRepository.Folder f : builds.findFolders()) {
            (f.publico ? kept : privateFolders).add(f.name);
        }
        // names the public version shows anyway: the catalog's, the public guides'
        kept.addAll(jdbc.queryForList("SELECT name FROM item", String.class));
        kept.addAll(jdbc.queryForList("SELECT name FROM unit", String.class));
        kept.addAll(jdbc.queryForList("SELECT code FROM unit", String.class));
        kept.addAll(jdbc.queryForList("SELECT name FROM build_guide WHERE publico = 1", String.class));
        return new PublicScrubber(all, publicIds, privateFolders, kept);
    }

    /** The discovery's results without the private builds, then every text of the copy swept for their names and ids. */
    private static void scrub(Connection c, PublicScrubber s, Report rep) throws SQLException {
        c.setAutoCommit(false);
        Map<Long, String[]> rows = new LinkedHashMap<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, unit_code, config, result FROM build_discovery")) {
            while (rs.next()) {
                String unit = rs.getString(2);
                String config = rs.getString(3);
                Long base = s.baseOf(config, unit);
                String result = rs.getString(4);
                rows.put(rs.getLong(1), new String[] {s.config(config), result == null ? null : s.result(result, unit, base)});
            }
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE build_discovery SET config = ?, result = ? WHERE id = ?")) {
            for (Map.Entry<Long, String[]> e : rows.entrySet()) {
                ps.setString(1, e.getValue()[0]);
                ps.setString(2, e.getValue()[1]);
                ps.setLong(3, e.getKey());
                ps.executeUpdate();
            }
        }
        int swept = s.sweep(c);
        c.commit();
        c.setAutoCommit(true);
        rep.excluded.add("das Descobertas: as builds conhecidas e salvas de pastas não marcadas, o nome da build base privada e o das "
                + "rivais privadas das matchups; e " + swept + " texto(s) que citavam nome ou id de build privada (" + s.touched + ")");
    }

    // ------------------------------------------------------------------ the copy

    /** An SQL list of ids; an empty set is "()", which SQLite reads as the empty list (NOT IN () keeps nothing out). */
    public static String in(Set<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(",", "(", ")"));
    }

    /** Rows a WHERE matches, counted before deleting (the driver's count would include the cascaded rows). */
    private static long count(Connection c, String table, String where) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + where)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    /** Deletes the rows a WHERE matches; returns how many of this table's (not the cascaded ones). */
    private static long delete(Connection c, String table, String where) throws SQLException {
        long n = count(c, table, where);
        exec(c, "DELETE FROM " + table + " WHERE " + where);
        return n;
    }

    private static int exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            return st.executeUpdate(sql);
        }
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private void trim(Connection c, Set<Long> folderIds, Set<Long> buildIds, Set<Long> scenarioIds, Set<Long> policyIds,
                      Report rep) throws SQLException {
        exec(c, "PRAGMA foreign_keys = ON");
        c.setAutoCommit(false);
        String patch = rep.patch;
        List<String> ex = rep.excluded;
        long n;
        n = delete(c, "build", "folder_id NOT IN " + in(folderIds));
        ex.add(n + " build(s) de pastas não marcadas (com as compras, momentos e status de boneco delas)");
        n = delete(c, "folder", "id NOT IN " + in(folderIds));
        ex.add(n + " pasta(s) não marcada(s)");
        exec(c, "UPDATE item SET capture = NULL");
        ex.add("de que print cada item foi transcrito (item.capture)");
        n = delete(c, "build_guide", "publico = 0");
        ex.add(n + " guia(s) não marcada(s)");
        if (tableExists(c, "community_guide")) {
            n = delete(c, "community_guide", "status <> 'aprovada'");
            ex.add(n + " guia(s) da comunidade não aprovada(s) (candidatas e recusadas)");
        }
        n = delete(c, "arena_scenario", "id NOT IN " + in(scenarioIds));
        ex.add(n + " cenário(s) da Arena com builds que não são públicas");
        n = delete(c, "arena_policy", "id NOT IN " + in(policyIds));
        ex.add(n + " IA(s) que o automático não escolhe (histórico de treino)");
        exec(c, "UPDATE arena_policy SET chain_id = NULL, chain_round = NULL, team_plan = NULL, parent_id = NULL, scenario = NULL");
        ex.add("das IAs mantidas: a cadeia de treino, o plano do time, a versão de origem e o cenário de treino (só os pesos e o que a luta lê ficam)");
        n = delete(c, "ai_result", "policy_id IS NULL OR policy_id NOT IN " + in(policyIds));
        ex.add(n + " linha(s) do histórico das IAs de fora");
        exec(c, "UPDATE ai_result SET impression = ''");
        ex.add("a impressão de cada luta do histórico (texto com builds e posições; a escolha do automático não a lê)");
        n = delete(c, "arena_combo", "active = 0");
        ex.add(n + " combo(s) inativo(s)");
        exec(c, "UPDATE arena_combo SET build = NULL, build_id = NULL");
        ex.add("de que build cada combo foi achado (o nome pode ser de uma build privada)");
        n = delete(c, "effect_test", "kind NOT IN ('cenario', 'relatorio_basico', 'relatorio_completo')");
        ex.add(n + " configuração(ões) da efetividade ofensiva (alvos e relatório)");
        n = delete(c, "meta_build", "1 = 1");
        ex.add(n + " build(s) da meta digitada(s) pelo administrador");
        n = delete(c, "build_discovery", "status <> 'feito' OR patch <> '" + patch.replace("'", "''") + "'");
        ex.add(n + " linha(s) da fila de campanhas e descobertas não terminadas ou de outro patch");
        for (String t : DROPPED) {
            if (tableExists(c, t)) {
                exec(c, "DROP TABLE " + t);
                ex.add("a tabela " + t + " (o público não lê)");
            }
        }
        c.commit();
        c.setAutoCommit(true);
        // DROP COLUMN cannot run inside the foreign-key checks of a transaction with pending changes
        for (Map.Entry<String, List<String>> e : new TreeMap<>(DROPPED_COLUMNS).entrySet()) {
            for (String col : e.getValue()) {
                exec(c, "ALTER TABLE " + e.getKey() + " DROP COLUMN " + col);
                ex.add("a coluna " + e.getKey() + "." + col + " (o público não lê)");
            }
        }
    }

    private void write(Connection c, List<Object[]> effects, Report rep) throws SQLException, JsonProcessingException {
        exec(c, "CREATE TABLE public_effect (build_id INTEGER PRIMARY KEY, minute REAL NOT NULL, data TEXT NOT NULL)");
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO public_effect (build_id, minute, data) VALUES (?,?,?)")) {
            for (Object[] e : effects) {
                ps.setLong(1, (Long) e[0]);
                ps.setDouble(2, (Double) e[1]);
                ps.setString(3, (String) e[2]);
                ps.executeUpdate();
            }
        }
        exec(c, "CREATE TABLE public_export (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        Map<String, String> info = new LinkedHashMap<>();
        info.put("exported_at", rep.exportedAt);
        info.put("patch", rep.patch);
        info.put("folders", JSON.writeValueAsString(rep.folders));
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO public_export (key, value) VALUES (?,?)")) {
            for (Map.Entry<String, String> e : info.entrySet()) {
                ps.setString(1, e.getKey());
                ps.setString(2, e.getValue());
                ps.executeUpdate();
            }
        }
    }

    /** Indexes of the public reads. */
    private static void indexes(Connection c) throws SQLException {
        exec(c, "CREATE INDEX IF NOT EXISTS idx_build_folder ON build (folder_id)");
        exec(c, "CREATE INDEX IF NOT EXISTS idx_build_step_item ON build_step (item_id)");
        exec(c, "CREATE INDEX IF NOT EXISTS idx_discovery_unit ON build_discovery (unit_code, patch, status)");
        exec(c, "CREATE INDEX IF NOT EXISTS idx_guide_unit ON build_guide (unit_code)");
        exec(c, "CREATE INDEX IF NOT EXISTS idx_effect_test_kind ON effect_test (kind)");
        exec(c, "ANALYZE");
    }

    private static void counts(Connection c, Report rep) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        for (String t : tables) {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + t)) {
                rep.counts.put(t, rs.next() ? rs.getLong(1) : 0);
            }
        }
    }

    /** The report next to the database: JSON (for tools) and Markdown (to read). */
    private static void writeReport(Path out, Report rep) throws IOException {
        String base = out.getFileName().toString().replaceAll("\\.db$", "");
        Files.writeString(out.resolveSibling(base + "-relatorio.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(rep), StandardCharsets.UTF_8);
        StringBuilder md = new StringBuilder();
        md.append("# Exportação da versão pública\n\n");
        md.append("- Em: ").append(rep.exportedAt).append("\n");
        md.append("- Patch: ").append(rep.patch).append("\n");
        md.append("- Arquivo: `").append(rep.file).append("` (").append(String.format(java.util.Locale.ROOT, "%.1f", rep.sizeBytes / 1024.0))
                .append(" KB, ").append(rep.seconds).append(" s)\n\n");
        md.append("## Pastas incluídas\n\n");
        rep.folders.forEach(f -> md.append("- ").append(f).append("\n"));
        md.append("\n## Builds (").append(rep.builds.size()).append(")\n\n");
        rep.builds.forEach(b -> md.append("- ").append(b).append("\n"));
        md.append("\nEfetividade Básica pré-calculada: ").append(rep.effects).append(" build(s).\n");
        md.append("\n## Cenários da Arena (").append(rep.scenarios.size()).append(")\n\n");
        rep.scenarios.forEach(s -> md.append("- ").append(s).append("\n"));
        md.append("\n## Linhas por tabela\n\n| Tabela | Linhas |\n|---|---|\n");
        rep.counts.forEach((t, n) -> md.append("| ").append(t).append(" | ").append(n).append(" |\n"));
        md.append("\n## O que ficou de fora\n\n");
        rep.excluded.forEach(e -> md.append("- ").append(e).append("\n"));
        Files.writeString(out.resolveSibling(base + "-relatorio.md"), md.toString(), StandardCharsets.UTF_8);
    }
}
