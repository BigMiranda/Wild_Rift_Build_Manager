package com.ornnplanner.api;

import com.ornnplanner.engine.GoldPricing.StatDef;
import com.ornnplanner.engine.Model.ForgeTier;
import com.ornnplanner.engine.Model.ItemDef;
import com.ornnplanner.engine.Model.StatLine;
import com.ornnplanner.engine.Model.UnitProfile;
import com.ornnplanner.repo.CatalogRepository;
import com.ornnplanner.repo.ReferenceRepository;
import com.ornnplanner.seed.CatalogImporter;
import com.ornnplanner.seed.CatalogImporter.ImportReport;
import com.ornnplanner.seed.ReferenceSeeder;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Plain CRUD over the reference data kept in SQLite. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final CatalogRepository catalog;
    private final ReferenceRepository reference;
    private final CatalogImporter importer;

    public AdminController(CatalogRepository catalog, ReferenceRepository reference, CatalogImporter importer) {
        this.catalog = catalog;
        this.reference = reference;
        this.importer = importer;
    }

    // ------------------------------------------------------------ items

    @GetMapping("/items")
    public Collection<ItemDef> items() {
        return catalog.findAllItems().values();
    }

    @PostMapping("/items")
    @Transactional
    public ItemDef createItem(@RequestBody ItemDef item) {
        validate(item);
        item.edited = true;
        try {
            long id = catalog.insertItem(item);
            catalog.replaceComponents(id, item.components);
            return catalog.findItem(id).orElseThrow();
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Nome duplicado ou componente inválido.");
        }
    }

    @PutMapping("/items/{id}")
    @Transactional
    public ItemDef updateItem(@PathVariable long id, @RequestBody ItemDef item) {
        validate(item);
        if (catalog.findItem(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (item.components.stream().anyMatch(c -> c.itemId == id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Um item não pode ser componente de si mesmo.");
        }
        item.edited = true;
        try {
            catalog.updateItem(id, item);
            catalog.replaceComponents(id, item.components);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Nome duplicado ou componente inválido.");
        }
        return catalog.findItem(id).orElseThrow();
    }

    @DeleteMapping("/items/{id}")
    @Transactional
    public void deleteItem(@PathVariable long id) {
        int used = catalog.countBuildsUsingItem(id);
        if (used > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Item usado em " + used + " compra(s) de builds salvas.");
        }
        catalog.deleteItem(id);
    }

    /** Re-reads the bundled YAML. Items corrected by hand are kept unless overwriteEdited=true. */
    @PostMapping("/catalog/reimport")
    public ImportReport reimport(@RequestParam(defaultValue = "false") boolean overwriteEdited) {
        return importer.importAll(overwriteEdited);
    }

    private static void validate(ItemDef item) {
        if (item.name == null || item.name.isBlank() || item.category == null || item.category.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nome e categoria são obrigatórios.");
        }
        if (item.cost < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Custo não pode ser negativo.");
        }
        for (StatLine s : item.stats) {
            if (s.type == null || s.type.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Toda linha de status precisa de um tipo.");
            }
        }
        item.name = item.name.trim();
    }

    // ------------------------------------------------------------ stat definitions (gold price bases)

    @GetMapping("/stat-defs")
    public List<StatDef> statDefs() {
        return catalog.findStatDefs();
    }

    @PutMapping("/stat-defs")
    @Transactional
    public List<StatDef> saveStatDefs(@RequestBody List<StatDef> defs) {
        for (StatDef d : defs) {
            catalog.upsertStatDef(d);
        }
        return catalog.findStatDefs();
    }

    // ------------------------------------------------------------ Ornn + Living Forge

    @GetMapping("/units/{code}")
    public UnitProfile unit(@PathVariable String code) {
        return reference.findUnit(code).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PutMapping("/units/{code}")
    @Transactional
    public UnitProfile saveUnit(@PathVariable String code, @RequestBody UnitProfile unit) {
        if (ReferenceSeeder.RAGDOLL.equals(code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O boneco de pano é configurado em cada build.");
        }
        UnitProfile existing = unit(code);
        existing.stats = unit.stats;
        if (unit.name != null && !unit.name.isBlank()) {
            existing.name = unit.name;
        }
        reference.upsertUnit(existing);
        return unit(code);
    }

    @GetMapping("/forge")
    public List<ForgeTier> forge() {
        return reference.findForgeTiers();
    }

    @PutMapping("/forge")
    @Transactional
    public List<ForgeTier> saveForge(@RequestBody List<ForgeTier> tiers) {
        if (tiers.stream().noneMatch(t -> t.minLevel == 1)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "É preciso uma faixa começando no nível 1.");
        }
        reference.replaceForgeTiers(tiers);
        return reference.findForgeTiers();
    }

    // ------------------------------------------------------------ XP table

    @GetMapping("/xp")
    public NavigableMap<Integer, Double> xp() {
        return reference.findXpTable();
    }

    @PutMapping("/xp")
    @Transactional
    public NavigableMap<Integer, Double> saveXp(@RequestBody Map<Integer, Double> table) {
        NavigableMap<Integer, Double> sorted = new TreeMap<>(table);
        if (!sorted.containsKey(1) || sorted.get(1) != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O nível 1 deve exigir 0 de XP.");
        }
        double prev = -1;
        for (Map.Entry<Integer, Double> e : sorted.entrySet()) {
            if (e.getValue() == null || e.getValue() < prev) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O XP acumulado deve crescer a cada nível.");
            }
            prev = e.getValue();
        }
        reference.replaceXpTable(sorted);
        return reference.findXpTable();
    }
}
