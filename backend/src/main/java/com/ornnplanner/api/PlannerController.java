package com.ornnplanner.api;

import com.ornnplanner.engine.GoldPricing.PriceTable;
import com.ornnplanner.engine.Model.TimelineResult;
import com.ornnplanner.engine.Model.UnitProfile;
import com.ornnplanner.engine.Stats;
import com.ornnplanner.engine.TimelineEngine;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.repo.BuildRepository.Build;
import com.ornnplanner.repo.BuildRepository.Folder;
import com.ornnplanner.repo.ReferenceRepository;
import com.ornnplanner.service.PlannerService;
import com.ornnplanner.service.PlannerService.ItemView;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PlannerController {

    private final PlannerService planner;
    private final BuildRepository builds;
    private final ReferenceRepository reference;

    public PlannerController(PlannerService planner, BuildRepository builds, ReferenceRepository reference) {
        this.planner = planner;
        this.builds = builds;
        this.reference = reference;
    }

    // ------------------------------------------------------------ reference (read only)

    @GetMapping("/items")
    public List<ItemView> items() {
        return planner.itemViews();
    }

    @GetMapping("/stat-prices")
    public PriceTable statPrices() {
        return planner.priceTable();
    }

    @GetMapping("/meta")
    public Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<UnitProfile> units = reference.findUnits();
        m.put("units", units);
        m.put("unitStats", Stats.UNIT_STATS);
        m.put("displayStats", Stats.DISPLAY_STATS);
        m.put("startingGold", TimelineEngine.STARTING_GOLD);
        m.put("maxLevel", TimelineEngine.MAX_LEVEL);
        m.put("forgeTiers", reference.findForgeTiers());
        m.put("xpTable", reference.findXpTable());
        return m;
    }

    // ------------------------------------------------------------ calculation

    /** Calculates an unsaved build (the editor sends its current state). */
    @PostMapping("/calculate")
    public TimelineResult calculate(@RequestBody Build build) {
        return planner.calculate(build);
    }

    @GetMapping("/builds/{id}/timeline")
    public TimelineResult timeline(@PathVariable long id) {
        return planner.calculate(getBuild(id));
    }

    // ------------------------------------------------------------ folders

    @GetMapping("/folders")
    public List<Folder> folders() {
        return builds.findFolders();
    }

    @PostMapping("/folders")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createFolder(@RequestBody Map<String, String> body) {
        String name = requireName(body.get("name"));
        try {
            return Map.of("id", builds.insertFolder(name));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe uma pasta com esse nome.");
        }
    }

    @PutMapping("/folders/{id}")
    public void renameFolder(@PathVariable long id, @RequestBody Map<String, String> body) {
        try {
            if (!builds.renameFolder(id, requireName(body.get("name")))) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe uma pasta com esse nome.");
        }
    }

    @DeleteMapping("/folders/{id}")
    public void deleteFolder(@PathVariable long id) {
        if (builds.countBuildsInFolder(id) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A pasta não está vazia: mova ou apague as builds antes.");
        }
        if (!builds.deleteFolder(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    // ------------------------------------------------------------ builds

    @GetMapping("/builds")
    public List<Build> buildSummaries() {
        return builds.findBuildSummaries();
    }

    @GetMapping("/builds/{id}")
    public Build getBuild(@PathVariable long id) {
        return builds.findBuild(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping("/builds")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Build createBuild(@RequestBody Build build) {
        validate(build);
        long id = builds.insertBuild(build);
        return getBuild(id);
    }

    @PutMapping("/builds/{id}")
    @Transactional
    public Build updateBuild(@PathVariable long id, @RequestBody Build build) {
        validate(build);
        if (!builds.updateBuild(id, build)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return getBuild(id);
    }

    @PutMapping("/builds/{id}/folder")
    public void moveBuild(@PathVariable long id, @RequestBody Map<String, Long> body) {
        Long folderId = body.get("folderId");
        if (folderId == null || !builds.folderExists(folderId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pasta inválida.");
        }
        if (!builds.moveBuild(id, folderId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    @DeleteMapping("/builds/{id}")
    public void deleteBuild(@PathVariable long id) {
        if (!builds.deleteBuild(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private void validate(Build b) {
        b.name = requireName(b.name);
        if (!builds.folderExists(b.folderId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pasta inválida.");
        }
        if (b.unitCode == null || reference.findUnit(b.unitCode).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unidade inválida.");
        }
        if (b.goldPerMin <= 0 || b.xpPerMin < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ouro/min deve ser > 0 e XP/min >= 0.");
        }
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome obrigatório.");
        }
        return name.trim();
    }
}
