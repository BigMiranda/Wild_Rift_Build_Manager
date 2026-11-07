package com.ornnplanner.arena.guide;

import com.ornnplanner.repo.BuildRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A guide (Rodada 9, part 7): the logic "if X happens, I buy Y" of a champion, as a tree. A condition node holds the
 * branch taken when it holds ({@link Node#then}) and the other ({@link Node#otherwise}); a decision node is the next item
 * or component, a page of runes or a boot swap. Reading a guide walks it in order: nodes one after the other, a condition
 * going down the branch its context picks. No rule names a champion, item or rune.
 */
public final class Guide {

    private Guide() {
    }

    /** A node: a condition with its two branches, or a decision. */
    public static class Node {
        public String id;
        /** condicao | decisao */
        public String kind = "decisao";
        public Condition cond;
        public List<Node> then = new ArrayList<>();
        public List<Node> otherwise = new ArrayList<>();
        /** item | componente | bota | runas */
        public String decision;
        public Long itemId;
        public BuildRepository.RunePage runePage;
        public String note;
        /** How often this branch's condition holds in the scenarios (the evaluation's weight; null = even). */
        public Double frequency;
    }

    /**
     * A condition on the match. Types:
     * inimigos_arquetipo (value: an archetype key; count), inimigos_classe (value: a class; count),
     * inimigos_dano (value: magico | fisico; count: enemies whose damage is mostly that), adversario_rota (value: a
     * class or a champion), ouro (value: frente | atras), minuto (op: >= | <; number), item_adversario (itemId: an
     * enemy finished it).
     */
    public static class Condition {
        public String type;
        public String value;
        public Integer count;
        public String op = ">=";
        public Double number;
        public Long itemId;
    }

    /** One enemy as the guide sees it. */
    public static class Enemy {
        public String champion;
        public String championClass;
        public String archetype;
        /** fisico | magico | misto */
        public String damage;
        public List<Long> items = new ArrayList<>();
    }

    /** What a guide is read against. */
    public static class Context {
        public List<Enemy> enemies = new ArrayList<>();
        public Enemy laneOpponent;
        /** Gold minus the lane opponent's (0 = even). */
        public double goldDiff;
        public double minute;
    }

    /** Whether a condition holds in a context. */
    public static boolean holds(Condition c, Context ctx) {
        if (c == null || c.type == null) {
            return false;
        }
        int need = c.count == null ? 1 : c.count;
        switch (c.type) {
            case "inimigos_arquetipo":
                return ctx.enemies.stream().filter(e -> eq(e.archetype, c.value)).count() >= need;
            case "inimigos_classe":
                return ctx.enemies.stream().filter(e -> eq(e.championClass, c.value)).count() >= need;
            case "inimigos_dano":
                return ctx.enemies.stream().filter(e -> eq(e.damage, c.value)).count() >= need;
            case "adversario_rota":
                return ctx.laneOpponent != null && (eq(ctx.laneOpponent.champion, c.value) || eq(ctx.laneOpponent.championClass, c.value));
            case "ouro":
                return "frente".equals(c.value) ? ctx.goldDiff > 0 : "atras".equals(c.value) && ctx.goldDiff < 0;
            case "minuto": {
                double m = c.number == null ? 0 : c.number;
                return "<".equals(c.op) ? ctx.minute < m : ctx.minute >= m;
            }
            case "item_adversario":
                return c.itemId != null && ctx.enemies.stream().anyMatch(e -> e.items.contains(c.itemId));
            default:
                return false;
        }
    }

    private static boolean eq(String a, String b) {
        return a != null && b != null && a.trim().toLowerCase(Locale.ROOT).equals(b.trim().toLowerCase(Locale.ROOT));
    }

    /** The decisions a context takes through the guide, in order. */
    public static List<Node> read(List<Node> nodes, Context ctx) {
        List<Node> out = new ArrayList<>();
        walk(nodes, ctx, out);
        return out;
    }

    private static void walk(List<Node> nodes, Context ctx, List<Node> out) {
        for (Node n : nodes == null ? List.<Node>of() : nodes) {
            if ("condicao".equals(n.kind)) {
                walk(holds(n.cond, ctx) ? n.then : n.otherwise, ctx, out);
            } else {
                out.add(n);
            }
        }
    }

    /**
     * The paths of a guide: every way through its conditions, each with its decisions and the product of the
     * frequencies on the way (null frequencies split a condition evenly). What the evaluation weighs.
     */
    public static List<Path> paths(List<Node> nodes) {
        List<Path> out = new ArrayList<>();
        expand(nodes == null ? List.of() : nodes, 0, new Path(), out);
        return out;
    }

    /** A path through a guide: its decisions, its weight and the choices on the way. */
    public static class Path {
        public List<Node> decisions = new ArrayList<>();
        public double weight = 1;
        public List<String> choices = new ArrayList<>();

        Path copy() {
            Path p = new Path();
            p.decisions = new ArrayList<>(decisions);
            p.weight = weight;
            p.choices = new ArrayList<>(choices);
            return p;
        }
    }

    private static void expand(List<Node> nodes, int i, Path p, List<Path> out) {
        if (i >= nodes.size()) {
            out.add(p);
            return;
        }
        Node n = nodes.get(i);
        if (!"condicao".equals(n.kind)) {
            p.decisions.add(n);
            expand(nodes, i + 1, p, out);
            return;
        }
        double f = n.frequency == null ? 0.5 : Math.max(0, Math.min(1, n.frequency));
        List<Node> rest = nodes.subList(i + 1, nodes.size());
        Path yes = p.copy();
        yes.weight *= f;
        yes.choices.add(describe(n.cond) + ": sim");
        List<Node> a = new ArrayList<>(n.then);
        a.addAll(rest);
        expand(a, 0, yes, out);
        Path no = p.copy();
        no.weight *= 1 - f;
        no.choices.add(describe(n.cond) + ": não");
        List<Node> b = new ArrayList<>(n.otherwise);
        b.addAll(rest);
        expand(b, 0, no, out);
    }

    /** A condition in words (pt-BR, as the guides are shown). */
    public static String describe(Condition c) {
        if (c == null) {
            return "?";
        }
        String n = c.count == null || c.count <= 1 ? "" : c.count + " ou mais ";
        switch (c.type == null ? "" : c.type) {
            case "inimigos_arquetipo":
                return n + "inimigos com build " + c.value;
            case "inimigos_classe":
                return n + "inimigos " + c.value;
            case "inimigos_dano":
                return n + "inimigos com dano " + ("magico".equals(c.value) ? "mágico" : "físico") + " alto";
            case "adversario_rota":
                return "adversário de rota " + c.value;
            case "ouro":
                return "frente".equals(c.value) ? "estou à frente em ouro" : "estou atrás em ouro";
            case "minuto":
                return ("<".equals(c.op) ? "antes dos " : "a partir dos ") + (c.number == null ? 0 : c.number.intValue()) + " min";
            case "item_adversario":
                return "o adversário fechou o item " + c.itemId;
            default:
                return c.type;
        }
    }
}
