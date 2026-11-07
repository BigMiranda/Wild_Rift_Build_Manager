package com.ornnplanner.arena.rift;

import com.ornnplanner.arena.fight.HitShape;
import org.springframework.core.io.Resource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The geometry of the full map ({@code mapa_<patch>.yml}), and the way around it: a grid of the walls, A* on it, and the
 * path pulled straight. Read-only after it is built, so every arena shares one. What is written for the blue side holds
 * for the red one mirrored through the center.
 */
public final class RiftMap {

    /** How far from a wall the center of a unit stays: about a champion's body. */
    private static final double MARGIN = 60;
    private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** A named place of the map. */
    public static final class Spot {
        public final String name;
        public final String kind;
        public final String lane;
        public final int team;
        public final double x;
        public final double y;

        Spot(String name, String kind, String lane, int team, double x, double y) {
            this.name = name;
            this.kind = kind;
            this.lane = lane;
            this.team = team;
            this.x = x;
            this.y = y;
        }
    }

    public final double size;
    public final double cell;
    public final int cells;
    public final String source;
    private final boolean[] solid;
    public final List<double[][]> walls = new ArrayList<>();
    /** {x, y, radius}. */
    public final List<double[]> bushes = new ArrayList<>();
    /** The lanes from the blue Nexus to the red one. */
    public final Map<String, double[][]> lanes = new LinkedHashMap<>();
    public final List<Spot> towers = new ArrayList<>();
    public final List<Spot> camps = new ArrayList<>();
    public final List<Spot> crabs = new ArrayList<>();
    public final List<Spot> plants = new ArrayList<>();
    public final double[] nexusBlue;
    public final double[] nexusRed;
    public final double[] baronPit;
    public final double[] dragonPit;
    /** {x, y, radius} of the side's fountain. */
    public final double[] fountainBlue;
    public final double[] fountainRed;
    public final double[][] river;
    public final double riverWidth;
    private final Map<Long, double[][]> paths = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    private RiftMap(Map<String, Object> m) {
        size = num(m.get("tamanho"));
        cell = num(m.get("grade"));
        source = String.valueOf(m.get("fonte"));
        cells = (int) Math.ceil(size / cell);
        Map<String, Object> raster = (Map<String, Object>) m.get("paredes_grade");
        if (raster != null) {
            walls.addAll(rasterWalls((List<String>) raster.get("linhas"), num(raster.get("celula"))));
        }
        for (List<List<Number>> w : (List<List<List<Number>>>) m.getOrDefault("paredes", List.of())) {
            double[][] p = new double[w.size()][];
            double[][] q = new double[w.size()][];
            for (int i = 0; i < p.length; i++) {
                p[i] = new double[] {w.get(i).get(0).doubleValue(), w.get(i).get(1).doubleValue()};
                q[i] = new double[] {size - p[i][0], size - p[i][1]};
            }
            walls.add(p);
            walls.add(q);
        }
        solid = new boolean[cells * cells];
        List<HitShape> shapes = new ArrayList<>();
        for (double[][] w : walls) {
            shapes.add(HitShape.polygon(w));
        }
        for (int i = 0; i < solid.length; i++) {
            double x = (i % cells + 0.5) * cell;
            double y = (i / cells + 0.5) * cell;
            for (HitShape s : shapes) {
                if (s.hits(x, y, MARGIN)) {
                    solid[i] = true;
                    break;
                }
            }
        }
        for (Map<String, Object> b : (List<Map<String, Object>>) m.get("moitas")) {
            double x = num(b.get("x"));
            double y = num(b.get("y"));
            double r = num(b.get("raio"));
            bushes.add(new double[] {x, y, r});
            bushes.add(new double[] {size - x, size - y, r});
        }
        ((Map<String, List<List<Number>>>) m.get("rotas")).forEach((name, pts) -> {
            double[][] p = new double[pts.size()][];
            for (int i = 0; i < p.length; i++) {
                p[i] = new double[] {pts.get(i).get(0).doubleValue(), pts.get(i).get(1).doubleValue()};
            }
            lanes.put(name, p);
        });
        for (Map<String, Object> t : (List<Map<String, Object>>) m.get("torres")) {
            String kind = String.valueOf(t.get("tipo"));
            String lane = String.valueOf(t.get("rota"));
            towers.add(new Spot(kind, kind, lane, 0, num(t.get("x")), num(t.get("y"))));
            // mirrored through the center, the Baron lane of one side is where the Dragon lane of the other is
            towers.add(new Spot(kind, kind, mirrorLane(lane), 1, size - num(t.get("x")), size - num(t.get("y"))));
        }
        for (Map<String, Object> c : (List<Map<String, Object>>) m.get("acampamentos")) {
            String name = String.valueOf(c.get("nome"));
            camps.add(new Spot(name, name, null, 0, num(c.get("x")), num(c.get("y"))));
            camps.add(new Spot(name, name, null, 1, size - num(c.get("x")), size - num(c.get("y"))));
        }
        for (Map<String, Object> c : (List<Map<String, Object>>) m.get("aronguejos")) {
            crabs.add(new Spot("Aronguejo", "Aronguejo", null, 2, num(c.get("x")), num(c.get("y"))));
        }
        for (Map<String, Object> p : (List<Map<String, Object>>) m.get("plantas")) {
            String kind = String.valueOf(p.get("tipo"));
            plants.add(new Spot(kind, kind, null, 2, num(p.get("x")), num(p.get("y"))));
        }
        Map<String, Object> nexus = (Map<String, Object>) m.get("nexo");
        nexusBlue = new double[] {num(nexus.get("x")), num(nexus.get("y"))};
        nexusRed = new double[] {size - nexusBlue[0], size - nexusBlue[1]};
        Map<String, Object> pits = (Map<String, Object>) m.get("fossos");
        Map<String, Object> baron = (Map<String, Object>) pits.get("barao");
        Map<String, Object> dragon = (Map<String, Object>) pits.get("dragao");
        baronPit = new double[] {num(baron.get("x")), num(baron.get("y")), num(baron.get("raio"))};
        dragonPit = new double[] {num(dragon.get("x")), num(dragon.get("y")), num(dragon.get("raio"))};
        Map<String, Object> f = (Map<String, Object>) m.get("fonte_base");
        fountainBlue = new double[] {num(f.get("x")), num(f.get("y")), num(f.get("raio"))};
        fountainRed = new double[] {size - fountainBlue[0], size - fountainBlue[1], fountainBlue[2]};
        Map<String, Object> r = (Map<String, Object>) m.get("rio");
        List<Number> from = (List<Number>) r.get("de");
        List<Number> to = (List<Number>) r.get("ate");
        river = new double[][] {{from.get(0).doubleValue(), from.get(1).doubleValue()}, {to.get(0).doubleValue(), to.get(1).doubleValue()}};
        riverWidth = num(r.get("largura"));
    }

