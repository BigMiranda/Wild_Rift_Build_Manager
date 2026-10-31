package com.ornnplanner.engine;

import com.ornnplanner.arena.EffectModel;
import com.ornnplanner.arena.EffectTestService;
import com.ornnplanner.arena.PassiveValueService;
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
    @Autowired
    PassiveValueService passives;

    @Autowired
    com.ornnplanner.arena.fight.ChampionProfiles profiles;
    @Autowired
    com.ornnplanner.arena.fight.BuildArchetypes archetypes;

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

    /** Classes against the profiles, and the archetypes of the saved builds. Writes target/classification-report.txt. */
    @Test
    void classification() throws IOException {
        Map<String, Integer> byClass = new java.util.TreeMap<>();
        int divergent = 0;
        for (com.ornnplanner.arena.fight.ChampionProfiles.Profile p : profiles.all()) {
            p.mainClasses.forEach(c -> byClass.merge(c, 1, Integer::sum));
            if (p.divergent) {
                divergent++;
                line("- %s: oficial %s, perfil %s (%.1f contra %.1f); %.0f de Vida, %.0f de Armadura, alcance %.0f, mobilidade %d, controle %s, explosão %.0f%%, físico %.0f%%, mágico %.0f%%",
                        p.name, p.classes, p.suggested, p.scores.get(p.suggested), p.mainClasses.stream().filter(p.scores::containsKey)
                                .mapToDouble(p.scores::get).max().orElse(0), p.healthLv15, p.armorLv15, p.attackRange, p.mobility, p.control,
                        100 * p.burstShare, 100 * p.physical, 100 * p.magic);
            }
        }
        line("campeões %d, por classe %s, divergentes %d", profiles.all().size(), byClass, divergent);
        for (BuildRepository.Build s : builds.findBuildSummaries()) {
            BuildRepository.Build b = builds.findBuild(s.id).orElseThrow();
            try {
                com.ornnplanner.arena.fight.BuildArchetypes.Archetype a = archetypes.of(b, null);
                StringBuilder sh = new StringBuilder();
                a.shares.forEach((k, v) -> sh.append(v >= 0.01 ? String.format(Locale.ROOT, " %s %.0f%%", k, 100 * v) : ""));
                line("- %s (%s): %s%s |%s | %s", b.name, b.unitCode, a.name, a.detail == null ? "" : " (" + a.detail + ")", sh, a.effects);
            } catch (RuntimeException e) {
                line("- %s: %s", b.name, e.getMessage());
            }
        }
        Files.write(Paths.get("target/classification-report.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private List<EffectModel.Outcome> run(EffectModel.Request base, List<EffectModel.Job> jobs) {
        EffectModel.Request r = new EffectModel.Request();
        r.buildId = base.buildId;
        r.minute = base.minute;
        r.readMode = base.readMode;
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
        line("| cenário | índice | tempo até morrer | referência | taxa líquida | %%Vida/s (ref.) | índice bruto | vida efetiva | mitigado | escudo | cura (perdida) | devolvido | controle |");
        line("|---|---|---|---|---|---|---|---|---|---|---|---|---|");
        double sum = 0;
        double w = 0;
        for (int i = 0; i < o.size(); i++) {
            EffectModel.Outcome x = o.get(i);
            if (x.error != null) {
                line("| %s | erro: %s |", jobs.get(i).testKey, x.error);
                continue;
            }
            EffectModel.Measures m = x.build;
            line("| %s | %s | %s | %s | %.0f/s | %.2f (%.2f) | %.0f | %.0f | %.0f%% | %.0f | %.0f (%.0f) | %.0f | %.1f s |", x.testName, index(x),
                    time(m), time(x.reference), m.netRate, m.healthRate, x.reference.healthRate, m.netRate > 0 ? 100 * x.reference.netRate / m.netRate : -1,
                    m.effectiveHealth, m.mitigatedPct, m.shieldAbsorbed, m.healed, m.healLost, m.dealt, m.controlApplied);
            if (x.index > x.cap + 1e-9) {
                throw new IllegalStateException(x.testName + ": índice acima do teto");
            }
            sum += rep.tests.get(i).weight * x.index;
            w += rep.tests.get(i).weight;
        }
        line("índice ponderado: %.0f", sum / w);
        line("devolvido: reflexo / aura e queimadura / golpes; cura do atacante (cortada):");
        for (EffectModel.Outcome x : o) {
            if (x.error == null) {
                line("- %s: %.0f / %.0f / %.0f; %.0f (%.0f)", x.testName, x.build.returnedThorns, x.build.returnedAuras, x.build.returnedHits,
                        x.build.enemyHealed, x.build.enemyHealCut);
            }
        }
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
                line("- %s: índice %s, tempo %s, absorveu %.0f%% do dano inimigo, aliados vivos %.1f s",
                        x.testName, index(x), time(x.build),
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

    /** With × without each item in each mode: how many readings say the item makes the build worse. */
    private void modes(EffectModel.Request base, EffectModel.Report rep) {
        List<EffectModel.HeldItem> items = effect.itemsAt(base);
        line("## Modos de leitura (com × sem, %d itens × %d cenários)", items.size(), rep.tests.size());
        for (String mode : EffectModel.READ_MODES) {
            EffectModel.Request r = new EffectModel.Request();
            r.buildId = base.buildId;
            r.minute = base.minute;
            r.readMode = mode;
            List<EffectModel.Job> jobs = new ArrayList<>();
            for (EffectModel.Entry e : rep.tests) {
                jobs.add(job(e.key, null));
                items.forEach(it -> jobs.add(job(e.key, variant("sem", it.id, null))));
            }
            long t0 = System.nanoTime();
            List<EffectModel.Outcome> o = run(r, jobs);
            double ms = (System.nanoTime() - t0) / 1e6;
            int negative = 0;
            int k = 0;
            StringBuilder worst = new StringBuilder();
            for (EffectModel.Entry e : rep.tests) {
                EffectModel.Outcome with = o.get(k++);
                for (EffectModel.HeldItem it : items) {
                    EffectModel.Outcome without = o.get(k++);
                    double g = 100 * (with.build.durability / without.build.durability - 1);
                    if (g < -0.5) {
                        negative++;
                        worst.append(String.format(Locale.ROOT, " %s × %s %+.0f%%;", it.name, with.testName, g));
                    }
                }
            }
            line("- %s: %d leituras negativas de %d (%.0f ms):%s", mode, negative, items.size() * rep.tests.size(), ms, worst);
        }
    }

    /** Each anti-heal passive of the build, measured against the fed marksman. */
    private void antiHeal(EffectModel.Request base) {
        List<EffectModel.HeldItem> items = effect.itemsAt(base);
        for (EffectModel.HeldItem it : items) {
            if (!it.name.contains("Espinh")) {
                continue;
            }
            PassiveValueService.MeasureRequest req = new PassiveValueService.MeasureRequest();
            req.buildId = base.buildId;
            req.scenario.kind = "teste";
            req.scenario.key = "atirador_alimentado";
            PassiveValueService.Job j = new PassiveValueService.Job();
            j.minute = builds.findBuild(base.buildId).map(b -> b.matchEnd != null ? b.matchEnd : 23.5).orElse(23.5);
            j.itemId = it.id;
            j.passive = "Entrelaçar";
            req.jobs.add(j);
            PassiveValueService.Reading r = passives.measure(req).get(0);
            line("- Entrelaçar de %s contra Atirador alimentado: %.0f de ouro (tira %.1f%%; %s)", it.name, r.gold, r.lossPct, r.damage ? "dano" : "tempo");
        }
    }

    private void perItem(EffectModel.Request base0, EffectModel.Report rep) {
        antiHeal(base0);
        modes(base0, rep);
        EffectModel.Request base = new EffectModel.Request();
        base.buildId = base0.buildId;
        base.minute = base0.minute;
        base.readMode = EffectModel.FIXED;
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
                    if (best == null || g.build.durability > best.build.durability) {
                        best = g;
                        bestMix = m;
                    }
                }
                double t = with.build.durability;
                line("| %s | %s | %+.0f%% | %+.0f%% | %+.0f%% (%s) |", it.name, with.testName,
                        100 * (t / without.build.durability - 1), 100 * (t / flat.build.durability - 1),
                        100 * (best.build.durability / without.build.durability - 1), mixName(EffectTestService.MIXES.get(bestMix)));
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
            line("- %s: índice %s (tempo %s, vida efetiva %.0f)%s", cands.get(i), index(x), time(x.build),
                    x.build.effectiveHealth, Boolean.FALSE.equals(x.held) ? " — não comprado no minuto" : "");
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

    private static String index(EffectModel.Outcome x) {
        return x.sustains ? "sustenta (+" + Math.round(x.build.surplus) + "/s)" : Math.round(x.index) + (x.capped ? "+" : "");
    }

    private static String time(EffectModel.Measures m) {
        return (m.died ? "" : "> ") + String.format(Locale.ROOT, "%.1f s", m.timeToDeath);
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
