package com.ornnplanner.publico;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ornnplanner.repo.BuildRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * The community guides (Rodada 11, part 4) in the app's own database (table {@code community_guide}): in the local app,
 * the candidates imported from the public server and the administrator's review; in the public version, the approved
 * ones the export carried.
 */
@Repository
public class CommunityGuides {

    public static final String CANDIDATE = "candidata";
    public static final String APPROVED = "aprovada";
    public static final String REJECTED = "recusada";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A community guide: a build the visitors shared, its votes and its basic effectiveness. */
    public static class Guide {
        public Long id;
        public String fingerprint;
        public String unitCode;
        public String patch;
        public BuildRepository.Build build;
        public int votes;
        public Double score;
        public String grade;
        public Double minute;
        public String status;
        public String importedAt;
        public String reviewedAt;
    }

    private final JdbcTemplate jdbc;

    public CommunityGuides(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Guide> list(String status) {
        String sql = "SELECT * FROM community_guide" + (status == null ? "" : " WHERE status = ?")
                + " ORDER BY unit_code, score DESC, votes DESC";
        Object[] args = status == null ? new Object[0] : new Object[]{status};
        return jdbc.query(sql, (rs, n) -> {
            Guide g = new Guide();
            g.id = rs.getLong("id");
            g.fingerprint = rs.getString("fingerprint");
            g.unitCode = rs.getString("unit_code");
            g.patch = rs.getString("patch");
            try {
                g.build = JSON.readValue(rs.getString("build"), BuildRepository.Build.class);
            } catch (JsonProcessingException e) {
                g.build = null;
            }
            g.votes = rs.getInt("votes");
            g.score = rs.getObject("score") == null ? null : rs.getDouble("score");
            g.grade = rs.getString("grade");
            g.minute = rs.getObject("minute") == null ? null : rs.getDouble("minute");
            g.status = rs.getString("status");
            g.importedAt = rs.getString("imported_at");
            g.reviewedAt = rs.getString("reviewed_at");
            return g;
        }, args);
    }

    /**
     * Imports the server's candidates: a new one comes as "candidata"; one already here keeps its review and gets the
     * new votes and score. Returns how many were new.
     */
    public int importCandidates(List<CommunityService.Candidate> list) {
        int added = 0;
        String now = Instant.now().toString();
        for (CommunityService.Candidate c : list) {
            if (c.fingerprint == null || c.unitCode == null || c.build == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Arquivo de candidatas inválido.");
            }
            String build;
            try {
                build = JSON.writeValueAsString(c.build);
            } catch (JsonProcessingException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Build inválida no arquivo.");
            }
            int updated = jdbc.update("UPDATE community_guide SET votes = ?, score = ?, grade = ?, minute = ?, patch = ? "
                    + "WHERE fingerprint = ?", c.votes, c.score, c.grade, c.minute, c.patch, c.fingerprint);
            if (updated == 0) {
                jdbc.update("INSERT INTO community_guide (fingerprint, unit_code, patch, build, votes, score, grade, minute, "
                                + "status, imported_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                        c.fingerprint, c.unitCode, c.patch, build, c.votes, c.score, c.grade, c.minute, CANDIDATE, now);
                added++;
            }
        }
        return added;
    }

    public void review(long id, String status) {
        if (!CANDIDATE.equals(status) && !APPROVED.equals(status) && !REJECTED.equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Situação desconhecida: " + status);
        }
        if (jdbc.update("UPDATE community_guide SET status = ?, reviewed_at = ? WHERE id = ?", status, Instant.now().toString(), id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
