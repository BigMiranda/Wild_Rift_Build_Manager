package com.ornnplanner.arena.fight;

import com.ornnplanner.seed.ChampionCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Who a champion is: its official classes, Wild Rift roles and lanes (seed), and a profile computed from its sheet and
 * stats (a vector of numbers: range, mobility, control, toughness, damage). The profile checks the official class, warns
 * when they diverge, classifies a champion with no source, and tells which champions are alike. No rule names a champion:
 * words and weights live in {@code classificacao_<patch>.yml}.
 */
@Service
public class ChampionProfiles {

    /** What is known of a champion. */
    public static class Profile {
        public String name;
        public String code;
        /** "Classe/Subclasse" as the seed has them, with their main classes in {@link #mainClasses}. */
        public List<String> classes = new ArrayList<>();
        public List<String> mainClasses = new ArrayList<>();
        public List<String> roles = new ArrayList<>();
        public List<String> lanes = new ArrayList<>();
        public String source;
        public double attackRange;
        public Double abilityRange;
        public int mobility;
        public Map<String, Integer> control = new LinkedHashMap<>();
        public double healthLv15;
        public double armorLv15;
        public double magicResistLv15;
        public double healthPerLevel;
        public double armorPerLevel;
        public double magicResistPerLevel;
        public int shields;
        public int heals;
        /** Shares of its damage by type (0..1), and what part of it comes in one burst. */
        public double physical;
        public double magic;
        public double trueDamage;
        public double burstShare;
        /** Basic attacks' and abilities' damage per second at the reference stats. */
        public double attackDps;
        public double abilityDps;
        /** The class the profile suggests, each class's score, and whether it diverges from the official one. */
        public String suggested;
        public Map<String, Double> scores = new LinkedHashMap<>();
        public boolean divergent;
        /**
         * Rodada 14: what kind of threat it is in a fight, finer than the class: the wiki's subclasses ("Colosso",
         * "Artilharia"...), "Atirador" for a marksman, and for a champion the wiki gives none (Especialista, a class with
         * no subclass) the subclass whose champions' profile is nearest ({@link #subclassSource} "perfil").
         */
        public List<String> subclasses = new ArrayList<>();
        public String subclassSource;
        /** The distance of its profile to each subclass's centre (z units), nearest first. */
        public Map<String, Double> subclassDistance = new LinkedHashMap<>();

        /** The numbers the class weights read. */
        Map<String, Double> vector() {
            Map<String, Double> v = new LinkedHashMap<>();
            v.put("vida", healthLv15);
            v.put("armadura", armorLv15);
            v.put("rm", magicResistLv15);
            v.put("alcance", attackRange);
            v.put("mobilidade", (double) mobility);
            v.put("controle", control.values().stream().mapToDouble(Integer::doubleValue).sum());
            v.put("sustento", (double) (shields + heals));
            v.put("dano_ataque", attackDps);
            v.put("dano_habilidade", abilityDps);
            v.put("fisico", physical);
            v.put("magico", magic);
            v.put("explosao", burstShare);
            return v;
        }
    }

    private final Map<String, Profile> byName = new LinkedHashMap<>();
    private final Map<String, Object> rules;

