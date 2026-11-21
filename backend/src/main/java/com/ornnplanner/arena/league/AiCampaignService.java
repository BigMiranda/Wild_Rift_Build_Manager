package com.ornnplanner.arena.league;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ornnplanner.arena.AutoBrain;
import com.ornnplanner.arena.ai.PolicyRepository;
import com.ornnplanner.arena.discovery.CampaignService;
import com.ornnplanner.repo.DiscoveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Rodada 21, part 4: the campaign "Treinar IAs de todos os campeões" (Admin › Campanhas), in the background, one owner at
 * a time, in a queue kept in table {@code ai_training_queue}: the 7 class AIs first, then the champions the builds
 * campaign uses next, then by their presence in the meta, A/B sheets before C/D. Pause (the owner on the way goes back
 * to the queue), stop after the current one, resume; a server stopped mid-way resumes the row it was on. An owner is
 * queued again when its sheet or the patch changed; the AI in use stays until a new one is accepted. The automatic
 * brain reads the AIs from the database at each choice, so an accepted one is used at once.
 */
@Service
public class AiCampaignService {

    private static final Logger log = LoggerFactory.getLogger(AiCampaignService.class);
    private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    public static final List<String> BUDGETS = List.of("rapido", "completo");

    /** What a campaign asks. */
    public static class Request {
        public String name;
        /** rapido | completo */
        public String budget = "rapido";
        /** The class AIs first (true), then the champions. */
        public boolean classes = true;
        /** Only these classes (empty = the seven). */
        public List<String> classNames = new ArrayList<>();
        /** Champions by name; empty = every champion with a sheet. */
        public List<String> champions = new ArrayList<>();
        /** Queue again even the owners whose AI is up to date (same sheet, same patch). */
        public boolean force;
    }

    /** One row of the queue. */
    public static class Row {
        public Long id;
        public String patch;
        public String owner;
        public String kind;
        public int position;
        public String budget;
        public String status;
        public String campaign;
        public String reason;
        public Map<String, Object> result;
        public Long policyId;
        public String sheetHash;
        public Double seconds;
        public String error;
        public String createdAt;
        public String updatedAt;
    }

    /** The queue's state for the screen and the bell. */
    public static class State {
        public boolean running;
        public boolean stopAfterCurrent;
        public Long current;
        public String currentOwner;
        public String stage;
        public Integer generation;
        public Integer generations;
        public Integer validationDone;
        public Integer validationTotal;
        public int threads;
        public boolean sharing;
        public Estimate estimate;
        public List<Row> rows = new ArrayList<>();
    }

    /** Measured with the first ones trained, projected for the rest and for every champion. */
    public static class Estimate {
        public String budget;
        /** Seconds per class AI and per champion (null = none trained yet with this budget), and how many measured. */
        public Double secondsPerClass;
        public Double secondsPerChampion;
        public int classesMeasured;
        public int championsMeasured;
        /** Pending rows and the time they need (s). */
        public int pending;
        public Double secondsLeft;
        /** Every champion with a sheet (143) and the 7 classes, from zero, with this budget (s). */
        public int championsAll;
        public Double secondsAll;
    }

    private final LeagueService league;
    private final LeagueTrainer trainer;
    private final LeagueHierarchy hierarchy;
    private final PolicyRepository policies;
    private final DiscoveryRepository discoveries;
    private final CampaignService builds;
    private final JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ornnplanner.publico.PublicMode publicMode;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ai-campaign");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running;
    private volatile Long current;
    private volatile String currentOwner;
    private volatile String stage;
    private volatile Integer generation;
    private volatile Integer generations;
    private volatile Integer validationDone;
    private volatile Integer validationTotal;
    private volatile int threads;
    private volatile boolean sharing;
    /** pausar | cancelar: what to do with the row on the way. */
    private volatile String interrupt;
    private volatile boolean stopAfterCurrent;

    public AiCampaignService(LeagueService league, LeagueTrainer trainer, LeagueHierarchy hierarchy, PolicyRepository policies,
                             DiscoveryRepository discoveries, @Lazy CampaignService builds, JdbcTemplate jdbc) {
        this.league = league;
        this.trainer = trainer;
        this.hierarchy = hierarchy;
        this.policies = policies;
        this.discoveries = discoveries;
        this.builds = builds;
        this.jdbc = jdbc;
    }

