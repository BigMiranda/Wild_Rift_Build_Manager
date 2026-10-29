package com.ornnplanner.engine;

import com.ornnplanner.arena.EffectModel;
import com.ornnplanner.arena.EffectTestService;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.service.PlannerService;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The effectiveness reports of real builds on a <b>copy</b> of a database, with their times (report style, opt-in):
 * {@code ./mvnw test -Dtest=EffectReportTest -Deffect.report=data/ornn-planner.db
 * -Deffect.builds="Big Carry v1,Soldado v8,Manopla dos Glacinatas"}. Writes target/effect-report.txt.
 */
@EnabledIfSystemProperty(named = "effect.report", matches = ".+")
@SpringBootTest(properties = {"server.port=0"})
class EffectReportTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        Path copy = Files.createTempDirectory("effect-report").resolve("copy.db");
        Files.copy(Paths.get(System.getProperty("effect.report")), copy);
        r.add("planner.db-path", copy::toString);
    }

    @Autowired
    EffectTestService effect;
    @Autowired
    BuildRepository builds;
    @Autowired
    PlannerService planner;

    private final StringBuilder out = new StringBuilder();

    private void line(String f, Object... a) {
        String s = String.format(Locale.ROOT, f, a);
        out.append(s).append('\n');
        System.out.println(s);
    }

    @Test
    void reports() throws IOException {
        String names = System.getProperty("effect.builds", "Big Carry v1,Soldado v8,Manopla dos Glacinatas");
        Map<String, Long> ids = new LinkedHashMap<>();
        for (BuildRepository.Build b : builds.findBuildSummaries()) {
            ids.put(b.name, b.id);
        }
        Map<String, Long> itemIds = new LinkedHashMap<>();
        planner.itemViews().forEach(i -> itemIds.put(i.name, i.id));
        boolean first = true;
        for (String name : names.split(",")) {
            Long id = ids.get(name.trim());
            if (id == null) {
                line("build %s não existe", name);
                continue;
            }
            EffectModel.Request base = new EffectModel.Request();
            base.buildId = id;
            line("# %s", name.trim());
            basic(base, effect.report("basico"));
            complete(base, effect.report("completo"));
            if (first) {
                perItem(base, effect.report("basico"));
                candidates(base, itemIds);
                first = false;
            }
        }
        Files.write(Paths.get("target/effect-report.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private List<EffectModel.Outcome> run(EffectModel.Request base, List<EffectModel.Job> jobs) {
        EffectModel.Request r = new EffectModel.Request();
        r.buildId = base.buildId;
        r.minute = base.minute;
        r.jobs = jobs;
        return effect.evaluate(r);
    }

    private static EffectModel.Job job(String key, EffectModel.Variant v) {
        EffectModel.Job j = new EffectModel.Job();
        j.testKey = key;
        j.variant = v;
        return j;
    }

    private void basic(EffectModel.Request base, EffectModel.Report rep) {
        List<EffectModel.Job> jobs = new ArrayList<>();
        rep.tests.forEach(e -> jobs.add(job(e.key, null)));
        long t0 = System.nanoTime();
        List<EffectModel.Outcome> o = run(base, jobs);
        double ms = (System.nanoTime() - t0) / 1e6;
        line("## Básico (%.0f ms, %d cenários)", ms, jobs.size());
        line("| cenário | índice | tempo até morrer | referência | vida efetiva | mitigado | escudo | cura (perdida) | devolvido | controle |");
        line("|---|---|---|---|---|---|---|---|---|---|");
        double sum = 0;
        double w = 0;
        for (int i = 0; i < o.size(); i++) {
            EffectModel.Outcome x = o.get(i);
            if (x.error != null) {
                line("| %s | erro: %s |", jobs.get(i).testKey, x.error);
                continue;
            }
            EffectModel.Measures m = x.build;
            line("| %s | %.0f | %.1f s%s | %.1f s | %.0f | %.0f%% | %.0f | %.0f (%.0f) | %.0f | %.1f s |", x.testName, x.index,
                    m.timeToDeath, m.estimated ? " (est.)" : "", x.reference.timeToDeath, m.effectiveHealth, m.mitigatedPct,
                    m.shieldAbsorbed, m.healed, m.healLost, m.dealt, m.controlApplied);
            sum += rep.tests.get(i).weight * x.index;
            w += rep.tests.get(i).weight;
        }
        line("índice ponderado: %.0f", sum / w);
    }

    private void complete(EffectModel.Request base, EffectModel.Report rep) {
        List<EffectModel.Job> jobs = new ArrayList<>();
        rep.tests.forEach(e -> jobs.add(job(e.key, null)));
        for (EffectModel.Sweep s : rep.sweeps) {
            for (double v : s.stages) {
                EffectModel.Job j = new EffectModel.Job();
                j.tag = s.key;
                j.stage = new EffectModel.Stage();
                j.stage.dimension = s.key;
                j.stage.value = v;
                jobs.add(j);
            }
        }
        long t0 = System.nanoTime();
        List<EffectModel.Outcome> o = run(base, jobs);
        double ms = (System.nanoTime() - t0) / 1e6;
        line("## Completo (%.0f ms, %d lutas pedidas, as do Básico já em cache)", ms, jobs.size());
        int k = rep.tests.size();
        for (int i = 0; i < k; i++) {
            EffectModel.Outcome x = o.get(i);
            if ("equipe".equals(effect.presets().containsKey(jobs.get(i).testKey) ? effect.presets().get(jobs.get(i).testKey).group : "")) {
                line("- %s: índice %.0f, tempo %.1f s%s, absorveu %.0f%% do dano inimigo, aliados vivos %.1f s",
                        x.testName, x.index, x.build.timeToDeath, x.build.estimated ? " (est.)" : "",
                        x.build.absorbedShare == null ? 0 : x.build.absorbedShare, x.build.alliesAlive == null ? 0 : x.build.alliesAlive);
            }
        }
        for (EffectModel.Sweep s : rep.sweeps) {
            StringBuilder sb = new StringBuilder();
            Double prev = null;
            String breakAt = "—";
            for (double v : s.stages) {
                EffectModel.Outcome x = o.get(k++);
                sb.append(String.format(Locale.ROOT, " %s→%.0f", fmt(v), x.index));
                if (prev != null && "—".equals(breakAt) && x.index < prev * 0.75) {
                    breakAt = fmt(v);
                }
                prev = x.index;
            }
            line("- %s:%s · quebra: %s", s.name, sb, breakAt);
        }
    }

    private void perItem(EffectModel.Request base, EffectModel.Report rep) {
        long t0 = System.nanoTime();
        List<EffectModel.HeldItem> items = effect.itemsAt(base);
        List<EffectModel.Job> jobs = new ArrayList<>();
        for (EffectModel.Entry e : rep.tests) {
            jobs.add(job(e.key, null));
            for (EffectModel.HeldItem it : items) {
                jobs.add(job(e.key, variant("sem", it.id, null)));
                jobs.add(job(e.key, variant("so_status", it.id, null)));
                for (Map<String, Double> mix : EffectTestService.MIXES) {
                    jobs.add(job(e.key, variant("ouro", it.id, mix)));
                }
            }
        }
        List<EffectModel.Outcome> o = run(base, jobs);
        double ms = (System.nanoTime() - t0) / 1e6;
        line("## Por item (%.0f ms, %d lutas pedidas)", ms, jobs.size());
        line("| item | cenário | com × sem | com × só status (passiva) | mesmo ouro em status (melhor mistura) |");
        line("|---|---|---|---|---|");
        int k = 0;
        for (EffectModel.Entry e : rep.tests) {
            EffectModel.Outcome with = o.get(k++);
            for (EffectModel.HeldItem it : items) {
                EffectModel.Outcome without = o.get(k++);
                EffectModel.Outcome flat = o.get(k++);
                EffectModel.Outcome best = null;
                int bestMix = 0;
                for (int m = 0; m < EffectTestService.MIXES.size(); m++) {
                    EffectModel.Outcome g = o.get(k++);
                    if (best == null || g.build.timeToDeath > best.build.timeToDeath) {
                        best = g;
                        bestMix = m;
                    }
                }
                double t = with.build.timeToDeath;
                line("| %s | %s | %+.0f%% | %+.0f%% | %+.0f%% (%s) |", it.name, with.testName,
                        100 * (t / without.build.timeToDeath - 1), 100 * (t / flat.build.timeToDeath - 1),
                        100 * (best.build.timeToDeath / without.build.timeToDeath - 1), mixName(EffectTestService.MIXES.get(bestMix)));
            }
        }
    }

    private void candidates(EffectModel.Request base, Map<String, Long> itemIds) {
        BuildRepository.Build b = builds.findBuild(base.buildId).orElseThrow();
        Integer slot = null;
        for (int i = 0; i < b.steps.size(); i++) {
            if (!b.steps.get(i).moment() && itemIds.get("Armadura de Espinhos").equals(b.steps.get(i).itemId)) {
                slot = i;
            }
        }
        if (slot == null) {
            line("sem Armadura de Espinhos para trocar");
            return;
        }
        List<String> cands = List.of("Armadura de Espinhos", "Presságio de Randuin", "Coração Congelado", "Manto da Meia-noite");
        List<EffectModel.Job> jobs = new ArrayList<>();
        for (String c : cands) {
            EffectModel.Variant v = new EffectModel.Variant();
            v.kind = "candidato";
            v.slot = slot;
            v.candidateId = itemIds.get(c);
            jobs.add(job("atirador_alimentado", v));
        }
        long t0 = System.nanoTime();
        List<EffectModel.Outcome> o = run(base, jobs);
        double ms = (System.nanoTime() - t0) / 1e6;
        line("## Candidatos no lugar da Armadura de Espinhos (compra #%d), contra Atirador alimentado (%.0f ms)", slot + 1, ms);
        for (int i = 0; i < cands.size(); i++) {
            EffectModel.Outcome x = o.get(i);
            line("- %s: índice %.0f (tempo %.1f s%s, vida efetiva %.0f)%s", cands.get(i), x.index, x.build.timeToDeath,
                    x.build.estimated ? " est." : "", x.build.effectiveHealth, Boolean.FALSE.equals(x.held) ? " — não comprado no minuto" : "");
        }
    }

    private static EffectModel.Variant variant(String kind, long item, Map<String, Double> mix) {
        EffectModel.Variant v = new EffectModel.Variant();
        v.kind = kind;
        v.itemId = item;
        v.mix = mix;
        return v;
    }

    private static String mixName(Map<String, Double> mix) {
        StringBuilder sb = new StringBuilder();
        mix.forEach((k, v) -> {
            if (v > 0) {
                sb.append(sb.length() == 0 ? "" : " + ").append(Math.round(v * 100)).append("% ")
                        .append(Stats.MAX_HEALTH.equals(k) ? "Vida" : Stats.ARMOR.equals(k) ? "Armadura" : "RM");
            }
        });
        return sb.toString();
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
