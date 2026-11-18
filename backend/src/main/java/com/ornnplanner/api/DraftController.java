package com.ornnplanner.api;

import com.ornnplanner.arena.draft.DraftCatalog;
import com.ornnplanner.arena.draft.LiveGameModel;
import com.ornnplanner.arena.draft.LiveGameRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public DraftController(LiveGameRepository games, DraftCatalog catalog) {
        this.games = games;
        this.catalog = catalog;
    }

    /** The formats (each turn in order), the Fearless default and the lanes. */
    @GetMapping("/formatos")
    public Map<String, Object> formats() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<DraftCatalog.Format> fs = new ArrayList<>(catalog.formats().values());
        out.put("formats", fs);
        out.put("fearless", catalog.fearlessDefault());
        out.put("lanes", catalog.lanes());
        out.put("dragons", catalog.seed().getOrDefault("dragoes", List.of()));
        out.put("weights", catalog.section("nota"));
        return out;
    }

    @GetMapping("/jogos")
    public List<Map<String, Object>> list() {
        return games.list();
    }

    @GetMapping("/jogos/{id}")
    public LiveGameModel.Game get(@PathVariable long id) {
        return games.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping("/jogos")
    public LiveGameModel.Game create(@RequestBody LiveGameModel.Game g) {
        g.id = null;
        return games.save(g);
    }

    @PutMapping("/jogos/{id}")
    public LiveGameModel.Game update(@PathVariable long id, @RequestBody LiveGameModel.Game g) {
        g.id = id;
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
        out.put("next", catalog.next(g));
        out.put("unavailableBlue", catalog.unavailable(g, LiveGameModel.BLUE));
        out.put("unavailableRed", catalog.unavailable(g, LiveGameModel.RED));
        out.put("fearlessBlue", catalog.fearlessUsed(g, LiveGameModel.BLUE));
        out.put("fearlessRed", catalog.fearlessUsed(g, LiveGameModel.RED));
        return out;
    }
}
