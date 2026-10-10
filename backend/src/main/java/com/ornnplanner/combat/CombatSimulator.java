package com.ornnplanner.combat;

import com.ornnplanner.engine.Stats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fight between two teams (1 to 6 champions each), in steps of {@value #DT}s, up to a time limit.
 *
 * <h2>Each champion</h2>
 * Stats are those of its build at the chosen minute (items, runes, champion effects, effective stats). Every step it
 * uses the first ready action of its order (abilities 1, 2, 3, R and "Ativo", the damage active of an item), at most
 * one every {@value #CAST_LOCK}s, and attacks whenever its attack timer allows (attack speed = base of the level x (1 +
 * bonus %)). Abilities hit only the target and their damage is the first one read from the game's text
 * ({@link AbilityParser}); cooldowns follow the rank and the haste (Ability Haste + Ultimate / Basic Ability Haste).
 * Target: the first enemy alive (default), the one with the lowest health %, or a given enemy.
 *
 * <h2>Damage</h2>
 * raw x (1 + amplifications) x 100 / (100 + resistance), resistance = resist x (1 - % reduction) x (1 - % pen) - flat
 * pen; then damage reductions of the target, its shields and its health. Expected critical strikes (chance x
 * multiplier). Lifesteal on attacks, omnivamp on all damage. Heals are cut by Grievous Wounds; shields by the enemy's
 * shield reaver. Shields of the build (Escudo) are up at the start; its heals (Cura) come back over 5s while hurt;
 * stasis triggers once below 30% of health (or on a lethal hit). Ignite and Exhaust are used on the target at the
 * start, Barrier and Heal below 35% of health.
 *
 * <h2>Ledger</h2>
 * Every effect is credited to the item (or ability, spell) behind it: damage dealt, damage added by resistance
 * reduction and by amplification, damage prevented, enemy shields and heals cut, own shields absorbed / lost, heals
 * done / lost.
 */
public class CombatSimulator {

    public static final double DT = 0.05;
    public static final double CAST_LOCK = 0.25;
    private static final double SERIES_EVERY = 0.5;

    static final int ATTACK = 0;
    static final int ABILITY = 1;
    static final int ITEM = 2;
    static final int DOT = 3;
    static final int REFLECT = 4;

    /** An item effect of a fighter. */
    public static class ItemEffect {
        public final String item;
        public final Map<String, Object> p;

        public ItemEffect(String item, Map<String, Object> p) {
            this.item = item;
            this.p = p;
        }

        String kind() {
            return String.valueOf(p.get("tipo"));
        }
    }

    /** A champion in the fight (built by CombatService from a build at a minute); not changed by a run. */
    public static class Fighter {
        public int index;
        public int team;
        public String name;
        public String champion;
        public int level;
        public double minute;
        public Map<String, Double> total = new LinkedHashMap<>();
        public Map<String, Double> base = new LinkedHashMap<>();
        /** Held items (names) and their ids. */
        public Map<String, Long> items = new LinkedHashMap<>();
        public List<ItemEffect> effects = new ArrayList<>();
        public List<AbilityParser.Ability> abilities = new ArrayList<>();
        /** Ranks of 1, 2, 3, R at the fighter's level. */
        public int[] ranks = new int[4];
        public double attackSpeed;
        public List<String> order = new ArrayList<>(List.of("R", "1", "2", "3", "ATIVO"));
        /** first | lowest | index of an enemy (0-based, as a string). */
        public String target = "first";
        public List<String> spells = new ArrayList<>();
        /** Where the effective stats come from (item / rune / champion effect name -> amount). */
        public Map<String, Double> shieldSources = new LinkedHashMap<>();
        public Map<String, Double> healSources = new LinkedHashMap<>();
        public Map<String, Double> allyShieldSources = new LinkedHashMap<>();
        public Map<String, Double> allyHealSources = new LinkedHashMap<>();
        public Map<String, Double> vampSources = new LinkedHashMap<>();

        double stat(String s) {
            return total.getOrDefault(s, 0.0);
        }

        double bonus(String s) {
            return stat(s) - base.getOrDefault(s, 0.0);
        }
    }

    /** What one source (item, ability, spell) of a fighter did in the fight. */
    public static class Ledger {
        public int fighter;
        public String source;
        public double damage;
        public double extraShred;
        public double extraAmp;
        public double prevented;
        public double shieldCut;
        public double healCut;
        public double shieldAbsorbed;
        public double shieldLost;
        public double healed;
        public double healLost;
    }

    public static class FighterResult {
        public int index;
        public int team;
        public String name;
        public String champion;
        public int level;
        public double minute;
        public double maxHealth;
        public double health;
        public double shieldStart;
        public double dealt;
        public double taken;
        public double mitigated;
        public double shieldAbsorbed;
        public double healed;
        public double healLost;
        /** Seconds alive (the fight's length when it survived). */
        public double alive;
        public Double deathTime;
        public Map<String, Double> damageBySource = new LinkedHashMap<>();
        public List<String> notes = new ArrayList<>();
    }

