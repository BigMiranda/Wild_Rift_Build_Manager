package com.ornnplanner.api;

import com.ornnplanner.arena.KitReviewMarks;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The user's "revisar" marks on the Arena sheets (Admin › Fichas); local only (not in the public allow-list). */
@RestController
@RequestMapping("/api/arena/kits/marks")
public class KitMarksController {

    private final KitReviewMarks marks;

    public KitMarksController(KitReviewMarks marks) {
        this.marks = marks;
    }

    @GetMapping
    public Map<String, Object> all() {
        return marks.all();
    }

    @PutMapping("/{champion}")
    public Map<String, Object> put(@PathVariable String champion, @RequestBody Map<String, Object> mark) {
        return marks.put(champion, mark);
    }
}
