package com.ornnplanner.engine;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel.Action;
import com.ornnplanner.arena.ArenaModel.Frame;
import com.ornnplanner.arena.ArenaModel.Result;
import com.ornnplanner.arena.ai.ActionSpace;
import com.ornnplanner.arena.ai.Policy;
import com.ornnplanner.arena.fight.SheetValidator;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One synthetic sheet per mechanic of the other champions (test resource {@code arena/fichas-sinteticas.yml}). */
class MecanicasTest {

    private static final Map<String, Object> SHEETS;
    /** Stands where it is: only the orders of the test move it. */
    private static final Policy STILL = (o, m) -> Policy.Decision.of(ActionSpace.IDLE);

    static {
        try (InputStream in = new ClassPathResource("arena/fichas-sinteticas.yml").getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) root.get("fichas");
            SHEETS = m;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The caster at x = 3000, with a sheet; the other one, with no abilities, at a distance. */
    @SuppressWarnings("unchecked")
    private static List<Arena.Input> pair(String sheet, double gap) {
        Arena.Input a = ArenaScenarios.champion("Darius", 0, 3000, 0, 3000, 100, 80);
        a.kit = (Map<String, Object>) SHEETS.get(sheet);
        a.forms = new LinkedHashMap<>();
        a.f.ranks = new int[] {1, 1, 1, 1};
        a.autoAbilities = false;
        Arena.Input b = ArenaScenarios.champion("Ornn", 1, 3000 + gap, 0, 3000, 100, 80);
        b.f.ranks = new int[4];
        b.autoAbilities = false;
        a.policy = STILL;
        b.policy = STILL;
        return new ArrayList<>(List.of(a, b));
    }

    /** The same, with the other one chasing and attacking on its own. */
    private static List<Arena.Input> attacked(String sheet, double gap) {
        List<Arena.Input> in = pair(sheet, gap);
        in.get(1).policy = null;
        return in;
    }

    private static Result fight(List<Arena.Input> in, double seconds, Action... orders) {
        return new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).run(in, List.of(orders), seconds);
    }

    private static Action cast(double t) {
        return ArenaScenarios.order(0, t, "ability", "1", 1);
    }

    private static Action recast(double t) {
        return ArenaScenarios.order(0, t, "recast", "1", 1);
    }

    private static Action move(int who, double t, double x, double y) {
        Action a = ArenaScenarios.order(who, t, "move", null, null);
        a.x = x;
        a.y = y;
        return a;
    }

    private static Frame at(Result r, double t) {
        return r.frames.stream().filter(f -> f.t >= t - 1e-6).findFirst().orElse(r.frames.get(r.frames.size() - 1));
    }

    private static double x(Result r, int who, double t) {
        return at(r, t).units.get(who).x;
    }

    private static boolean marked(Result r, int who, double t, String mark) {
        return at(r, t).units.get(who).marks.contains(mark);
    }

    private static double ev(Result r, int who, String key) {
        return r.fighters.get(who).events.getOrDefault(key, 0.0);
    }

    // ------------------------------------------------------------------ crowd control

    @Test
    void aRootStopsMovingButNotAttacking() {
        Result r = fight(pair("enraizar", 150), 2, cast(0.1), move(1, 0.2, 5000, 0));
        assertEquals(x(r, 1, 0.5), x(r, 1, 1.8), 1e-6, "rooted");
        assertTrue(marked(r, 1, 1, "Enraizado"));
        assertTrue(fight(attacked("enraizar", 150), 2, cast(0.1)).fighters.get(1).dealt > 0, "it still attacks");
    }

    @Test
    void aRootKeepsItFromDashing() {
        List<Arena.Input> in = pair("enraizar", 150);
        Map<String, Object> dash = new LinkedHashMap<>();
        dash.put("nome", "Fuga");
        dash.put("forma", "avanco");
        dash.put("recarga", 5);
        dash.put("alcance", 500);
        dash.put("velocidade", 1000);
        dash.put("sem_dano", true);
        in.get(1).kit = Map.of("raio", 80, "alcance", 175, "habilidades", Map.of("1", dash));
        in.get(1).f.ranks = new int[] {1, 1, 1, 1};
        Result r = fight(in, 2, cast(0.1), ArenaScenarios.order(1, 0.3, "ability", "1", 0));
        assertEquals(3150, x(r, 1, 1.5), 1e-6);
    }