    public static class Result {
        /** Winning team (0 or 1), or -1 when both still stand at the time limit. */
        public int winner = -1;
        public double duration;
        public List<FighterResult> fighters = new ArrayList<>();
        public List<Ledger> ledger = new ArrayList<>();
        /** Health (+ shield) of every fighter every half second. */
        public List<double[]> series = new ArrayList<>();
        public List<String> events = new ArrayList<>();
    }

    /** Runtime state of a fighter. */
    private final class State {
        final Fighter f;
        final FighterResult r = new FighterResult();
        double hp;
        double maxHp;
        boolean dead;
        double invulnerableUntil = -1;
        boolean stasisUsed;
        double attackTimer;
        double castLock;
        int attacks;
        boolean bladeArmed;
        double bladeReady;
        double[] cd = new double[4];
        double activeReady;
        Map<String, Double> strikeReady = new LinkedHashMap<>();
        Map<String, Double> auraNext = new LinkedHashMap<>();
        boolean startSpells;
        boolean defensiveSpells;
        /** Shield left by source key ("fighter|source"). */
        Map<String, Double> shield = new LinkedHashMap<>();
        double healPool;
        double healRate;
        double allyHealPool;
        double allyHealRate;
        // debuffs
        double gw;
        double gwUntil;
        String gwSource;
        double shieldCutPct;
        double shieldCutUntil = -1;
        String shieldCutSource;
        double[] shred = new double[2];
        double[] shredUntil = {-1, -1};
        String[] shredSource = new String[2];
        double mark;
        double markUntil = -1;
        String markSource;
        double exhaust;
        double exhaustUntil = -1;
        List<Burn> burns = new ArrayList<>();

        State(Fighter f) {
            this.f = f;
        }

        boolean targetable() {
            return !dead && now >= invulnerableUntil;
        }

        double shieldLeft() {
            return shield.values().stream().mapToDouble(Double::doubleValue).sum();
        }
    }

    private static final class Burn {
        State from;
        String source;
        double perTick;
        int ticks;
        double next;
        String type;
    }

    private final List<State> all = new ArrayList<>();
    private final Map<String, Ledger> ledger = new LinkedHashMap<>();
    private final Result result = new Result();
    private final CombatCatalog catalog;
    private double now;

    public CombatSimulator(CombatCatalog catalog) {
        this.catalog = catalog;
    }

    public Result run(List<Fighter> teamA, List<Fighter> teamB, double duration) {
        List<Fighter> fighters = new ArrayList<>(teamA);
        fighters.addAll(teamB);
        for (int i = 0; i < fighters.size(); i++) {
            Fighter f = fighters.get(i);
            f.index = i;
            f.team = i < teamA.size() ? 0 : 1;
            all.add(start(f));
        }
        // Shields given to allies are split among them at the start.
        for (State s : all) {
            List<State> allies = allies(s);
            if (!allies.isEmpty()) {
                s.f.allyShieldSources.forEach((src, amount) -> {
                    for (State a : allies) {
                        a.shield.merge(key(s, src), amount / allies.size(), Double::sum);
                    }
                });
            }
            s.r.shieldStart = s.shieldLeft();
        }
        double nextSeries = 0;
        for (now = 0; now <= duration + 1e-9; now += DT) {
            if (now >= nextSeries - 1e-9) {
                record();
                nextSeries += SERIES_EVERY;
            }
            for (State s : all) {
                if (!s.dead) {
                    act(s);
                }
            }
            tickPeriodic();
            int alive0 = alive(0);
            int alive1 = alive(1);
            if (alive0 == 0 || alive1 == 0) {
                result.winner = alive0 == 0 && alive1 == 0 ? -1 : alive0 == 0 ? 1 : 0;
                break;
            }
        }
        result.duration = Math.min(now, duration);
        record();
        for (State s : all) {
            s.r.health = Math.max(0, s.hp);
            s.r.alive = s.r.deathTime != null ? s.r.deathTime : result.duration;
            result.fighters.add(s.r);
        }
        result.ledger.addAll(ledger.values());
        return result;
    }

    private State start(Fighter f) {
        State s = new State(f);
        s.maxHp = Math.max(1, f.stat(Stats.MAX_HEALTH));
        s.hp = s.maxHp;
        f.shieldSources.forEach((src, v) -> s.shield.merge(key(s, src), v, Double::sum));
        s.healPool = f.healSources.values().stream().mapToDouble(Double::doubleValue).sum();
        s.healRate = s.healPool / 5;
        s.allyHealPool = f.allyHealSources.values().stream().mapToDouble(Double::doubleValue).sum();
        s.allyHealRate = s.allyHealPool / 5;
        s.attackTimer = 0;
        FighterResult r = s.r;
        r.index = f.index;
        r.team = f.team;
        r.name = f.name;
        r.champion = f.champion;
        r.level = f.level;
        r.minute = f.minute;
        r.maxHealth = s.maxHp;
        if (f.attackSpeed <= 0) {
            r.notes.add("sem ataques (velocidade de ataque desconhecida)");
        }
        for (AbilityParser.Ability a : f.abilities) {
            if (a.damage == null) {
                r.notes.add("habilidade " + a.slot + " (" + a.name + "): sem dano lido do texto");
            }
        }
        return s;
    }