    /**
     * The walls of a grid traced from the minimap ({@code paredes_grade}): rows from the top (high y) down, '#' a wall;
     * runs of a row merged, and equal runs of the rows below them merged into one rectangle. The grid is the whole map,
     * already mirrored by the tracing (tools/mapa_minimapa.py).
     */
    static List<double[][]> rasterWalls(List<String> rows, double cell) {
        List<double[][]> out = new ArrayList<>();
        int n = rows.size();
        boolean[][] used = new boolean[n][];
        for (int r = 0; r < n; r++) {
            used[r] = new boolean[rows.get(r).length()];
        }
        for (int r = 0; r < n; r++) {
            String row = rows.get(r);
            int c = 0;
            while (c < row.length()) {
                if (row.charAt(c) != '#' || used[r][c]) {
                    c++;
                    continue;
                }
                int end = c;
                while (end < row.length() && row.charAt(end) == '#' && !used[r][end]) {
                    end++;
                }
                int down = r;
                while (down + 1 < n && sameRun(rows.get(down + 1), used[down + 1], c, end)) {
                    down++;
                }
                for (int k = r; k <= down; k++) {
                    for (int j = c; j < end; j++) {
                        used[k][j] = true;
                    }
                }
                double x0 = c * cell;
                double x1 = end * cell;
                double y1 = (n - r) * cell;
                double y0 = (n - 1 - down) * cell;
                out.add(new double[][] {{x0, y0}, {x1, y0}, {x1, y1}, {x0, y1}});
                c = end;
            }
        }
        return out;
    }

