package com.ornnplanner.arena;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ornnplanner.arena.ArenaModel.Scenario;
import com.ornnplanner.arena.ArenaModel.Slot;
import com.ornnplanner.combat.CombatCatalog;
import com.ornnplanner.combat.CombatService;
import com.ornnplanner.combat.CombatSimulator.Fighter;
import com.ornnplanner.combat.CombatSimulator.ItemEffect;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.seed.RuneCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrated matchups: runs a scenario on the arena (each champion from a saved build at a minute, with its ability
 * sheet from {@code kits_<patch>.yml}) and stores scenarios (table arena_scenario, as JSON).
 */
@Service
public class ArenaService {

    /** Up to 5 per side, 6 with clones (Wukong). */
    public static final int MAX_TEAM = 6;
    public static final double MAX_DURATION = 180;

    /** Cooldowns of item actives the combat file does not carry (s). */
    private static final Map<String, Double> ITEM_COOLDOWNS = Map.of(
            "Ampulheta de Zhonya", 90.0, "Armaguarda da Caçadora", 150.0, "Placa Gargolítica", 60.0,
            "Medalhão dos Solari de Ferro", 60.0);

    private final CombatService combat;
    private final CombatCatalog catalog;
    private final BuildRepository builds;
    private final RuneCatalog runes;
    private final JdbcTemplate jdbc;
    private final Map<String, Object> kits = new LinkedHashMap<>();
    private final Map<String, Object> map = new LinkedHashMap<>();
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @SuppressWarnings("unchecked")
    public ArenaService(CombatService combat, CombatCatalog catalog, BuildRepository builds, RuneCatalog runes,
                        JdbcTemplate jdbc, @Value("${planner.seed.kits:classpath:seed/kits_7_3.yml}") Resource kitsFile) {
        this.combat = combat;
        this.catalog = catalog;
        this.builds = builds;
        this.runes = runes;
        this.jdbc = jdbc;
        try (InputStream in = kitsFile.getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            kits.putAll((Map<String, Object>) root.get("campeoes"));
            map.putAll((Map<String, Object>) root.get("mapa"));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + kitsFile, e);
        }
    }

    /** The ability sheets and the map, for the screen (ranges to draw, shapes, sources). */
    public Map<String, Object> kits() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mapa", map);
        out.put("campeoes", kits);
        return out;
    }

    @SuppressWarnings("unchecked")
    public ArenaModel.Result run(Scenario sc) {
        if (sc.fighters == null || sc.fighters.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O cenário não tem campeões.");
        }
        long blue = sc.fighters.stream().filter(f -> f.team == 0).count();
        long red = sc.fighters.size() - blue;
        if (blue == 0 || red == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cada lado precisa de ao menos 1 campeão.");
        }
        if (blue > MAX_TEAM || red > MAX_TEAM) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No máximo " + MAX_TEAM + " por lado.");
        }
        List<Arena.Input> inputs = new ArrayList<>();
        int[] perTeam = new int[2];
        int[] ofTeam = {(int) blue, (int) red};
        for (Slot slot : sc.fighters) {
            if (slot.buildId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha uma build para cada campeão.");
            }
            BuildRepository.Build build = builds.findBuild(slot.buildId).orElseThrow(
                    () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Build " + slot.buildId + " não existe."));
            CombatService.Combatant c = new CombatService.Combatant();
            c.buildId = slot.buildId;
            c.minute = slot.minute;
            c.activations = slot.activations;
            // The champion's name decides its sheet; the sheet's form effects are switched by the arena itself.
            Fighter probe = combat.arenaFighter(c, Set.of());
            Map<String, Object> kit = (Map<String, Object>) kits.get(probe.champion);
            Set<String> forms = new LinkedHashSet<>();
            if (kit != null && kit.get("efeitos_de_forma") instanceof Map) {
                forms.addAll(((Map<String, Object>) kit.get("efeitos_de_forma")).keySet());
            }
            Fighter f = forms.isEmpty() ? probe : combat.arenaFighter(c, forms);
            f.team = slot.team;
            Arena.Input in = new Arena.Input();
            in.f = f;
            in.build = build.name;
            in.kit = kit;
            in.forms = forms.isEmpty() ? new LinkedHashMap<>() : combat.formDeltas(c, forms);
            in.target = slot.target;
            in.autoAbilities = slot.autoAbilities;
            if (slot.priority != null && !slot.priority.isEmpty()) {
                in.priority = new ArrayList<>(slot.priority);
            }
            in.baseAttackSpeed = f.baseAttackSpeed > 0 ? f.baseAttackSpeed : 0.7;
            double[] start = start(slot.team, perTeam[slot.team]++, ofTeam[slot.team]);
            in.x = slot.x != null ? slot.x : start[0];
            in.y = slot.y != null ? slot.y : start[1];
            for (String item : f.items.keySet()) {
                Double cd = ITEM_COOLDOWNS.get(item);
                for (ItemEffect e : f.effects) {
                    if (e.item.equals(item) && "ativo".equals(e.kind())) {
                        cd = ((Number) e.p.getOrDefault("recarga", 60)).doubleValue();
                    }
                }
                if (cd != null) {
                    in.activeCooldowns.put(item, cd);
                }
            }
            for (String spell : f.spells) {
                runes.catalog().spells.stream().filter(s -> s.name.equals(spell) && s.cooldown != null)
                        .findFirst().ifPresent(s -> in.activeCooldowns.put(spell, s.cooldown.doubleValue()));
            }
            inputs.add(in);
        }
        double duration = Math.max(5, Math.min(MAX_DURATION, sc.duration <= 0 ? 60 : sc.duration));
        return new Arena(catalog, map).run(inputs, sc.actions == null ? List.of() : sc.actions, duration);
    }

    /** Default start: next to the side's Nexus, side by side. */
    private double[] start(int team, int k, int count) {
        List<?> nexus = (List<?>) map.get(team == 0 ? "nexus_azul" : "nexus_vermelho");
        double x = ((Number) nexus.get(0)).doubleValue();
        double y = ((Number) nexus.get(1)).doubleValue() + (k - (count - 1) / 2.0) * 160;
        return new double[] {x, y};
    }

    // ------------------------------------------------------------------ stored scenarios

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT id, name, updated_at AS updatedAt FROM arena_scenario ORDER BY name COLLATE NOCASE");
    }

    public Scenario find(long id) {
        List<String> data = jdbc.queryForList("SELECT data FROM arena_scenario WHERE id = ?", String.class, id);
        if (data.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        try {
            Scenario sc = JSON.readValue(data.get(0), Scenario.class);
            sc.id = id;
            return sc;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cenário " + id + " inválido", e);
        }
    }

    public Scenario save(Scenario sc) {
        String name = sc.name == null || sc.name.isBlank() ? "Cenário" : sc.name.trim();
        String now = Instant.now().toString();
        String data;
        try {
            sc.name = name;
            data = JSON.writeValueAsString(sc);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
        if (sc.id == null) {
            KeyHolder kh = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO arena_scenario (name, data, created_at, updated_at) VALUES (?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, name);
                ps.setString(2, data);
                ps.setString(3, now);
                ps.setString(4, now);
                return ps;
            }, kh);
            sc.id = kh.getKey().longValue();
        } else if (jdbc.update("UPDATE arena_scenario SET name = ?, data = ?, updated_at = ? WHERE id = ?",
                name, data, now, sc.id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return sc;
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM arena_scenario WHERE id = ?", id);
    }
}
