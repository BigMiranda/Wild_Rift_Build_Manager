package com.ornnplanner.api;

import com.ornnplanner.repo.DiscoveryRepository;
import com.ornnplanner.service.PlannerService;
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

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The meta builds the user keeps per champion (Rodada 9, part 5), and the plain text paste. */
@RestController
@RequestMapping("/api/meta-builds")
public class MetaBuildController {

    /** A pasted text, the champion it is for, and what the parse made of it. */
    public static class Paste {
        public String text;
        public String unitCode;
        public String name;
        public String source;
    }

    public static class Parsed {
        public DiscoveryRepository.MetaBuild build;
        /** Pieces of the text that are no item of the shop nor a keystone. */
        public List<String> unknown = new ArrayList<>();
    }

    private final DiscoveryRepository repo;
    private final PlannerService planner;
    private final com.ornnplanner.seed.RuneCatalog runes;

    public MetaBuildController(DiscoveryRepository repo, PlannerService planner, com.ornnplanner.seed.RuneCatalog runes) {
        this.repo = repo;
        this.planner = planner;
        this.runes = runes;
    }

    @GetMapping
    public List<DiscoveryRepository.MetaBuild> list(@RequestParam(required = false) String unit) {
        return repo.metaBuilds(unit);
    }

    @PostMapping
    public DiscoveryRepository.MetaBuild create(@RequestBody DiscoveryRepository.MetaBuild m) {
        m.id = null;
        return save(m);
    }

    @PutMapping("/{id}")
    public DiscoveryRepository.MetaBuild update(@PathVariable long id, @RequestBody DiscoveryRepository.MetaBuild m) {
        m.id = id;
        return save(m);
    }

    private DiscoveryRepository.MetaBuild save(DiscoveryRepository.MetaBuild m) {
        if (m.unitCode == null || m.unitCode.isBlank() || m.items == null || m.items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uma build meta precisa do campeão e de itens.");
        }
        if (m.name == null || m.name.isBlank()) {
            m.name = "Build meta";
        }
        return repo.saveMeta(m);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        repo.deleteMeta(id);
    }

    /** Reads "item, item → item / runa" by the shop's names (accents and case ignored); does not save. */
    @PostMapping("/parse")
    public Parsed parse(@RequestBody Paste p) {
        Parsed out = new Parsed();
        DiscoveryRepository.MetaBuild m = new DiscoveryRepository.MetaBuild();
        m.unitCode = p.unitCode;
        m.name = p.name;
        m.source = p.source;
        String text = p.text == null ? "" : p.text;
        for (String raw : text.split("[,;\\n\\r→>|+/]+|\\s-\\s")) {
            String token = raw.trim().replaceAll("^\\d+[.)]\\s*", "");
            if (token.isEmpty()) {
                continue;
            }
            String key = plain(token);
            PlannerService.ItemView item = null;
            for (PlannerService.ItemView i : planner.itemViews()) {
                if (plain(i.name).equals(key)) {
                    item = i;
                    break;
                }
            }
            if (item == null) {
                for (PlannerService.ItemView i : planner.itemViews()) {
                    if (key.length() >= 5 && plain(i.name).contains(key) && "aprimorado".equals(i.section)) {
                        item = i;
                        break;
                    }
                }
            }
            if (item != null) {
                if (item.boots()) {
                    m.boots = item.id;
                } else if (!m.items.contains(item.id)) {
                    m.items.add(item.id);
                }
                continue;
            }
            String keystone = runes.catalog().keystones.stream().map(k -> k.name).filter(k -> plain(k).equals(key)).findFirst().orElse(null);
            if (keystone != null) {
                m.keystone = keystone;
            } else {
                out.unknown.add(token);
            }
        }
        out.build = m;
        return out;
    }

    static String plain(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }
}
