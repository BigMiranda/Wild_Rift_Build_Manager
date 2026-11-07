package com.ornnplanner.publico;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.ornnplanner.arena.EffectModel;
import com.ornnplanner.arena.EffectTestService;
import com.ornnplanner.engine.Model;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.repo.ReferenceRepository;
import com.ornnplanner.seed.ChampionCatalog;
import com.ornnplanner.seed.ReferenceSeeder;
import com.ornnplanner.service.PlannerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.sqlite.SQLiteDataSource;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Builds the public version's visitors share to help elect better guides (Rodada 11, part 4). Only with the visitor's
 * consent, only the build (champion, purchases and order, runes, spells, pace and end of match), the patch and a random
 * anonymous id kept in the visitor's browser: no name, e-mail, account or IP is stored (the IP only counts toward the
 * hourly limit, in memory). The same build sent again counts as one more vote, never a new row.
 *
 * <p>The shared builds go to a database of their own ({@code planner.community.db-path}): the exported database stays
 * read-only. A light background job groups them, counts the votes, measures the most voted by the basic effectiveness
 * (cached, one at a time, on a low-priority thread) and marks candidates; nothing is published by itself: the
 * administrator downloads the candidates, reviews them in the local app and exports the approved ones.
 */
@Service
@Profile(PublicMode.PROFILE)
public class CommunityService {

    private static final Logger log = LoggerFactory.getLogger(CommunityService.class);
    /** Canonical JSON: the same build always gives the same text, so the same fingerprint. */
    private static final ObjectMapper CANON = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .serializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            .build();

    public static final String NO_CONSENT = "Sem a permissão do visitante, nada é guardado.";

    /** What a visitor sends. */
    public static class Submission {
        /** Random id kept in the visitor's browser: the only link between the visitor and what was sent. */
        public String anonId;
        /** The box "Compartilhar minhas builds" was on. */
        public boolean consent;
        public BuildRepository.Build build;
        /** The public guide the build came from, if any. */
        public Long guideId;
    }

    /** A candidate for community guide, as the administrator downloads it. */
    public static class Candidate {
        public String fingerprint;
        public String unitCode;
        public String patch;
        public BuildRepository.Build build;
        public Long guideId;
        public int votes;
        public Double score;
        public String grade;
        public Double minute;
    }

    /** The answer to a submission. */
    public static class Receipt {
        public String fingerprint;
        /** True when this visitor's vote was new (a new build or a new vote on a known one). */
        public boolean counted;
        public int votes;
    }

    private final PlannerService planner;
    private final EffectTestService effect;
    private final ReferenceRepository reference;
    private final ChampionCatalog champions;
    private final String dbPath;
    private final int perAnonPerHour;
    private final int perIpPerHour;
    private final int maxBytes;
    private final int minVotes;
    private final int candidatesPerChampion;
    private final int intervalMinutes;
    private JdbcTemplate db;
    private ScheduledExecutorService background;
    /** "anon:..." / "ip:..." → [hour, sends] of the current hour (memory only). */
    private final Map<String, long[]> hourly = new ConcurrentHashMap<>();

    public CommunityService(PlannerService planner, EffectTestService effect, ReferenceRepository reference, ChampionCatalog champions,
                            @Value("${planner.community.db-path:./data/comunidade/comunidade.db}") String dbPath,
                            @Value("${planner.community.per-anon-per-hour:20}") int perAnonPerHour,
                            @Value("${planner.community.per-ip-per-hour:40}") int perIpPerHour,
                            @Value("${planner.community.max-bytes:16384}") int maxBytes,
                            @Value("${planner.community.min-votes:3}") int minVotes,
                            @Value("${planner.community.candidates-per-champion:3}") int candidatesPerChampion,
                            @Value("${planner.community.interval-minutes:10}") int intervalMinutes) {
        this.planner = planner;
        this.effect = effect;
        this.reference = reference;
        this.champions = champions;
        this.dbPath = dbPath;
        this.perAnonPerHour = perAnonPerHour;
        this.perIpPerHour = perIpPerHour;
        this.maxBytes = maxBytes;
        this.minVotes = minVotes;
        this.candidatesPerChampion = candidatesPerChampion;
        this.intervalMinutes = intervalMinutes;
    }

