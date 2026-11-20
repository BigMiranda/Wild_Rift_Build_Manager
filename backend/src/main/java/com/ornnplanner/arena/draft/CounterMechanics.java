package com.ornnplanner.arena.draft;

import com.ornnplanner.arena.fight.ChampionProfiles;
import com.ornnplanner.arena.fight.CombatCatalog;
import com.ornnplanner.arena.progressao.ProgressionService;
import com.ornnplanner.engine.Model;
import com.ornnplanner.service.PlannerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rodada 20.b, Parte 3: the mechanics that punish ({@code contra_mecanicas_7_3f.yml}): each champion's vulnerabilities
 * (chained short dashes, healing, shields, a frontline without an escape, relying on projectiles...) and punishments
 * (slows in the kit or the likely build, anti-heal, shield reaver, true damage, % health damage, projectile blockers),
 * every one a field of the kit sheet, of the profile or of the likely build's items. The punishment of b over a is the
 * seed's weighted sum of a's vulnerabilities that b's punishments answer (0..1). No rule names a champion: the names
 * only appear in the seed, for the fields the sheets do not have yet.
 */
@Component
public class CounterMechanics {

    private static final Logger log = LoggerFactory.getLogger(CounterMechanics.class);

    /** What a champion has: its vulnerabilities and its punishments (keys of the seed). */
    public static class Traits {
        public Set<String> vulnerabilities = new LinkedHashSet<>();
        public Set<String> punishments = new LinkedHashSet<>();
    }

    /** One vulnerability of the seed: the punishments that answer it and its weight. */
    public static class Vulnerability {
        public String key;
        public String name;
        public String nameEn;
        public List<String> punishedBy = new ArrayList<>();
        public double weight = 0.5;
    }

    /** Why b punishes a: the vulnerability and the punishment that answers it. */
    public static class Why {
        public String vulnerability;
        public String punishment;
        public double weight;
    }

    private final Map<String, Object> seed;
    private final Map<String, Vulnerability> vulnerabilities = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> punishments = new LinkedHashMap<>();
    private final ChampionProfiles profiles;
    private final Map<String, Object> kits;
    private final CombatCatalog combat;
    private final PlannerService planner;
    private final ProgressionService progression;
    private volatile Map<String, Traits> traits;

