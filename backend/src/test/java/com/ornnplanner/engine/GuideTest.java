package com.ornnplanner.engine;

import com.ornnplanner.arena.guide.Guide;
import com.ornnplanner.arena.guide.GuideService;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.service.PlannerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The guides (Rodada 9, part 7): conditions, reading, paths with their weights, and a guide read in a match. */
@SpringBootTest(properties = {"server.port=0"})
class GuideTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        String db = Files.createTempDirectory("guide").resolve("t.db").toString();
        r.add("planner.db-path", () -> db);
    }

    @Autowired
    GuideService guides;
    @Autowired
    PlannerService planner;
    @Autowired
    BuildRepository builds;

    private long id(String name) {
        return planner.itemViews().stream().filter(i -> i.name.equals(name)).findFirst().orElseThrow().id;
    }

    private static Guide.Node decision(long item) {
        Guide.Node n = new Guide.Node();
        n.decision = "item";
        n.itemId = item;
        return n;
    }

    private static Guide.Node condition(String type, String value, Integer count) {
        Guide.Node n = new Guide.Node();
        n.kind = "condicao";
        n.cond = new Guide.Condition();
        n.cond.type = type;
        n.cond.value = value;
        n.cond.count = count;
        return n;
    }

    private static Guide.Enemy enemy(String damage, String cls) {
        Guide.Enemy e = new Guide.Enemy();
        e.damage = damage;
        e.championClass = cls;
        return e;
    }

    @Test
    void aGuideIsReadByItsConditions() {
        // first item 1; then if 2+ enemies with magic damage → 2, else 3; then 4
        Guide.Node magic = condition("inimigos_dano", "magico", 2);
        magic.then.add(decision(2));
        magic.otherwise.add(decision(3));
        List<Guide.Node> tree = new ArrayList<>(List.of(decision(1), magic, decision(4)));
        Guide.Context ctx = new Guide.Context();
        ctx.enemies.add(enemy("magico", "Mago"));
        ctx.enemies.add(enemy("fisico", "Lutador"));
        assertEquals(List.of(1L, 3L, 4L), ids(Guide.read(tree, ctx)));
        ctx.enemies.add(enemy("magico", "Mago"));
        assertEquals(List.of(1L, 2L, 4L), ids(Guide.read(tree, ctx)));
        Guide.Condition minute = new Guide.Condition();
        minute.type = "minuto";
        minute.op = ">=";
        minute.number = 10.0;
        ctx.minute = 9.5;
        assertTrue(!Guide.holds(minute, ctx));
        ctx.minute = 10;
        assertTrue(Guide.holds(minute, ctx));
    }

    @Test
    void thePathsWeighByTheFrequencies() {
        Guide.Node c = condition("ouro", "frente", null);
        c.frequency = 0.25;
        c.then.add(decision(2));
        c.otherwise.add(decision(3));
        List<Guide.Path> paths = Guide.paths(List.of(decision(1), c));
        assertEquals(2, paths.size());
        assertEquals(0.25, paths.get(0).weight, 1e-9);
        assertEquals(List.of(1L, 2L), ids(paths.get(0).decisions));
        assertEquals(0.75, paths.get(1).weight, 1e-9);
        assertEquals(List.of(1L, 3L), ids(paths.get(1).decisions));
    }

    /** "From 10 min" turns when the planner's timeline gets there: the order a guide gives in a match. */
    @Test
    void aGuideBecomesAnOrderInAMatch() {
        BuildRepository.Build me = new BuildRepository.Build();
        me.name = "eu";
        me.unitCode = "ORNN";
        me.goldPerMin = 700;
        me.xpPerMin = 700;
        me.folderId = builds.findFolders().get(0).id;
        BuildRepository.Build mage = new BuildRepository.Build();
        mage.name = "mago";
        mage.unitCode = "ORNN";
        mage.goldPerMin = 700;
        mage.xpPerMin = 700;
        mage.folderId = me.folderId;
        mage.steps = new ArrayList<>(List.of(new BuildRepository.Step(id("Cajado do Vazio"), true)));
        long mageId = builds.insertBuild(mage);
        GuideService.View g = new GuideService.View();
        g.unitCode = "ORNN";
        g.name = "teste";
        Guide.Node magic = condition("inimigos_dano", "magico", 1);
        magic.then.add(decision(id("Máscara Abissal")));
        magic.otherwise.add(decision(id("Armadura de Espinhos")));
        Guide.Node late = condition("minuto", null, null);
        late.cond.number = 10.0;
        late.then.add(decision(id("Coração de Aço")));
        late.otherwise.add(decision(id("Égide de Fogo Solar")));
        g.nodes = new ArrayList<>(List.of(magic, late));
        g = guides.save(g);
        GuideService.Match m = new GuideService.Match();
        m.build = me;
        m.enemies.add(mageId);
        GuideService.Reading r = guides.resolve(guides.get(g.id), m);
        assertEquals(id("Máscara Abissal"), r.order.get(0), "a magic enemy: the magic resist item first");
        // the first item is ready before 10:00 at 700 gold per minute: the second is read before 10 minutes
        assertEquals(id("Égide de Fogo Solar"), r.order.get(1));
        GuideService.Evaluation ev = guides.evaluate(g, me);
        assertEquals(4, ev.paths.size());
        assertTrue(ev.score > 0);
    }

    private static List<Long> ids(List<Guide.Node> nodes) {
        List<Long> out = new ArrayList<>();
        nodes.forEach(n -> out.add(n.itemId));
        return out;
    }
}
