package com.example;

import io.javalin.Javalin;

public class App {

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

        Javalin app = Javalin.create(config -> {
            config.bundledPlugins.enableCors(cors -> cors.addRule(it -> it.anyHost()));
        }).start(port);

        app.get("/", ctx -> ctx.result("Hello, World!"));

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/info", ctx -> {
            String info = String.format(
                "App: hello-javalin | Version: 1.0.0 | Java: %s | Port: %d",
                System.getProperty("java.version"),
                port
            );
            ctx.result(info);
        });

        System.out.printf("Server started on port %d%n", port);
    }
}