    @Test
    void silenceStopsCastsNotMovesOrAttacks() {
        List<Arena.Input> in = attacked("silencio", 150);
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("nome", "Golpe");
        hit.put("forma", "alvo");
        hit.put("recarga", 1);
        hit.put("alcance", 900);
        hit.put("dano", Map.of("tipo", "verdadeiro", "base", 100));
        in.get(1).kit = Map.of("raio", 80, "alcance", 175, "habilidades", Map.of("1", hit));
        in.get(1).f.ranks = new int[] {1, 1, 1, 1};
        Result r = fight(in, 4, cast(0.1), ArenaScenarios.order(1, 0.5, "ability", "1", 0));
        assertTrue(r.fighters.get(1).uses.stream().noneMatch(u -> u.t < 3), "no cast before the silence ends");
        assertTrue(r.fighters.get(1).uses.stream().anyMatch(u -> u.t >= 3), "it casts after it");
        assertTrue(r.fighters.get(1).damageBySource.containsKey("Ataque"), "it attacks");
    }

    @Test
    void blindAttacksMiss() {
        Result r = fight(attacked("cegueira", 150), 2.5, cast(0.1));
        assertTrue(ev(r, 1, "ataque_errou") >= 1);
        assertFalse(r.fighters.get(1).damageBySource.containsKey("Ataque"));
    }

    @Test
    void aTauntedTargetWalksToTheCasterAndAttacksIt() {
        Result r = fight(pair("provocar", 500), 2.3, cast(0.1), move(1, 0.05, 6000, 0));
        assertTrue(x(r, 1, 1) < x(r, 1, 0.2), "it walks back");
        assertTrue(r.fighters.get(1).dealt > 0, "and attacks when it arrives");
    }

    @Test
    void aFearedTargetRunsAway() {
        Result r = fight(attacked("medo", 400), 2.3, cast(0.1));
        assertTrue(x(r, 1, 2) > x(r, 1, 0.3) + 400);
        assertEquals(0, r.fighters.get(1).dealt, 1e-6);
    }

    @Test
    void aCharmedTargetWalksToTheCasterWithoutAttacking() {
        Result r = fight(pair("encanto", 700), 2.3, cast(0.1));
        assertTrue(x(r, 1, 2) < x(r, 1, 0.3) - 300);
        assertFalse(r.fighters.get(1).damageBySource.containsKey("Ataque"));
    }

    @Test
    void sleepEndsWithDamage() {
        Result quiet = fight(pair("sono", 900), 4, cast(0.1), move(1, 0.3, 5000, 0));
        assertEquals(3900, x(quiet, 1, 3), 1e-6, "asleep");
        List<Arena.Input> in = pair("sono", 150);
        in.get(0).policy = null;
        Result hit = fight(in, 4, cast(0.1));
        assertFalse(marked(hit, 1, 2, "Adormecido"), "the attacks wake it up");
    }

    @Test
    void tenacityCountsByControl() {
        List<Arena.Input> root = pair("enraizar", 900);
        root.get(1).f.total.put("% Tenacity", 50.0);
        assertFalse(marked(fight(root, 3, cast(0.1)), 1, 2, "Enraizado"), "3 s by half");
        List<Arena.Input> suppress = pair("supressao", 900);
        suppress.get(1).f.total.put("% Tenacity", 50.0);
        assertTrue(marked(fight(suppress, 3, cast(0.1)), 1, 1.8, "Suprimido"), "suppression ignores tenacity");
    }

    @Test
    void healthRegenUsesTheTotalOnce() {
        // the total already has the items' % (base x (1 + %)): 100 per 5s with 250% must heal 20 per second, not 70
        List<Arena.Input> in = pair("enraizar", 2000);
        Arena.Input b = in.get(1);
        b.startHealth = 0.5;
        b.f.total.put(Stats.HEALTH_REGEN, 100.0);
        b.f.total.put(Stats.PCT_HEALTH_REGEN, 250.0);
        Result r = fight(in, 10);
        assertEquals(200, r.fighters.get(1).healed, 15);
    }

