package com.ornnplanner.api;

import com.ornnplanner.seed.RuneCatalog;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
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
    public ResponseEntity<Resource> runeIcon(@PathVariable String file, WebRequest request) throws IOException {
        return icon("seed/runas/", file, request);
    }

    @GetMapping("/api/spells/icons/{file}")
    public ResponseEntity<Resource> spellIcon(@PathVariable String file, WebRequest request) throws IOException {
        return icon("seed/feiticos/", file, request);
    }

    private static ResponseEntity<Resource> icon(String dir, String file, WebRequest request) throws IOException {
        if (!FILE.matcher(file).matches()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Resource img = new ClassPathResource(dir + file);
        if (!img.exists()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        // Revalidated every time (cheap 304 through the ETag), so redone icons show up without a hard reload.
        String etag;
        try (InputStream in = img.getInputStream()) {
            etag = "\"" + DigestUtils.md5DigestAsHex(in) + "\"";
        }
        CacheControl cache = CacheControl.noCache();
        if (request.checkNotModified(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).cacheControl(cache).eTag(etag).build();
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(cache).eTag(etag).body(img);
    }
}
