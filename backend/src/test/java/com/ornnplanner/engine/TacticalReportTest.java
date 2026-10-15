package com.ornnplanner.engine;

import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel.Result;
import com.ornnplanner.arena.ai.Policy;
import com.ornnplanner.arena.ai.ScriptedPolicy;
import com.ornnplanner.arena.ai.TacticalPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The tactical autopilot with predictions against the one before them, in every duel of the six champions (both
 * sides, three lanes, three second spells next to Flash, and Cleanse): head to head, and each against the script.
 * Off by default; {@code ./mvnw test -Dtest=TacticalReportTest -Darena.report=true}.
 */
@EnabledIfSystemProperty(named = "arena.report", matches = "true")
class TacticalReportTest {

    static final List<String> SPELLS = List.of("Incendiar", "Barreira", "Purificar");

    static Arena.Input fighter(String champion, int team, double x, double y, String spell, Function<List<String>, Policy> brain) {
        Arena.Input in = ArenaScenarios.champion(champion, team, x, y, 2800, 160, 90);
        in.f.spells.addAll(List.of("Flash", spell));
        in.activeCooldowns.put("Flash", 150.0);
        in.activeCooldowns.put(spell, 110.0);
        in.policy = brain.apply(in.priority);
        return in;
    }

    /** {wins, losses, draws} of side A's brain against side B's, over every duel and both sides. */
    static int[] versus(Function<List<String>, Policy> a, Function<List<String>, Policy> b, Map<String, int[]> perChampion) {
        int[] out = new int[3];
        for (String spell : SPELLS) {
            for (String x : ArenaScenarios.CHAMPIONS) {
                for (String y : ArenaScenarios.CHAMPIONS) {
                    if (x.equals(y)) {
                        continue;
                    }
                    for (double lane : new double[] {-250, 0, 250}) {
                        Result r = new Arena(ArenaScenarios.CATALOG, ArenaScenarios.MAP).withoutFrames().run(List.of(
                                fighter(x, 0, 4000, lane, spell, a), fighter(y, 1, 4900, -lane, spell, b)), List.of(), 60);
                        int k = r.winner == 0 ? 0 : r.winner == 1 ? 1 : 2;
                        out[k]++;
                        perChampion.computeIfAbsent(x, c -> new int[3])[k]++;
                    }
                }
            }
        }
        return out;
    }

    @Test
    void report() {
        Function<List<String>, Policy> script = ScriptedPolicy::new;
        Function<List<String>, Policy> legacy = TacticalPolicy::legacy;
        Function<List<String>, Policy> fresh = TacticalPolicy::new;
        Map<String, int[]> byNew = new LinkedHashMap<>();
        int[] newVsOld = versus(fresh, legacy, byNew);
        int[] newVsScript = versus(fresh, script, new LinkedHashMap<>());
        int[] oldVsScript = versus(legacy, script, new LinkedHashMap<>());
        int[] scriptVsScript = versus(script, script, new LinkedHashMap<>());
        System.out.printf("REPORT new x old: %d W / %d L / %d D%n", newVsOld[0], newVsOld[1], newVsOld[2]);
        System.out.printf("REPORT new x script: %d W / %d L / %d D%n", newVsScript[0], newVsScript[1], newVsScript[2]);
        System.out.printf("REPORT old x script: %d W / %d L / %d D%n", oldVsScript[0], oldVsScript[1], oldVsScript[2]);
        System.out.printf("REPORT script x script: %d W / %d L / %d D%n", scriptVsScript[0], scriptVsScript[1], scriptVsScript[2]);
        byNew.forEach((c, v) -> System.out.printf("REPORT   new %s x old: %d W / %d L / %d D%n", c, v[0], v[1], v[2]));
    }

    /** Each rule alone, and all but each one, against the old rules. */
    @Test
    void ablation() {
        Function<List<String>, Policy> legacy = TacticalPolicy::legacy;
        String[] names = {"CLEANSE", "BURST_FLASH", "RECAST_HITS", "ATTACK_MARK", "CHAIN_FIRST", "AIM", "NO_OVERLAP"};
        for (int i = 0; i < names.length; i++) {
            int bit = 1 << i;
            Map<String, int[]> byOnly = new LinkedHashMap<>();
            int[] only = versus(p -> new TacticalPolicy(p, bit), legacy, byOnly);
            int[] without = versus(p -> new TacticalPolicy(p, TacticalPolicy.ALL & ~bit), legacy, new LinkedHashMap<>());
            StringBuilder per = new StringBuilder();
            byOnly.forEach((c, v) -> per.append(' ').append(c).append(' ').append(v[0]).append('/').append(v[1]));
            System.out.printf("ABL %-12s only: %d W / %d L / %d D   all but: %d W / %d L / %d D  |%s%n", names[i],
                    only[0], only[1], only[2], without[0], without[1], without[2], per);
        }
    }
}
