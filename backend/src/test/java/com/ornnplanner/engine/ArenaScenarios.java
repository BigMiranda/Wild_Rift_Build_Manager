package com.ornnplanner.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ornnplanner.arena.Arena;
import com.ornnplanner.arena.ArenaModel.Action;
import com.ornnplanner.arena.ArenaModel.Result;
import com.ornnplanner.combat.CombatCatalog;
import com.ornnplanner.combat.CombatSimulator.Fighter;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Fixed arena scenarios shared by the regression, determinism and benchmark tests: every pair of the six champions
 * with a sheet in a duel on autopilot, a 3v3, fights mixing timeline orders with the autopilot, and long fights that
 * run to the time limit. Each scenario builds fresh inputs, so a run never sees another run's objects.
 */
final class ArenaScenarios {

    static final List<String> CHAMPIONS = List.of("Ornn", "Darius", "Renekton", "Rumble", "Vayne", "K'Sante");

    static final CombatCatalog CATALOG;
    static final Map<String, Object> KITS;
    static final Map<String, Object> MAP;
    private static final ObjectMapper JSON = new ObjectMapper();

    static {
        try {
            CATALOG = new CombatCatalog(new ClassPathResource("seed/combate_7_3.yml"));
            try (InputStream in = new ClassPathResource("seed/kits_7_3.yml").getInputStream()) {
                Map<String, Object> root = new Yaml().load(in);
                @SuppressWarnings("unchecked")
                Map<String, Object> k = (Map<String, Object>) root.get("campeoes");
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) root.get("mapa");
                KITS = k;
                MAP = m;
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ArenaScenarios() {
    }

    /** A named fight: inputs and orders are built anew for each run. */
    static final class Scenario {
        final String name;
        final Supplier<List<Arena.Input>> inputs;
        final Supplier<List<Action>> actions;
        final double duration;

        Scenario(String name, Supplier<List<Arena.Input>> inputs, Supplier<List<Action>> actions, double duration) {
            this.name = name;
            this.inputs = inputs;
            this.actions = actions;
            this.duration = duration;
        }

        Result run(boolean frames) {
            Arena arena = new Arena(CATALOG, MAP);
            if (!frames) {
                arena.withoutFrames();
            }
            return arena.run(inputs.get(), actions.get(), duration);
        }
    }

    @SuppressWarnings("unchecked")
    static Arena.Input champion(String name, int team, double x, double y, double hp, double ad, double armor) {
        Fighter f = new Fighter();
        f.name = name + (team == 0 ? " (azul)" : " (vermelho)");
        f.champion = name;
        f.team = team;
        f.level = 13;
        f.minute = 14;
        for (Map<String, Double> m : List.of(f.total, f.base)) {
            m.put(Stats.MAX_HEALTH, hp);
            m.put(Stats.ATTACK_DAMAGE, ad);
            m.put(Stats.ARMOR, armor);
            m.put(Stats.MAGIC_RESIST, armor * 0.7);
            m.put(Stats.MOVE_SPEED, 350.0);
        }
        f.total.put(Stats.ABILITY_HASTE, 20.0);
        f.total.put("% Attack Speed", 30.0);
        f.ranks = new int[] {4, 3, 2, 2};
        Arena.Input in = new Arena.Input();
        in.f = f;
        in.build = name;
        in.kit = (Map<String, Object>) KITS.get(name);
        in.x = x;
        in.y = y;
        in.baseAttackSpeed = "Vayne".equals(name) ? 0.66 : 0.63;
        if ("Darius".equals(name)) {
            in.forms.put("Hemorragia", Map.of(Stats.ATTACK_DAMAGE, 120.0));
        }
        return in;
    }

    static Action order(int fighter, double time, String type, String slot, Integer target) {
        Action a = new Action();
        a.fighter = fighter;
        a.time = time;
        a.type = type;
        a.slot = slot;
        a.target = target;
        return a;
    }

    static List<Scenario> all() {
        List<Scenario> out = new ArrayList<>();
        // Every pair, both orders of side, on autopilot; the y offsets bring the walls into play.
        for (int i = 0; i < CHAMPIONS.size(); i++) {
            for (int j = 0; j < CHAMPIONS.size(); j++) {
                if (i == j) {
                    continue;
                }
                String a = CHAMPIONS.get(i);
                String b = CHAMPIONS.get(j);
                double y = (i - j) * 70;
                out.add(new Scenario("duel " + a + " x " + b,
                        () -> List.of(champion(a, 0, 1000, y, 2800, 160, 90), champion(b, 1, 1900, -y, 2800, 160, 90)),
                        List::of, 60));
            }
        }
        out.add(new Scenario("3v3",
                () -> List.of(champion("Ornn", 0, 1000, -200, 3800, 120, 160), champion("Darius", 0, 900, 0, 3000, 170, 90),
                        champion("Vayne", 0, 700, 200, 2200, 190, 50), champion("Renekton", 1, 2000, -200, 3000, 170, 90),
                        champion("Rumble", 1, 2100, 0, 2800, 120, 80), champion("K'Sante", 1, 1900, 200, 3600, 130, 150)),
                List::of, 60));
        out.add(new Scenario("orders + autopilot", () -> {
            List<Arena.Input> in = List.of(champion("Ornn", 0, 1000, 0, 3500, 140, 120), champion("Vayne", 1, 1700, 350, 2600, 180, 60));
            in.get(1).priority = new ArrayList<>(List.of("1", "3", "2", "R"));
            return in;
        }, () -> new ArrayList<>(List.of(order(0, 0, "ability", "R", 1), order(0, 1.5, "recast", "R", null),
                order(1, 2, "ability", "3", 0), order(0, 6, "ability", "1", 1), order(1, 9, "stop", null, null))), 40));
        out.add(new Scenario("fixed target, no abilities", () -> {
            List<Arena.Input> in = List.of(champion("Darius", 0, 1000, 0, 3000, 170, 90),
                    champion("Rumble", 1, 1800, 100, 2800, 120, 80), champion("K'Sante", 1, 2000, -100, 3600, 130, 150));
            in.get(0).target = 2;
            in.get(1).autoAbilities = false;
            return in;
        }, List::of, 45));
        // Long fights: nobody dies before the limit (worst case for the benchmark).
        out.add(new Scenario("long Darius x Vayne",
                () -> List.of(champion("Darius", 0, 1000, 0, 400000, 160, 90), champion("Vayne", 1, 1900, 0, 400000, 160, 90)),
                List::of, 60));
        out.add(new Scenario("long Ornn x K'Sante",
                () -> List.of(champion("Ornn", 0, 1000, 150, 400000, 160, 90), champion("K'Sante", 1, 1900, -150, 400000, 160, 90)),
                List::of, 60));
        return out;
    }

    /**
     * Fields of each fighter's totals that only describe the fight (counters and summaries added after the golden file
     * was recorded): left out of the fingerprint, so describing a fight better never looks like a changed fight.
     */
    static final java.util.Set<String> DESCRIPTIVE = java.util.Set.of("events", "eventSummary", "damageByType", "controlSpans",
            "cooldownsLeft");

    /** SHA-256 of the whole result as JSON (but {@link #DESCRIPTIVE}): any change in any number, event or frame changes it. */
    static String fingerprint(Result r) {
        try {
            com.fasterxml.jackson.databind.JsonNode tree = JSON.valueToTree(r);
            strip(tree);
            byte[] d = MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsString(tree).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void strip(com.fasterxml.jackson.databind.JsonNode result) {
        for (com.fasterxml.jackson.databind.JsonNode f : result.path("fighters")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) f).remove(DESCRIPTIVE);
        }
    }

    /** Short readable line to tell what changed when a fingerprint differs. */
    static String summary(Result r) {
        StringBuilder sb = new StringBuilder("winner=" + r.winner + " t=" + r.duration);
        r.fighters.forEach(f -> sb.append(" | ").append(f.champion).append(" hp=").append(Math.round(f.health))
                .append(" dealt=").append(Math.round(f.dealt)));
        return sb.toString();
    }

    static Map<String, String> fingerprints(boolean frames) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Scenario s : all()) {
            out.put(s.name, fingerprint(s.run(frames)));
        }
        return out;
    }
}