    @Test
    void cleanseFollowsTheTable() {
        List<Arena.Input> root = pair("enraizar", 900);
        root.get(1).f.spells.add("Purificar");
        root.get(1).activeCooldowns.put("Purificar", 90.0);
        assertFalse(marked(fight(root, 2, cast(0.1), ArenaScenarios.named(1, 0.5, "spell", "Purificar")), 1, 1, "Enraizado"));
        List<Arena.Input> suppress = pair("supressao", 900);
        suppress.get(1).f.spells.add("Purificar");
        suppress.get(1).activeCooldowns.put("Purificar", 90.0);
        assertTrue(marked(fight(suppress, 2, cast(0.1), ArenaScenarios.named(1, 0.5, "spell", "Purificar")), 1, 1, "Suprimido"));
    }

    @Test
    void polymorphTakesAttacksAndAbilitiesAndSlows() {
        Result r = fight(attacked("polimorfo", 150), 2, cast(0.1));
        assertEquals(0, r.fighters.get(1).dealt, 1e-6);
        assertTrue(marked(r, 1, 1, "Polimorfado"));
        assertTrue(marked(r, 1, 1, "Lentidão 60%"));
    }

    @Test
    void aWallStopsAPushAndStuns() {
        List<Arena.Input> in = pair("empurrao_parede", 200);
        in.get(0).y = -100;
        in.get(1).y = 400;
        in.get(1).x = 3000;
        Result r = fight(in, 2, cast(0.1));
        assertTrue(at(r, 1).units.get(1).y >= 400, "pushed towards the wall");
        assertTrue("stun".equals(at(r, 1).units.get(1).state), "stunned at the wall: " + at(r, 1).units.get(1).state);
    }

    // ------------------------------------------------------------------ states

    @Test
    void invisibilityEndsUpCloseOrWithDamage() {
        Result far = fight(pair("invisivel", 900), 2, cast(0.1));
        assertTrue(at(far, 1).units.get(0).invisible);
        Result near = fight(pair("invisivel", 150), 2, cast(0.1));
        assertFalse(at(near, 1).units.get(0).invisible, "next to an enemy");
    }

    @Test
    void anInvulnerableChampionTakesNothingAndCanStillBeAimedAt() {
        Result r = fight(attacked("invulneravel", 150), 1.8, cast(0.1));
        assertEquals(0, r.fighters.get(0).taken, 1e-6);
        assertTrue(ev(r, 0, "dano_anulado") >= 1);
    }

    @Test
    void anUntargetableChampionIsNotAttacked() {
        Result r = fight(attacked("nao_selecionavel", 150), 1.8, cast(0.1));
        assertEquals(0, r.fighters.get(0).taken, 1e-6);
        assertEquals(0, ev(r, 0, "dano_anulado"));
    }