    // ------------------------------------------------------------------ actions

    private void act(State s) {
        s.castLock -= DT;
        s.attackTimer -= DT;
        for (int i = 0; i < 4; i++) {
            s.cd[i] -= DT;
        }
        if (now < s.invulnerableUntil) {
            return;   // in stasis: cooldowns run, no action
        }
        State target = target(s);
        if (target == null) {
            return;
        }
        if (!s.startSpells) {
            s.startSpells = true;
            for (String sp : s.f.spells) {
                Map<String, Object> spell = catalog.spell(sp);
                if (spell == null) {
                    continue;
                }
                if ("Incendiar".equals(sp)) {
                    Burn b = new Burn();
                    b.from = s;
                    b.source = sp;
                    b.ticks = (int) num(spell, "duracao", 5);
                    b.perTick = level(spell, "dano", s.f.level) / b.ticks;
                    b.next = now + 1;
                    b.type = "verdadeiro";
                    target.burns.add(b);
                    applyGw(target, num(spell, "feridas", 0.4), 5, key(s, sp));
                    event(s.f.name + " usou Incendiar em " + target.f.name);
                } else if ("Exaustão".equals(sp)) {
                    target.exhaust = num(spell, "reduz_dano_causado", 0.4);
                    target.exhaustUntil = now + num(spell, "duracao", 2.5);
                    event(s.f.name + " usou Exaustão em " + target.f.name);
                }
            }
        }
        if (s.castLock <= 0) {
            for (String a : s.f.order) {
                if (cast(s, target, a)) {
                    s.castLock = CAST_LOCK;
                    break;
                }
            }
        }
        if (s.f.attackSpeed > 0 && s.attackTimer <= 0 && target.targetable()) {
            attack(s, target);
            s.attackTimer += 1 / (s.f.attackSpeed * (1 - attackSlow(s)));
        }
    }

    /** Attack speed reduction from the enemies' items (the strongest one counts). */
    private double attackSlow(State s) {
        double slow = 0;
        for (State e : all) {
            if (e.f.team != s.f.team && !e.dead) {
                for (ItemEffect fx : e.f.effects) {
                    if ("reduz_vel_ataque".equals(fx.kind())) {
                        slow = Math.max(slow, num(fx.p, "pct", 0));
                    }
                }
            }
        }
        return Math.min(0.9, slow);
    }

    private boolean cast(State s, State target, String action) {
        if ("ATIVO".equals(action)) {
            for (ItemEffect e : s.f.effects) {
                if ("ativo".equals(e.kind()) && now >= s.activeReady) {
                    double dmg = level(e.p, "dano", s.f.level) + num(e.p, "dda", 0) * s.f.stat(Stats.ATTACK_DAMAGE)
                            + num(e.p, "dda_base", 0) * s.f.base.getOrDefault(Stats.ATTACK_DAMAGE, 0.0)
                            + num(e.p, "dda_bonus", 0) * s.f.bonus(Stats.ATTACK_DAMAGE)
                            + num(e.p, "pdh", 0) * s.f.stat(Stats.ABILITY_POWER)
                            + num(e.p, "vida_max_alvo", 0) * target.maxHp;
                    deal(s, target, dmg, type(e.p), e.item, ITEM, 0);
                    double heal = num(e.p, "cura_dda", 0) * s.f.stat(Stats.ATTACK_DAMAGE);
                    if (heal > 0) {
                        heal(s, heal, Map.of(key(s, e.item), heal));
                    }
                    s.activeReady = now + num(e.p, "recarga", 60);
                    return true;
                }
            }
            return false;
        }
        int slot = "R".equals(action) ? 3 : "1".equals(action) ? 0 : "2".equals(action) ? 1 : "3".equals(action) ? 2 : -1;
        if (slot < 0 || slot >= s.f.abilities.size() || s.f.ranks[slot] <= 0 || s.cd[slot] > 0) {
            return false;
        }
        AbilityParser.Ability a = s.f.abilities.get(slot);
        int rank = s.f.ranks[slot];
        if (a.damage != null) {
            double dmg = a.damage.at(rank, s.f.total, s.f.base);
            deal(s, target, dmg, a.damage.type, a.slot + " · " + a.name, ABILITY, 0);
        }
        onAbility(s, target);
        double haste = s.f.stat(Stats.ABILITY_HASTE) + s.f.stat(slot == 3 ? "Ultimate Haste" : "Basic Ability Haste");
        double base = a.cooldown[Math.max(0, Math.min(a.cooldown.length, rank) - 1)];
        s.cd[slot] = base / (1 + haste / 100);
        return true;
    }

