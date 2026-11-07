package com.ornnplanner.publico;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS of the public version: only the origins in {@code planner.public.allowed-origins} (none by default: the page is
 * served by the same server), only the methods the public API has, no cookies.
 */
@Configuration
@Profile(PublicMode.PROFILE)
public class PublicWebConfig implements WebMvcConfigurer {

    private final PublicLimits limits;

    public PublicWebConfig(PublicLimits limits) {
        this.limits = limits;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (limits.getAllowedOrigins().isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(limits.getAllowedOrigins().toArray(new String[0]))
                .allowedMethods("GET", "POST", "DELETE")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
