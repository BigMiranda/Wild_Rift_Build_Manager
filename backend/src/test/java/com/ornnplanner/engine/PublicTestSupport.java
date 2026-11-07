package com.ornnplanner.engine;

import com.ornnplanner.OrnnPlannerApplication;
import com.ornnplanner.publico.PublicApi;
import com.ornnplanner.repo.BuildRepository;
import com.ornnplanner.service.PlannerService;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Consumer;

/** Helpers of the public version's tests (Rodada 11): a prepared database, the endpoints, plain HTTP calls. */
final class PublicTestSupport {

    private PublicTestSupport() {
    }

    /** A new database made by the local app (schema, seeds), then {@code fill} with its context; returns its path. */
    static Path prepare(String tag, Consumer<ConfigurableApplicationContext> fill) {
        try {
            Path db = Files.createTempDirectory(tag).resolve("local.db");
            try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(OrnnPlannerApplication.class)
                    .web(org.springframework.boot.WebApplicationType.NONE).run("--planner.db-path=" + db)) {
                fill.accept(ctx);
            }
            return db;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A build of Ornn with a few items, in a folder. */
    static BuildRepository.Build build(PlannerService planner, long folderId, String name, String... items) {
        BuildRepository.Build b = new BuildRepository.Build();
        b.folderId = folderId;
        b.name = name;
        b.unitCode = "ORNN";
        b.goldPerMin = 700;
        b.xpPerMin = 700;
        for (String i : items) {
            long id = planner.itemViews().stream().filter(v -> v.name.equals(i)).findFirst().orElseThrow().id;
            b.steps.add(new BuildRepository.Step(id, true));
        }
        return b;
    }

    /** Method and path of every endpoint of the app's controllers (local and public ones), "{x}" as written. */
    static List<String[]> endpoints() {
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        TreeSet<String> out = new TreeSet<>();
        for (String pkg : List.of("com.ornnplanner.api", "com.ornnplanner.publico")) {
            for (var bd : scan.findCandidateComponents(pkg)) {
                Class<?> c;
                try {
                    c = Class.forName(bd.getBeanClassName());
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException(e);
                }
                RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(c, RequestMapping.class);
                String[] prefixes = base == null || base.path().length == 0 ? new String[]{""} : base.path();
                for (Method m : c.getDeclaredMethods()) {
                    RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                    if (rm == null) {
                        continue;
                    }
                    String[] paths = rm.path().length == 0 ? new String[]{""} : rm.path();
                    RequestMethod[] methods = rm.method().length == 0 ? new RequestMethod[]{RequestMethod.GET} : rm.method();
                    for (String p : prefixes) {
                        for (String q : paths) {
                            for (RequestMethod verb : methods) {
                                out.add(verb.name() + " " + p + q);
                            }
                        }
                    }
                }
            }
        }
        List<String[]> list = new ArrayList<>();
        out.forEach(s -> list.add(s.split(" ", 2)));
        return list;
    }

    /** A path with its variables filled ("{id}" → "1"), as a request would have it. */
    static String concrete(String path) {
        return path.replaceAll("\\{[^}]+}", "1");
    }

    static boolean publicEndpoint(String method, String path) {
        return PublicApi.allows(method, concrete(path));
    }

    static HttpResponse<String> call(int port, String method, String path, String body, String... headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            if (body != null) {
                b.header("Content-Type", "application/json");
            }
            for (int i = 0; i + 1 < headers.length; i += 2) {
                b.header(headers[i], headers[i + 1]);
            }
            return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
