package com.ornnplanner.publico;

import com.ornnplanner.arena.ArenaModel;
import com.ornnplanner.arena.EffectModel;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * What a visitor may ask of the calculations the public version keeps: the basic effectiveness (the report's scenarios,
 * no sweeps, per-item readings or candidates) and a short fight in a public scenario, as exported. The local app never
 * calls these.
 */
public final class PublicRequests {

    private PublicRequests() {
    }

    private static ResponseStatusException refuse(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    /** The basic effectiveness: a few saved scenarios by key, the build against itself or one build. */
    public static void basicEffect(EffectModel.Request req, PublicLimits limits) {
        if (req.jobs == null || req.jobs.isEmpty() || req.jobs.size() > limits.getEffectJobs()) {
            throw refuse("Na versão pública, de 1 a " + limits.getEffectJobs() + " cenários por pedido.");
        }
        for (EffectModel.Job j : req.jobs) {
            if (j.testKey == null || j.test != null || j.stage != null || j.variant != null) {
                throw refuse("Na versão pública, só a Efetividade Básica (os cenários do relatório).");
            }
        }
        if (req.reference != null && req.reference.kind != null && !"proprio".equals(req.reference.kind)
                && !"build".equals(req.reference.kind)) {
            throw refuse("Na versão pública, a referência é a própria build ou uma build.");
        }
        if (req.readMode != null && !EffectModel.READ_MODES.contains(req.readMode)) {
            throw refuse("Modo de leitura desconhecido.");
        }
        if (req.starts != null && (req.starts < 1 || req.starts > 8)) {
            throw refuse("Na versão pública, até 8 largadas.");
        }
    }

    /**
     * A short fight: the exported scenario of that id, exactly as stored (the request only names it), its length cut to
     * {@code planner.public.arena-max-seconds} and no training.
     */
    public static ArenaModel.Scenario shortFight(ArenaModel.Scenario asked, ArenaModel.Scenario stored, PublicLimits limits) {
        if (asked == null || asked.id == null || stored == null) {
            throw refuse("Na versão pública, a Arena roda só os cenários públicos.");
        }
        double max = Math.max(5, limits.getArenaMaxSeconds());
        stored.duration = stored.duration <= 0 ? max : Math.min(stored.duration, max);
        return stored;
    }
}
