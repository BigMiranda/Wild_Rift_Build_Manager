package com.ornnplanner.arena.rift;

import com.ornnplanner.arena.ArenaModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Scenarios of the full map ready to fill: the places and what is alive are set, and the user only picks the builds and
 * the minutes. Their slots come without a build; roles name who goes where, never a champion.
 */
public final class RiftTemplates {

    private RiftTemplates() {
    }

    /** A template: its name and the scenario, with empty slots. */
    public static class Template {
        public String key;
        public String name;
        public String note;
        public ArenaModel.Scenario scenario;
    }

    public static List<Template> all(RiftMap map) {
        List<Template> out = new ArrayList<>();
        for (Map.Entry<String, String> lane : Map.of("barao", "rota do Barão", "dragao", "rota do Dragão", "meio", "meio").entrySet()) {
            duels(out, map, lane.getKey(), lane.getValue());
        }
        out.sort(java.util.Comparator.comparing(t -> t.key));
        double[] crab = {map.crabs.get(0).x, map.crabs.get(0).y};
        out.add(group("rio", "Luta no rio pelo Aronguejo", "caçador + meio + rota do Barão contra o mesmo", crab, 3, 6, null, 500));
        double[] crab2 = {map.crabs.get(1).x, map.crabs.get(1).y};
        out.add(group("rio-dupla", "Luta no rio, com a dupla do Dragão", "caçador + meio + dupla da rota do Dragão contra o mesmo", crab2, 4, 8, null, 500));
        out.add(group("barao", "Luta de equipe no Barão", "Barão vivo: quem entra no fosso sofre os debuffs", map.baronPit, 5, 20, null, 900));
        out.add(group("barao-fazendo", "Luta de equipe: azul fazendo o Barão", "o lado azul bate no Barão enquanto o vermelho chega", map.baronPit, 5, 20, "barao", 900));
        out.add(group("dragao", "Luta de equipe no Dragão", "Dragão vivo no fosso", map.dragonPit, 5, 14, null, 900));
        return out;
    }

    private static void duels(List<Template> out, RiftMap map, String lane, String label) {
        double[] own = tower(map, lane, 0);
        double[] other = tower(map, lane, 1);
        double[] mid = {(own[0] + other[0]) / 2, (own[1] + other[1]) / 2};
        double[] toOther = unit(own, other);
        out.add(duel("x1-" + lane + "-rota", "x1 no " + label.replace("rota do", "rota do") + ": na rota", lane, shift(mid, toOther, -300), shift(mid, toOther, 300)));
        out.add(duel("x1-" + lane + "-aliada", "x1 no " + label + ": sob a torre aliada", lane, shift(own, toOther, 150), shift(own, toOther, 650)));
        out.add(duel("x1-" + lane + "-inimiga", "x1 no " + label + ": sob a torre inimiga", lane, shift(other, toOther, -650), shift(other, toOther, -150)));
    }

    private static Template duel(String key, String name, String lane, double[] a, double[] b) {
        Template t = base(key, name, "com tropas e torres", 8, 60);
        t.scenario.fighters.add(slot(0, a));
        t.scenario.fighters.add(slot(1, b));
        return t;
    }

    private static Template group(String key, String name, String note, double[] at, int perSide, double minute, String takes, double gap) {
        Template t = base(key, name, note, minute, 60);
        t.scenario.rift.takes = takes;
        for (int team = 0; team < 2; team++) {
            for (int k = 0; k < perSide; k++) {
                double side = team == 0 ? -1 : 1;
                double spread = (k - (perSide - 1) / 2.0) * 170;
                t.scenario.fighters.add(slot(team, new double[] {at[0] + side * gap * 0.7 + spread * 0.7, at[1] + side * gap * 0.7 - spread * 0.7}));
            }
        }
        return t;
    }

    private static Template base(String key, String name, String note, double minute, double duration) {
        Template t = new Template();
        t.key = key;
        t.name = name;
        t.note = note;
        t.scenario = new ArenaModel.Scenario();
        t.scenario.name = name;
        t.scenario.duration = duration;
        t.scenario.rift = new ArenaModel.RiftOptions();
        t.scenario.rift.minute = minute;
        return t;
    }

    private static ArenaModel.Slot slot(int team, double[] at) {
        ArenaModel.Slot s = new ArenaModel.Slot();
        s.team = team;
        s.x = (double) Math.round(at[0]);
        s.y = (double) Math.round(at[1]);
        s.brain = "auto";
        return s;
    }

    private static double[] tower(RiftMap map, String lane, int team) {
        for (RiftMap.Spot s : map.towers) {
            if (s.team == team && lane.equals(s.lane) && "externa".equals(s.kind)) {
                return new double[] {s.x, s.y};
            }
        }
        throw new IllegalStateException("no outer tower in " + lane);
    }

    private static double[] unit(double[] a, double[] b) {
        double d = Math.hypot(b[0] - a[0], b[1] - a[1]);
        return new double[] {(b[0] - a[0]) / d, (b[1] - a[1]) / d};
    }

    private static double[] shift(double[] p, double[] dir, double by) {
        return new double[] {p[0] + dir[0] * by, p[1] + dir[1] * by};
    }
}
