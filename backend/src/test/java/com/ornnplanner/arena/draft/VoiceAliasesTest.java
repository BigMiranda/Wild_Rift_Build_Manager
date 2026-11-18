package com.ornnplanner.arena.draft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rodada 20, Parte 2: the voice nicknames (seed apelidos_7_3f.yml with the user's edits in draft_apelido). */
class VoiceAliasesTest {

    private static VoiceAliases aliases(Path dir) {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("t.db"));
        ds.setDriverClassName("org.sqlite.JDBC");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        return new VoiceAliases(new JdbcTemplate(ds), new ClassPathResource("seed/apelidos_7_3f.yml"));
    }

    private static String target(List<VoiceAliases.Alias> all, String kind, String alias) {
        return all.stream().filter(a -> a.kind.equals(kind) && a.alias.equals(alias)).map(a -> a.target).findFirst().orElse(null);
    }

    @Test
    void seedHasTheNicknamesOfThePrompt(@TempDir Path dir) {
        VoiceAliases v = aliases(dir);
        List<VoiceAliases.Alias> all = v.all();
        assertEquals("Flash", target(all, "feiticos", "flash"));
        assertEquals("Incendiar", target(all, "feiticos", "ignite"));
        assertEquals("Teleporte", target(all, "feiticos", "tp"));
        for (String champ : List.of("mf", "tf", "j4", "yi", "morde", "kassa", "wu", "mundo")) {
            assertTrue(target(all, "campeoes", champ) != null, champ);
        }
    }

    @Test
    void userEditsGoOnTopOfTheSeed(@TempDir Path dir) {
        VoiceAliases v = aliases(dir);
        VoiceAliases.Alias a = new VoiceAliases.Alias();
        a.alias = "Órnão";
        a.target = "Ornn";
        a.kind = "campeoes";
        v.put(a);
        assertEquals("Ornn", target(v.all(), "campeoes", "ornao"));
        // the same word can mean something else in another kind
        a.kind = "itens";
        a.target = "Força da Natureza";
        v.put(a);
        assertEquals("Ornn", target(v.all(), "campeoes", "ornao"));
        assertEquals("Força da Natureza", target(v.all(), "itens", "ornao"));
        // removing a seed nickname hides it; the reset brings it back
        v.remove("feiticos", "flash");
        assertFalse(v.all().stream().anyMatch(x -> x.kind.equals("feiticos") && x.alias.equals("flash")));
        v.reset();
        assertEquals("Flash", target(v.all(), "feiticos", "flash"));
        assertEquals(null, target(v.all(), "campeoes", "ornao"));
    }
}
