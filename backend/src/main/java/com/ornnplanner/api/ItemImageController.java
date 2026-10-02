package com.ornnplanner.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ornnplanner.engine.Model.ItemDef;
import com.ornnplanner.repo.CatalogRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Serves item icons bundled in {@code seed/images} (downloaded once by {@code tools/download_item_images.py}), so the
 * app never calls external hosts at runtime. Variants ("X (Passive)") share the icon of their base item.
 */
@RestController
public class ItemImageController {

    private static final String DIR = "seed/images/";

    private final CatalogRepository catalog;
    private final Map<String, String> manifest;

    public ItemImageController(CatalogRepository catalog, ObjectMapper mapper) throws IOException {
        this.catalog = catalog;
        ClassPathResource res = new ClassPathResource(DIR + "manifest.json");
        if (res.exists()) {
            try (InputStream in = res.getInputStream()) {
                this.manifest = mapper.readValue(in, new TypeReference<Map<String, String>>() { });
            }
        } else {
            this.manifest = Collections.emptyMap();
        }
    }

    @GetMapping("/api/items/{id}/image")
    public ResponseEntity<Resource> image(@PathVariable long id) {
        ItemDef item = catalog.findItemHeader(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String file = manifest.get(item.name);
        if (file == null) {
            file = manifest.get(item.name.replaceAll("\\s*\\(.*\\)\\s*$", "").trim());
        }
        if (file == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Resource img = new ClassPathResource(DIR + file);
        if (!img.exists()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        MediaType type = MediaTypeFactory.getMediaType(img).orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS))
                .body(img);
    }
}
