package com.ornnplanner.engine;

import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.ArenaService;
import com.ornnplanner.arena.ArenaTrainingService;
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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rodada 18, part 2: an AI for each main class (the automatic brain falls back to it for a champion without an AI of
 * its own), trained on a copy of the database with three champions of the class in turn, 1 x 1 at minute 15 against
 * the rival of a saved scenario. The copy stays in {@code target/classai/copy.db}; its {@code class:} AIs are then
 * copied into the real database by hand (tools or SQL), so a running campaign is never stopped.
 *
 * <pre>./mvnw test -Dtest=ClassAiTrainTest -Dclassai.db=data/ornn-planner.db [-Dclassai.generations=30 -Dclassai.scenario=2
 * -Dclassai.classes=Mago,Assassino]</pre>
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "classai.db", matches = ".+")
class ClassAiTrainTest {

    static final Path COPY = Paths.get("target/classai/copy.db");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        Files.createDirectories(COPY.getParent());
        Files.copy(Paths.get(System.getProperty("classai.db")), COPY, StandardCopyOption.REPLACE_EXISTING);
        r.add("planner.db-path", COPY::toString);
    }

    @Autowired
    ArenaService arena;
    @Autowired
    ArenaTrainingService training;
    @Autowired
    BuildRepository builds;
    @Autowired
    PlannerService planner;

    private final StringBuilder out = new StringBuilder();

    private void line(String f, Object... a) {
        String s = String.format(Locale.ROOT, f, a);
        out.append(s).append('\n');
        System.out.println(s);
        try {
            Files.write(Paths.get("target/classai/report.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Three champions of each class with an A, B or C sheet, and four items of the class (pt-BR shop names). */
    static final Map<String, List<String>> CHAMPIONS = new LinkedHashMap<>();
    static final Map<String, List<String>> ITEMS = new LinkedHashMap<>();

    static {
        CHAMPIONS.put("Tanque", List.of("ORNN", "MALPHITE", "KSANTE"));
        ITEMS.put("Tanque", List.of("Coração de Aço", "Armadura de Espinhos", "Presságio de Randuin", "Égide de Fogo Solar"));
        CHAMPIONS.put("Lutador", List.of("DARIUS", "GAREN", "AATROX"));
        ITEMS.put("Lutador", List.of("Cutelo Negro", "Sinal de Sterak", "Dança da Morte", "Hidra Titânica"));
        CHAMPIONS.put("Atirador", List.of("CAITLYN", "JINX", "EZREAL"));
        ITEMS.put("Atirador", List.of("Gume do Infinito", "Canhão Fumegante", "Lembranças do Lorde Dominik", "Sedenta por Sangue"));
        CHAMPIONS.put("Mago", List.of("AHRI", "LUX", "ANNIE"));
        ITEMS.put("Mago", List.of("Eco de Luden", "Capuz da Morte de Rabadon", "Cajado do Vazio", "Ampulheta de Zhonya"));
        CHAMPIONS.put("Assassino", List.of("KHAZIX", "AKALI", "DIANA"));
        ITEMS.put("Assassino", List.of("Lâmina Fantasma de Youmuu", "Eclipse", "Rancor de Serylda", "Limiar da Noite"));
        CHAMPIONS.put("Controlador", List.of("THRESH", "MORGANA", "NAMI"));
        ITEMS.put("Controlador", List.of("Medalhão dos Solari de Ferro", "Hino Bélico de Shurelya", "Armadura de Warmog", "Presságio de Randuin"));
        CHAMPIONS.put("Especialista", List.of("TEEMO", "SINGED", "KENNEN"));
        ITEMS.put("Especialista", List.of("Tormento de Liandry", "Cetro de Cristal de Rylai", "Ampulheta de Zhonya", "Capuz da Morte de Rabadon"));
    }

    private long draft(String unit, List<String> items) {
        Map<String, Long> ids = new LinkedHashMap<>();
        planner.itemViews().forEach(i -> ids.put(i.name, i.id));
        BuildRepository.Build b = new BuildRepository.Build();
        b.name = "IA de classe " + unit;
        b.unitCode = unit;
        b.goldPerMin = 1000;
        b.xpPerMin = 650;
        b.steps = new ArrayList<>();
        for (String i : items) {
            Long id = ids.get(i);
            if (id == null) {
                throw new IllegalStateException("item sem id: " + i);
            }
            b.steps.add(new BuildRepository.Step(id, true));
        }
        b.folderId = builds.findBuildSummaries().get(0).folderId;
        return builds.insertBuild(b);
    }

    @Test
    void trainEveryClass() throws Exception {
        long scenario = Long.getLong("classai.scenario", 2);
        int gens = Integer.getInteger("classai.generations", 30);
        String only = System.getProperty("classai.classes", "");
        line("# IAs de classe: cenário %d, %d gerações, cópia %s", scenario, gens, COPY.toAbsolutePath());
        for (Map.Entry<String, List<String>> e : CHAMPIONS.entrySet()) {
            if (!only.isBlank() && !List.of(only.split(",")).contains(e.getKey())) {
                continue;
            }
            List<Long> ids = new ArrayList<>();
            for (String unit : e.getValue()) {
                ids.add(draft(unit, ITEMS.get(e.getKey())));
            }
            ArenaTrainingService.ClassRequest q = new ArenaTrainingService.ClassRequest();
            q.scenario = arena.find(scenario);
            for (ArenaModel.Slot s : q.scenario.fighters) {
                s.minute = 15.0;
            }
            q.scenario.fighters.get(0).brain = "tactical";
            q.fighter = 0;
            q.className = e.getKey();
            q.builds = ids;
            q.generations = gens;
            long t0 = System.nanoTime();
            ArenaTrainingService.Job job = training.startClass(q);
            while (job.finishedAt == null && !"cancelled".equals(job.status)) {
                Thread.sleep(500);
            }
            line("classe %s (%s): %s, %s, IA id %s v%s, %d gerações, %.0f s, melhor que o Roteiro %.0f%%, que o Tático %.0f%%, aceita=%s",
                    e.getKey(), String.join(", ", e.getValue()), job.status, job.message, job.policy == null ? "?" : job.policy.id,
                    job.policy == null ? "?" : job.policy.version, job.generation, (System.nanoTime() - t0) / 1e9,
                    job.policy == null ? 0 : 100 * job.policy.betterVsScript, job.policy == null ? 0 : 100 * job.policy.betterVsTactical,
                    job.policy != null && job.policy.accepted);
        }
    }
}
