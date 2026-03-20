package com.example;

import io.javalin.Javalin;

public class App {

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "7100"));

        Javalin.create(config -> {
            config.bundledPlugins.enableCors(cors -> cors.addRule(it -> it.anyHost()));
            config.routes.get("/", ctx -> ctx.result("Hello, World!"));
            config.routes.get("/health", ctx -> ctx.result("OK"));
            config.routes.get("/info", ctx -> ctx.result(String.format(
                "App: hello-javalin | Version: 1.0.0 | Java: %s | Port: %d",
                System.getProperty("java.version"),
                port
            )));
        }).start(port);

        System.out.printf("Server started on port %d%n", port);
    }
}
