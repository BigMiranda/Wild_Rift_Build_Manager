package com.ornnplanner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
public class OrnnPlannerApplication {

    public static void main(String[] args) throws IOException {
        // SQLite creates the file but not missing parent folders.
        String dbPath = System.getProperty("planner.db-path", "./data/ornn-planner.db");
        Path parent = Path.of(dbPath).toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        SpringApplication.run(OrnnPlannerApplication.class, args);
    }
}
