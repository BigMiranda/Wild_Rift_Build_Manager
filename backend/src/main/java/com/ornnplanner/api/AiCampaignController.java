package com.ornnplanner.api;

import com.ornnplanner.arena.league.AiCampaignService;
import com.ornnplanner.arena.league.LeagueService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rodada 21, part 4: the campaign "Treinar IAs de todos os campeões" (Admin › Campanhas): its queue, controls, estimate
 * and the table of every champion's AI. Local only (not in the public version's allow-list).
 */
@RestController
@RequestMapping("/api/ai-campaign")
public class AiCampaignController {

    private final AiCampaignService campaign;
    private final LeagueService league;

    public AiCampaignController(AiCampaignService campaign, LeagueService league) {
        this.campaign = campaign;
        this.league = league;
    }

    @GetMapping
    public AiCampaignService.State state() {
        return campaign.state();
    }

    /** What a campaign would queue, in its order, and why (without queuing). */
    @PostMapping("/preview")
    public List<Map<String, Object>> preview(@RequestBody AiCampaignService.Request r) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (String owner : campaign.order(r)) {
            String why = campaign.needs(owner);
            if (why == null && !r.force) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("owner", owner);
            m.put("reason", why == null ? "pedido" : why);
            out.add(m);
        }
        return out;
    }

    @PostMapping
    public List<AiCampaignService.Row> enqueue(@RequestBody AiCampaignService.Request r) {
        return campaign.enqueue(r);
    }

    @PostMapping("/start")
    public void start() {
        campaign.resumeAll();
    }

    @PostMapping("/pause")
    public void pause() {
        campaign.pause();
    }

    @PostMapping("/stop-after-current")
    public void stopAfterCurrent() {
        campaign.stopAfterCurrent();
    }

    @PostMapping("/{id}/resume")
    public void resume(@PathVariable long id) {
        campaign.resumeRow(id);
    }

    @PostMapping("/{id}/cancel")
    public void cancel(@PathVariable long id) {
        campaign.cancel(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        campaign.delete(id);
    }

    /** Every champion with a sheet: its own AI, in the queue, refused (why) or untrained. */
    @GetMapping("/table")
    public List<AiCampaignService.ChampionAi> table() {
        return campaign.table();
    }

    /** The saved validation matrices (Rodada 21, part 1), newest first; one with its cells by id. */
    @GetMapping("/validations")
    public List<LeagueService.Saved> validations(@RequestParam(required = false) Long policyId,
                                                 @RequestParam(required = false) String champion) {
        if (policyId != null && champion != null) {
            LeagueService.Saved s = league.latest(policyId, champion);
            return s == null ? List.of() : List.of(s);
        }
        return league.list();
    }
}
