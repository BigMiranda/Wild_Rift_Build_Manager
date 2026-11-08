package com.ornnplanner.arena.fight;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads an ability sheet of {@code kits_<patch>.yml} and says what is wrong with it: a field the arena does not know, a
 * unit that looks wrong (seconds in a percentage, degrees over 360), or an ability that does nothing. The sheets of the
 * other champions come from outside, so this is the check they go through.
 */
public final class SheetValidator {

    private static final Set<String> CHAMPION = Set.of("raio", "alcance", "espera_ataque", "velocidade_projetil", "efeitos_de_forma",
            "habilidades", "fragil", "mobilidade", "acumulos", "recurso", "marcas", "marcas_ksante", "formas", "forma_inicial", "fontes", "nota", "qualidade",
            "armazena", "acumulos_permanentes", "lacunas");
    private static final Set<String> SHAPES = Set.of("linha", "area", "cone", "avanco", "alvo", "ataque", "proprio", "canal", "campo",
            "elemental", "empurrao", "tudo_ou_nada", "zonas", "piscar", "teleporte", "invocacao", "parede", "anexar", "volta_no_tempo");
    private static final Set<String> ABILITY = Set.of("nome", "forma", "recarga", "conjuracao", "atraso", "alcance", "largura", "raio",
            "raio_fortalecido", "raio_interno", "angulo", "velocidade", "velocidade_mais_vdm", "velocidade_final", "aceleracao", "duracao",
            "intervalo", "dano", "danos", "efeitos", "efeitos_fortalecido", "fortalecido", "zonas", "origem", "expande", "move", "segue",
            "atravessa", "para_apos_campeao", "pilar", "avanca", "imparavel", "imune_deslocamento", "cc_durante_avanco", "ultimo_golpe",
            "terreno", "destroi_pilar", "distancia", "comprimento", "velocidade_projetil", "reconjurar", "trava", "janela", "alcance_extra",
            "reinicia_ataque", "salta", "por_acumulo", "cargas", "recarga_carga", "sem_dano", "proximo_ataque", "invisivel_com_R", "marca",
            "escudo", "duracao_escudo", "duracao_velocidade", "velocidade_movimento", "velocidade_ataque", "vampirismo", "dda", "vida_extra",
            "furia_ao_usar", "furia_por_s", "aura", "carregar", "segunda_carga", "empurra", "terreno_ate", "fontes", "nota",
            // mechanics of the other champions
            "custo", "alvo", "cura", "invisivel", "invulneravel", "nao_selecionavel", "imparavel_por", "canaliza", "invoca", "perpendicular",
            "segundos", "ate_ponto", "ate_alvo", "atravessa_parede", "interrompivel", "troca_forma",
            // Rodada das fichas, lote 1
            "ao_acertar", "reducao_dano", "consome_armazenado", "deixa_marco", "linha_entre", "ataques", "lacuna", "status", "status_pct");
    private static final Set<String> ZONE = Set.of("nome", "forma", "raio", "raio_interno", "angulo", "comprimento", "largura", "centro",
            "vertices", "pontos", "dano", "efeitos", "acumula");
    private static final Set<String> EFFECTS = Set.of("lentidao", "duracao", "atordoa", "atordoa_max", "arremessa", "arremessa_demais", "puxa",
            "fragil", "marca", "acumulos", "reduz_rm", "reduz_arm", "cura_propria", "escudo_proprio", "cura_vida_perdida", "cura_max",
            "cura_por_campeao", "remove_escudos", "empurra",
            "enraiza", "silencia", "cega", "provoca", "amedronta", "encanta", "adormece", "suprime", "polimorfa", "lentidao_polimorfo",
            "afasta", "afasta_velocidade", "afasta_parede", "rouba", "executa_abaixo", "reduz_vel_ataque");
    private static final Set<String> DAMAGE = Set.of("tipo", "base", "dda", "dda_base", "dda_bonus", "pdh", "vida_max_alvo", "vida_bonus",
            "arm_bonus", "rm_bonus", "vida_max", "minimo", "base_nv15", "vida_max_alvo_nv15",
            "vida_perdida_alvo", "vida_atual_alvo", "vida_bonus_alvo", "armazenado", "acumulos_permanentes", "base_nivel", "base_nivel_nv15", "arm", "rm");
    private static final Set<String> DAMAGE_TYPES = Set.of("fisico", "magico", "verdadeiro", "adaptativo");
    private static final Set<String> SUMMON = Set.of("nome", "comportamento", "vida", "dda", "armadura", "rm", "velocidade", "velocidade_ataque",
            "alcance", "raio", "duracao", "quantidade", "fracao", "raio_disparo", "raio_explosao", "arma_em", "dano", "efeitos", "morre_com_dono",
            "distancia_dono", "velocidade_projetil");
    private static final Set<String> BEHAVIORS = Set.of("segue", "ataca", "parada", "explode", "mina", "clone");
    /** Fields that make an ability do something on their own. */
    private static final Set<String> DOES_SOMETHING = Set.of("dano", "danos", "efeitos", "zonas", "escudo", "cura", "proximo_ataque", "aura",
            "velocidade_movimento", "velocidade_ataque", "vampirismo", "dda", "vida_extra", "invisivel", "invulneravel", "nao_selecionavel",
            "imparavel_por", "invoca", "terreno", "pilar", "troca_forma", "alcance_extra", "fortalecido", "reconjurar",
            "linha_entre", "deixa_marco", "reducao_dano", "status", "status_pct");
    /** Shapes that do something by moving the champion or the map. */
    private static final Set<String> ACTS_ALONE = Set.of("avanco", "piscar", "teleporte", "parede", "anexar", "volta_no_tempo", "elemental",
            "tudo_ou_nada", "empurrao");