    /** After the schema is up: a row left "rodando" by a stopped server goes back to the queue. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void resumeAfterRestart() {
        if (publicMode != null && publicMode.on()) {
            return;
        }
        int n = jdbc.update("UPDATE ai_training_queue SET status = 'pendente', updated_at = ? WHERE status = 'rodando'", now());
        if (n > 0) {
            log.info("Treino das IAs: {} linha(s) interrompida(s) voltaram para a fila (Retomar continua).", n);
        }
    }

    private static String now() {
        return Instant.now().toString();
    }

    // ------------------------------------------------------------------ the order

    /** The champions the builds campaign will use next: its pending rows by priority and order (names). */
    List<String> buildsNext() {
        List<String> out = new ArrayList<>();
        List<DiscoveryRepository.DiscoveryRow> rows = new ArrayList<>(discoveries.discoveries(null));
        rows.removeIf(r -> !"pendente".equals(r.status) && !"rodando".equals(r.status) && !"pausado".equals(r.status));
        rows.sort(Comparator.<DiscoveryRepository.DiscoveryRow>comparingInt(r -> "rodando".equals(r.status) ? 0 : 1)
                .thenComparingInt(r -> -r.priority).thenComparingLong(r -> r.id));
        for (DiscoveryRepository.DiscoveryRow r : rows) {
            if (r.champion != null && !out.contains(r.champion)) {
                out.add(r.champion);
            }
        }
        return out;
    }

    /**
     * The campaign's order: the class AIs (the bank's order), then the champions the builds campaign uses next, then by
     * presence in the meta, A/B sheets before C/D, then the name.
     */
    public List<String> order(Request r) {
        List<String> out = new ArrayList<>();
        if (r.classes) {
            for (String c : league.bankSettings().classes) {
                if (r.classNames == null || r.classNames.isEmpty() || r.classNames.contains(c)) {
                    out.add(AutoBrain.CLASS_PREFIX + c);
                }
            }
        }
        List<String> pool = r.champions == null || r.champions.isEmpty() ? league.withSheet() : new ArrayList<>(r.champions);
        pool.retainAll(league.withSheet());   // only champions with a sheet can be trained
        List<String> next = buildsNext();
        pool.sort(Comparator.<String>comparingInt(c -> next.contains(c) ? next.indexOf(c) : Integer.MAX_VALUE)
                .thenComparingDouble(c -> -league.presence(c))
                .thenComparingInt(c -> {
                    String q = league.quality(c);
                    return "A".equals(q) || "B".equals(q) ? 0 : 1;
                })
                .thenComparing(c -> c));
        out.addAll(pool);
        return out;
    }

    /** Why an owner needs training now (null = its AI is up to date: same sheet and patch, accepted in the league). */
    public String needs(String owner) {
        PolicyRepository.Record now = latestLeague(owner, true);
        if (now == null) {
            PolicyRepository.Record any = latestLeague(owner, false);
            return any == null ? "sem IA da liga" : "a última foi recusada";
        }
        if (now.gamePatch != null && !now.gamePatch.equals(policies.gamePatch())) {
            return "patch mudou (" + now.gamePatch + " -> " + policies.gamePatch() + ")";
        }
        if (!owner.startsWith(AutoBrain.CLASS_PREFIX)) {
            LeagueService.Saved v = league.latest(now.id, owner);
            String hash = league.sheetHash(owner);
            if (v != null && v.sheetHash != null && hash != null && !hash.equals(v.sheetHash)) {
                return "ficha mudou";
            }
        }
        return null;
    }

