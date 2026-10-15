package com.ornnplanner.arena.fight;

import java.util.Map;

/** An item effect of a fighter. */
public class ItemEffect {
    public final String item;
    public final Map<String, Object> p;

    public ItemEffect(String item, Map<String, Object> p) {
        this.item = item;
        this.p = p;
    }

    public String kind() {
        return String.valueOf(p.get("tipo"));
    }
}