    private SheetValidator() {
    }

    /** What is wrong with one champion's sheet (empty = nothing). */
    public static List<String> check(String champion, Map<String, Object> kit) {
        List<String> out = new ArrayList<>();
        unknown(out, champion, kit, CHAMPION);
        length(out, champion, "raio", kit.get("raio"));
        length(out, champion, "alcance", kit.get("alcance"));
        Object abilities = kit.get("habilidades");
        if (abilities instanceof Map) {
            ((Map<?, ?>) abilities).forEach((slot, a) -> {
                if (a instanceof Map) {
                    ability(out, champion + " " + slot, map(a));
                }
            });
        }
        Object forms = kit.get("formas");
        if (forms instanceof Map) {
            ((Map<?, ?>) forms).forEach((name, f) -> {
                Object own = map(f).get("habilidades");
                if (own instanceof Map) {
                    ((Map<?, ?>) own).forEach((slot, a) -> ability(out, champion + " (" + name + ") " + slot, map(a)));
                }
            });
        }
        return out;
    }

    private static void ability(List<String> out, String where, Map<String, Object> ab) {
        unknown(out, where, ab, ABILITY);
        Object shape = ab.get("forma");
        if (!SHAPES.contains(String.valueOf(shape))) {
            out.add(where + ": forma desconhecida \"" + shape + "\"");
        }
        cooldown(out, where, ab.get("recarga"));
        range(out, where, "conjuracao", ab.get("conjuracao"), 0, 5, "s");
        range(out, where, "atraso", ab.get("atraso"), 0, 10, "s");
        range(out, where, "duracao", ab.get("duracao"), 0, 120, "s");
        range(out, where, "angulo", ab.get("angulo"), 1, 360, "graus");
        length(out, where, "alcance", ab.get("alcance"));
        length(out, where, "raio", ab.get("raio"));
        length(out, where, "largura", ab.get("largura"));
        Object speed = ab.get("velocidade");
        range(out, where, "velocidade", speed, 50, 20000, "unidades/s");
        damage(out, where, ab.get("dano"));
        if (ab.get("danos") instanceof Map) {
            map(ab.get("danos")).values().forEach(d -> damage(out, where, d));
        }
        effects(out, where, ab.get("efeitos"));
        effects(out, where, ab.get("efeitos_fortalecido"));
        if (ab.get("zonas") instanceof List) {
            zones(out, where, ab);
        } else if ("zonas".equals(shape) || "parede".equals(shape)) {
            out.add(where + ": forma " + shape + " sem zonas");
        }
        if (ab.get("invoca") instanceof Map) {
            Map<String, Object> s = map(ab.get("invoca"));
            unknown(out, where + " invoca", s, SUMMON);
            if (!BEHAVIORS.contains(String.valueOf(s.getOrDefault("comportamento", "ataca")))) {
                out.add(where + ": comportamento desconhecido \"" + s.get("comportamento") + "\"");
            }
            damage(out, where + " invoca", s.get("dano"));
            effects(out, where + " invoca", s.get("efeitos"));
        } else if ("invocacao".equals(shape)) {
            out.add(where + ": forma invocacao sem invoca");
        }
        boolean does = ACTS_ALONE.contains(String.valueOf(shape));
        for (String k : DOES_SOMETHING) {
            does |= ab.get(k) != null;
        }
        if (!does) {
            out.add(where + ": a habilidade não faz nada");
        }
    }

