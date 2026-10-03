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
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;

/**
 * Serves item icons bundled in {@code seed/icones} (cropped from the shop screenshots by
 * {@code tools/capturas_icones.py}), so the app never calls external hosts at runtime. Evolutions use the icon of
 * their base item.
 */
@RestController
public class ItemImageController {

    private static final String DIR = "seed/icones/";

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
    public ResponseEntity<Resource> image(@PathVariable long id, WebRequest request) throws IOException {
        ItemDef item = catalog.findItemHeader(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String file = manifest.get(item.name);
        if (file == null && item.group != null) {
            file = manifest.get(item.group);
        }
        if (file == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Resource img = new ClassPathResource(DIR + file);
        if (!img.exists()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        // Item ids are reused when the catalog is replaced, so the browser must revalidate every time; the ETag
        // (content hash) turns that into a cheap 304 while the icon is unchanged.
        String etag;
        try (InputStream in = img.getInputStream()) {
            etag = "\"" + DigestUtils.md5DigestAsHex(in) + "\"";
        }
        CacheControl cache = CacheControl.noCache();
        if (request.checkNotModified(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).cacheControl(cache).eTag(etag).build();
        }
        MediaType type = MediaTypeFactory.getMediaType(img).orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok().contentType(type).cacheControl(cache).eTag(etag).body(img);
    }
}
