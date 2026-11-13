package com.ornnplanner.api;

import com.ornnplanner.arena.atlas.AtlasModel;
import com.ornnplanner.arena.atlas.AtlasService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Rodada 16: the interaction atlas (local only). */
@RestController
@RequestMapping("/api/atlas")
public class AtlasController {

    private final AtlasService atlas;

    public AtlasController(AtlasService atlas) {
        this.atlas = atlas;
    }

    @GetMapping("/presets")
    public Map<String, AtlasModel.Config> presets() {
        return atlas.presets();
    }

    @GetMapping("/runs")
    public List<AtlasModel.Run> runs() {
        return atlas.list();
    }

    @GetMapping("/runs/{id}")
    public AtlasModel.Run run(@PathVariable long id) {
        return atlas.find(id);
    }

    /** A new run: a preset by key ({@code ?preset=ornn_rumble}) or a config in the body. */
    @PostMapping("/runs")
    public AtlasModel.Run start(@RequestParam(required = false) String preset, @RequestBody(required = false) AtlasModel.Config config) {
        return atlas.start(preset, config);
    }

    @PostMapping("/runs/{id}/cancel")
    public void cancel(@PathVariable long id) {
        atlas.cancel(id);
    }

    @DeleteMapping("/runs/{id}")
    public void delete(@PathVariable long id) {
        atlas.delete(id);
    }

    /** Redoes the validation of a finished run (new sets kept with their races, both scales). */
    @PostMapping("/runs/{id}/validate")
    public AtlasModel.Run validate(@PathVariable long id) {
        return atlas.revalidate(id);
    }

    /** Fights for real what each side fixed, next to the prediction. */
    @PostMapping("/runs/{id}/confirm")
    public AtlasModel.Confirmation confirm(@PathVariable long id, @RequestBody AtlasModel.GuideRequest q) {
        return atlas.confirm(id, q);
    }

    @PostMapping("/runs/{id}/guide")
    public AtlasModel.Guide guide(@PathVariable long id, @RequestBody AtlasModel.GuideRequest q) {
        return atlas.guide(id, q);
    }
}
