package com.ornnplanner.api;

import com.ornnplanner.publico.CommunityGuides;
import com.ornnplanner.publico.CommunityService;
import com.ornnplanner.publico.PublicMode;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The administrator's review of the community guides (Rodada 11, part 4), in the local app: the candidates downloaded
 * from the public server are imported here, approved or rejected; the approved ones go to the next export.
 */
@RestController
@RequestMapping("/api/admin/community")
@Profile("!" + PublicMode.PROFILE)
public class CommunityAdminController {

    /** The file the public server gives ({@code GET /api/community/candidates}). */
    public static class Import {
        public String format;
        public List<CommunityService.Candidate> candidates = new ArrayList<>();
    }

    private final CommunityGuides guides;

    public CommunityAdminController(CommunityGuides guides) {
        this.guides = guides;
    }

    @GetMapping
    public List<CommunityGuides.Guide> list() {
        return guides.list(null);
    }

    @PostMapping("/import")
    public Map<String, Object> importCandidates(@RequestBody Import file) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("added", guides.importCandidates(file.candidates == null ? List.of() : file.candidates));
        m.put("total", file.candidates == null ? 0 : file.candidates.size());
        return m;
    }

    @PutMapping("/{id}")
    public void review(@PathVariable long id, @RequestBody Map<String, String> body) {
        guides.review(id, body.get("status"));
    }
}
