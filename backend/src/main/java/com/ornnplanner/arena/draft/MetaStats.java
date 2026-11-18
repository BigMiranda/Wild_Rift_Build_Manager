package com.ornnplanner.arena.draft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rodada 20, Parte 3: the optional meta statistics (presence, bans and wins per champion, lane and elo), written by
 * {@code tools/importar_meta.py} from the public statistics of the Chinese server. Without the file every meta part is
 * neutral. Read again when the file changes.
 *
 * <pre>{"fonte": "...", "data": "2026-11-17", "elos": {"todos": {"Ornn": {"Barão": {"presenca": 0.05, "banimento": 0.01, "vitoria": 0.52}}}}}</pre>
 */
@Component
public class MetaStats {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final File file;
    private long readAt = -1;
    private JsonNode root;

    public MetaStats(@Value("${planner.meta-path:./data/meta/meta.json}") String path) {
        this.file = new File(path);
    }

    private synchronized JsonNode root() {
        long m = file.exists() ? file.lastModified() : 0;
        if (m != readAt) {
            readAt = m;
            try {
                root = m == 0 ? null : JSON.readTree(file);
            } catch (Exception e) {
                root = null;
            }
        }
        return root;
    }

    /** Where it came from and when (null = no import). */
    public Map<String, Object> info() {
        JsonNode r = root();
        if (r == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fonte", r.path("fonte").asText(null));
        out.put("data", r.path("data").asText(null));
        List<String> elos = new ArrayList<>();
        r.path("elos").fieldNames().forEachRemaining(elos::add);
        out.put("elos", elos);
        out.put("arquivo", file.getPath());
        return out;
    }

    /** Champion -> lane -> stats, of an elo (null = the first one). Empty without an import. */
    public Map<String, Map<String, DraftModel.Meta>> of(String elo) {
        Map<String, Map<String, DraftModel.Meta>> out = new LinkedHashMap<>();
        JsonNode r = root();
        if (r == null) {
            return out;
        }
        JsonNode elos = r.path("elos");
        JsonNode e = elo != null && elos.has(elo) ? elos.get(elo) : elos.elements().hasNext() ? elos.elements().next() : null;
        if (e == null) {
            return out;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = e.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> c = it.next();
            Map<String, DraftModel.Meta> byLane = new LinkedHashMap<>();
            c.getValue().fields().forEachRemaining(l -> {
                DraftModel.Meta m = new DraftModel.Meta();
                m.presence = l.getValue().path("presenca").asDouble();
                m.ban = l.getValue().path("banimento").asDouble();
                m.win = l.getValue().path("vitoria").asDouble(0.5);
                byLane.put(l.getKey(), m);
            });
            out.put(c.getKey(), byLane);
        }
        return out;
    }
}