    @Test
    void anUnstoppableChampionIsNotControlled() {
        List<Arena.Input> in = pair("imparavel", 150);
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) SHEETS.get("enraizar");
        in.get(1).kit = root;
        in.get(1).f.ranks = new int[] {1, 1, 1, 1};
        Result r = fight(in, 2, cast(0.1), ArenaScenarios.order(1, 0.5, "ability", "1", 0));
        assertFalse(marked(r, 0, 1, "Enraizado"));
    }

    // ------------------------------------------------------------------ movement

    @Test
    void blinkIsInstantAndCrossesTerrain() {
        List<Arena.Input> in = pair("piscar", 900);
        Result r = fight(in, 0.5, cast(0.1));
        assertEquals(3400, x(r, 0, 0.2), 1e-6);
    }

    @Test
    void aTeleportArrivesAfterItsChannelAndAControlCancelsIt() {
        Action there = cast(0.1);
        there.target = null;
        there.x = 7000.0;
        there.y = 0.0;
        Result r = fight(pair("teleporte", 900), 2, there);
        assertEquals(3000, x(r, 0, 0.9), 1e-6);
        assertEquals(7000, x(r, 0, 1.3), 1e-6);
    }

    @Test
    void aDashAfterATargetReachesItAndADashToAPointStops() {
        Result chase = fight(pair("avanco_alvo", 800), 1.5, cast(0.1), move(1, 0.05, 3900, 0));
        assertEquals(100, chase.fighters.get(0).damageBySource.get("1 · Investida"), 1e-6);
        Action point = cast(0.1);
        point.target = null;
        point.x = 3300.0;
        point.y = 0.0;
        Result stop = fight(pair("avanco_ponto", 2000), 1.2, point);
        assertEquals(3300, x(stop, 0, 1), 1);
    }

    @Test
    void aLeapCrossesAWallAndADashDoesNot() {
        List<Arena.Input> in = pair("salto", 2000);
        in.add(wallCaster());
        Action wall = ArenaScenarios.order(2, 0.05, "ability", "1", null);
        wall.x = 3400.0;
        wall.y = 0.0;
        Result leap = fight(in, 2, wall, cast(0.3));
        assertTrue(x(leap, 0, 1.5) > 3700, "it landed past the wall: " + x(leap, 0, 1.5));
    }

    /** A second fighter on the side that raises the wall in front of the caster. */
    @SuppressWarnings("unchecked")
    private static Arena.Input wallCaster() {
        Arena.Input w = ArenaScenarios.champion("Darius", 0, 3100, 0, 3000, 0, 80);
        w.kit = (Map<String, Object>) SHEETS.get("parede");
        w.forms = new LinkedHashMap<>();
        w.f.ranks = new int[] {1, 1, 1, 1};
        w.autoAbilities = false;
        return w;
    }

    @Test
    void aWallBlocksTheWay() {
        List<Arena.Input> in = pair("parede", 2000);
        in.get(1).x = 4000;
        Action wall = cast(0.1);
        wall.target = null;
        wall.x = 3300.0;
        wall.y = 0.0;
        Result r = fight(in, 3, wall, move(1, 0.2, 2000, 0));
        assertTrue(x(r, 1, 2.8) > 3300, "stopped at the wall: " + x(r, 1, 2.8));
    }

    // ------------------------------------------------------------------ casting

    @Test
    void aControlBreaksAChannelButNotAnUnstoppableOne() {
        Result free = fight(pair("canalizar", 150), 2.5, cast(0.1));
        assertEquals(400, free.fighters.get(0).damageBySource.get("1 · Rajada"), 1e-6);
        List<Arena.Input> in = pair("canalizar", 150);
        @SuppressWarnings("unchecked")
        Map<String, Object> stun = (Map<String, Object>) SHEETS.get("supressao");
        in.get(1).kit = stun;
        in.get(1).f.ranks = new int[] {1, 1, 1, 1};
        Result broken = fight(in, 2.5, cast(0.1), ArenaScenarios.order(1, 0.8, "ability", "1", 0));
        assertEquals(100, broken.fighters.get(0).damageBySource.get("1 · Rajada"), 1e-6);
        assertEquals(1, ev(broken, 0, "canal_interrompido:1"));
    }

    @Test
    void holdingAChargeReachesFartherAndHitsHarder() {
        Action quick = cast(0.1);
        quick.charge = 0.0;
        assertEquals(0, fight(pair("carregar", 1200), 2, quick).fighters.get(0).dealt, 1e-6, "out of the short range");
        Action full = cast(0.1);
        full.charge = 2.0;
        assertEquals(200, fight(pair("carregar", 1200), 4, full).fighters.get(0).dealt, 1e-6);
    }

    @Test
    void recastsUseTheirVersionsInOrder() {
        Result r = fight(pair("reconjuracao", 300), 3, cast(0.1), recast(0.5), recast(1));
        assertEquals(10, r.fighters.get(0).damageBySource.get("1 · Três Golpes"), 1e-6);
        assertEquals(20, r.fighters.get(0).damageBySource.get("1 · Segundo Golpe"), 1e-6);
        assertEquals(40, r.fighters.get(0).damageBySource.get("1 · Terceiro Golpe"), 1e-6);
    }

    @Test
    void aFormChangesAbilitiesAndStats() {
        Result r = fight(pair("formas", 300), 3, cast(0.1), cast(1.5));
        assertEquals(1, ev(r, 0, "forma:fera"));
        assertEquals(50, r.fighters.get(0).damageBySource.get("1 · Mordida"), 1e-6);
    }

    @Test
    void aFormFollowsItsResource() {
        Result r = fight(pair("forma_por_recurso", 900), 1, cast(0.1));
        assertEquals(1, ev(r, 0, "forma:base"), "spending drops it below the threshold");
    }

    // ------------------------------------------------------------------ summons

    @Test
    void aSummonFollowsAndAttacksAndItsDamageCountsForTheOwner() {
        Result r = fight(pair("invocacao_segue", 600), 6, cast(0.1));
        assertEquals(1, ev(r, 0, "invocou:Mascote"));
        assertTrue(r.fighters.get(0).damageBySource.keySet().stream().anyMatch(k -> k.startsWith("Invocação · Mascote")));
        assertEquals(2, r.fighters.size(), "summons are not fighters");
    }

    @Test
    void aMineGoesOffNearAnEnemyOnlyOnceArmed() {
        Action mine = cast(0.1);
        mine.target = null;
        mine.x = 3500.0;
        mine.y = 0.0;
        Result r = fight(pair("invocacao_mina", 1000), 4, mine, move(1, 0.2, 2000, 0));
        assertEquals(1, ev(r, 0, "explodiu:Mina"));
        assertEquals(200, r.fighters.get(0).damageBySource.get("Invocação · Mina"), 1e-6);
    }

    @Test
    void aBombRunsAtItsTarget() {
        Result r = fight(pair("invocacao_explode", 800), 3, cast(0.1));
        assertEquals(300, r.fighters.get(0).damageBySource.get("Invocação · Bomba"), 1e-6);
    }

    @Test
    void aCloneFightsWithAShareOfItsOwner() {
        Result r = fight(pair("invocacao_clone", 300), 5, cast(0.1));
        assertTrue(r.fighters.get(0).damageBySource.keySet().stream().anyMatch(k -> k.contains("Clone")));
    }

    // ------------------------------------------------------------------ others

    @Test
    void attachedItStaysOnTheAllyAndCannotBeAimedAt() {
        List<Arena.Input> in = pair("anexar", 2000);
        Arena.Input ally = ArenaScenarios.champion("Darius", 0, 3200, 0, 3000, 0, 80);
        ally.f.ranks = new int[4];
        ally.autoAbilities = false;
        in.add(ally);
        Result r = fight(in, 2, cast(0.1), move(2, 0.2, 2000, 0));
        assertEquals(1, ev(r, 0, "anexado"));
        assertEquals(x(r, 2, 1.5), x(r, 0, 1.5), 1e-6);
    }

    @Test
    void goingBackRestoresPlaceAndHealth() {
        Result r = fight(attacked("volta_no_tempo", 150), 3, move(0, 0.1, 3100, 0), cast(2.5));
        assertEquals(1, ev(r, 0, "voltou_no_tempo"));
        assertTrue(at(r, 2.6).units.get(0).hp > at(r, 2.4).units.get(0).hp, "the health came back");
    }

    @Test
    void aGlobalAbilityReachesAcrossTheMap() {
        Result r = fight(pair("global", 6500), 1, cast(0.1));
        assertEquals(100, r.fighters.get(0).damageBySource.get("1 · Barragem"), 1e-6);
    }

    @Test
    void stealingAStatTakesFromOneAndGivesToTheOther() {
        Result r = fight(attacked("roubo_status", 150), 3, cast(0.1));
        assertEquals(1, ev(r, 0, "status_roubado"));
        Result none = fight(attacked("roubo_status", 150), 3);
        assertTrue(r.fighters.get(1).dealt < none.fighters.get(1).dealt, r.fighters.get(1).dealt + " < " + none.fighters.get(1).dealt);
    }

    @Test
    void anExecutionKillsOnlyBelowItsThreshold() {
        List<Arena.Input> low = pair("execucao", 150);
        low.get(1).startHealth = 0.4;
        assertEquals(1, ev(fight(low, 1, cast(0.1)), 0, "execucao"));
        assertEquals(0, ev(fight(pair("execucao", 150), 1, cast(0.1)), 0, "execucao"));
    }

    @Test
    void aBuffAbilityReachesTheAllyWithLeastHealth() {
        List<Arena.Input> in = pair("escudo_aliado", 2000);
        Arena.Input ally = ArenaScenarios.champion("Darius", 0, 3200, 0, 3000, 0, 80);
        ally.f.ranks = new int[4];
        ally.autoAbilities = false;
        ally.startHealth = 0.5;
        in.add(ally);
        Result r = fight(in, 1, cast(0.1));
        assertEquals(300, at(r, 0.5).units.get(2).shield, 1e-6);
        assertEquals(0, at(r, 0.5).units.get(0).shield, 1e-6);
    }

    @Test
    void resourcesPayForCasts() {
        assertEquals(2, fight(pair("mana", 300), 3.6, cast(0.1), cast(0.5), cast(0.9), cast(1.3)).fighters.get(0).uses.size(),
                "60 of 100 Mana, 10 per second: the second cast waits for it, the third never comes");
        assertEquals(2, fight(pair("energia", 300), 3, cast(0.1), cast(0.5), cast(0.9)).fighters.get(0).uses.size());
        assertEquals(3, fight(pair("sem_custo", 300), 3, cast(0.1), cast(0.5), cast(0.9)).fighters.get(0).uses.size());
    }

    // ------------------------------------------------------------------ the sheets

    @Test
    void mechanicsAreDeterministic() {
        for (String sheet : SHEETS.keySet()) {
            String a = ArenaScenarios.fingerprint(fight(pair(sheet, 300), 4, cast(0.1), recast(1)));
            String b = ArenaScenarios.fingerprint(fight(pair(sheet, 300), 4, cast(0.1), recast(1)));
            assertEquals(a, b, sheet);
        }
    }

    // ------------------------------------------------------------------ Rodada das fichas, lote 1

    private static double dealt(Result r, int who) {
        return r.fighters.get(who).dealt;
    }

    @Test
    void damageOnTheTargetsHealth() {
        List<Arena.Input> in = pair("vida_perdida_alvo", 300);
        in.get(1).startHealth = 0.5;
        Result r = fight(in, 1, cast(0.1));
        double max = at(r, 0.5).units.get(1).maxHp;
        assertEquals(10 + 0.5 * (max - Math.max(1, max * 0.5)), dealt(r, 0), 1, "lost health");
        Result full = fight(pair("vida_perdida_alvo", 300), 1, cast(0.1));
        assertEquals(10, dealt(full, 0), 1e-6, "nothing lost, only the base");
        Result now = fight(pair("vida_atual_alvo", 300), 1, cast(0.1));
        assertEquals(10 + 0.1 * at(now, 0.5).units.get(1).maxHp, dealt(now, 0), 1, "current health");
        Result bonus = fight(pair("vida_bonus_alvo", 300), 1, cast(0.1));
        assertTrue(dealt(bonus, 0) >= 10, "bonus health: base plus half of what is above the base");
    }

    @Test
    void aHitGivesBackPartOfTheCooldown() {
        Result r = fight(pair("devolve_recarga", 300), 8, cast(0.1), cast(5.3));
        assertEquals(2.0, ev(r, 0, "recarga_devolvida:1"), 1e-6, "once per cast (two casts)");
        assertEquals(2, r.fighters.get(0).uses.size(), "ready again in half the time");
        Result plain = fight(pair("vida_atual_alvo", 300), 8, cast(0.1), cast(5.3));
        assertEquals(1, plain.fighters.get(0).uses.size(), "without it, still on cooldown");
    }

    @Test
    void aMarkedTargetResetsTheCooldown() {
        Action mark = ArenaScenarios.order(0, 0.1, "ability", "2", 1);
        Result r = fight(pair("zera_recarga_marca", 300), 2, mark, cast(0.5), cast(0.8), cast(1.1));
        assertEquals(1.0, ev(r, 0, "recarga_zerada:1"), 1e-6, "the hit on the marked target resets, once");
        assertEquals(1.0, ev(r, 0, "marca_consumida:marca"), 1e-6, "the mark is used up");
        assertEquals(3, r.fighters.get(0).uses.size(), "mark, the hit that resets, one more hit; the last waits");
    }

    @Test
    void storedDamageBecomesAShield() {
        Result r = fight(attacked("armazena", 150), 4, cast(2.5));
        double shieldAt = at(r, 2.55).units.get(0).shield;
        Result early = fight(attacked("armazena", 150), 4, cast(0.05));
        assertTrue(shieldAt > at(early, 0.1).units.get(0).shield + 20, "the damage taken became shield: " + shieldAt);
    }

    @Test
    void aLineBetweenTheBladeAndTheCaster() {
        Action aim = cast(0.1);
        aim.x = 3600.0;
        aim.y = 0.0;
        Result r = fight(pair("linha_entre_pontos", 300), 2, aim, recast(0.5));
        assertEquals(50, dealt(r, 0), 1e-6, "the target stands on the segment");
        assertEquals("stun", at(r, 0.8).units.get(1).state, "and gets the effects");
        Action off = cast(0.1);
        off.x = 3000.0;
        off.y = 600.0;
        assertEquals(0, dealt(fight(pair("linha_entre_pontos", 300), 2, off, recast(0.5)), 0), 1e-6, "off the segment");
    }

    @Test
    void permanentStacksByTheMinuteOrTheScenario() {
        List<Arena.Input> in = pair("acumulos_permanentes", 300);
        in.get(0).permanentStacks = 50.0;
        Result r = fight(in, 1, cast(0.1));
        assertEquals(60, dealt(r, 0), 1e-6, "base plus one per stack");
        List<Arena.Input> byMinute = pair("acumulos_permanentes", 300);
        byMinute.get(0).f.minute = 12;
        assertEquals(10 + 120, dealt(fight(byMinute, 1, cast(0.1)), 0), 1e-6, "10 per minute");
        List<Arena.Input> late = pair("acumulos_permanentes", 300);
        late.get(0).f.minute = 40;
        Result capped = fight(late, 1, cast(0.1));
        assertEquals(10 + 300, dealt(capped, 0), 1e-6, "up to the max");
        List<Arena.Input> early = pair("acumulos_permanentes", 300);
        early.get(0).f.minute = 0;
        Result none = fight(early, 1, cast(0.1));
        assertEquals(at(capped, 0.5).units.get(0).maxHp - 600, at(none, 0.5).units.get(0).maxHp, 1e-6, "2 health per stack");
    }

    @Test
    void camouflageIsSeenOnlyUpClose() {
        Result r = fight(pair("camuflagem", 600), 4, cast(0.1), move(1, 1, 3150, 0), move(1, 2.5, 3900, 0));
        assertTrue(at(r, 0.5).units.get(0).invisible, "far: hidden");
        assertFalse(at(r, 2).units.get(0).invisible, "within the detection radius: seen");
        assertTrue(at(r, 3.9).units.get(0).invisible, "the camouflage stayed on");
    }

    @Test
    void damageReductionOfASheet() {
        Result with = fight(attacked("reducao_dano", 150), 3, cast(0.05));
        Result without = fight(attacked("reducao_dano", 150), 3);
        double a = with.fighters.get(0).taken;
        double b = without.fighters.get(0).taken;
        assertEquals(b * 0.5, a, b * 0.05, "half the damage");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRealAndSyntheticSheetsPassTheValidator() {
        List<String> problems = new ArrayList<>();
        ArenaScenarios.KITS.forEach((name, kit) -> problems.addAll(SheetValidator.check(name, (Map<String, Object>) kit)));
        SHEETS.forEach((name, kit) -> problems.addAll(SheetValidator.check(name, (Map<String, Object>) kit)));
        assertEquals(List.of(), problems);
    }

    @Test
    void theValidatorFindsWhatIsWrong() {
        Map<String, Object> ab = new LinkedHashMap<>();
        ab.put("nome", "Ruim");
        ab.put("forma", "zonas");
        ab.put("recarga", 900);
        ab.put("campo_novo", 1);
        ab.put("zonas", List.of(Map.of("forma", "anel", "raio", 100, "raio_interno", 200, "efeitos", Map.of("lentidao", 40))));
        Map<String, Object> nothing = new LinkedHashMap<>();
        nothing.put("nome", "Nada");
        nothing.put("forma", "alvo");
        Map<String, Object> kit = Map.of("raio", 80, "habilidades", Map.of("1", ab, "2", nothing));
        List<String> out = SheetValidator.check("Teste", kit);
        String all = String.join("\n", out);
        assertTrue(all.contains("campo desconhecido \"campo_novo\""), all);
        assertTrue(all.contains("recarga estranha"), all);
        assertTrue(all.contains("raio_interno maior"), all);
        assertTrue(all.contains("lentidao é fração"), all);
        assertTrue(all.contains("não faz nada"), all);
    }

    /** What can be seen of a fight: places, health, shields, marks and states, without the cooldowns. */
    private static String seen(Result r) {
        StringBuilder sb = new StringBuilder();
        for (Frame f : r.frames) {
            f.units.forEach(u -> sb.append(u.x).append(u.y).append(u.hp).append(u.shield).append(u.marks).append(u.invisible).append(u.state));
            sb.append(f.shapes.size());
        }
        r.fighters.forEach(x -> sb.append(x.damageBySource));
        return sb.toString();
    }

    /** Every synthetic sheet changes a real fight (the check the validator cannot do from the sheet alone). */
    @Test
    void everySheetDoesSomethingInAFight() {
        for (String sheet : SHEETS.keySet()) {
            Action aim = cast(0.1);
            aim.x = 3250.0;
            aim.y = 0.0;
            assertFalse(seen(fight(attacked(sheet, 300), 3)).equals(seen(fight(attacked(sheet, 300), 3, aim))), sheet + " did nothing");
        }
    }
}
