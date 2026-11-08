package com.ornnplanner.engine;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel.Action;
import com.ornnplanner.arena.ArenaModel.Frame;
import com.ornnplanner.arena.ArenaModel.Result;
import com.ornnplanner.arena.ai.ActionSpace;
import com.ornnplanner.arena.ai.Policy;
import com.ornnplanner.arena.ai.TacticalPolicy;
import com.ornnplanner.arena.fight.AbilityParser;
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

/**
 * The sheets of the 16 toplaners of the first batch (docs/fichas/lote-1.md): each ability does something against a
 * ragdoll, its damage at the top rank matches the game's text (by table, with the known differences), and a 30 s fight
 * of the tactical brain against Darius runs.
 */
class FichasLote1Test {

    static final List<String> LOTE = List.of("Mordekaiser", "Garen", "Sett", "Jax", "Fiora", "Camille", "Irelia", "Riven", "Aatrox",
            "Malphite", "Nasus", "Gwen", "Kayle", "Teemo", "Shen", "Sion");
    private static final List<String> SLOTS = List.of("1", "2", "3", "R");
    private static final Policy STILL = (o, m) -> Policy.Decision.of(ActionSpace.IDLE);
    private static final double TARGET_HP = 400000;

    /**
     * Sheet damage of one cast / text damage (the parser reads the first damage of the text), when it is not 1: the
     * sheet sums what the text gives per hit, or keeps a different part on purpose. Each one is in the batch's report.
     */
    static final Map<String, Double> FACTOR = new LinkedHashMap<>();
    /** Abilities whose damage is not compared, and why (also in the report). */
    static final Map<String, String> SKIP = new LinkedHashMap<>();

    static {
        FACTOR.put("Sett 1", 2.0);          // two empowered attacks
        FACTOR.put("Camille 1", 2.0);       // two empowered attacks (the text: the 2nd is 40% true damage)
        FACTOR.put("Irelia 2", 2.0);        // full charge: +100%
        SKIP.put("Mordekaiser 1", "o texto soma a escala por nível ao valor do nível 1 (80 + (4–70)); a ficha usa base_nivel");
        SKIP.put("Mordekaiser R", "sem dano: rouba atributos");
        SKIP.put("Garen 3", "o texto dá o dano por golpe (8 golpes); a ficha soma 10");
        SKIP.put("Garen R", "o texto lê só a parte fixa; a ficha soma 15% da Vida perdida (o boneco está cheio: 0)");
        SKIP.put("Sett 2", "a ficha soma 25% da Ousadia (sem dano sofrido: 0) e divide centro e lados");
        SKIP.put("Jax 3", "o texto soma 3,5% da Vida máxima do alvo como {Vida}");
        SKIP.put("Fiora 3", "sem dano no texto: o crítico do 2º ataque, repartido");
        SKIP.put("Fiora R", "o dano dos 4 Pontos Vitais");
        SKIP.put("Riven 1", "três golpes por reconjuração");
        SKIP.put("Riven 3", "escudo, sem dano");
        SKIP.put("Riven R", "o Golpe de Vento é a reconjuração");
        SKIP.put("Aatrox 1", "zonas de borda e corpo; a 1ª conjuração acerta a borda (+60%)");
        SKIP.put("Aatrox 3", "avanço sem dano");
        SKIP.put("Aatrox R", "sem dano");
        SKIP.put("Malphite 2", "o texto lê o dano dos ataques em cone; a ficha, o do 1º ataque");
        SKIP.put("Nasus 1", "a ficha soma os acúmulos permanentes do minuto");
        SKIP.put("Nasus 2", "sem dano");
        SKIP.put("Nasus 3", "campo: o dano inicial repartido nos pulsos");
        SKIP.put("Nasus R", "aura por segundo");
        SKIP.put("Gwen 1", "2 cargas: 3 cortes e o último");
        SKIP.put("Gwen 2", "sem dano");
        SKIP.put("Gwen 3", "o ataque fortalecido depois do avanço");
        SKIP.put("Kayle 2", "cura, sem dano");
        SKIP.put("Kayle 3", "o texto lê o passivo; a ficha, o ativo (% da Vida perdida: 0 no boneco cheio)");
        SKIP.put("Teemo 2", "sem dano");
        SKIP.put("Teemo 3", "sem dano");
        SKIP.put("Shen 2", "sem dano");
        SKIP.put("Shen R", "escudo, sem dano");
        SKIP.put("Sion 1", "carga cheia: o dano máximo");
        SKIP.put("Sion 2", "escudo; o dano é a reconjuração");
        SKIP.put("Sion R", "dano do meio da faixa");
        SKIP.put("Mordekaiser 2", "escudo, sem dano");
        SKIP.put("Garen 2", "escudo, sem dano");
        SKIP.put("Sett 1", "o parser não lê \"5 mais 1,6% (…) da Vida máxima\" (a ficha: 2 ataques de 50 + 2,5% da Vida do alvo)");
        SKIP.put("Sett R", "o parser não lê o dano (a ficha: 400 + 100% do DdA adicional + 55% da Vida adicional do alvo)");
        SKIP.put("Jax R", "o texto começa pelo passivo (75 a cada 3 ataques); a ficha é o ativo (100 + 100% PdH)");
        SKIP.put("Camille 1", "o texto dá o ataque inteiro (160% do DdA); a ficha, o adicional (60%) em 2 ataques: 2 × (240 − 150) = 180");
        SKIP.put("Camille 2", "o parser lê só o % da Vida máxima; a ficha soma também 190 + 110% do DdA adicional");
        SKIP.put("Camille R", "o parser não lê \"30 mais 15% da Vida atual\"");
        SKIP.put("Irelia 3", "o dano é da 2ª lâmina (a reconjuração)");
        SKIP.put("Shen 1", "o parser não lê \"12 mais 3% da Vida máxima do alvo\" (a ficha: 3 ataques)");
        SKIP.put("Sion 3", "o parser não lê o dano");
    }