    @Autowired
    @SuppressWarnings("unchecked")
    public CounterMechanics(@Value("${planner.seed.counter:classpath:seed/contra_mecanicas_7_3f.yml}") Resource file,
                            @Value("${planner.seed.kits:classpath:seed/kits_7_3f.yml}") Resource kitsFile,
                            ChampionProfiles profiles, CombatCatalog combat, PlannerService planner, @Lazy ProgressionService progression) {
        try (InputStream in = file.getInputStream(); InputStream k = kitsFile.getInputStream()) {
            this.seed = new Yaml().load(in);
            this.kits = (Map<String, Object>) ((Map<String, Object>) new Yaml().load(k)).getOrDefault("campeoes", Map.of());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + file, e);
        }
        this.profiles = profiles;
        this.combat = combat;
        this.planner = planner;
        this.progression = progression;
        read();
    }

    /** For tests: the seed and the traits already known (no sheets, profiles or builds). */
    public CounterMechanics(Map<String, Object> seed, Map<String, Traits> traits) {
        this.seed = seed;
        this.kits = Map.of();
        this.profiles = null;
        this.combat = null;
        this.planner = null;
        this.progression = null;
        read();
        this.traits = traits;
    }

    @SuppressWarnings("unchecked")
    private void read() {
        Map<String, Object> vs = (Map<String, Object>) seed.getOrDefault("vulnerabilidades", Map.of());
        vs.forEach((k, v) -> {
            Map<String, Object> m = (Map<String, Object>) v;
            Vulnerability x = new Vulnerability();
            x.key = k;
            x.name = String.valueOf(m.getOrDefault("nome", k));
            x.nameEn = String.valueOf(m.getOrDefault("nome_en", x.name));
            x.punishedBy.addAll((List<String>) m.getOrDefault("punida_por", List.of()));
            x.weight = num(m.get("peso"), 0.5);
            vulnerabilities.put(k, x);
        });
        ((Map<String, Object>) seed.getOrDefault("punicoes", Map.of())).forEach((k, v) -> punishments.put(k, (Map<String, Object>) v));
    }

    public Map<String, Vulnerability> vulnerabilities() {
        return vulnerabilities;
    }

    /** The seed's punishments by key (name, name_en, how to read). */
    public Map<String, Map<String, Object>> punishments() {
        return punishments;
    }

    /** Every champion's traits (computed once: the sheets, the profiles and the likely builds). */
    public Map<String, Traits> traits() {
        Map<String, Traits> t = traits;
        if (t == null) {
            synchronized (this) {
                if (traits == null) {
                    long t0 = System.nanoTime();
                    traits = compute();
                    log.info("Counter mechanics of {} champions in {} ms", traits.size(), (System.nanoTime() - t0) / 1_000_000);
                }
                t = traits;
            }
        }
        return t;
    }

    private volatile boolean warming;

    /**
     * The traits if already computed, else null, starting their computation in the background once (the likely builds
     * of every champion take seconds: a draft request does not wait for them, the mechanics come in when ready).
     */
    public Map<String, Traits> traitsIfReady() {
        if (traits == null && !warming) {
            synchronized (this) {
                if (traits == null && !warming) {
                    warming = true;
                    Thread th = new Thread(() -> {
                        try {
                            traits();
                        } catch (RuntimeException e) {
                            log.warn("Counter mechanics failed", e);
                        } finally {
                            warming = false;
                        }
                    }, "contra-mecanicas");
                    th.setDaemon(true);
                    th.start();
                }
            }
        }
        return traits;
    }

    /** The punishment of b over a (0..1). */
    public double punish(String b, String a) {
        return punish(traits(), b, a);
    }

    /** The punishment of b over a by the given traits (0..1). */
    public double punish(Map<String, Traits> t, String b, String a) {
        double s = 0;
        for (Why w : why(t, b, a)) {
            s += w.weight;
        }
        return Math.min(1, s);
    }

    /** Each vulnerability of a that a punishment of b answers (the strongest punishment per vulnerability). */
    public List<Why> why(String b, String a) {
        return why(traits(), b, a);
    }

    public List<Why> why(Map<String, Traits> t, String b, String a) {
        Traits ta = t.get(a);
        Traits tb = t.get(b);
        List<Why> out = new ArrayList<>();
        if (ta == null || tb == null) {
            return out;
        }
        for (String v : ta.vulnerabilities) {
            Vulnerability x = vulnerabilities.get(v);
            if (x == null) {
                continue;
            }
            for (String p : x.punishedBy) {
                if (tb.punishments.contains(p)) {
                    Why w = new Why();
                    w.vulnerability = v;
                    w.punishment = p;
                    w.weight = x.weight;
                    out.add(w);
                    break;
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ reading the fields

    @SuppressWarnings("unchecked")
    private Map<String, Traits> compute() {
        Map<String, Traits> out = new LinkedHashMap<>();
        List<ChampionProfiles.Profile> all = profiles.all();
        // the frontline: health and resistances at level 15 in the top third (as the draft's composition reads it)
        List<Double> durab = new ArrayList<>();
        for (ChampionProfiles.Profile p : all) {
            durab.add(durability(p));
        }
        durab.sort(Double::compare);
        double front = durab.isEmpty() ? 0 : durab.get((int) Math.floor(durab.size() * 0.7));
        Map<Long, Model.ItemDef> items = planner.referenceData().items;
        for (ChampionProfiles.Profile p : all) {
            Map<String, Object> kit = (Map<String, Object>) kits.get(p.name);
            Map<String, Double> fields = new LinkedHashMap<>();
            fields.put("perfil.curas", (double) p.heals);
            fields.put("perfil.escudos", (double) p.shields);
            fields.put("perfil.lentidao", (double) p.control.getOrDefault("lentidao", 0));
            fields.put("perfil.dano_verdadeiro", p.trueDamage);
            fields.put("perfil.linha_sem_fuga", durability(p) >= front && p.mobility == 0 ? 1.0 : 0.0);
            fields.put("ficha.dano_pct_vida", (double) healthShareTerms(kit));
            Collection<Model.ItemDef> build = likelyBuild(p.name, items);
            Traits t = new Traits();
            for (Vulnerability v : vulnerabilities.values()) {
                Map<String, Object> le = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) seed.get("vulnerabilidades")).get(v.key)).get("le");
                if (passes(le, p, kit, fields, build)) {
                    t.vulnerabilities.add(v.key);
                }
            }
            for (Map.Entry<String, Map<String, Object>> e : punishments.entrySet()) {
                if (passes((Map<String, Object>) e.getValue().get("le"), p, kit, fields, build)) {
                    t.punishments.add(e.getKey());
                }
            }
            out.put(p.name, t);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private boolean passes(Map<String, Object> le, ChampionProfiles.Profile p, Map<String, Object> kit, Map<String, Double> fields,
                           Collection<Model.ItemDef> build) {
        if (le == null) {
            return false;
        }
        String field = String.valueOf(le.get("campo"));
        switch (field) {
            case "ficha.avancos_curtos":
                return shortDashes(kit, num(le.get("alcance_max"), 450), num(le.get("recarga_max"), 8)) >= num(le.get("minimo"), 1);
            case "ficha.ataque_projetil":
                return kit != null && kit.get("velocidade_projetil") != null && p.attackRange >= num(le.get("alcance_min"), 450);
            case "build.efeito": {
                String type = String.valueOf(le.get("tipo"));
                for (Model.ItemDef d : build) {
                    for (Map<String, Object> e : combat.effects(d.name)) {
                        if (type.equals(e.get("tipo"))) {
                            return true;
                        }
                    }
                }
                return false;
            }
            case "build.texto": {
                Pattern re = Pattern.compile(String.valueOf(le.get("expressao")));
                for (Model.ItemDef d : build) {
                    for (Model.PassiveText pt : d.passives) {
                        if (pt.text != null && re.matcher(pt.text).find()) {
                            return true;
                        }
                    }
                }
                return false;
            }
            case "por_campeao":
                return ((List<Object>) le.getOrDefault("campeoes", List.of())).contains(p.name);
            default:
                Double v = fields.get(field);
                return v != null && v >= num(le.get("minimo"), 1);
        }
    }

    /** The champion's likely build: the progression's most likely continuation from nothing (empty when it has none). */
    private Collection<Model.ItemDef> likelyBuild(String champion, Map<Long, Model.ItemDef> items) {
        List<Model.ItemDef> out = new ArrayList<>();
        try {
            ProgressionService.Answer a = progression.answer(champion, List.of(), List.of(), List.of(), null, 1);
            if (!a.continuations.isEmpty()) {
                for (ProgressionService.Step s : a.continuations.get(0).steps) {
                    Model.ItemDef d = items.get(s.itemId);
                    if (d != null) {
                        out.add(d);
                    }
                }
            }
        } catch (RuntimeException e) {
            log.debug("No likely build for {}: {}", champion, e.getMessage());
        }
        return out;
    }

    /**
     * The sheet's chained short dashes: abilities that dash or blink (or jump) at most {@code maxRange} away and chain
     * (cooldown at the last rank up to {@code maxCooldown}, a recast or an attack reset).
     */
    @SuppressWarnings("unchecked")
    static int shortDashes(Map<String, Object> kit, double maxRange, double maxCooldown) {
        if (kit == null || !(kit.get("habilidades") instanceof Map)) {
            return 0;
        }
        int n = 0;
        for (Object o : ((Map<String, Object>) kit.get("habilidades")).values()) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<String, Object> a = (Map<String, Object>) o;
            Object forma = a.get("forma");
            boolean dash = "avanco".equals(forma) || "piscar".equals(forma) || a.get("salta") != null;
            if (!dash || !(a.get("alcance") instanceof Number) || ((Number) a.get("alcance")).doubleValue() > maxRange) {
                continue;
            }
            boolean recast = a.get("reconjurar") instanceof Map && ((Map<String, Object>) a.get("reconjurar")).get("versoes") instanceof List;
            boolean reset = a.get("reinicia_ataque") != null;
            Object cd = a.get("recarga");
            double last = cd instanceof List && !((List<Object>) cd).isEmpty() ? num(((List<Object>) cd).get(((List<Object>) cd).size() - 1), 99)
                    : num(cd, 99);
            if (recast || reset || last <= maxCooldown) {
                n++;
            }
        }
        return n;
    }

    private static final Set<String> HEALTH_TERMS = Set.of("vida_max_alvo", "vida_atual_alvo", "vida_perdida_alvo");

    /** How many damage terms of the sheet scale with the target's health. */
    @SuppressWarnings("unchecked")
    static int healthShareTerms(Object node) {
        int n = 0;
        if (node instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) node).entrySet()) {
                if (HEALTH_TERMS.contains(e.getKey())) {
                    n++;
                } else {
                    n += healthShareTerms(e.getValue());
                }
            }
        } else if (node instanceof List) {
            for (Object o : (List<Object>) node) {
                n += healthShareTerms(o);
            }
        }
        return n;
    }

    private static double durability(ChampionProfiles.Profile p) {
        return Math.max(1, p.healthLv15) * (1 + (p.armorLv15 + p.magicResistLv15) / 200);
    }

    private static double num(Object o, double def) {
        return o instanceof Number ? ((Number) o).doubleValue() : def;
    }
}