    private static boolean sameRun(String row, boolean[] used, int from, int end) {
        if (end > row.length() || from > 0 && row.charAt(from - 1) == '#' && !used[from - 1]
                || end < row.length() && row.charAt(end) == '#' && !used[end]) {
            return false;
        }
        for (int j = from; j < end; j++) {
            if (row.charAt(j) != '#' || used[j]) {
                return false;
            }
        }
        return true;
    }

    public static RiftMap load(Resource file) {
        try (InputStream in = file.getInputStream()) {
            Map<String, Object> m = new Yaml().load(in);
            return new RiftMap(m);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + file, e);
        }
    }

    private int index(double x, double y) {
        int cx = Math.max(0, Math.min(cells - 1, (int) (x / cell)));
        int cy = Math.max(0, Math.min(cells - 1, (int) (y / cell)));
        return cy * cells + cx;
    }

    public boolean solid(double x, double y) {
        return solid[index(x, y)];
    }

    /**
     * No wall on the straight line between two points: every cell the segment crosses is visited (a grid traversal), so
     * the line never clips the corner of a wall between two samples (the unit walking it would stop at the corner).
     */
    public boolean clear(double ax, double ay, double bx, double by) {
        int cx = clampCell(ax);
        int cy = clampCell(ay);
        int ex = clampCell(bx);
        int ey = clampCell(by);
        double dx = bx - ax;
        double dy = by - ay;
        int sx = dx > 0 ? 1 : -1;
        int sy = dy > 0 ? 1 : -1;
        double tdx = dx == 0 ? Double.MAX_VALUE : Math.abs(cell / dx);
        double tdy = dy == 0 ? Double.MAX_VALUE : Math.abs(cell / dy);
        double tx = dx == 0 ? Double.MAX_VALUE : ((sx > 0 ? (cx + 1) * cell - ax : ax - cx * cell) / Math.abs(dx));
        double ty = dy == 0 ? Double.MAX_VALUE : ((sy > 0 ? (cy + 1) * cell - ay : ay - cy * cell) / Math.abs(dy));
        for (int guard = 0; guard < 4 * cells; guard++) {
            if (solid[cy * cells + cx]) {
                return false;
            }
            if (cx == ex && cy == ey || Math.min(tx, ty) > 1) {
                return true;   // at the end cell, or the next crossing is past the end point
            }
            if (Math.abs(tx - ty) < 1e-12) {
                // through a corner exactly: both side cells count
                if (cx + sx >= 0 && cx + sx < cells && solid[cy * cells + cx + sx]
                        || cy + sy >= 0 && cy + sy < cells && solid[(cy + sy) * cells + cx]) {
                    return false;
                }
                cx += sx;
                cy += sy;
                tx += tdx;
                ty += tdy;
            } else if (tx < ty) {
                cx += sx;
                tx += tdx;
            } else {
                cy += sy;
                ty += tdy;
            }
            if (cx < 0 || cy < 0 || cx >= cells || cy >= cells) {
                return true;
            }
        }
        return true;
    }

    private int clampCell(double v) {
        return Math.max(0, Math.min(cells - 1, (int) (v / cell)));
    }

    /** The bush a point is in, or -1. */
    public int bush(double x, double y) {
        for (int i = 0; i < bushes.size(); i++) {
            double[] b = bushes.get(i);
            if (Math.hypot(b[0] - x, b[1] - y) <= b[2]) {
                return i;
            }
        }
        return -1;
    }

    /** The nearest free point to one inside a wall (itself when it is free). */
    public double[] free(double x, double y) {
        if (!solid(x, y)) {
            return new double[] {x, y};
        }
        int start = index(x, y);
        return center(nearestFree(start));
    }

    private double[] center(int i) {
        return new double[] {(i % cells + 0.5) * cell, (i / cells + 0.5) * cell};
    }

    private int nearestFree(int start) {
        int sx = start % cells;
        int sy = start / cells;
        for (int r = 1; r < cells; r++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    int x = sx + dx;
                    int y = sy + dy;
                    if (Math.max(Math.abs(dx), Math.abs(dy)) == r && x >= 0 && y >= 0 && x < cells && y < cells && !solid[y * cells + x]) {
                        return y * cells + x;
                    }
                }
            }
        }
        return start;
    }

    /** The way from a point to another around the walls, pulled straight: the points after the start. */
    public double[][] path(double ax, double ay, double bx, double by) {
        int a = index(ax, ay);
        int b = index(bx, by);
        if (solid[a]) {
            a = nearestFree(a);
        }
        if (solid[b]) {
            b = nearestFree(b);
        }
        long key = (long) a * cells * cells + b;
        double[][] cached = paths.get(key);
        if (cached == null) {
            cached = search(a, b);
            paths.put(key, cached);
        }
        double[][] out = cached.clone();
        if (out.length > 0 && clear(bx, by, out[out.length - 1][0], out[out.length - 1][1])) {
            out[out.length - 1] = new double[] {bx, by};
        }
        return out;
    }

    private double[][] search(int from, int to) {
        double[] best = new double[solid.length];
        int[] came = new int[solid.length];
        Arrays.fill(best, Double.MAX_VALUE);
        Arrays.fill(came, -1);
        PriorityQueue<double[]> open = new PriorityQueue<>((p, q) -> p[0] != q[0] ? Double.compare(p[0], q[0]) : Double.compare(p[1], q[1]));
        best[from] = 0;
        open.add(new double[] {0, from});
        int tx = to % cells;
        int ty = to / cells;
        while (!open.isEmpty()) {
            int i = (int) open.poll()[1];
            if (i == to) {
                break;
            }
            int x = i % cells;
            int y = i / cells;
            for (int[] s : STEPS) {
                int nx = x + s[0];
                int ny = y + s[1];
                if (nx < 0 || ny < 0 || nx >= cells || ny >= cells) {
                    continue;
                }
                int n = ny * cells + nx;
                // no cutting a corner of a wall
                if (solid[n] || s[0] != 0 && s[1] != 0 && (solid[y * cells + nx] || solid[ny * cells + x])) {
                    continue;
                }
                double cost = best[i] + (s[0] != 0 && s[1] != 0 ? 1.41421356 : 1);
                if (cost < best[n] - 1e-9) {
                    best[n] = cost;
                    came[n] = i;
                    open.add(new double[] {cost + Math.hypot(nx - tx, ny - ty), n});
                }
            }
        }
        List<double[]> way = new ArrayList<>();
        int at = to;
        while (at != from && at >= 0) {
            way.add(0, center(at));
            at = came[at];
        }
        if (at < 0) {
            return new double[0][];   // no way: the caller stays
        }
        // pull the string: from each point, the farthest one in a straight line
        List<double[]> pulled = new ArrayList<>();
        double[] cur = center(from);
        int k = 0;
        while (k < way.size()) {
            int far = k;
            for (int j = way.size() - 1; j > k; j--) {
                if (clear(cur[0], cur[1], way.get(j)[0], way.get(j)[1])) {
                    far = j;
                    break;
                }
            }
            pulled.add(way.get(far));
            cur = way.get(far);
            k = far + 1;
        }
        return pulled.toArray(new double[0][]);
    }

    /** What the screen draws. */
    public Map<String, Object> drawing() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("size", size);
        m.put("walls", walls);
        m.put("bushes", bushes);
        m.put("lanes", lanes);
        m.put("river", river);
        m.put("riverWidth", riverWidth);
        m.put("baronPit", baronPit);
        m.put("dragonPit", dragonPit);
        m.put("fountains", List.of(fountainBlue, fountainRed));
        m.put("source", source);
        return m;
    }

    /** The lane of the other side at the mirrored point: Baron and Dragon swap, the middle stays. */
    public static String mirrorLane(String lane) {
        return "barao".equals(lane) ? "dragao" : "dragao".equals(lane) ? "barao" : lane;
    }

    private static double num(Object v) {
        return v instanceof Number ? ((Number) v).doubleValue() : 0;
    }
}
