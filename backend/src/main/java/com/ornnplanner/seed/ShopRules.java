package com.ornnplanner.seed;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** Gold rules of the shop that the items do not show ({@code seed/regras_loja.yml}): selling and swapping boots. */
@Component
public class ShopRules {

    /** Share of an item's cost given back when it is sold. */
    public final double sellRate;
    /** Boot swap: share of the old boots' cost taken off the new ones. */
    public final double bootSwapCredit;
    /** Boot swap to cheaper boots gives the difference back in gold. */
    public final boolean bootSwapGivesBack;

    @SuppressWarnings("unchecked")
    public ShopRules(@Value("${planner.seed.shopRules:classpath:seed/regras_loja.yml}") Resource file) {
        try (InputStream in = file.getInputStream()) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> sell = (Map<String, Object>) root.getOrDefault("venda", Map.of());
            Map<String, Object> swap = (Map<String, Object>) root.getOrDefault("troca_de_botas", Map.of());
            sellRate = ((Number) sell.getOrDefault("reembolso", 0.7)).doubleValue();
            bootSwapCredit = ((Number) swap.getOrDefault("credito", 0.7)).doubleValue();
            bootSwapGivesBack = !Boolean.FALSE.equals(swap.get("devolve_diferenca"));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + file, e);
        }
    }
}
