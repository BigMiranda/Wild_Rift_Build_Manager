package com.ornnplanner.publico;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whether the app runs as the public, read-only version (Spring profile {@code publico}, Rodada 11): no admin, no
 * writes, no heavy calculations. The local app (the default) leaves it off and behaves as before.
 */
@Component
public class PublicMode {

    /** The Spring profile of the public version. */
    public static final String PROFILE = "publico";

    private final boolean on;

    public PublicMode(@Value("${planner.public:false}") boolean on) {
        this.on = on;
    }

    public boolean on() {
        return on;
    }
}
