package com.ornnplanner.arena.fight;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The hit area of an ability zone, in its own frame: the origin is where it was cast from and +x is where it points.
 * Every form answers the same question, {@link #gap}: how far a point is from the area (0 inside). A target is hit when
 * its circle touches the area, so {@link #hits} is one test for all forms, and {@link #outline} is the same geometry
 * for the map.
 *
 * <p>Forms of the sheets ({@code forma}): circulo (raio), anel (raio_interno, raio), setor (angulo, raio, raio_interno),
 * retangulo (comprimento, largura, centro), capsula (comprimento, largura, centro), poligono (vertices),
 * arco / corrente (pontos, largura). {@code centro: true} puts the origin in the middle instead of at the base.
 */
public final class HitShape {
    public static final Set<String> FORMS = Set.of("circulo", "anel", "setor", "retangulo", "capsula", "poligono", "arco", "corrente");

    private static final double EPS = 1e-9;
    private static final int ARC_POINTS = 32;

    public final String form;
    public final double radius;
    public final double inner;
    /** Whole opening of a sector, in radians. */
    public final double arc;
    public final double length;
    public final double width;
    public final boolean centered;
    /** Vertices of a polygon, or the points of a chain. */
    public final double[][] points;

    private HitShape(String form, double radius, double inner, double arc, double length, double width, boolean centered,
                     double[][] points) {
        this.form = form;
        this.radius = radius;
        this.inner = inner;
        this.arc = arc;
        this.length = length;
        this.width = width;
        this.centered = centered;
        this.points = points;
    }

    public static HitShape circle(double radius) {
        return new HitShape("circulo", radius, 0, 0, 0, 0, false, null);
    }

    public static HitShape ring(double inner, double radius) {
        return new HitShape("anel", radius, inner, 0, 0, 0, false, null);
    }

    public static HitShape sector(double arc, double radius, double inner) {
        return new HitShape("setor", radius, inner, arc, 0, 0, false, null);
    }

    public static HitShape rectangle(double length, double width, boolean centered) {
        return new HitShape("retangulo", 0, 0, 0, length, width, centered, null);
    }

    public static HitShape capsule(double length, double width, boolean centered) {
        return new HitShape("capsula", 0, 0, 0, length, width, centered, null);
    }

    public static HitShape polygon(double[][] vertices) {
        return new HitShape("poligono", 0, 0, 0, 0, 0, false, vertices);
    }

    public static HitShape chain(double[][] points, double width) {
        return new HitShape("corrente", 0, 0, 0, 0, width, false, points);
    }

    /** The shape of a zone of a sheet. */
    public static HitShape of(Map<String, Object> z) {
        String form = String.valueOf(z.get("forma"));
        switch (form) {
            case "circulo":
                return circle(num(z.get("raio")));
            case "anel":
                return ring(num(z.get("raio_interno")), num(z.get("raio")));
            case "setor":
                return sector(Math.toRadians(num(z.get("angulo"))), num(z.get("raio")), num(z.get("raio_interno")));
            case "retangulo":
                return rectangle(num(z.get("comprimento")), num(z.get("largura")), Boolean.TRUE.equals(z.get("centro")));
            case "capsula":
                return capsule(num(z.get("comprimento")), num(z.get("largura")), Boolean.TRUE.equals(z.get("centro")));
            case "poligono":
                return polygon(points(z.get("vertices")));
            case "arco":
            case "corrente":
                return chain(points(z.get("pontos")), num(z.get("largura")));
            default:
                throw new IllegalArgumentException("Forma desconhecida: " + form);
        }
    }

    /** The same shape {@code f} times as large, about its origin (forms that grow over time). */
    public HitShape scaled(double f) {
        double[][] p = null;
        if (points != null) {
            p = new double[points.length][];
            for (int i = 0; i < points.length; i++) {
                p[i] = new double[] {points[i][0] * f, points[i][1] * f};
            }
        }
        return new HitShape(form, radius * f, inner * f, arc, length * f, width * f, centered, p);
    }

    /** Distance from a point (in the shape's frame) to the area; 0 inside. */
    public double gap(double x, double y) {
        switch (form) {
            case "circulo":
                return Math.max(0, Math.hypot(x, y) - radius);
            case "anel": {
                double d = Math.hypot(x, y);
                return d < inner ? inner - d : Math.max(0, d - radius);
            }
            case "setor":
                return sectorGap(x, y);
            case "retangulo": {
                double from = centered ? -length / 2 : 0;
                double along = Math.max(Math.max(from - x, x - (from + length)), 0);
                double across = Math.max(0, Math.abs(y) - width / 2);
                return Math.hypot(along, across);
            }
            case "capsula": {
                double from = centered ? -length / 2 : 0;
                return Math.max(0, segment(from, 0, from + length, 0, x, y) - width / 2);
            }
            case "poligono":
                return polygonGap(x, y);
            default: {
                double best = Double.MAX_VALUE;
                for (int i = 0; i + 1 < points.length; i++) {
                    best = Math.min(best, segment(points[i][0], points[i][1], points[i + 1][0], points[i + 1][1], x, y));
                }
                return Math.max(0, best - width / 2);
            }
        }
    }

    /** A circle of radius {@code r} at a point of the shape's frame touches the area. */
    public boolean hits(double x, double y, double r) {
        return gap(x, y) <= r + EPS;
    }

    /** The same for a target in the world, with the shape at (ox, oy) pointing along {@code angle}. */
    public boolean hitsAt(double ox, double oy, double angle, double px, double py, double r) {
        double dx = px - ox;
        double dy = py - oy;
        double c = Math.cos(angle);
        double s = Math.sin(angle);
        return hits(dx * c + dy * s, -dx * s + dy * c, r);
    }

    /** How far from the origin the area goes. */
    public double reach() {
        switch (form) {
            case "circulo":
            case "anel":
            case "setor":
                return radius;
            case "retangulo": {
                double far = centered ? length / 2 : length;
                return Math.hypot(far, width / 2);
            }
            case "capsula":
                return (centered ? length / 2 : length) + width / 2;
            default: {
                double best = 0;
                for (double[] p : points) {
                    best = Math.max(best, Math.hypot(p[0], p[1]));
                }
                return best + width / 2;
            }
        }
    }

    /**
     * Distance a target of radius {@code r} at a point of the frame has to walk to leave the area; at most 0 when it is
     * out. What the predictions call its way out.
     */
    public double escape(double x, double y, double r) {
        double d = Math.hypot(x, y);
        switch (form) {
            case "circulo":
            case "anel":
                return radius + r - d;
            case "setor":
                return Math.min(radius + r - d, d * Math.sin(arc / 2) + r);
            case "retangulo":
            case "capsula": {
                double from = centered ? -length / 2 : 0;
                double along = Math.min(x - from, from + length - x) + r;
                return Math.min(along, width / 2 + r - Math.abs(y));
            }
            default:
                return hits(x, y, r) ? r + 1 : 0;
        }
    }

    /**
     * The outline as closed rings of points, in the shape's frame: one ring, or two for a form with a hole (the second
     * ring is the hole, drawn even-odd).
     */
    public List<double[][]> outline() {
        List<double[][]> rings = new ArrayList<>();
        switch (form) {
            case "circulo":
                rings.add(circlePoints(radius));
                break;
            case "anel":
                rings.add(circlePoints(radius));
                rings.add(circlePoints(inner));
                break;
            case "setor":
                rings.add(sectorPoints());
                break;
            case "retangulo": {
                double from = centered ? -length / 2 : 0;
                rings.add(new double[][] {{from, -width / 2}, {from + length, -width / 2}, {from + length, width / 2}, {from, width / 2}});
                break;
            }
            case "capsula": {
                double from = centered ? -length / 2 : 0;
                rings.add(chainPoints(new double[][] {{from, 0}, {from + length, 0}}));
                break;
            }
            case "poligono":
                rings.add(points);
                break;
            default:
                rings.add(chainPoints(points));
                break;
        }
        return rings;
    }

    /** The outline moved and turned to the world. */
    public List<double[][]> worldOutline(double ox, double oy, double angle) {
        double c = Math.cos(angle);
        double s = Math.sin(angle);
        List<double[][]> out = new ArrayList<>();
        for (double[][] ring : outline()) {
            double[][] w = new double[ring.length][];
            for (int i = 0; i < ring.length; i++) {
                w[i] = new double[] {Math.round((ox + ring[i][0] * c - ring[i][1] * s) * 10) / 10.0,
                    Math.round((oy + ring[i][0] * s + ring[i][1] * c) * 10) / 10.0};
            }
            out.add(w);
        }
        return out;
    }

    private double sectorGap(double x, double y) {
        double d = Math.hypot(x, y);
        double half = arc / 2;
        if (d > EPS && Math.abs(Math.atan2(y, x)) <= half) {
            return d < inner ? inner - d : Math.max(0, d - radius);
        }
        if (d <= EPS && inner <= 0) {
            return 0;
        }
        // outside the opening: the nearest of the two straight edges
        double c = Math.cos(half);
        double s = Math.sin(half);
        double upper = segment(inner * c, inner * s, radius * c, radius * s, x, y);
        double lower = segment(inner * c, -inner * s, radius * c, -radius * s, x, y);
        return Math.min(upper, lower);
    }

    private double polygonGap(double x, double y) {
        boolean inside = false;
        double best = Double.MAX_VALUE;
        for (int i = 0, j = points.length - 1; i < points.length; j = i++) {
            double[] a = points[i];
            double[] b = points[j];
            best = Math.min(best, segment(a[0], a[1], b[0], b[1], x, y));
            if (a[1] > y != b[1] > y && x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]) {
                inside = !inside;
            }
        }
        return inside ? 0 : best;
    }

    private static double segment(double ax, double ay, double bx, double by, double px, double py) {
        double dx = bx - ax;
        double dy = by - ay;
        double l2 = dx * dx + dy * dy;
        double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l2));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    private static double[][] circlePoints(double r) {
        double[][] p = new double[ARC_POINTS][];
        for (int i = 0; i < ARC_POINTS; i++) {
            double a = 2 * Math.PI * i / ARC_POINTS;
            p[i] = new double[] {r * Math.cos(a), r * Math.sin(a)};
        }
        return p;
    }

    private double[][] sectorPoints() {
        int n = ARC_POINTS / 2 + 1;
        List<double[]> p = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double a = -arc / 2 + arc * i / (n - 1);
            p.add(new double[] {radius * Math.cos(a), radius * Math.sin(a)});
        }
        if (inner > 0) {
            for (int i = n - 1; i >= 0; i--) {
                double a = -arc / 2 + arc * i / (n - 1);
                p.add(new double[] {inner * Math.cos(a), inner * Math.sin(a)});
            }
        } else {
            p.add(new double[] {0, 0});
        }
        return p.toArray(new double[0][]);
    }

    /** Way out along the points' left side, round end, way back along the right side, round start. */
    private double[][] chainPoints(double[][] spine) {
        double r = width / 2;
        List<double[]> left = new ArrayList<>();
        List<double[]> right = new ArrayList<>();
        for (int i = 0; i < spine.length; i++) {
            double[] a = spine[Math.max(0, i - 1)];
            double[] b = spine[Math.min(spine.length - 1, i + 1)];
            double t = Math.atan2(b[1] - a[1], b[0] - a[0]);
            left.add(new double[] {spine[i][0] - Math.sin(t) * r, spine[i][1] + Math.cos(t) * r});
            right.add(0, new double[] {spine[i][0] + Math.sin(t) * r, spine[i][1] - Math.cos(t) * r});
        }
        List<double[]> p = new ArrayList<>(left);
        addCap(p, spine[spine.length - 1], spine[spine.length - 2], r);
        p.addAll(right);
        addCap(p, spine[0], spine[1], r);
        return p.toArray(new double[0][]);
    }

    private static void addCap(List<double[]> p, double[] end, double[] before, double r) {
        double t = Math.atan2(end[1] - before[1], end[0] - before[0]);
        for (int i = 1; i < 8; i++) {
            double a = t + Math.PI / 2 - Math.PI * i / 8;
            p.add(new double[] {end[0] + Math.cos(a) * r, end[1] + Math.sin(a) * r});
        }
    }

    private static double num(Object v) {
        return v instanceof Number ? ((Number) v).doubleValue() : 0;
    }

    private static double[][] points(Object o) {
        List<?> list = (List<?>) o;
        double[][] p = new double[list.size()][];
        for (int i = 0; i < p.length; i++) {
            List<?> xy = (List<?>) list.get(i);
            p[i] = new double[] {num(xy.get(0)), num(xy.get(1))};
        }
        return p;
    }
}
