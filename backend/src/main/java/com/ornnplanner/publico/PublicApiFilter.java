package com.ornnplanner.publico;

import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The door of the public version: under {@code /api}, only what {@link PublicApi} lists exists (anything else is 404),
 * calls from other sites' pages are refused unless their origin is allowed, and each IP has a number of requests and of
 * calculations per minute (past it: 429 "tente mais tarde"). The IP only counts in memory, for the current minute; it is
 * never written anywhere.
 */
@Component
@Profile(PublicMode.PROFILE)
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PublicApiFilter extends OncePerRequestFilter {

    public static final String NOT_HERE = "Não existe na versão pública.";
    public static final String LATER = "Muitos pedidos seguidos: tente mais tarde.";

    private final PublicLimits limits;
    /** IP → [minute, requests, calculations] of the current minute. */
    private final Map<String, long[]> windows = new ConcurrentHashMap<>();

    public PublicApiFilter(PublicLimits limits) {
        this.limits = limits;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI().substring(req.getContextPath().length());
        if (!path.startsWith("/api/") && !path.equals("/api")) {
            chain.doFilter(req, res);   // the page itself and its files
            return;
        }
        if (!sameSiteOrAllowed(req)) {
            send(res, 403, "Origem não permitida.");
            return;
        }
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            chain.doFilter(req, res);   // a preflight of an allowed origin: Spring's CORS answers it
            return;
        }
        PublicApi.Rule rule = PublicApi.find(req.getMethod(), path);
        if (rule == null) {
            send(res, 404, NOT_HERE);
            return;
        }
        if (!count(req.getRemoteAddr(), rule.compute)) {
            res.setHeader("Retry-After", "60");
            send(res, 429, LATER);
            return;
        }
        chain.doFilter(req, res);
    }

    /** No Origin (same page, curl), the request's own origin, or one of {@code planner.public.allowed-origins}. */
    private boolean sameSiteOrAllowed(HttpServletRequest req) {
        String origin = req.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            return true;
        }
        int port = req.getServerPort();
        boolean defaultPort = "http".equals(req.getScheme()) && port == 80 || "https".equals(req.getScheme()) && port == 443;
        String own = req.getScheme() + "://" + req.getServerName() + (defaultPort ? "" : ":" + port);
        return origin.equalsIgnoreCase(own) || limits.getAllowedOrigins().stream().anyMatch(origin::equalsIgnoreCase);
    }

    /** Counts a request of the IP in the current minute; false = over a limit. */
    boolean count(String ip, boolean compute) {
        long minute = System.currentTimeMillis() / 60_000;
        if (windows.size() > 50_000) {
            prune(minute);
        }
        long[] w = windows.compute(ip == null ? "?" : ip, (k, v) -> {
            long[] out = v == null || v[0] != minute ? new long[]{minute, 0, 0} : v;
            out[1]++;
            if (compute) {
                out[2]++;
            }
            return out;
        });
        synchronized (w) {
            return w[1] <= limits.getRequestsPerMinute() && (!compute || w[2] <= limits.getComputePerMinute());
        }
    }

    private void prune(long minute) {
        for (Iterator<Map.Entry<String, long[]>> it = windows.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue()[0] != minute) {
                it.remove();
            }
        }
    }

    static void send(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        String body = "{\"status\":" + status + ",\"message\":\"" + message.replace("\"", "'") + "\"}";
        res.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }
}
