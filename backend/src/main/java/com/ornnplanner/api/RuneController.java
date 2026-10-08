package com.ornnplanner.api;

import com.ornnplanner.seed.RuneCatalog;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Runes and summoner spells (catalog and icons cropped from the game, bundled in {@code seed/runas}, {@code seed/feiticos}). */
@RestController
public class RuneController {

    private static final Pattern FILE = Pattern.compile("[a-z0-9-]+\\.png");

    private final RuneCatalog runes;

    public RuneController(RuneCatalog runes) {
        this.runes = runes;
    }

    @GetMapping("/api/runes")
    public RuneCatalog.CatalogDto catalog() {
        return runes.catalog();
    }

    @GetMapping("/api/runes/icons/{file}")
    public ResponseEntity<Resource> runeIcon(@PathVariable String file) {
        return icon("seed/runas/", file);
    }

    @GetMapping("/api/spells/icons/{file}")
    public ResponseEntity<Resource> spellIcon(@PathVariable String file) {
        return icon("seed/feiticos/", file);
    }

    private static ResponseEntity<Resource> icon(String dir, String file) {
        if (!FILE.matcher(file).matches()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Resource img = new ClassPathResource(dir + file);
        if (!img.exists()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS)).body(img);
    }
}
