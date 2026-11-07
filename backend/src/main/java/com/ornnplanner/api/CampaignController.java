package com.ornnplanner.api;

import com.ornnplanner.arena.discovery.CampaignService;
import com.ornnplanner.repo.DiscoveryRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The campaigns of build discovery (Rodada 9, parts 6 and 8): the queue, its controls and the results. */
@RestController
@RequestMapping("/api/campaigns")
public class CampaignController {

    private final CampaignService campaigns;

    public CampaignController(CampaignService campaigns) {
        this.campaigns = campaigns;
    }

    @GetMapping
    public CampaignService.State state() {
        return campaigns.state();
    }

    @PostMapping("/estimate")
    public CampaignService.Estimate estimate(@RequestBody CampaignService.Request r) {
        return campaigns.estimate(r);
    }

    @PostMapping
    public List<DiscoveryRepository.DiscoveryRow> enqueue(@RequestBody CampaignService.Request r) {
        return campaigns.enqueue(r);
    }

    @PostMapping("/start")
    public void start() {
        campaigns.start();
    }

    @PostMapping("/pause")
    public void pause() {
        campaigns.pause();
    }

    @GetMapping("/{id}")
    public DiscoveryRepository.DiscoveryRow row(@PathVariable long id) {
        return campaigns.row(id);
    }

    @PostMapping("/{id}/resume")
    public void resume(@PathVariable long id) {
        campaigns.resumeRow(id);
    }

    @PostMapping("/{id}/cancel")
    public void cancel(@PathVariable long id) {
        campaigns.cancel(id);
    }

    @PutMapping("/{id}/priority")
    public void priority(@PathVariable long id, @RequestParam int value) {
        campaigns.priority(id, value);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        campaigns.delete(id);
    }

    /** The newest finished result of a champion for this patch (the quick analyses). */
    @GetMapping("/latest")
    public CampaignService.ChampionResult latest(@RequestParam String unit) {
        return campaigns.latest(unit);
    }
}
