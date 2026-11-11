package com.ornnplanner.publico;

import org.springframework.util.AntPathMatcher;

import java.util.List;

/**
 * The only endpoints of the public version (Rodada 11): reading the catalog and the exported data, and the light
 * calculations of the planner. Everything else under {@code /api} answers 404 there, as if it did not exist; the admin
 * and heavy controllers are not even created ({@code @Profile("!publico")}).
 */
public final class PublicApi {

    /** An endpoint: method and Ant path; {@code compute} = a calculation, limited per IP and in time. */
    public static final class Rule {
        public final String method;
        public final String path;
        public final boolean compute;

        Rule(String method, String path, boolean compute) {
            this.method = method;
            this.path = path;
            this.compute = compute;
        }
    }

    private static Rule get(String path) {
        return new Rule("GET", path, false);
    }

    private static Rule calc(String path) {
        return new Rule("POST", path, true);
    }

    private static Rule send(String method, String path) {
        return new Rule(method, path, false);
    }

    public static final List<Rule> RULES = List.of(
            // catalog and reference data
            get("/api/meta"), get("/api/items"), get("/api/items/*/image"), get("/api/stat-prices"),
            get("/api/runes"), get("/api/runes/icons/*"), get("/api/spells/icons/*"), get("/api/champions/icons/*"),
            get("/api/champions"), get("/api/champions/profiles"), get("/api/champions/*"),
            // the exported builds (public folders only) and what was calculated before the export
            get("/api/folders"), get("/api/builds"), get("/api/builds/*"), get("/api/builds/*/timeline"),
            get("/api/archetypes"), get("/api/score/rules"),
            get("/api/effect/meta"), get("/api/effect/tests"), get("/api/effect/reports/*"),
            get("/api/guides"), get("/api/guides/*"), get("/api/meta-builds"),
            get("/api/campaigns"), get("/api/campaigns/latest"), get("/api/campaigns/*"),
            get("/api/lab/effect-sources"),
            get("/api/arena/facts"), get("/api/arena/templates"), get("/api/arena/rift-rules"), get("/api/arena/kits"),
            get("/api/arena/scenarios"), get("/api/arena/scenarios/*"), get("/api/arena/policies"),
            get("/api/arena/combos"),
            get("/api/public/info"), get("/api/public/effect"),
            // the community (part 4): send with consent, take back, read the approved guides; candidates by token
            send("POST", "/api/community/builds"), send("DELETE", "/api/community/mine"),
            get("/api/community/guides"), get("/api/community/candidates"),
            // the planner (timeline, sheet, relevance, estimated effective gold), the quick comparison, seen under
            // effects, the basic effectiveness and a short Arena fight in a public scenario
            calc("/api/calculate"), calc("/api/relevance"), calc("/api/archetypes"),
            calc("/api/quick/compare"), calc("/api/quick/fits"), calc("/api/quick/delta"), calc("/api/quick/suggest"),
            calc("/api/lab/seen-by"), calc("/api/lab/effect-values"), calc("/api/lab/abilities"),
            calc("/api/passives/estimate"),
            calc("/api/effect/evaluate"), calc("/api/effect/scenario"),
            calc("/api/arena/run"));

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private PublicApi() {
    }

    /** The rule of a request (null = not part of the public version). */
    public static Rule find(String method, String path) {
        for (Rule r : RULES) {
            if (r.method.equalsIgnoreCase(method) && MATCHER.match(r.path, path)) {
                return r;
            }
        }
        return null;
    }

    public static boolean allows(String method, String path) {
        return find(method, path) != null;
    }
}
