package com.ornnplanner.api;

import com.ornnplanner.arena.progressao.ProgressionModel;
import com.ornnplanner.arena.progressao.ProgressionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/** Rodada 19: the natural progression of builds (local only). */
@RestController
@RequestMapping("/api/progressao")
public class ProgressionController {

    private final ProgressionService progression;

    public ProgressionController(ProgressionService progression) {
        this.progression = progression;
    }

    /**
     * The next item and the k most likely continuations to a complete build, with each item's minute at the three paces:
     * {@code campeao} (name or unit code), {@code itens} (finished items and boots held, in order), {@code componentes},
     * {@code inimigos} (names or unit codes), all lists separated by commas.
     */
    @GetMapping
    public ProgressionService.Answer answer(@RequestParam String campeao, @RequestParam(required = false) String itens,
                                            @RequestParam(required = false) String componentes,
                                            @RequestParam(required = false) String inimigos,
                                            @RequestParam(required = false) Boolean suporte,
                                            @RequestParam(required = false) Integer k) {
        return progression.answer(campeao, ids(itens), ids(componentes), names(inimigos), suporte, k);
    }

    /** n builds drawn at a minute (what the Atlas fights against). */
    @GetMapping("/amostras")
    public List<ProgressionModel.Sample> samples(@RequestParam String campeao, @RequestParam double minuto,
                                                 @RequestParam(required = false) String itens,
                                                 @RequestParam(required = false) String componentes,
                                                 @RequestParam(required = false) String inimigos,
                                                 @RequestParam(required = false) Boolean suporte,
                                                 @RequestParam(defaultValue = "20") int n,
                                                 @RequestParam(defaultValue = "1") long semente) {
        return progression.sample(campeao, ids(itens), ids(componentes), names(inimigos), suporte, minuto, n, semente);
    }

    /** The leave-one-out check: how often the real next item of a known build is among the 3 most likely. */
    @GetMapping("/validacao")
    public ProgressionModel.Check validation(@RequestParam(required = false) String campeoes) {
        return progression.validate(names(campeoes));
    }

    static List<Long> ids(String s) {
        List<Long> out = new ArrayList<>();
        for (String p : s == null ? new String[0] : s.split(",")) {
            if (!p.isBlank()) {
                out.add(Long.parseLong(p.trim()));
            }
        }
        return out;
    }

    static List<String> names(String s) {
        List<String> out = new ArrayList<>();
        for (String p : s == null ? new String[0] : s.split(",")) {
            if (!p.isBlank()) {
                out.add(p.trim());
            }
        }
        return out;
    }
}
