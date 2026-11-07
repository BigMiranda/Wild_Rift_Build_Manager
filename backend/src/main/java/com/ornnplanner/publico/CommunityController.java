package com.ornnplanner.publico;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The community of the public version (Rodada 11, part 4): a visitor who allowed it sends builds and can take them
 * back; everybody reads the approved community guides; the administrator downloads the candidates with a token.
 */
@RestController
@RequestMapping("/api/community")
@Profile(PublicMode.PROFILE)
public class CommunityController {

    private final CommunityService community;
    private final CommunityGuides guides;
    private final String adminToken;

    public CommunityController(CommunityService community, CommunityGuides guides,
                               @Value("${planner.community.admin-token:}") String adminToken) {
        this.community = community;
        this.guides = guides;
        this.adminToken = adminToken;
    }

    @PostMapping("/builds")
    public CommunityService.Receipt submit(@RequestBody CommunityService.Submission s, HttpServletRequest req) {
        return community.submit(s, req.getRemoteAddr());   // the IP only counts toward the hourly limit, in memory
    }

    /** "Apagar o que enviei", by the anonymous id kept in the visitor's browser (a header, never in the address). */
    @DeleteMapping("/mine")
    public Map<String, Object> forget(@RequestHeader("X-Anon-Id") String anonId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("removed", community.forget(anonId));
        return m;
    }

    /** The community guides the administrator approved (they came with the export). */
    @GetMapping("/guides")
    public List<CommunityGuides.Guide> approved() {
        try {
            return guides.list(CommunityGuides.APPROVED);
        } catch (org.springframework.dao.DataAccessException e) {
            return List.of();   // an export made before the community guides existed
        }
    }

    /**
     * The candidates, for the administrator to import in the local app. Only with {@code planner.community.admin-token}
     * set and sent in {@code X-Admin-Token}; without a token configured it does not exist.
     */
    @GetMapping("/candidates")
    public Map<String, Object> candidates(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (adminToken == null || adminToken.isBlank() || token == null
                || !MessageDigest.isEqual(adminToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, PublicApiFilter.NOT_HERE);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", "ornn-planner-community");
        m.put("candidates", community.candidates());
        m.put("stats", community.stats());
        return m;
    }
}