    private static void zones(List<String> out, String where, Map<String, Object> ab) {
        Map<String, Object> named = ab.get("danos") instanceof Map ? map(ab.get("danos")) : Map.of();
        for (Object o : (List<?>) ab.get("zonas")) {
            Map<String, Object> z = map(o);
            unknown(out, where + " zona", z, ZONE);
            if (!HitShape.FORMS.contains(String.valueOf(z.get("forma")))) {
                out.add(where + ": forma de zona desconhecida \"" + z.get("forma") + "\"");
                continue;
            }
            range(out, where + " zona", "angulo", z.get("angulo"), 1, 360, "graus");
            length(out, where + " zona", "raio", z.get("raio"));
            if (z.get("raio_interno") != null && z.get("raio") != null
                    && ((Number) z.get("raio_interno")).doubleValue() >= ((Number) z.get("raio")).doubleValue()) {
                out.add(where + ": raio_interno maior que o raio");
            }
            Object d = z.get("dano");
            if (d instanceof String && !named.containsKey(d)) {
                out.add(where + ": dano \"" + d + "\" não está em danos");
            } else if (!(d instanceof String)) {
                damage(out, where + " zona", d);
            }
            effects(out, where + " zona", z.get("efeitos"));
        }
    }

    private static void damage(List<String> out, String where, Object d) {
        if (!(d instanceof Map)) {
            return;
        }
        Map<String, Object> m = map(d);
        unknown(out, where + " dano", m, DAMAGE);
        if (m.get("tipo") != null && !DAMAGE_TYPES.contains(String.valueOf(m.get("tipo")))) {
            out.add(where + ": tipo de dano desconhecido \"" + m.get("tipo") + "\"");
        }
    }

    private static void effects(List<String> out, String where, Object e) {
        if (!(e instanceof Map)) {
            return;
        }
        Map<String, Object> m = map(e);
        unknown(out, where + " efeitos", m, EFFECTS);
        fraction(out, where, "lentidao", m.get("lentidao"));
        fraction(out, where, "executa_abaixo", m.get("executa_abaixo"));
        for (String k : List.of("atordoa", "enraiza", "silencia", "cega", "provoca", "amedronta", "encanta", "adormece", "suprime", "polimorfa", "arremessa")) {
            seconds(out, where, k, m.get(k));
        }
    }

    private static void cooldown(List<String> out, String where, Object v) {
        if (v == null) {
            return;
        }
        if (v instanceof List && (((List<?>) v).isEmpty() || ((List<?>) v).size() > 6)) {
            out.add(where + ": recarga deve ter de 1 a 6 valores");
        }
        for (double x : values(v)) {
            if (x < 0 || x > 400) {
                out.add(where + ": recarga estranha (" + x + " s)");
            }
        }
    }

    private static void seconds(List<String> out, String where, String key, Object v) {
        if (Boolean.TRUE.equals(v)) {
            return;
        }
        for (double x : values(v)) {
            if (x <= 0 || x > 20) {
                out.add(where + ": " + key + " estranho (" + x + " s)");
            }
        }
    }

    private static void fraction(List<String> out, String where, String key, Object v) {
        for (double x : values(v)) {
            if (x < 0 || x > 1) {
                out.add(where + ": " + key + " é fração de 0 a 1, não " + x);
            }
        }
    }

    private static void length(List<String> out, String where, String key, Object v) {
        if ("global".equals(v)) {
            return;
        }
        range(out, where, key, v, 1, 8000, "unidades");
    }

    private static void range(List<String> out, String where, String key, Object v, double min, double max, String unit) {
        for (double x : values(v)) {
            if (x < min || x > max) {
                out.add(where + ": " + key + " estranho (" + x + " " + unit + ")");
            }
        }
    }

    private static List<Double> values(Object v) {
        List<Double> out = new ArrayList<>();
        if (v instanceof Number) {
            out.add(((Number) v).doubleValue());
        } else if (v instanceof List) {
            for (Object x : (List<?>) v) {
                if (x instanceof Number) {
                    out.add(((Number) x).doubleValue());
                }
            }
        }
        return out;
    }

    private static void unknown(List<String> out, String where, Map<String, Object> m, Set<String> known) {
        for (String k : m.keySet()) {
            if (!known.contains(k)) {
                out.add(where + ": campo desconhecido \"" + k + "\"");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }
}
