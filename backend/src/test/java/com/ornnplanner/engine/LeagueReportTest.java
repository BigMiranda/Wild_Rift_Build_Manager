package com.ornnplanner.engine;

import com.ornnplanner.arena.AutoBrain;
import com.ornnplanner.arena.ai.PolicyRepository;
import com.ornnplanner.arena.league.LeagueModel;
import com.ornnplanner.arena.league.LeagueService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rodada 21, part 1 (report style, opt-in): the generalization of every AI in use on a <b>copy</b> of a database, on
 * the fixed validation bank ({@code ia_validacao_7_3f.yml}): each champion's own AI (the newest accepted version) and
 * each class AI, its success per opponent class x fight type and the gap to the tactical rules, and the same AI on the
 * scenario it was trained on (the overfitting). The matrices are saved in the copy's {@code ai_validation}.
 *
 * <pre>./mvnw test -Dtest=LeagueReportTest -Dleague.report=data/ornn-planner.db [-Dleague.owners=Ornn,class:Tanque
 * -Dleague.threads=8 -Dleague.seeds=3 -Dleague.out=target/league/linha-de-base.md]</pre>
 */
@SpringBootTest(properties = {"server.port=0"})
@EnabledIfSystemProperty(named = "league.report", matches = ".+")
class LeagueReportTest {

    static final Path COPY = Paths.get("target/league/copy.db");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        Files.createDirectories(COPY.getParent());
        if (!Boolean.getBoolean("league.keepCopy") || !Files.exists(COPY)) {
            Files.copy(Paths.get(System.getProperty("league.report")), COPY, StandardCopyOption.REPLACE_EXISTING);
        }
        r.add("planner.db-path", COPY::toString);
    }

    @Autowired
    LeagueService league;
    @Autowired
    PolicyRepository policies;

    private final StringBuilder out = new StringBuilder();

    private void line(String f, Object... a) {
        String s = a.length == 0 ? f : String.format(Locale.ROOT, f, a);
        out.append(s).append('\n');
        System.out.println(s);
        try {
            Path p = Paths.get(System.getProperty("league.out", "target/league/linha-de-base.md"));
            Files.createDirectories(p.toAbsolutePath().getParent());
            Files.write(p, out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The AIs in use: each champion's newest accepted, compatible version, and each class AI. */
    List<PolicyRepository.Record> inUse() {
        Map<String, PolicyRepository.Record> best = new LinkedHashMap<>();
        for (PolicyRepository.Record r : policies.list(null)) {
            if (r.accepted && r.compatible() && !"liga".equals(r.objective)) {
                best.merge(r.champion.toLowerCase(), r, (a, b) -> a.version >= b.version ? a : b);
            }
        }
        return new ArrayList<>(best.values());
    }

    @Test
    void baseline() {
        int threads = Integer.getInteger("league.threads", Runtime.getRuntime().availableProcessors());
        String only = System.getProperty("league.owners", "");
        Integer seeds = Integer.getInteger("league.seeds");
        if (seeds != null) {
            league.bankSettings().seeds = seeds;
        }
        line("# Generalização das IAs: linha de base (Rodada 21, parte 1)");
        line("");
        line("Banco: %s; %d threads; cópia %s.", league.adversaries(), threads, COPY.toAbsolutePath());
        line("");
        List<String[]> summary = new ArrayList<>();
        for (PolicyRepository.Record r : inUse()) {
            if (!only.isBlank() && !List.of(only.split(",")).contains(r.champion)) {
                continue;
            }
            List<String> players = league.players(r.champion);
            List<LeagueModel.Bout> bouts = league.bank(players);
            long t0 = System.nanoTime();
            LeagueModel.Matrix m = league.validate("ai:" + r.id, bouts, threads, null);
            double sec = (System.nanoTime() - t0) / 1e9;
            long t1 = System.nanoTime();
            m.trainingScenario = league.trainingScenario(r, 18, threads);
            double secScenario = (System.nanoTime() - t1) / 1e9;
            boolean ofClass = r.champion.startsWith(AutoBrain.CLASS_PREFIX);
            league.save(r.id, r.champion, ofClass ? "*" : r.champion, league.bankKey(players), m, sec);
            line("## %s v%d (id %d, jogando %s)", r.champion, r.version, r.id, String.join(", ", players));
            line("");
            line("%d lutas em %.0f s; êxito %.1f%%, Tático %.1f%%, diferença %+.1f pontos.", m.fights, sec, m.mean, m.tacticalMean, m.gap());
            Map<String, Object> ts = m.trainingScenario;
            line(ts == null ? "Cenário de treino (\"" + r.scenario + "\"): não está mais salvo." : String.format(Locale.ROOT,
                    "Cenário de treino \"%s\" (%s lutas, %.0f s): êxito %.1f%%, Tático %.1f%%; vitória %.0f%%, Tático %.0f%%; "
                            + "melhor que o Tático no objetivo do treino em %.0f%% das lutas.",
                    ts.get("cenario"), ts.get("lutas"), secScenario, (Double) ts.get("exito"), (Double) ts.get("tatico"),
                    (Double) ts.get("vitoria"), (Double) ts.get("vitoriaTatico"), (Double) ts.get("melhorQueTatico")));
            line("");
            StringBuilder head = new StringBuilder("| classe adversária |");
            StringBuilder sep = new StringBuilder("|---|");
            for (String t : league.bankSettings().types) {
                head.append(' ').append(t).append(" |");
                sep.append("---|");
            }
            line(head.append(" todas |").toString());
            line(sep.append("---|").toString());
            for (String cls : league.bankSettings().classes) {
                StringBuilder row = new StringBuilder("| " + cls + " |");
                for (String t : league.bankSettings().types) {
                    LeagueModel.Cell c = m.cell(cls, t);
                    row.append(c == null ? " - |" : String.format(Locale.ROOT, " %.0f (%+.0f) |", c.success, c.gap()));
                }
                double[] bc = m.byClass.get(cls);
                row.append(bc == null ? " - |" : String.format(Locale.ROOT, " %.0f (%+.0f) |", bc[0], bc[0] - bc[1]));
                line(row.toString());
            }
            StringBuilder all = new StringBuilder("| todas |");
            for (String t : league.bankSettings().types) {
                double[] bt = m.byType.get(t);
                all.append(bt == null ? " - |" : String.format(Locale.ROOT, " %.0f (%+.0f) |", bt[0], bt[0] - bt[1]));
            }
            line(all.append(String.format(Locale.ROOT, " %.0f (%+.0f) |", m.mean, m.gap())).toString());
            line("");
            summary.add(new String[] {r.champion + " v" + r.version, String.format(Locale.ROOT, "%.1f", m.mean),
                    String.format(Locale.ROOT, "%.1f", m.tacticalMean), String.format(Locale.ROOT, "%+.1f", m.gap()),
                    ts == null ? "-" : String.format(Locale.ROOT, "vitória %.0f / %.0f; melhor %.0f%%", (Double) ts.get("vitoria"),
                            (Double) ts.get("vitoriaTatico"), (Double) ts.get("melhorQueTatico")),
                    String.format(Locale.ROOT, "%.0f", sec)});
        }
        line("## Resumo");
        line("");
        line("| IA | êxito no banco | Tático no banco | diferença | no cenário de treino (IA / Tático) | s |");
        line("|---|---|---|---|---|---|");
        for (String[] s : summary) {
            line("| " + String.join(" | ", s) + " |");
        }
    }
}