    @PostConstruct
    void open() throws java.io.IOException {
        Path file = Path.of(dbPath).toAbsolutePath();
        Files.createDirectories(file.getParent());
        // a connection per operation: the background election and the visitors' sends never share one
        org.sqlite.SQLiteConfig config = new org.sqlite.SQLiteConfig();
        config.setBusyTimeout(5000);
        config.setJournalMode(org.sqlite.SQLiteConfig.JournalMode.WAL);
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + file);
        db = new JdbcTemplate(ds);
        // one row per build; one row per (visitor, build) so a visitor votes once and can take back what they sent
        db.execute("CREATE TABLE IF NOT EXISTS shared_build (fingerprint TEXT PRIMARY KEY, unit_code TEXT NOT NULL, "
                + "patch TEXT NOT NULL, group_key TEXT NOT NULL, data TEXT NOT NULL, guide_id INTEGER, "
                + "votes INTEGER NOT NULL, score REAL, grade TEXT, minute REAL, measured_patch TEXT, "
                + "candidate INTEGER NOT NULL DEFAULT 0, first_at TEXT NOT NULL, last_at TEXT NOT NULL)");
        db.execute("CREATE TABLE IF NOT EXISTS shared_vote (anon_id TEXT NOT NULL, fingerprint TEXT NOT NULL, "
                + "created_at TEXT NOT NULL, PRIMARY KEY (anon_id, fingerprint))");
        db.execute("CREATE INDEX IF NOT EXISTS idx_shared_group ON shared_build (unit_code, patch, group_key)");
        db.execute("CREATE INDEX IF NOT EXISTS idx_vote_fingerprint ON shared_vote (fingerprint)");
        if (intervalMinutes > 0) {
            background = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "community-elect");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
            background.scheduleWithFixedDelay(() -> {
                try {
                    elect();
                } catch (RuntimeException e) {
                    log.warn("Eleição das guias da comunidade falhou: {}", e.getMessage());
                }
            }, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
        }
    }

    @PreDestroy
    void close() {
        if (background != null) {
            background.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ receiving

    public synchronized Receipt submit(Submission s, String ip) {
        if (s == null || !s.consent) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, NO_CONSENT);
        }
        String anon = anonId(s.anonId);
        if (s.build == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sem build.");
        }
        if (!within("anon:" + anon, perAnonPerHour) || !within("ip:" + (ip == null ? "?" : ip), perIpPerHour)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Muitos envios nesta hora: tente mais tarde.");
        }
        BuildRepository.Build b = only(s.build);
        String data = canonical(b);
        if (data.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Build grande demais.");
        }
        Model.TimelineResult t = validate(b);
        String fingerprint = sha256(data);
        String now = Instant.now().toString();
        Receipt r = new Receipt();
        r.fingerprint = fingerprint;
        boolean known = !db.queryForList("SELECT 1 FROM shared_build WHERE fingerprint = ?", fingerprint).isEmpty();
        boolean voted = !db.queryForList("SELECT 1 FROM shared_vote WHERE anon_id = ? AND fingerprint = ?", anon, fingerprint).isEmpty();
        if (!known) {
            db.update("INSERT INTO shared_build (fingerprint, unit_code, patch, group_key, data, guide_id, votes, minute, first_at, last_at) "
                    + "VALUES (?,?,?,?,?,?,0,?,?,?)", fingerprint, b.unitCode, champions.patch(), groupKey(b), data,
                    s.guideId, t.steps.get(t.steps.size() - 1).minute, now, now);
        }
        if (!voted) {
            db.update("INSERT INTO shared_vote (anon_id, fingerprint, created_at) VALUES (?,?,?)", anon, fingerprint, now);
            db.update("UPDATE shared_build SET votes = votes + 1, last_at = ? WHERE fingerprint = ?", now, fingerprint);
        }
        r.counted = !voted;
        r.votes = db.queryForObject("SELECT votes FROM shared_build WHERE fingerprint = ?", Integer.class, fingerprint);
        return r;
    }

    /** "Apagar o que enviei": the visitor's votes go, and a build nobody else sent goes with them. */
    public synchronized int forget(String anonId) {
        String anon = anonId(anonId);
        List<String> mine = db.queryForList("SELECT fingerprint FROM shared_vote WHERE anon_id = ?", String.class, anon);
        db.update("DELETE FROM shared_vote WHERE anon_id = ?", anon);
        for (String f : mine) {
            db.update("UPDATE shared_build SET votes = votes - 1 WHERE fingerprint = ?", f);
        }
        db.update("DELETE FROM shared_build WHERE votes <= 0");
        return mine.size();
    }

    /** Counts a send of a key in the current hour; false = over the limit. */
    private boolean within(String key, int limit) {
        long hour = System.currentTimeMillis() / 3_600_000;
        if (hourly.size() > 50_000) {
            hourly.entrySet().removeIf(e -> e.getValue()[0] != hour);
        }
        long[] w = hourly.compute(key, (k, v) -> {
            long[] out = v == null || v[0] != hour ? new long[]{hour, 0} : v;
            out[1]++;
            return out;
        });
        return w[1] <= limit;
    }

    private static String anonId(String id) {
        try {
            return UUID.fromString(id == null ? "" : id).toString();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Identificador anônimo inválido.");
        }
    }

    /** Only the build: champion, purchases and their order, runes, spells, pace and end of match. No name, note or ids. */
    static BuildRepository.Build only(BuildRepository.Build in) {
        BuildRepository.Build b = new BuildRepository.Build();
        b.unitCode = in.unitCode;
        b.goldPerMin = in.goldPerMin;
        b.xpPerMin = in.xpPerMin;
        b.matchEnd = in.matchEnd;
        b.assumeHalfItems = in.assumeHalfItems;
        b.assumeSmallItems = in.assumeSmallItems;
        b.steps = in.steps == null ? new ArrayList<>() : in.steps;
        b.runePage = in.runePage;
        b.spells = in.spells == null ? new ArrayList<>() : in.spells;
        b.name = "Build da comunidade";
        b.championSetup = null;
        b.itemSetup = null;
        b.ragdollStats = new LinkedHashMap<>();
        return b;
    }

    /** The shop's rules on the build: a champion that exists, items that exist, no purchase the game refuses. */
    private Model.TimelineResult validate(BuildRepository.Build b) {
        if (b.unitCode == null || ReferenceSeeder.RAGDOLL.equals(b.unitCode) || reference.findUnit(b.unitCode).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Campeão inválido.");
        }
        if (b.goldPerMin <= 0 || b.goldPerMin > 3000 || b.xpPerMin < 0 || b.xpPerMin > 3000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ouro/min ou XP/min fora do jogo.");
        }
        if (b.steps.isEmpty() || b.steps.size() > 40) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "De 1 a 40 compras.");
        }
        Model.TimelineResult t;
        try {
            t = planner.calculate(b);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Build inválida: " + e.getMessage());
        }
        if (t.steps.isEmpty() || !t.ignored.isEmpty() || t.steps.stream().anyMatch(x -> !x.violations.isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Build recusada: a loja não permite essa sequência.");
        }
        return t;
    }

    /** Builds alike: the same champion with the same items, in any order (pace, runes and order apart). */
    static String groupKey(BuildRepository.Build b) {
        TreeSet<Long> items = new TreeSet<>();
        for (BuildRepository.Step s : b.steps) {
            if (!s.moment() && !s.sell() && s.itemId != null) {
                items.add(s.itemId);
            }
        }
        return b.unitCode + ":" + items;
    }

    static String canonical(BuildRepository.Build b) {
        try {
            return CANON.writeValueAsString(b);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String s) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", x));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ the election (background, light)

    /**
     * Groups the builds alike by champion and patch and counts their votes; the most voted build of each group with at
     * least {@code min-votes} is measured by the basic effectiveness (once per patch: cached in the row), and the best
     * measured groups of each champion become candidates. Returns how many builds it measured.
     */
    public int elect() {
        String patch = champions.patch();
        List<Map<String, Object>> groups = db.queryForList("SELECT unit_code, group_key, SUM(votes) AS votes FROM shared_build "
                + "WHERE patch = ? GROUP BY unit_code, group_key HAVING SUM(votes) >= ? ORDER BY unit_code, votes DESC", patch, minVotes);
        int measured = 0;
        Map<String, List<Object[]>> byChampion = new LinkedHashMap<>();
        for (Map<String, Object> g : groups) {
            String unit = (String) g.get("unit_code");
            String key = (String) g.get("group_key");
            int votes = ((Number) g.get("votes")).intValue();
            Map<String, Object> top = db.queryForMap("SELECT fingerprint, data, score, measured_patch FROM shared_build "
                    + "WHERE patch = ? AND unit_code = ? AND group_key = ? ORDER BY votes DESC, first_at LIMIT 1", patch, unit, key);
            String fingerprint = (String) top.get("fingerprint");
            Double score = top.get("score") == null ? null : ((Number) top.get("score")).doubleValue();
            if (score == null || !patch.equals(top.get("measured_patch"))) {
                score = measure(fingerprint, (String) top.get("data"), patch);
                measured++;
            }
            if (score != null) {
                byChampion.computeIfAbsent(unit, u -> new ArrayList<>()).add(new Object[]{fingerprint, score, votes});
            }
        }
        db.update("UPDATE shared_build SET candidate = 0");
        for (List<Object[]> list : byChampion.values()) {
            list.sort((a, b) -> Double.compare((Double) b[1], (Double) a[1]));
            for (int i = 0; i < Math.min(candidatesPerChampion, list.size()); i++) {
                db.update("UPDATE shared_build SET candidate = 1 WHERE fingerprint = ?", list.get(i)[0]);
            }
        }
        return measured;
    }

    /** The basic effectiveness of a shared build at its last purchase, written in its row (null = it could not run). */
    private Double measure(String fingerprint, String data, String patch) {
        try {
            BuildRepository.Build b = CANON.readValue(data, BuildRepository.Build.class);
            Model.TimelineResult t = planner.calculate(b);
            double minute = Math.round(t.steps.get(t.steps.size() - 1).minute * 100) / 100.0;
            EffectModel.Report report = effect.report("basico");
            EffectModel.Request req = new EffectModel.Request();
            req.build = b;
            req.minute = minute;
            for (EffectModel.Entry e : report.tests) {
                EffectModel.Job j = new EffectModel.Job();
                j.tag = e.key;
                j.testKey = e.key;
                req.jobs.add(j);
            }
            List<EffectModel.Outcome> out = effect.evaluate(req);
            double sum = 0;
            double w = 0;
            List<EffectModel.Grade> grades = null;
            for (int i = 0; i < out.size(); i++) {
                EffectModel.Outcome o = out.get(i);
                if (o.index != null && Double.isFinite(o.index)) {
                    sum += report.tests.get(i).weight * o.index;
                    w += report.tests.get(i).weight;
                }
                if (o.grades != null) {
                    grades = o.grades;
                }
            }
            Double score = w > 0 ? sum / w : null;
            String grade = score != null && grades != null ? EffectTestService.gradeOf(score, grades) : null;
            db.update("UPDATE shared_build SET score = ?, grade = ?, minute = ?, measured_patch = ? WHERE fingerprint = ?",
                    score, grade, minute, patch, fingerprint);
            return score;
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("Build da comunidade {} não medida: {}", fingerprint, e.getMessage());
            return null;
        }
    }

    /** The candidates, for the administrator's review in the local app. */
    public List<Candidate> candidates() {
        return db.query("SELECT * FROM shared_build WHERE candidate = 1 ORDER BY unit_code, score DESC", (rs, n) -> {
            Candidate c = new Candidate();
            c.fingerprint = rs.getString("fingerprint");
            c.unitCode = rs.getString("unit_code");
            c.patch = rs.getString("patch");
            try {
                c.build = CANON.readValue(rs.getString("data"), BuildRepository.Build.class);
            } catch (JsonProcessingException e) {
                c.build = null;
            }
            c.guideId = rs.getObject("guide_id") == null ? null : rs.getLong("guide_id");
            // the votes of its group (the builds alike), not only of this exact build
            c.votes = db.queryForObject("SELECT SUM(votes) FROM shared_build WHERE patch = ? AND unit_code = ? AND group_key = ?",
                    Integer.class, c.patch, c.unitCode, rs.getString("group_key"));
            c.score = rs.getObject("score") == null ? null : rs.getDouble("score");
            c.grade = rs.getString("grade");
            c.minute = rs.getObject("minute") == null ? null : rs.getDouble("minute");
            return c;
        });
    }

    /** How many builds and votes are kept, and the file's size (the account of Part 4). */
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("builds", db.queryForObject("SELECT COUNT(*) FROM shared_build", Long.class));
        m.put("votes", db.queryForObject("SELECT COUNT(*) FROM shared_vote", Long.class));
        m.put("dataBytes", db.queryForObject("SELECT COALESCE(SUM(LENGTH(data)), 0) FROM shared_build", Long.class));
        return m;
    }
}