    /** The newest league version of an owner (accepted only, or any). */
    PolicyRepository.Record latestLeague(String owner, boolean accepted) {
        for (PolicyRepository.Record r : policies.list(owner)) {
            if ("liga".equals(r.objective) && r.champion.equalsIgnoreCase(owner) && (!accepted || r.accepted) && r.compatible()) {
                return r;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the queue

    /** Puts the owners of a campaign in the queue (the up-to-date ones are left out unless forced). */
    public List<Row> enqueue(Request r) {
        if (!BUDGETS.contains(r.budget)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Orçamento desconhecido: " + r.budget + ".");
        }
        String campaign = r.name == null || r.name.isBlank() ? "Treinar IAs " + java.time.LocalDate.now() : r.name.trim();
        Set<String> queued = new LinkedHashSet<>();
        for (Row x : rows()) {
            if ("pendente".equals(x.status) || "rodando".equals(x.status) || "pausado".equals(x.status)) {
                queued.add(x.owner.toLowerCase());
            }
        }
        Integer maxPos = jdbc.queryForObject("SELECT MAX(position) FROM ai_training_queue", Integer.class);
        int pos = maxPos == null ? 0 : maxPos + 1;
        List<Row> out = new ArrayList<>();
        for (String owner : order(r)) {
            if (queued.contains(owner.toLowerCase())) {
                continue;
            }
            String why = needs(owner);
            if (why == null && !r.force) {
                continue;
            }
            Row row = new Row();
            row.patch = policies.gamePatch();
            row.owner = owner;
            row.kind = owner.startsWith(AutoBrain.CLASS_PREFIX) ? "classe" : "campeao";
            row.position = pos++;
            row.budget = r.budget;
            row.status = "pendente";
            row.campaign = campaign;
            row.reason = why == null ? "pedido" : why;
            row.sheetHash = row.kind.equals("classe") ? null : league.sheetHash(owner);
            insert(row);
            out.add(row);
        }
        start();
        return out;
    }

    public synchronized void start() {
        if (running || publicMode != null && publicMode.on()) {
            return;
        }
        running = true;
        interrupt = null;
        stopAfterCurrent = false;
        worker.submit(this::loop);
    }

    /** Pauses: the owner on the way stops at its next check and goes back to the queue as "pausado". */
    public void pause() {
        interrupt = "pausar";
    }

    /** Lets the owner on the way finish (trained, validated, saved), then stops. */
    public void stopAfterCurrent() {
        stopAfterCurrent = true;
    }

    public void resumeRow(long id) {
        Row r = row(id);
        if (List.of("pausado", "erro", "cancelado").contains(r.status)) {
            r.status = "pendente";
            r.error = null;
            update(r);
        }
        start();
    }

    /** Resumes every paused row and the worker. */
    public void resumeAll() {
        jdbc.update("UPDATE ai_training_queue SET status = 'pendente', updated_at = ? WHERE status = 'pausado'", now());
        start();
    }

    public void cancel(long id) {
        Row r = row(id);
        if (current != null && current == id) {
            interrupt = "cancelar";
            return;
        }
        if (List.of("pendente", "pausado").contains(r.status)) {
            r.status = "cancelado";
            update(r);
        }
    }

    public void delete(long id) {
        if (current != null && current == id) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Pare a linha antes de apagar.");
        }
        jdbc.update("DELETE FROM ai_training_queue WHERE id = ?", id);
    }

    /** The cores the campaign trains on now: all of them alone, a share with the builds campaign running. */
    int threadsNow() {
        int cores = Runtime.getRuntime().availableProcessors();
        boolean shared = builds.running();
        sharing = shared;
        double share = shared ? trainer.settings().workersShared : trainer.settings().workersAlone;
        return Math.max(1, (int) Math.round(cores * Math.max(0.05, Math.min(1, share))));
    }

    private void loop() {
        try {
            while (interrupt == null && !stopAfterCurrent) {
                Row next = rows().stream().filter(x -> "pendente".equals(x.status)).min(Comparator.comparingInt(x -> x.position)).orElse(null);
                if (next == null) {
                    break;
                }
                runRow(next);
            }
        } finally {
            running = false;
            current = null;
            currentOwner = null;
            stage = null;
            generation = null;
            generations = null;
            validationDone = null;
            validationTotal = null;
            if ("pausar".equals(interrupt)) {
                interrupt = null;
            }
            stopAfterCurrent = false;
        }
    }

    /** One owner: trained in the league, validated, saved (accepted or refused); its result written on the row. */
    void runRow(Row row) {
        current = row.id;
        currentOwner = row.owner;
        row.status = "rodando";
        update(row);
        long t0 = System.nanoTime();
        try {
            boolean ofClass = "classe".equals(row.kind);
            LeagueTrainer.Budget budget = trainer.budget(row.budget, ofClass ? "classe" : "campeao");
            generations = budget.generations;
            generation = 0;
            validationDone = null;
            validationTotal = null;
            threads = threadsNow();
            stage = "treinando";
            LeagueTrainer.Listener l = new LeagueTrainer.Listener() {
                @Override
                public void message(String text) {
                    stage = text;
                }

                @Override
                public void point(LeagueTrainer.Point p) {
                    generation = p.generation;
                }

                @Override
                public void validation(int done, int total) {
                    validationDone = done;
                    validationTotal = total;
                }

                @Override
                public boolean cancelled() {
                    return interrupt != null;
                }
            };
            long seed = LeagueModel.mix(1, row.owner, row.patch);
            LeagueTrainer.Result r = ofClass
                    ? hierarchy.trainClass(row.owner.substring(AutoBrain.CLASS_PREFIX.length()), budget, seed, threads, l)
                    : hierarchy.trainChampion(row.owner, budget, seed, threads, l);
            if (r.cancelled) {
                row.status = "cancelar".equals(interrupt) ? "cancelado" : "pausado";
                if ("cancelar".equals(interrupt)) {
                    interrupt = null;
                }
            } else {
                row.status = r.acceptance.accepted ? "aceita" : "recusada";
                row.policyId = r.policy == null ? null : r.policy.id;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("policyId", row.policyId);
                m.put("version", r.policy == null ? null : r.policy.version);
                m.put("accepted", r.acceptance.accepted);
                m.put("reason", r.acceptance.reason);
                m.put("against", r.acceptance.against);
                m.put("mean", r.matrix.mean);
                m.put("tacticalMean", r.matrix.tacticalMean);
                m.put("incumbentMean", r.incumbentMatrix == null ? null : r.incumbentMatrix.mean);
                m.put("worstCell", r.acceptance.worstCell);
                m.put("worstCellKey", r.acceptance.worstCellKey);
                m.put("generations", r.generations);
                m.put("bestGeneration", r.bestGeneration);
                m.put("trainSeconds", r.trainSeconds);
                m.put("validationSeconds", r.validationSeconds);
                m.put("threads", threads);
                m.put("sharedWithBuilds", sharing);
                m.put("budget", budget);
                row.result = m;
            }
        } catch (RuntimeException e) {
            log.warn("Treino das IAs: {} falhou", row.owner, e);
            row.status = "erro";
            row.error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        row.seconds = (row.seconds == null ? 0 : row.seconds) + (System.nanoTime() - t0) / 1e9;
        update(row);
        current = null;
    }

    // ------------------------------------------------------------------ state, estimate, table

    public State state() {
        State s = new State();
        s.running = running;
        s.stopAfterCurrent = stopAfterCurrent;
        s.current = current;
        s.currentOwner = currentOwner;
        s.stage = stage;
        s.generation = generation;
        s.generations = generations;
        s.validationDone = validationDone;
        s.validationTotal = validationTotal;
        s.threads = running ? threads : threadsNow();
        s.sharing = sharing;
        s.rows = rows();
        s.estimate = estimate(s.rows, s.rows.stream().filter(r -> "pendente".equals(r.status) || "rodando".equals(r.status))
                .map(r -> r.budget).findFirst().orElse("rapido"));
        return s;
    }

    /** The estimate of a budget: the mean seconds of the first ones done of each kind, over the rest and over all. */
    public Estimate estimate(List<Row> rows, String budget) {
        Estimate e = new Estimate();
        e.budget = budget;
        int n = Math.max(1, trainer.settings().estimateSample);
        List<Double> cls = new ArrayList<>();
        List<Double> champ = new ArrayList<>();
        rows.stream().filter(r -> budget.equals(r.budget) && r.seconds != null && ("aceita".equals(r.status) || "recusada".equals(r.status)))
                .sorted(Comparator.comparingInt(r -> r.position)).forEach(r -> {
                    List<Double> l = "classe".equals(r.kind) ? cls : champ;
                    if (l.size() < n) {
                        l.add(r.seconds);
                    }
                });
        e.classesMeasured = cls.size();
        e.championsMeasured = champ.size();
        e.secondsPerClass = cls.isEmpty() ? null : cls.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        e.secondsPerChampion = champ.isEmpty() ? null : champ.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double left = 0;
        boolean known = true;
        for (Row r : rows) {
            if (!budget.equals(r.budget) || !"pendente".equals(r.status) && !"rodando".equals(r.status)) {
                continue;
            }
            e.pending++;
            Double per = "classe".equals(r.kind) ? e.secondsPerClass : e.secondsPerChampion;
            if (per == null) {
                known = false;
            } else {
                left += per;
            }
        }
        e.secondsLeft = known ? left : null;
        e.championsAll = league.withSheet().size();
        e.secondsAll = e.secondsPerChampion == null || e.secondsPerClass == null ? null
                : e.championsAll * e.secondsPerChampion + league.bankSettings().classes.size() * e.secondsPerClass;
        return e;
    }

    /** One champion in the table of all of them. */
    public static class ChampionAi {
        public String champion;
        public String className;
        public String quality;
        /** propria | na_fila | treinando | recusada | sem_treino */
        public String status;
        public Integer version;
        public String date;
        public Double mean;
        /** Its class AI and the tactical rules on the same champion's bank. */
        public Double classMean;
        public Double tacticalMean;
        public String reason;
        /** Its AI is in use (it beats its class AI on its validation). */
        public Boolean inUse;
        /** Why it needs training again (sheet or patch changed), else null. */
        public String stale;
    }

    /** The table of every champion with a sheet: its own AI, in the queue, refused (why) or untrained. */
    public List<ChampionAi> table() {
        List<Row> rows = rows();
        List<ChampionAi> out = new ArrayList<>();
        for (String c : league.withSheet()) {
            ChampionAi a = new ChampionAi();
            a.champion = c;
            a.className = league.mainClass(c);
            a.quality = league.quality(c);
            PolicyRepository.Record own = latestLeague(c, true);
            Row last = null;
            for (Row r : rows) {
                if (r.owner.equalsIgnoreCase(c) && (last == null || r.id > last.id)) {
                    last = r;
                }
            }
            if (own != null) {
                a.status = "propria";
                a.version = own.version;
                a.date = own.createdAt;
                LeagueService.Saved v = league.latest(own.id, c);
                a.mean = v == null ? null : v.mean;
                a.tacticalMean = v == null ? null : v.tacticalMean;
                PolicyRepository.Record cls = hierarchy.classOf(c);
                LeagueService.Saved cv = cls == null ? null : league.latest(cls.id, c);
                a.classMean = cv == null ? null : cv.mean;
                a.inUse = !Boolean.FALSE.equals(hierarchy.championOverClass(own, cls, c));
                a.stale = needs(c);
            }
            if (last != null && ("pendente".equals(last.status) || "pausado".equals(last.status))) {
                a.status = own != null ? a.status : "na_fila";
                a.reason = last.reason;
            } else if (last != null && "rodando".equals(last.status)) {
                a.status = own != null ? a.status : "treinando";
            } else if (last != null && "recusada".equals(last.status) && own == null) {
                a.status = "recusada";
                a.reason = last.result == null ? null : String.valueOf(last.result.get("reason"));
            }
            if (a.status == null) {
                a.status = "sem_treino";
            }
            out.add(a);
        }
        return out;
    }

    // ------------------------------------------------------------------ the table's rows

    public List<Row> rows() {
        return jdbc.query("SELECT * FROM ai_training_queue ORDER BY position, id", (rs, i) -> {
            Row r = new Row();
            r.id = rs.getLong("id");
            r.patch = rs.getString("patch");
            r.owner = rs.getString("owner");
            r.kind = rs.getString("kind");
            r.position = rs.getInt("position");
            r.budget = rs.getString("budget");
            r.status = rs.getString("status");
            r.campaign = rs.getString("campaign");
            r.reason = rs.getString("reason");
            String res = rs.getString("result");
            try {
                r.result = res == null ? null : JSON.readValue(res, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            } catch (Exception e) {
                r.result = null;
            }
            long p = rs.getLong("policy_id");
            r.policyId = rs.wasNull() ? null : p;
            r.sheetHash = rs.getString("sheet_hash");
            double sec = rs.getDouble("seconds");
            r.seconds = rs.wasNull() ? null : sec;
            r.error = rs.getString("error");
            r.createdAt = rs.getString("created_at");
            r.updatedAt = rs.getString("updated_at");
            return r;
        });
    }

    public Row row(long id) {
        return rows().stream().filter(r -> r.id == id).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Linha " + id + " não encontrada."));
    }

    private void insert(Row r) {
        r.createdAt = now();
        r.updatedAt = r.createdAt;
        jdbc.update("INSERT INTO ai_training_queue (patch, owner, kind, position, budget, status, campaign, reason, result, policy_id, "
                        + "sheet_hash, seconds, error, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                r.patch, r.owner, r.kind, r.position, r.budget, r.status, r.campaign, r.reason, write(r.result), r.policyId, r.sheetHash,
                r.seconds, r.error, r.createdAt, r.updatedAt);
        r.id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    private void update(Row r) {
        r.updatedAt = now();
        jdbc.update("UPDATE ai_training_queue SET status = ?, reason = ?, result = ?, policy_id = ?, seconds = ?, error = ?, updated_at = ? "
                + "WHERE id = ?", r.status, r.reason, write(r.result), r.policyId, r.seconds, r.error, r.updatedAt, r.id);
    }

    private static String write(Object o) {
        try {
            return o == null ? null : JSON.writeValueAsString(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
