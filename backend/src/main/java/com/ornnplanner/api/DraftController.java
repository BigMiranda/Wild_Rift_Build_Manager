package com.ornnplanner.api;

import com.ornnplanner.arena.draft.DraftCatalog;
import com.ornnplanner.arena.draft.DraftModel;
import com.ornnplanner.arena.draft.DraftService;
import com.ornnplanner.arena.draft.DraftTablesService;
import com.ornnplanner.arena.draft.FirstEncounterService;
import com.ornnplanner.arena.draft.LiveBuildsService;
import com.ornnplanner.arena.draft.TipsService;
import com.ornnplanner.arena.draft.LiveGameModel;
import com.ornnplanner.arena.draft.LiveGameRepository;
import com.ornnplanner.arena.draft.VoiceAliases;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rodada 20: Simulações › Draft e partida (local only: the public version answers 404). */
@RestController
@RequestMapping("/api/draft")
public class DraftController {

    private final LiveGameRepository games;
    private final DraftCatalog catalog;
    private final VoiceAliases aliases;
    private final DraftService draft;
    private final DraftTablesService tables;
    private final FirstEncounterService encounter;
    private final LiveBuildsService builds;
    private final TipsService tips;

    public DraftController(LiveGameRepository games, DraftCatalog catalog, VoiceAliases aliases, DraftService draft, DraftTablesService tables,
                           FirstEncounterService encounter, LiveBuildsService builds, TipsService tips) {
        this.tips = tips;
        this.encounter = encounter;
        this.builds = builds;
        this.games = games;
        this.catalog = catalog;
        this.aliases = aliases;
        this.draft = draft;
        this.tables = tables;
    }

    /** Parte 3: the body of the draft's requests: the game and a branch of hypothetical picks. */
    public static class SuggestRequest {
        public LiveGameModel.Game game;
        public List<DraftModel.Pick> path = new ArrayList<>();
    }

    /** Parte 3: the enemy's 5 most likely next picks, the ally's 5 best, the 5 bans and the branch's children. */
    @PostMapping("/sugestoes")
    public DraftService.Suggestions suggest(@RequestBody SuggestRequest q) {
        if (q.game == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta a partida.");
        }
        return draft.suggest(q.game, q.path);
    }

    /** Parte 4: the body of the per-player requests: the game and a card (side and index). */
    public static class PlayerRequest {
        public LiveGameModel.Game game;
        public String side;
        public int index;
        /** Parte 6: the enemy cards the tips are against (empty = the lane rival first, then the others). */
        public List<Integer> against = new ArrayList<>();
    }

