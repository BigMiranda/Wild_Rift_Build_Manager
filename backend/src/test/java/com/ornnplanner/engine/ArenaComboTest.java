package com.ornnplanner.engine;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel.Combo;
import com.ornnplanner.arena.ArenaModel.ComboReport;
import com.ornnplanner.arena.ArenaModel.ComboStep;
import com.ornnplanner.arena.ArenaModel.Condition;
import com.ornnplanner.arena.ArenaModel.Result;
import com.ornnplanner.arena.ComboRepository;
import com.ornnplanner.arena.ai.ActionMask;
import com.ornnplanner.arena.ai.ActionSpace;
import com.ornnplanner.arena.ai.Observation;
import com.ornnplanner.arena.ai.Policy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Combos as data: the executor (steps, event waits, aborts) and as actions of a policy; the library. */
class ArenaComboTest {

    private static Combo combo(String name, ComboStep... steps) {
        Combo c = new Combo();
        c.champion = "Darius";
        c.name = name;
        c.steps = new ArrayList<>(List.of(steps));
        return c;
    }

    private static Result bench(Arena.Input me, Arena.Input dummy, double duration) {
        me.autoAbilities = false;
        dummy.autoAbilities = false;
        dummy.policy = (o, m) -> Policy.Decision.of(ActionSpace.IDLE);
        me.policy = (o, m) -> Policy.Decision.of(ActionSpace.IDLE);
        return new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).run(List.of(me, dummy), List.of(), duration);
    }

    private static Arena.Input darius() {
        return ArenaScenarios.champion("Darius", 0, 1000, 0, 5000, 160, 90);
    }

    private static Arena.Input dummy(double hp) {
        return ArenaScenarios.champion("Ornn", 1, 1300, 0, hp, 1, 90);
    }

    @Test
    void stepsRunInOrderWaitingForTheirEvents() {
        Arena.Input d = darius();
        // pull, then attack once the pull is over (control left 0), then W (empowered attack), then attack
        d.script = combo("puxar e bater", new ComboStep("habilidade:3"), new ComboStep("atacar", "controle_restante", 0),
                new ComboStep("habilidade:2"), new ComboStep("atacar", "ataque_pronto", 0));
        Result r = bench(d, dummy(50000), 8);
        assertEquals(1, r.combos.size());
        ComboReport rep = r.combos.get(0);
        assertEquals("done", rep.outcome, rep.reason);
        assertEquals(4, rep.steps.size());
        assertEquals(1, r.fighters.get(0).events.getOrDefault("combo_concluido:puxar e bater", 0.0), 1e-9);
        assertTrue(r.fighters.get(0).events.getOrDefault("habilidade_acertou:3", 0.0) >= 1);
        // the steps happen in order, and the attack after the pull waited for it to end
        for (int i = 1; i < rep.steps.size(); i++) {
            assertTrue(rep.steps.get(i).t >= rep.steps.get(i - 1).t);
        }
        assertTrue(rep.steps.get(1).t > rep.steps.get(0).t, "the attack waited for the pull");
    }

    @Test
    void abortsWhenTheTargetDiesTheTimeIsUpOrAStepCannotBeDone() {
        Arena.Input d = darius();
        d.script = combo("até a morte", new ComboStep("habilidade:3"), new ComboStep("esperar", "tempo", 5),
                new ComboStep("atacar"));
        d.script.maxTime = 1;   // the wait (at most its max of 2s) outlasts it
        Result timeUp = bench(d, dummy(50000), 6);
        assertEquals("aborted", timeUp.combos.get(0).outcome);
        assertEquals("tempo esgotado", timeUp.combos.get(0).reason);

        Arena.Input d2 = darius();
        d2.f.ranks = new int[] {4, 3, 0, 2};   // no E learned
        d2.script = combo("sem E", new ComboStep("habilidade:3"));
        Result impossible = bench(d2, dummy(50000), 3);
        assertEquals("passo impossível: habilidade:3", impossible.combos.get(0).reason);

        Arena.Input d3 = darius();
        ComboStep wait = new ComboStep("esperar", "tempo", 3);
        wait.max = 4;   // the bleed kills it meanwhile
        d3.script = combo("até a morte", new ComboStep("habilidade:3"), new ComboStep("atacar"), wait, new ComboStep("atacar"));
        Result died = bench(d3, dummy(60), 6);   // the first attack kills it
        assertEquals("aborted", died.combos.get(0).outcome, died.combos.get(0).steps.stream().map(e -> e.t + " " + e.text)
                .collect(java.util.stream.Collectors.joining("; ")) + " | death " + died.fighters.get(1).deathTime);
        assertEquals("alvo morreu", died.combos.get(0).reason);
    }

    @Test
    void abortConditionsStopIt() {
        Arena.Input d = darius();
        d.script = combo("cuidado", new ComboStep("habilidade:3"), new ComboStep("esperar", "tempo", 2), new ComboStep("atacar"));
        d.script.abort.add(new Condition("alvo.lentidao_pct", ">", 0.3));   // Darius' E slows by 40%
        Result r = bench(d, dummy(50000), 4);
        assertEquals("aborted", r.combos.get(0).outcome);
        assertTrue(r.combos.get(0).reason.startsWith("abortar_se alvo.lentidao_pct"), r.combos.get(0).reason);
    }

    /** Picks combo 1 when it may, else idles; records what was valid while the combo ran. */
    private static final class ComboPolicy implements Policy {
        boolean sawOnlyContinue = true;
        boolean ran;

        @Override
        public Decision decide(Observation obs, ActionMask mask) {
            if (mask.valid(ActionSpace.combo(0)) && ran) {
                // running: attacking and casting are not valid, only going on (and dodge / Flash away)
                sawOnlyContinue &= !mask.valid(ActionSpace.attack(0)) && !mask.valid(ActionSpace.cast(0, 0));
            }
            if (mask.valid(ActionSpace.combo(0))) {
                ran = true;
                return Decision.of(ActionSpace.combo(0));
            }
            return Decision.of(ActionSpace.IDLE);
        }
    }

    @Test
    void aPolicyRunsAComboAsOneActionAndCannotCastMeanwhile() {
        Arena.Input d = darius();
        d.combos = List.of(combo("puxar e bater", new ComboStep("habilidade:3"), new ComboStep("atacar", "controle_restante", 0),
                new ComboStep("habilidade:2"), new ComboStep("atacar", "ataque_pronto", 0)));
        ComboPolicy p = new ComboPolicy();
        d.policy = p;
        Arena.Input o = dummy(50000);
        o.autoAbilities = false;
        o.policy = (ob, m) -> Policy.Decision.of(ActionSpace.IDLE);
        Result r = new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).run(List.of(d, o), List.of(), 4);
        assertTrue(p.ran);
        assertTrue(p.sawOnlyContinue);
        assertEquals("done", r.combos.get(0).outcome, r.combos.get(0).reason);
        // the casts of the combo come back as orders of the timeline
        assertTrue(r.policyOrders.stream().anyMatch(a -> "3".equals(a.slot)));
    }

    @Test
    void startConditionsGateTheAction() {
        Arena.Input d = darius();
        Combo c = combo("só no ar", new ComboStep("habilidade:3"));
        c.start.add(new Condition("alvo.no_ar_restante", ">", 0));   // never: nobody knocks the dummy up
        d.combos = List.of(c);
        ComboPolicy p = new ComboPolicy();
        d.policy = p;
        Arena.Input o = dummy(50000);
        o.autoAbilities = false;
        o.policy = (ob, m) -> Policy.Decision.of(ActionSpace.IDLE);
        Result r = new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).run(List.of(d, o), List.of(), 3);
        assertFalse(p.ran);
        assertTrue(r.combos.isEmpty());
    }

    @Test
    void libraryKeepsTheBestActiveCombosForAPolicy(@TempDir Path dir) {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("t.db"));
        ds.setDriverClassName("org.sqlite.JDBC");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        ComboRepository repo = new ComboRepository(new JdbcTemplate(ds));
        for (int i = 0; i < 6; i++) {
            Combo c = combo("c" + i, new ComboStep("habilidade:3"), new ComboStep("atacar", "alvo_no_chao", 0.1 * i));
            c.score = (double) i;
            c.origin = i % 2 == 0 ? "descoberto" : "manual";
            repo.save(c);
        }
        List<Combo> all = repo.list("darius");
        assertEquals(6, all.size());
        assertEquals("c5", all.get(0).name);
        assertEquals(0.4, all.get(1).steps.get(1).delta, 1e-9);
        repo.setActive(all.get(0).id, false);
        List<Combo> forAi = repo.forPolicy("Darius", null);
        assertEquals(List.of("c4", "c3", "c2", "c1"), forAi.stream().map(x -> x.name).collect(java.util.stream.Collectors.toList()));
        assertEquals(List.of("c4", "c2", "c0"), repo.forPolicy("Darius", List.of("descoberto")).stream().map(x -> x.name)
                .collect(java.util.stream.Collectors.toList()));
        assertTrue(repo.delete(all.get(0).id));
        assertEquals(5, repo.list(null).size());
    }
}