    @SuppressWarnings("unchecked")
    public ChampionProfiles(ChampionCatalog champions,
                            @Value("${planner.seed.classification:classpath:seed/classificacao_7_3d.yml}") Resource file,
                            @Value("${planner.seed.kits:classpath:seed/kits_7_3f.yml}") Resource kitsFile) {
        Map<String, Object> kits;
        try (InputStream in = file.getInputStream(); InputStream k = kitsFile.getInputStream()) {
            rules = new Yaml().load(in);
            kits = (Map<String, Object>) ((Map<String, Object>) new Yaml().load(k)).get("campeoes");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + file, e);
        }
        for (Map<String, Object> s : champions.summaries()) {
            champions.find(String.valueOf(s.get("code"))).ifPresent(c -> {
                Profile p = profile(c, (Map<String, Object>) kits.get(c.get("nome")));
                byName.put(p.name.toLowerCase(), p);
            });
        }
        score();
    }

    public List<Profile> all() {
        return new ArrayList<>(byName.values());
    }

    /** By the champion's name (null = unknown, such as a ragdoll). */
    public Profile of(String champion) {
        return champion == null ? null : byName.get(champion.toLowerCase());
    }

    /** The subclasses of a champion (see {@link Profile#subclasses}); empty for an unknown one (a ragdoll). */
    public List<String> subclassesOf(String champion) {
        Profile p = of(champion);
        return p == null ? List.of() : p.subclasses;
    }

    /** The main classes of a champion: the official ones, or the suggested one when it has no source. */
    public List<String> classesOf(String champion) {
        Profile p = of(champion);
        if (p == null) {
            return List.of();
        }
        return p.mainClasses.isEmpty() ? p.suggested == null ? List.of() : List.of(p.suggested) : p.mainClasses;
    }

    @SuppressWarnings("unchecked")
    private Profile profile(Map<String, Object> c, Map<String, Object> kit) {
        Map<String, Object> cfg = (Map<String, Object>) rules.get("perfil");
        Profile p = new Profile();
        p.name = String.valueOf(c.get("nome"));
        p.code = String.valueOf(c.get("code"));
        for (Object o : (List<Object>) c.getOrDefault("classes", List.of())) {
            String full = String.valueOf(o);
            p.classes.add(full);
            String main = full.contains("/") ? full.substring(0, full.indexOf('/')) : full;
            if (!p.mainClasses.contains(main)) {
                p.mainClasses.add(main);
            }
        }
        for (String full : p.classes) {
            String sub = full.contains("/") ? full.substring(full.indexOf('/') + 1) : "Atirador".equals(full) ? full : null;
            if (sub != null && !p.subclasses.contains(sub)) {
                p.subclasses.add(sub);
            }
        }
        p.subclassSource = p.subclasses.isEmpty() ? null : "wiki";
        ((List<Object>) c.getOrDefault("funcoes", List.of())).forEach(o -> p.roles.add(String.valueOf(o)));
        ((List<Object>) c.getOrDefault("rotas", List.of())).forEach(o -> p.lanes.add(String.valueOf(o)));
        p.source = c.get("fonte_classes") == null ? null : String.valueOf(c.get("fonte_classes"));
        p.attackRange = num(kit != null ? kit.get("alcance") : c.get("alcance_ataque"), num(c.get("alcance_ataque"), 0));
        if (kit != null && kit.get("habilidades") instanceof Map) {
            double sum = 0;
            int n = 0;
            for (Object a : ((Map<String, Object>) kit.get("habilidades")).values()) {
                Object r = a instanceof Map ? ((Map<String, Object>) a).get("alcance") : null;
                if (r instanceof Number && ((Number) r).doubleValue() < 5000) {   // a global ultimate says nothing of its reach
                    sum += ((Number) r).doubleValue();
                    n++;
                }
            }
            p.abilityRange = n == 0 ? null : sum / n;
        }
        Map<String, Object> status = (Map<String, Object>) c.getOrDefault("status", Map.of());
        Map<String, Object> l1 = (Map<String, Object>) status.getOrDefault("nv1", Map.of());
        Map<String, Object> l15 = (Map<String, Object>) status.getOrDefault("nv15", Map.of());
        p.healthLv15 = num(l15.get("Vida"), 0);
        p.armorLv15 = num(l15.get("Armadura"), 0);
        p.magicResistLv15 = num(l15.get("RM"), 0);
        p.healthPerLevel = (p.healthLv15 - num(l1.get("Vida"), 0)) / 14;
        p.armorPerLevel = (p.armorLv15 - num(l1.get("Armadura"), 0)) / 14;
        p.magicResistPerLevel = (p.magicResistLv15 - num(l1.get("RM"), 0)) / 14;
        readTexts(c, cfg, p);
        readDamage(c, cfg, p, num(l15.get("DdA"), 0), num(l15.get("VdA"), 0.7));
        return p;
    }

    /** Mobility, control, shields and healing: the game's tags and the words of the texts of its abilities and passive. */
    @SuppressWarnings("unchecked")
    private static void readTexts(Map<String, Object> c, Map<String, Object> cfg, Profile p) {
        Pattern mobility = pattern(cfg.get("mobilidade"));
        Pattern shield = pattern(cfg.get("escudo"));
        Pattern heal = pattern(cfg.get("cura"));
        Map<String, Pattern> control = new LinkedHashMap<>();
        ((Map<String, Object>) cfg.get("controle")).forEach((k, v) -> control.put(k, pattern(v)));
        List<Map<String, Object>> all = new ArrayList<>();
        if (c.get("passiva") instanceof Map) {
            all.add((Map<String, Object>) c.get("passiva"));
        }
        all.addAll((List<Map<String, Object>>) c.getOrDefault("habilidades", List.of()));
        for (Map<String, Object> a : all) {
            String text = String.valueOf(a.get("texto"));
            List<Object> tags = (List<Object>) a.getOrDefault("tipos", List.of());
            if (tags.contains("Mobilidade") || tags.contains("Avanço") || mobility.matcher(text).find()) {
                p.mobility++;
            }
            if (shield.matcher(text).find() || tags.contains("Bloqueio")) {
                p.shields++;
            }
            if (tags.contains("Cura") || tags.contains("Restaurar") || heal.matcher(text).find()) {
                p.heals++;
            }
            control.forEach((k, re) -> {
                if (re.matcher(text).find()) {
                    p.control.merge(k, 1, Integer::sum);
                }
            });
        }
    }

    /** Damage by type and burst or sustained, at the reference stats, read by {@link AbilityParser}. */
    @SuppressWarnings("unchecked")
    private static void readDamage(Map<String, Object> c, Map<String, Object> cfg, Profile p, double ad, double attackSpeed) {
        Map<String, Object> ref = (Map<String, Object>) cfg.get("referencia");
        double window = num(cfg.get("janela_explosao"), 6);
        Map<String, Double> base = Map.of(com.ornnplanner.engine.Stats.ATTACK_DAMAGE, ad);
        Map<String, Double> total = new LinkedHashMap<>();
        total.put(com.ornnplanner.engine.Stats.ATTACK_DAMAGE, ad + num(ref.get("dda_bonus"), 150));
        total.put(com.ornnplanner.engine.Stats.ABILITY_POWER, num(ref.get("pdh"), 300));
        total.put(com.ornnplanner.engine.Stats.MAX_HEALTH, p.healthLv15);
        double[] byType = new double[3];
        double burst = 0;
        p.attackDps = (ad + num(ref.get("dda_bonus"), 150)) * attackSpeed;
        for (AbilityParser.Ability a : AbilityParser.abilities(c)) {
            if (a.damage == null) {
                continue;
            }
            double dmg = a.damage.at(99, total, base);
            burst += dmg;
            double cd = a.cooldown == null || a.cooldown.length == 0 ? 10 : Math.max(1, a.cooldown[a.cooldown.length - 1]);
            p.abilityDps += dmg / cd;
            int type = "fisico".equals(a.damage.type) ? 0 : "magico".equals(a.damage.type) ? 1 : 2;
            byType[type] += dmg;
        }
        // the parser leaves out what it does not understand, so the game's tags of every ability count as much as it does
        double[] tags = new double[3];
        for (Map<String, Object> a : (List<Map<String, Object>>) c.getOrDefault("habilidades", List.of())) {
            List<Object> t = (List<Object>) a.getOrDefault("tipos", List.of());
            tags[0] += t.contains("Físico") ? 1 : 0;
            tags[1] += t.contains("Mágico") ? 1 : 0;
            tags[2] += t.contains("Verdadeiro") ? 1 : 0;
        }
        double[] shares = blend(byType, tags);
        p.physical = shares[0];
        p.magic = shares[1];
        p.trueDamage = shares[2];
        double dps = p.attackDps + p.abilityDps;
        p.burstShare = burst + dps * window > 0 ? burst / (burst + dps * window) : 0;
    }

    /** The mean of two splits by damage type (each to shares); a split with nothing in it does not count. */
    private static double[] blend(double[] a, double[] b) {
        double sa = a[0] + a[1] + a[2];
        double sb = b[0] + b[1] + b[2];
        double[] out = new double[3];
        for (int k = 0; k < 3; k++) {
            double x = sa > 0 ? a[k] / sa : 0;
            double y = sb > 0 ? b[k] / sb : 0;
            out[k] = sa > 0 && sb > 0 ? (x + y) / 2 : x + y;
        }
        return out;
    }

    /** Each champion's score per class (weights over the z of each number), the class it suggests and whether it diverges. */
    @SuppressWarnings("unchecked")
    private void score() {
        Map<String, double[]> stats = new LinkedHashMap<>();
        for (Profile p : byName.values()) {
            p.vector().forEach((k, v) -> {
                double[] s = stats.computeIfAbsent(k, x -> new double[3]);
                s[0]++;
                s[1] += v;
                s[2] += v * v;
            });
        }
        Map<String, Map<String, Object>> weights = (Map<String, Map<String, Object>>) rules.get("classes_por_perfil");
        double margin = num(rules.get("margem_divergencia"), 0.75);
        Map<String, List<String>> close = (Map<String, List<String>>) rules.getOrDefault("classes_proximas", Map.of());
        for (Profile p : byName.values()) {
            Map<String, Double> vector = p.vector();
            double best = Double.NEGATIVE_INFINITY;
            for (Map.Entry<String, Map<String, Object>> cl : weights.entrySet()) {
                double score = 0;
                for (Map.Entry<String, Object> w : cl.getValue().entrySet()) {
                    double[] s = stats.get(w.getKey());
                    double mean = s[1] / s[0];
                    double sd = Math.sqrt(Math.max(1e-9, s[2] / s[0] - mean * mean));
                    score += num(w.getValue(), 0) * (vector.get(w.getKey()) - mean) / sd;
                }
                p.scores.put(cl.getKey(), score);
                if (score > best) {
                    best = score;
                    p.suggested = cl.getKey();
                }
            }
            Set<String> official = new LinkedHashSet<>(p.mainClasses);
            official.retainAll(weights.keySet());
            double bestOfficial = official.stream().mapToDouble(p.scores::get).max().orElse(Double.NEGATIVE_INFINITY);
            List<String> near = close.getOrDefault(p.suggested, List.of());
            p.divergent = !official.isEmpty() && !official.contains(p.suggested) && official.stream().noneMatch(near::contains)
                    && best - bestOfficial > margin;
        }
        subclasses(stats);
    }

    /** The subclasses of every champion (see {@link Profile#subclasses}): the nearest centre for those the wiki gives none. */
    private void subclasses(Map<String, double[]> stats) {
        // each subclass's centre, in z units, over the champions the wiki gives it
        Map<String, Map<String, Double>> sums = new java.util.TreeMap<>();
        Map<String, Integer> counts = new java.util.TreeMap<>();
        Map<Profile, Map<String, Double>> z = new LinkedHashMap<>();
        for (Profile p : byName.values()) {
            Map<String, Double> v = new LinkedHashMap<>();
            p.vector().forEach((k, x) -> {
                double[] s = stats.get(k);
                double mean = s[1] / s[0];
                double sd = Math.sqrt(Math.max(1e-9, s[2] / s[0] - mean * mean));
                v.put(k, (x - mean) / sd);
            });
            z.put(p, v);
            if ("wiki".equals(p.subclassSource)) {
                for (String sub : p.subclasses) {
                    counts.merge(sub, 1, Integer::sum);
                    Map<String, Double> sum = sums.computeIfAbsent(sub, k -> new LinkedHashMap<>());
                    v.forEach((k, x) -> sum.merge(k, x / p.subclasses.size(), Double::sum));
                }
            }
        }
        int least = (int) num(((Map<String, Object>) rules.getOrDefault("subclasses", Map.of())).get("minimo_campeoes"), 3);
        Map<String, Map<String, Double>> centres = new LinkedHashMap<>();
        sums.forEach((sub, sum) -> {
            if (counts.get(sub) >= least) {
                Map<String, Double> c = new LinkedHashMap<>();
                sum.forEach((k, x) -> c.put(k, x / counts.get(sub)));
                centres.put(sub, c);
            }
        });
        for (Profile p : byName.values()) {
            Map<String, Double> v = z.get(p);
            List<Map.Entry<String, Double>> d = new ArrayList<>();
            centres.forEach((sub, c) -> {
                double sq = 0;
                for (Map.Entry<String, Double> e : c.entrySet()) {
                    double diff = v.get(e.getKey()) - e.getValue();
                    sq += diff * diff;
                }
                d.add(Map.entry(sub, Math.sqrt(sq)));
            });
            d.sort(Map.Entry.comparingByValue());
            d.forEach(e -> p.subclassDistance.put(e.getKey(), Math.round(e.getValue() * 100) / 100.0));
            if (p.subclasses.isEmpty() && !d.isEmpty()) {
                p.subclasses.add(d.get(0).getKey());
                p.subclassSource = "perfil";
            }
        }
    }

    private static Pattern pattern(Object re) {
        return Pattern.compile(String.valueOf(re), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static double num(Object o, double def) {
        return o instanceof Number ? ((Number) o).doubleValue() : def;
    }
}