    /** Item effects triggered by an ability: burns, strikes and the next attack's spellblade. */
    private void onAbility(State s, State target) {
        s.bladeArmed = true;
        for (ItemEffect e : s.f.effects) {
            switch (e.kind()) {
                case "queimadura":
                    burn(s, target, e);
                    break;
                case "golpe":
                    String g = String.valueOf(e.p.getOrDefault("gatilho", "qualquer"));
                    if (!"ataque".equals(g)) {
                        strike(s, target, e);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private void attack(State s, State t) {
        s.attacks++;
        double ad = s.f.stat(Stats.ATTACK_DAMAGE);
        double c = Math.min(1, s.f.stat("% Critical Rate") / 100);
        double mult = 2.0;
        for (ItemEffect e : s.f.effects) {
            if ("critico".equals(e.kind())) {
                mult = Math.max(mult, num(e.p, "mult", 2.0));
            }
        }
        double raw = ad * (1 + c * (mult - 1));
        deal(s, t, raw, "fisico", "Ataque", ATTACK, ad * c * mult);
        for (ItemEffect e : s.f.effects) {
            if (t.dead) {
                break;
            }
            switch (e.kind()) {
                case "ao_contato":
                    deal(s, t, num(e.p, "dano", 0) + num(e.p, "pdh", 0) * s.f.stat(Stats.ABILITY_POWER)
                            + num(e.p, "vida_atual_alvo", 0) * Math.max(0, t.hp)
                            + num(e.p, "mana", 0) * s.f.stat(Stats.MAX_MANA), type(e.p), e.item, ITEM, 0);
                    break;
                case "a_cada_ataque":
                    int n = (int) num(e.p, "n", 3);
                    if (s.attacks % n == 0) {
                        deal(s, t, level(e.p, "dano", s.f.level)
                                + num(e.p, "dda_base", 0) * s.f.base.getOrDefault(Stats.ATTACK_DAMAGE, 0.0)
                                + num(e.p, "vida", 0) * s.maxHp + num(e.p, "vida_bonus", 0) * s.f.bonus(Stats.MAX_HEALTH),
                                type(e.p), e.item, ITEM, 0);
                    }
                    break;
                case "lamina":
                    if (s.bladeArmed && now >= s.bladeReady) {
                        double adBase = s.f.base.getOrDefault(Stats.ATTACK_DAMAGE, 0.0);
                        double dmg = num(e.p, "dda_base", 0) * adBase + num(e.p, "dda", 0) * ad
                                + num(e.p, "pdh", 0) * s.f.stat(Stats.ABILITY_POWER)
                                + num(e.p, "armadura_bonus", 0) * s.f.bonus(Stats.ARMOR)
                                + num(e.p, "vida_max_alvo", 0) * t.maxHp
                                + num(e.p, "critico_dano", 0) * c;
                        deal(s, t, dmg, type(e.p), e.item, ITEM, 0);
                        double heal = num(e.p, "cura_vida_max_alvo", 0) * t.maxHp
                                + num(e.p, "cura_vida_bonus", 0) * s.f.bonus(Stats.MAX_HEALTH)
                                + num(e.p, "cura_pdh", 0) * s.f.stat(Stats.ABILITY_POWER);
                        if (heal > 0) {
                            heal(s, heal, Map.of(key(s, e.item), heal));
                        }
                        s.bladeArmed = false;
                        s.bladeReady = now + 1.5;
                    }
                    break;
                case "golpe":
                    String g = String.valueOf(e.p.getOrDefault("gatilho", "qualquer"));
                    if (!"habilidade".equals(g)) {
                        strike(s, t, e);
                    }
                    break;
                default:
                    break;
            }
        }
        // Thorns of the target answer the attack.
        for (ItemEffect e : t.f.effects) {
            if ("espinhos".equals(e.kind()) && !s.dead) {
                deal(t, s, num(e.p, "dano", 0) + num(e.p, "armadura_bonus", 0) * t.f.bonus(Stats.ARMOR)
                        + num(e.p, "vida_bonus", 0) * t.f.bonus(Stats.MAX_HEALTH), type(e.p), e.item, REFLECT, 0);
            } else if ("feridas".equals(e.kind()) && Boolean.TRUE.equals(e.p.get("ao_ser_atingido"))) {
                applyGw(s, num(e.p, "pct", 0.4), 3, key(t, e.item));
            }
        }
    }

    private void strike(State s, State t, ItemEffect e) {
        if (now < s.strikeReady.getOrDefault(e.item, 0.0)) {
            return;
        }
        double dmg = level(e.p, "dano", s.f.level) + num(e.p, "vida_max", 0) * s.maxHp
                + num(e.p, "vida_max_alvo", 0) * t.maxHp + num(e.p, "pdh", 0) * s.f.stat(Stats.ABILITY_POWER)
                + num(e.p, "dda_bonus", 0) * s.f.bonus(Stats.ATTACK_DAMAGE)
                + num(e.p, "vida_bonus", 0) * s.f.bonus(Stats.MAX_HEALTH)
                + num(e.p, "mana", 0) * s.f.stat(Stats.MAX_MANA);
        deal(s, t, dmg, type(e.p), e.item, ITEM, 0);
        s.strikeReady.put(e.item, now + num(e.p, "recarga", 10));
    }

    private void burn(State s, State t, ItemEffect e) {
        Burn b = new Burn();
        b.from = s;
        b.source = e.item;
        b.ticks = (int) num(e.p, "duracao", 3);
        b.perTick = num(e.p, "por_s", 0) + num(e.p, "pdh_por_s", 0) * s.f.stat(Stats.ABILITY_POWER)
                + num(e.p, "vida_max_alvo_por_s", 0) * t.maxHp;
        b.next = now + 1;
        b.type = type(e.p);
        t.burns.removeIf(x -> x.from == s && x.source.equals(e.item));   // refreshed, not stacked
        t.burns.add(b);
    }

    // ------------------------------------------------------------------ periodic effects

    private void tickPeriodic() {
        for (State s : all) {
            if (s.dead) {
                continue;
            }
            // Burns and Ignite on s.
            for (Burn b : new ArrayList<>(s.burns)) {
                if (now >= b.next - 1e-9 && b.ticks > 0) {
                    deal(b.from, s, b.perTick, b.type, b.source, DOT, 0);
                    b.ticks--;
                    b.next += 1;
                }
            }
            s.burns.removeIf(b -> b.ticks <= 0);
            if (s.dead || now < s.invulnerableUntil) {
                continue;
            }
            // Auras of s on every enemy.
            for (ItemEffect e : s.f.effects) {
                if (!"aura".equals(e.kind())) {
                    continue;
                }
                double next = s.auraNext.getOrDefault(e.item, num(e.p, "a_cada", 1));
                if (now >= next - 1e-9) {
                    double dealt = 0;
                    for (State t : enemies(s)) {
                        double dmg = level(e.p, "dano", s.f.level) + num(e.p, "vida_bonus", 0) * s.f.bonus(Stats.MAX_HEALTH)
                                + num(e.p, "vida_max", 0) * s.maxHp;
                        dealt += deal(s, t, dmg, type(e.p), e.item, DOT, 0);
                    }
                    double heal = dealt * num(e.p, "cura_do_dano", 0);
                    if (heal > 0) {
                        heal(s, heal, Map.of(key(s, e.item), heal));
                    }
                    s.auraNext.put(e.item, next + num(e.p, "a_cada", 1));
                }
            }
            // Heals of the build come back while hurt; heals for allies go to the most hurt one.
            if (s.healPool > 0 && s.hp < s.maxHp) {
                double r = Math.min(s.healPool, s.healRate * DT);
                s.healPool -= r;
                heal(s, r, share(s, s.f.healSources, r));
            }
            if (s.allyHealPool > 0) {
                State hurt = null;
                for (State a : allies(s)) {
                    if (a.hp < a.maxHp && (hurt == null || a.hp / a.maxHp < hurt.hp / hurt.maxHp)) {
                        hurt = a;
                    }
                }
                if (hurt != null) {
                    double r = Math.min(s.allyHealPool, s.allyHealRate * DT);
                    s.allyHealPool -= r;
                    heal(hurt, r, share(s, s.f.allyHealSources, r));
                }
            }
            // Barrier / Heal below 35% of health.
            if (!s.defensiveSpells && s.hp < 0.35 * s.maxHp) {
                s.defensiveSpells = true;
                for (String sp : s.f.spells) {
                    Map<String, Object> spell = catalog.spell(sp);
                    if ("Barreira".equals(sp) && spell != null) {
                        addShield(s, level(spell, "escudo", s.f.level), key(s, sp));
                        event(s.f.name + " usou Barreira");
                    } else if ("Curar".equals(sp) && spell != null) {
                        double v = level(spell, "cura", s.f.level);
                        heal(s, v, Map.of(key(s, sp), v));
                        event(s.f.name + " usou Curar");
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ damage

    /** Deals one damage instance; returns the damage after mitigation (shields included). */
    private double deal(State a, State t, double raw, String type, String source, int kind, double critPart) {
        if (raw <= 0 || t.dead || !t.targetable() || a.dead && kind != DOT) {
            return 0;
        }
        if (now < a.exhaustUntil) {
            raw *= 1 - a.exhaust;
            critPart *= 1 - a.exhaust;
        }
        // Amplifications: the attacker's items, the target's marks, auras of the attacker's team.
        Map<String, Double> amps = new LinkedHashMap<>();
        for (ItemEffect e : a.f.effects) {
            if (!"amplifica".equals(e.kind()) || e.p.get("dano_tipo") != null && !type.equals(e.p.get("dano_tipo"))) {
                continue;
            }
            if (Boolean.TRUE.equals(e.p.get("so_habilidade")) && kind != ABILITY) {
                continue;
            }
            if (Boolean.TRUE.equals(e.p.get("vida_alta")) && a.hp <= 0.5 * a.maxHp) {
                continue;
            }
            double pct = num(e.p, "pct", 0);
            if (e.p.get("pct_por_s") != null) {
                pct = Math.min(num(e.p, "max", 1), num(e.p, "pct_por_s", 0) * now);
            }
            if (e.p.get("alvo_vida_bonus") != null) {
                pct = num(e.p, "max", 0) * Math.min(1, Math.max(0, t.f.bonus(Stats.MAX_HEALTH)) / num(e.p, "alvo_vida_bonus", 1200));
            }
            amps.merge(key(a, e.item), pct, Math::max);
        }
        if (now < t.markUntil) {
            amps.merge(t.markSource, t.mark, Math::max);
        }
        java.util.Set<String> auras = new java.util.HashSet<>();
        for (State ally : team(a.f.team)) {
            for (ItemEffect e : ally.f.effects) {
                if ("amplifica_alvo".equals(e.kind()) && Boolean.TRUE.equals(e.p.get("aura")) && !ally.dead
                        && (e.p.get("dano_tipo") == null || type.equals(e.p.get("dano_tipo"))) && auras.add(e.item)) {
                    amps.merge(key(ally, e.item), num(e.p, "pct", 0), Math::max);   // the same aura does not stack
                }
            }
        }
        double ampTotal = amps.values().stream().mapToDouble(Double::doubleValue).sum();
        double amped = raw * (1 + ampTotal);
        // Resistances.
        double mult = 1;
        double multNoShred = 1;
        int res = "fisico".equals(type) ? 0 : "magico".equals(type) ? 1 : -1;
        if (res >= 0) {
            double resist = t.f.stat(res == 0 ? Stats.ARMOR : Stats.MAGIC_RESIST);
            double shred = now < t.shredUntil[res] ? t.shred[res] : 0;
            double pen = a.f.stat(res == 0 ? "% Armor Penetration" : "% Magic Penetration") / 100;
            double flat = a.f.stat(res == 0 ? "Armor Penetration" : "Magic Penetration");
            mult = mitigation(resist, shred, pen, flat);
            multNoShred = mitigation(resist, 0, pen, flat);
        }
        double dmg = amped * mult;
        double extraShred = shredGain(amped, mult, multNoShred);
        // Damage reductions of the target.
        double prevented = 0;
        for (ItemEffect e : t.f.effects) {
            if (!"reduz_dano".equals(e.kind())) {
                continue;
            }
            String against = String.valueOf(e.p.get("contra"));
            double cut = 0;
            if ("ataque".equals(against) && kind == ATTACK) {
                cut = dmg * num(e.p, "pct", 0);
            } else if ("critico".equals(against) && kind == ATTACK && critPart > 0) {
                cut = critPart * (1 + ampTotal) * mult * num(e.p, "pct", 0);
            }
            if (cut > 0) {
                prevented += cut;
                led(key(t, e.item)).prevented += cut;
            }
        }
        dmg = Math.max(0, dmg - prevented);
        a.r.dealt += dmg;
        t.r.taken += dmg;
        t.r.mitigated += amped - amped * mult;
        a.r.damageBySource.merge(source, dmg, Double::sum);
        led(key(a, source)).damage += dmg;
        if (res >= 0 && extraShred > 0 && t.shredSource[res] != null && now < t.shredUntil[res]) {
            led(t.shredSource[res]).extraShred += extraShred * (dmg / Math.max(1e-9, amped * mult));
        }
        if (ampTotal > 0) {
            for (Map.Entry<String, Double> e : amps.entrySet()) {
                if (e.getValue() > 0) {
                    led(e.getKey()).extraAmp += dmg * e.getValue() / (1 + ampTotal);
                }
            }
        }
        // Shields (cut first by the attacker's shield reaver), then health.
        if (kind != REFLECT) {
            reaver(a, t);
        }
        double left = dmg;
        double shield = t.shieldLeft();
        if (shield > 0) {
            double absorbed = Math.min(shield, left);
            consumeShield(t, absorbed);
            t.r.shieldAbsorbed += absorbed;
            left -= absorbed;
        }
        t.hp -= left;
        if (t.hp <= 0.3 * t.maxHp && !t.stasisUsed && t.f.stat("Stasis") > 0) {
            t.stasisUsed = true;
            t.hp = Math.max(t.hp, 1);
            t.invulnerableUntil = now + t.f.stat("Stasis");
            event(t.f.name + " entrou em Estase (" + fmt(t.f.stat("Stasis")) + "s)");
        }
        if (t.hp <= 0) {
            t.dead = true;
            t.r.deathTime = now;
            event(t.f.name + " foi abatido por " + a.f.name + " (" + source + ")");
        }
        // Healing of the attacker and the effects its hit applies.
        if (kind != REFLECT && !a.dead) {
            double vamp = (kind == ATTACK ? a.f.stat("% Lifesteal") : 0) + a.f.stat("% Omnivamp");
            if (vamp > 0 && dmg > 0) {
                double h = dmg * vamp / 100;
                heal(a, h, share(a, a.f.vampSources, h));
            }
            onHit(a, t, type, kind);
        }
        return dmg;
    }

    /** Damage multiplier of a resistance after % reduction, % penetration and flat penetration. */
    public static double mitigation(double resist, double shred, double pen, double flat) {
        double eff = resist > 0 ? Math.max(0, resist * (1 - shred) * (1 - pen) - flat) : resist;
        return eff >= 0 ? 100 / (100 + eff) : 2 - 100 / (100 - eff);
    }

    private static double shredGain(double amped, double mult, double multNoShred) {
        return amped * (mult - multNoShred);
    }

    /** Debuffs the attacker's items apply on a hit: Grievous Wounds, shield reaver, resistance reduction, marks. */
    private void onHit(State a, State t, String type, int kind) {
        for (ItemEffect e : a.f.effects) {
            switch (e.kind()) {
                case "feridas":
                    if (Boolean.TRUE.equals(e.p.get("ao_ser_atingido"))) {
                        break;
                    }
                    String g = String.valueOf(e.p.getOrDefault("gatilho", "qualquer"));
                    if ("qualquer".equals(g) || g.equals(type)) {
                        applyGw(t, num(e.p, "pct", 0.4), 3, key(a, e.item));
                    }
                    break;
                case "reduz_resistencia":
                    int res = "armadura".equals(e.p.get("resistencia")) ? 0 : 1;
                    String gt = String.valueOf(e.p.get("gatilho"));
                    boolean ok = "fisico".equals(gt) ? "fisico".equals(type)
                            : "magico".equals(type) && kind != ATTACK;
                    if (ok) {
                        double cur = now < t.shredUntil[res] ? t.shred[res] : 0;
                        t.shred[res] = Math.min(num(e.p, "max", 0.3), cur + num(e.p, "por_acerto", 0.06));
                        t.shredUntil[res] = now + num(e.p, "duracao", 6);
                        t.shredSource[res] = key(a, e.item);
                    }
                    break;
                case "amplifica_alvo":
                    if (Boolean.TRUE.equals(e.p.get("marca")) && kind == ABILITY) {
                        t.mark = num(e.p, "pct", 0.07);
                        t.markUntil = now + num(e.p, "duracao", 4);
                        t.markSource = key(a, e.item);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    /** Shield reaver: a hit on an enemy not yet affected cuts every shield it has; then its new shields for 3s. */
    private void reaver(State a, State t) {
        for (ItemEffect e : a.f.effects) {
            if (!"corta_escudo".equals(e.kind())) {
                continue;
            }
            double cut = Math.min(num(e.p, "max", 0.6), num(e.p, "base", 0)
                    + num(e.p, "dda_bonus", 0) * a.f.bonus(Stats.ATTACK_DAMAGE)
                    + num(e.p, "pdh", 0) * a.f.stat(Stats.ABILITY_POWER));
            if (now >= t.shieldCutUntil) {
                double cutAmount = t.shieldLeft() * cut;
                if (cutAmount > 0) {
                    consumeShieldAsLost(t, cutAmount);
                    led(key(a, e.item)).shieldCut += cutAmount;
                }
            }
            t.shieldCutPct = Math.max(now < t.shieldCutUntil ? t.shieldCutPct : 0, cut);
            t.shieldCutUntil = now + 3;
            t.shieldCutSource = key(a, e.item);
        }
    }

    private void applyGw(State t, double pct, double seconds, String source) {
        if (now >= t.gwUntil || pct >= t.gw) {
            t.gw = pct;
            t.gwSource = source;
        }
        t.gwUntil = Math.max(t.gwUntil, now + seconds);
    }

    /** Heals a fighter (cut by Grievous Wounds), crediting the sources ("fighter|source" -> amount). */
    private void heal(State s, double amount, Map<String, Double> sources) {
        if (amount <= 0 || s.dead) {
            return;
        }
        double gw = now < s.gwUntil ? s.gw : 0;
        double total = sources.values().stream().mapToDouble(Double::doubleValue).sum();
        if (gw > 0 && s.gwSource != null) {
            led(s.gwSource).healCut += amount * gw;
            s.r.healLost += amount * gw;
        }
        double effective = Math.min(amount * (1 - gw), Math.max(0, s.maxHp - s.hp));
        s.hp += effective;
        s.r.healed += effective;
        for (Map.Entry<String, Double> e : sources.entrySet()) {
            double share = total > 0 ? e.getValue() / total : 0;
            led(e.getKey()).healed += effective * share;
            led(e.getKey()).healLost += amount * gw * share;
        }
    }

    private void addShield(State s, double amount, String source) {
        double cut = now < s.shieldCutUntil ? s.shieldCutPct : 0;
        if (cut > 0 && s.shieldCutSource != null) {
            led(s.shieldCutSource).shieldCut += amount * cut;
            led(source).shieldLost += amount * cut;
        }
        s.shield.merge(source, amount * (1 - cut), Double::sum);
    }

    private void consumeShield(State s, double amount) {
        double total = s.shieldLeft();
        for (Map.Entry<String, Double> e : s.shield.entrySet()) {
            double part = amount * e.getValue() / total;
            e.setValue(e.getValue() - part);
            led(e.getKey()).shieldAbsorbed += part;
        }
    }

    private void consumeShieldAsLost(State s, double amount) {
        double total = s.shieldLeft();
        for (Map.Entry<String, Double> e : s.shield.entrySet()) {
            double part = amount * e.getValue() / total;
            e.setValue(e.getValue() - part);
            led(e.getKey()).shieldLost += part;
        }
    }

    // ------------------------------------------------------------------ helpers

    private State target(State s) {
        List<State> enemies = enemies(s);
        if (enemies.isEmpty()) {
            return null;
        }
        String policy = s.f.target == null ? "first" : s.f.target;
        if ("lowest".equals(policy)) {
            State best = enemies.get(0);
            for (State e : enemies) {
                if (e.hp / e.maxHp < best.hp / best.maxHp) {
                    best = e;
                }
            }
            return best;
        }
        if (policy.matches("\\d+")) {
            int idx = Integer.parseInt(policy);
            for (State e : enemies) {
                if (team(e.f.team).indexOf(e) == idx) {
                    return e;
                }
            }
        }
        return enemies.get(0);
    }

    private List<State> enemies(State s) {
        List<State> out = new ArrayList<>();
        for (State x : all) {
            if (x.f.team != s.f.team && x.targetable()) {
                out.add(x);
            }
        }
        return out;
    }

    private List<State> allies(State s) {
        List<State> out = new ArrayList<>();
        for (State x : all) {
            if (x.f.team == s.f.team && x != s && !x.dead) {
                out.add(x);
            }
        }
        return out;
    }

    private List<State> team(int team) {
        List<State> out = new ArrayList<>();
        for (State x : all) {
            if (x.f.team == team) {
                out.add(x);
            }
        }
        return out;
    }

    private int alive(int team) {
        int n = 0;
        for (State x : all) {
            if (x.f.team == team && !x.dead) {
                n++;
            }
        }
        return n;
    }

    private Map<String, Double> share(State s, Map<String, Double> sources, double amount) {
        Map<String, Double> out = new LinkedHashMap<>();
        double total = sources.values().stream().mapToDouble(Double::doubleValue).sum();
        sources.forEach((k, v) -> out.put(key(s, k), total > 0 ? amount * v / total : 0));
        return out;
    }

    private static String key(State s, String source) {
        return s.f.index + "|" + source;
    }

    private Ledger led(String key) {
        return ledger.computeIfAbsent(key, k -> {
            Ledger l = new Ledger();
            int bar = k.indexOf('|');
            l.fighter = Integer.parseInt(k.substring(0, bar));
            l.source = k.substring(bar + 1);
            return l;
        });
    }

    private void record() {
        double[] row = new double[all.size() + 1];
        row[0] = Math.round(now * 100) / 100.0;
        for (int i = 0; i < all.size(); i++) {
            State s = all.get(i);
            row[i + 1] = s.dead ? 0 : Math.max(0, s.hp) + s.shieldLeft();
        }
        result.series.add(row);
    }

    private void event(String text) {
        result.events.add(fmt(now) + "s: " + text);
    }

    private static String type(Map<String, Object> p) {
        return String.valueOf(p.getOrDefault("dano_tipo", "fisico"));
    }

    static double num(Map<String, Object> p, String k, double def) {
        Object v = p.get(k);
        return v instanceof Number ? ((Number) v).doubleValue() : v == null ? def : Double.parseDouble(String.valueOf(v));
    }

    /** `k` at a champion level, interpolated up to `k_nv15` when given. */
    static double level(Map<String, Object> p, String k, int level) {
        double a = num(p, k, 0);
        if (p.get(k + "_nv15") == null) {
            return a;
        }
        return a + (num(p, k + "_nv15", a) - a) * (Math.max(1, Math.min(15, level)) - 1) / 14.0;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v).replace('.', ',');
    }
}
