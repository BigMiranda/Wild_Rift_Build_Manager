package com.ornnplanner.engine;

import com.ornnplanner.arena.league.AiCampaignService;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rodada 21, part 4 (report style, opt-in): the campaign "Treinar IAs de todos os campeões" on a <b>copy</b>: the 7 class
 * AIs and the first champions of its order, with a budget, then each one's matrix against the AI it had to beat and the
 * tactical rules, the times and the projection for every champion.
 *
 * <pre>./mvnw test -Dtest=AiCampaignReportTest -Daicamp.report=data/ornn-planner.db [-Daicamp.champions=3
 * -Daicamp.budget=rapido -Daicamp.out=target/aicamp/relatorio.md]</pre>
 */
@SpringBootTest(properties = {"server.port=0"})
@EnabledIfSystemProperty(named = "aicamp.report", matches = ".+")
class AiCampaignReportTest {

    static final Path COPY = Paths.get("target/aicamp/copy.db");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        Files.createDirectories(COPY.getParent());
        Files.copy(Paths.get(System.getProperty("aicamp.report")), COPY, StandardCopyOption.REPLACE_EXISTING);
        r.add("planner.db-path", COPY::toString);
    }

    @Autowired
    AiCampaignService campaign;
    @Autowired
    LeagueService league;
    @Autowired
    com.ornnplanner.arena.league.LeagueTrainer trainer;

    private final StringBuilder out = new StringBuilder();

    private void line(String f, Object... a) {
        String s = a.length == 0 ? f : String.format(Locale.ROOT, f, a);
        out.append(s).append('\n');
        System.out.println(s);
        try {
            Path p = Paths.get(System.getProperty("aicamp.out", "target/aicamp/relatorio.md"));
            Files.createDirectories(p.toAbsolutePath().getParent());
            Files.write(p, out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static double num(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : Double.NaN;
    }

    @Test
    void campaign() throws Exception {
        int champions = Integer.getInteger("aicamp.champions", 3);
        String budget = System.getProperty("aicamp.budget", "rapido");
        AiCampaignService.Request q = new AiCampaignService.Request();
        q.budget = budget;
        q.name = "Relatório da Rodada 21";
        List<String> order = campaign.order(new AiCampaignService.Request());
        List<String> firstChampions = new ArrayList<>();
        for (String o : order) {
            if (!o.startsWith("class:") && firstChampions.size() < champions) {
                firstChampions.add(o);
            }
        }
        q.champions = firstChampions;
        String only = System.getProperty("aicamp.classes", "");
        if (!only.isBlank()) {
            q.classNames = List.of(only.split(","));
        }
        if (champions == 0) {
            q.champions = List.of("-");   // no champion: a name with no sheet queues nothing
        }
        // -Daicamp.share=0.5: train with this share of the cores, as with the builds campaign running (the impact)
        String share = System.getProperty("aicamp.share");
        if (share != null) {
            trainer.settings().workersAlone = Double.parseDouble(share);
        }
        long t0 = System.nanoTime();
        campaign.enqueue(q);
        line("# Campanha de treino das IAs (Rodada 21): orçamento %s, %d núcleos", budget, Runtime.getRuntime().availableProcessors());
        line("");
        line("Ordem: as 7 classes, depois %s.", String.join(", ", firstChampions));
        line("");
        String last = null;
        while (true) {
            AiCampaignService.State s = campaign.state();
            String now = s.currentOwner + " " + s.stage;
            if (!now.equals(last) && s.running) {
                System.out.println("... " + now + " " + s.generation + "/" + s.generations);
                last = now;
            }
            if (!s.running && s.rows.stream().noneMatch(r -> "pendente".equals(r.status) || "rodando".equals(r.status))) {
                break;
            }
            Thread.sleep(2000);
        }
        double total = (System.nanoTime() - t0) / 1e9;
        line("| IA | status | êxito da nova | IA em uso (referência) | Tático | pior célula | gerações (melhor) | treino s | validação s | total s |");
        line("|---|---|---|---|---|---|---|---|---|---|");
        AiCampaignService.State s = campaign.state();
        for (AiCampaignService.Row r : s.rows) {
            Map<String, Object> m = r.result;
            if (m == null) {
                line("| %s | %s | - | - | - | - | - | - | - | %.0f |", r.owner, r.status + (r.error == null ? "" : " (" + r.error + ")"),
                        r.seconds == null ? 0 : r.seconds);
                continue;
            }
            line("| %s v%s | %s | %.1f | %s | %.1f | %+.1f (%s) | %s (%s) | %.0f | %.0f | %.0f |", r.owner, m.get("version"), r.status,
                    num(m.get("mean")), m.get("incumbentMean") == null ? "nenhuma" : String.format(Locale.ROOT, "%.1f", num(m.get("incumbentMean"))),
                    num(m.get("tacticalMean")), num(m.get("worstCell")), m.get("worstCellKey"), m.get("generations"), m.get("bestGeneration"),
                    num(m.get("trainSeconds")), num(m.get("validationSeconds")), r.seconds == null ? 0 : r.seconds);
        }
        line("");
        AiCampaignService.Estimate e = s.estimate;
        line("Estimativa medida (%s): %.0f s por IA de classe (%d medidas), %.0f s por campeão (%d medidos); os %d campeões e as 7 classes "
                        + "do zero: %.1f h. Esta execução: %.0f s.", e.budget, e.secondsPerClass == null ? Double.NaN : e.secondsPerClass,
                e.classesMeasured, e.secondsPerChampion == null ? Double.NaN : e.secondsPerChampion, e.championsMeasured, e.championsAll,
                e.secondsAll == null ? Double.NaN : e.secondsAll / 3600, total);
        line("");
        // the matrices of the new class AIs, cell by cell, as the baseline shows them
        for (AiCampaignService.Row r : s.rows) {
            if (r.policyId == null) {
                continue;
            }
            String champion = r.owner.startsWith("class:") ? "*" : r.owner;
            LeagueService.Saved v = league.latest(r.policyId, champion);
            if (v == null || v.matrix == null) {
                continue;
            }
            LeagueModel.Matrix mx = v.matrix;
            line("## %s (%s): êxito %.1f%%, Tático %.1f%% (%+.1f)", r.owner, r.status, mx.mean, mx.tacticalMean, mx.gap());
            line("");
            StringBuilder head = new StringBuilder("| classe adversária |");
            StringBuilder sep = new StringBuilder("|---|");
            for (String t : league.bankSettings().types) {
                head.append(' ').append(t).append(" |");
                sep.append("---|");
            }
            line(head.toString());
            line(sep.toString());
            for (String cls : league.bankSettings().classes) {
                StringBuilder row = new StringBuilder("| " + cls + " |");
                for (String t : league.bankSettings().types) {
                    LeagueModel.Cell c = mx.cell(cls, t);
                    row.append(c == null ? " - |" : String.format(Locale.ROOT, " %.0f (%+.0f) |", c.success, c.gap()));
                }
                line(row.toString());
            }
            line("");
        }
    }
}
