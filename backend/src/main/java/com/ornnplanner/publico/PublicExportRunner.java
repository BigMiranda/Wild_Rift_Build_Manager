package com.ornnplanner.publico;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@code ./mvnw -Pexport}: starts the local app without a web server (profile {@code exportar}), exports the public
 * database ({@link PublicExporter}) and exits. The report is printed and written next to the database.
 */
@Component
@Profile("exportar")
public class PublicExportRunner implements ApplicationRunner {

    private final PublicExporter exporter;
    private final ConfigurableApplicationContext context;

    public PublicExportRunner(PublicExporter exporter, ConfigurableApplicationContext context) {
        this.exporter = exporter;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        int code = 0;
        try {
            PublicExporter.Report r = exporter.export();
            System.out.println();
            System.out.println("Exportação pública: " + r.file);
            System.out.println("  " + r.builds.size() + " build(s) de " + r.folders + ", " + r.sizeBytes / 1024 + " KB, " + r.seconds + " s");
            r.counts.forEach((t, n) -> System.out.println("  " + t + ": " + n));
            System.out.println("  Relatório ao lado do banco (-relatorio.md / -relatorio.json).");
        } catch (RuntimeException e) {
            System.err.println("A exportação falhou: " + e.getMessage());
            code = 1;
        }
        int exit = code;
        System.exit(SpringApplication.exit(context, () -> exit));
    }
}