    /**
     * Parte 4: the first encounter of a card's champion with its lane rival (levels 1-3, every start, three styles).
     * Rodada 20.b: the per-player panels read a copy of the game whose open lanes are the most likely assignment.
     */
    @PostMapping("/encontro")
    public FirstEncounterService.Result encounter(@RequestBody PlayerRequest q) {
        try {
            return encounter.run(draft.resolved(q.game), q.side, q.index);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Parte 5: a card's 10 most likely complete builds (the progression model from what it bought), by importance for the
     * near context ({@code medir=false}: only the chain, no fights).
     */
    @PostMapping("/builds")
    public LiveBuildsService.Result builds(@RequestBody PlayerRequest q, @RequestParam(defaultValue = "true") boolean medir) {
        try {
            return builds.run(draft.resolved(q.game), q.side, q.index, medir);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** Parte 6: tips against each chosen enemy (templates with measured numbers, each with its source). */
    @PostMapping("/dicas")
    public TipsService.Result tips(@RequestBody PlayerRequest q) {
        try {
            return tips.run(draft.resolved(q.game), q.side, q.index, q.against);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** Rodada 20.b, Parte 3: the mechanics that punish (the seed's table and each champion's vulnerabilities and punishments). */
    @GetMapping("/mecanicas")
    public Map<String, Object> mechanics() {
        return draft.mechanicsInfo();
    }

    /** Parte 3: the precomputed tables (Admin): the job's state. */
    @GetMapping("/tabelas")
    public DraftTablesService.Status tablesStatus() {
        return tables.status();
    }

    /** Starts the tables in the background ({@code calibrar}: also the 5x5 calibration; {@code rotas}: only these lanes). */
    @PostMapping("/tabelas")
    public DraftTablesService.Status startTables(@RequestParam(defaultValue = "true") boolean calibrar,
                                                 @RequestParam(required = false) List<String> rotas) {
        try {
            return tables.start(calibrar, rotas);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/tabelas/cancelar")
    public DraftTablesService.Status cancelTables() {
        tables.cancel();
        return tables.status();
    }

    /** Parte 2: the voice reader's nicknames in force (seed + the user's edits). */
    @GetMapping("/apelidos")
    public List<VoiceAliases.Alias> aliases() {
        return aliases.all();
    }

    @PutMapping("/apelidos")
    public List<VoiceAliases.Alias> putAlias(@RequestBody VoiceAliases.Alias a) {
        try {
            aliases.put(a);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return aliases.all();
    }

    @DeleteMapping("/apelidos")
    public List<VoiceAliases.Alias> deleteAlias(@RequestParam String tipo, @RequestParam String apelido) {
        aliases.remove(tipo, apelido);
        return aliases.all();
    }

    /** Back to the seed's nicknames. */
    @PostMapping("/apelidos/restaurar")
    public List<VoiceAliases.Alias> resetAliases() {
        aliases.reset();
        return aliases.all();
    }

    /** The formats (each turn in order), the Fearless default and the lanes. */
    @GetMapping("/formatos")
    public Map<String, Object> formats() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<DraftCatalog.Format> fs = new ArrayList<>(catalog.formats().values());
        out.put("formats", fs);
        out.put("lanes", catalog.lanes());
        out.put("dragons", catalog.seed().getOrDefault("dragoes", List.of()));
        out.put("weights", catalog.section("nota"));
        // Rodada 20.b: each champion's possible lanes (the seed's and the meta's), to prefill a pick
        out.put("possibleLanes", draft.possibleLanes(null));
        out.put("unsureBelow", draft.unsureBelow());
        return out;
    }

    @GetMapping("/jogos")
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = games.list();
        // Rodada 20.b: an old game is listed by the format it will open as
        for (Map<String, Object> m : out) {
            LiveGameModel.Game g = new LiveGameModel.Game();
            g.mode = String.valueOf(m.get("mode"));
            g.fearless = Boolean.TRUE.equals(m.get("fearless"));
            g.version = (Integer) m.get("version");
            catalog.upgrade(g);
            m.put("mode", g.mode);
            m.put("fearless", g.fearless);
        }
        return out;
    }

    /** A game; one saved before Rodada 20.b comes upgraded, with {@code notices} saying what changed. */
    @GetMapping("/jogos/{id}")
    public LiveGameModel.Game get(@PathVariable long id) {
        LiveGameModel.Game g = games.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        catalog.upgrade(g);
        return g;
    }

    @PostMapping("/jogos")
    public LiveGameModel.Game create(@RequestBody LiveGameModel.Game g) {
        g.id = null;
        if (g.version == null) {
            g.version = LiveGameModel.VERSION;
        }
        catalog.upgrade(g);
        return games.save(g);
    }

    /**
     * Rodada 20.b: the next game of a Fearless series: a new game with the earlier games' bans plus this one's ten
     * champions.
     */
    @PostMapping("/jogos/{id}/proximo")
    public LiveGameModel.Game nextInSeries(@PathVariable long id) {
        LiveGameModel.Game g = games.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return games.save(catalog.nextInSeries(g));
    }

    @PutMapping("/jogos/{id}")
    public LiveGameModel.Game update(@PathVariable long id, @RequestBody LiveGameModel.Game g) {
        g.id = id;
        catalog.upgrade(g);
        try {
            return games.save(g);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @DeleteMapping("/jogos/{id}")
    public void delete(@PathVariable long id) {
        games.delete(id);
    }

    /** The next turn of the draft and what each side cannot pick (bans, picks, Fearless). */
    @PostMapping("/estado")
    public Map<String, Object> state(@RequestBody LiveGameModel.Game g) {
        Map<String, Object> out = new LinkedHashMap<>();
        catalog.upgrade(g);
        out.put("next", catalog.next(g));
        out.put("unavailableBlue", catalog.unavailable(g, LiveGameModel.BLUE));
        out.put("unavailableRed", catalog.unavailable(g, LiveGameModel.RED));
        out.put("fearlessBlue", catalog.fearlessUsed(g, LiveGameModel.BLUE));
        out.put("fearlessRed", catalog.fearlessUsed(g, LiveGameModel.RED));
        return out;
    }
}