    private static final Map<String, Object> TEXTS;

    static {
        try (InputStream in = new ClassPathResource("seed/campeoes_7_3d.yml").getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            @SuppressWarnings("unchecked")
            Map<String, Object> c = (Map<String, Object>) root.getOrDefault("campeoes", root);
            TEXTS = c;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The champion at the top ranks, 150 AD (50 bonus) and 100 AP; the ragdoll in front of it, without resistances. */
    private static List<Arena.Input> duel(String champion, double gap) {
        Arena.Input a = ArenaScenarios.champion(champion, 0, 3000, 0, 4000, 100, 0);
        a.f.total.put("Attack Damage", 150.0);
        a.f.total.put("Ability Power", 100.0);
        a.f.ranks = new int[] {4, 4, 4, 3};
        a.autoAbilities = false;
        Arena.Input b = ArenaScenarios.champion("Darius", 1, 3000 + gap, 0, TARGET_HP, 1, 0);
        b.kit = Map.of("raio", 80, "alcance", 175, "habilidades", Map.of());
        b.forms = new LinkedHashMap<>();
        b.f.ranks = new int[4];
        b.autoAbilities = false;
        b.policy = STILL;
        return new ArrayList<>(List.of(a, b));
    }

    private static Result run(List<Arena.Input> in, double seconds, Action... orders) {
        return new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).run(in, List.of(orders), seconds);
    }

    private static Action cast(String slot, double t, double x) {
        Action a = ArenaScenarios.order(0, t, "ability", slot, 1);
        a.x = x;
        a.y = 0.0;
        return a;
    }

    /** Damage of a slot's ability (its hits, its empowered attacks and its summons). */
    private static double damage(Result r, String slot) {
        return r.fighters.get(0).damageBySource.entrySet().stream()
                .filter(e -> e.getKey().startsWith(slot + " · ") || e.getKey().startsWith("Invocação"))
                .mapToDouble(Map.Entry::getValue).sum();
    }

    /** What can be seen of a fight: places, health, shields, marks, states, shapes and the damage. */
    private static String seen(Result r) {
        StringBuilder sb = new StringBuilder();
        for (Frame f : r.frames) {
            f.units.forEach(u -> sb.append(u.x).append(u.y).append(u.hp).append(u.shield).append(u.marks).append(u.invisible).append(u.state));
            sb.append(f.shapes.size());
        }
        r.fighters.forEach(x -> sb.append(x.damageBySource).append(x.events));
        return sb.toString();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSheetsPassTheValidator() {
        List<String> problems = new ArrayList<>();
        for (String c : LOTE) {
            assertTrue(ArenaScenarios.KITS.containsKey(c), c + " has a sheet");
            problems.addAll(SheetValidator.check(c, (Map<String, Object>) ArenaScenarios.KITS.get(c)));
        }
        assertEquals(List.of(), problems);
    }

    /** Every ability (and every recast version) changes a fight against the ragdoll. */
    @Test
    @SuppressWarnings("unchecked")
    void everyAbilityDoesSomethingAgainstTheRagdoll() {
        List<String> idle = new ArrayList<>();
        for (String c : LOTE) {
            Map<String, Object> abilities = (Map<String, Object>) ((Map<String, Object>) ArenaScenarios.KITS.get(c)).get("habilidades");
            for (String slot : SLOTS) {
                boolean changed = false;
                for (double gap : new double[] {200, 900}) {   // far: what changes the walk (move speed)
                    changed |= !seen(run(duel(c, gap), 5)).equals(seen(run(duel(c, gap), 5, cast(slot, 0.1, 3000 + gap))));
                }
                if (!changed) {
                    idle.add(c + " " + slot);
                }
                Map<String, Object> rec = (Map<String, Object>) ((Map<String, Object>) abilities.get(slot)).get("reconjurar");
                if (rec != null && rec.get("versoes") != null) {
                    Action again = ArenaScenarios.order(0, 3.0, "recast", slot, 1);
                    again.x = 3200.0;
                    again.y = 0.0;
                    String once = seen(run(duel(c, 200), 6, cast(slot, 0.1, 3200)));
                    if (once.equals(seen(run(duel(c, 200), 6, cast(slot, 0.1, 3200), again)))) {
                        idle.add(c + " " + slot + " (reconjuração)");
                    }
                }
            }
        }
        assertEquals(List.of(), idle, "abilities that changed nothing");
    }

    /** Damage of one cast at the top rank against the ragdoll, against the text's (±1%), by table. */
    @Test
    @SuppressWarnings("unchecked")
    void damageAtTheTopRankMatchesTheText() {
        List<String> off = new ArrayList<>();
        StringBuilder report = new StringBuilder();
        for (String c : LOTE) {
            List<AbilityParser.Ability> parsed = AbilityParser.abilities((Map<String, Object>) TEXTS.get(c));
            for (int i = 0; i < SLOTS.size(); i++) {
                String slot = SLOTS.get(i);
                String key = c + " " + slot;
                if (SKIP.containsKey(key)) {
                    continue;
                }
                AbilityParser.Ability pa = parsed.get(i);
                List<Arena.Input> in = duel(c, 200);
                Result r = run(in, 6, cast(slot, 0.1, 3200));
                double measured = damage(r, slot);
                int rank = "R".equals(slot) ? 3 : 4;
                Arena.Input a = in.get(0);
                double text = pa.damage == null ? 0 : pa.damage.at(rank, s -> a.f.total.getOrDefault(s, 0.0),
                        s -> a.f.base.getOrDefault(s, 0.0), TARGET_HP);
                double expected = text * FACTOR.getOrDefault(key, 1.0);
                report.append(String.format("%-14s %-2s sheet %9.1f text %9.1f%n", c, slot, measured, text));
                if (expected <= 0 || Math.abs(measured - expected) > 0.01 * expected) {
                    off.add(String.format("%s: ficha %.1f, texto %.1f (×%.2f)", key, measured, text, FACTOR.getOrDefault(key, 1.0)));
                }
            }
        }
        System.out.print(report);
        assertEquals(List.of(), off);
    }

    /** 30 s of the tactical brain with each sheet against Darius (scripted), without errors, using its abilities. */
    @Test
    void aTacticalFightAgainstDarius() {
        List<String> none = new ArrayList<>();
        StringBuilder report = new StringBuilder();
        for (String c : LOTE) {
            Arena.Input a = ArenaScenarios.champion(c, 0, 3000, 0, 2500, 120, 60);
            a.f.ranks = new int[] {4, 3, 2, 2};
            a.policy = new TacticalPolicy(List.of("R", "3", "1", "2"));
            Arena.Input d = ArenaScenarios.champion("Darius", 1, 3600, 0, 2500, 120, 60);
            Result r = new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).run(List.of(a, d), List.of(), 30);
            assertFalse(r.fighters.isEmpty(), c);
            if (r.fighters.get(0).uses.isEmpty()) {
                none.add(c);
            }
            report.append(String.format("%-12s usos %2d, dano %6.0f, sofrido %6.0f, %s%n", c, r.fighters.get(0).uses.size(),
                    r.fighters.get(0).dealt, r.fighters.get(0).taken, r.fighters.get(0).health <= 0 ? "morreu" : "vivo"));
        }
        System.out.print(report);
        assertEquals(List.of(), none, "never cast an ability");
    }
}
