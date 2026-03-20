# ---- Build Stage ----
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copy Maven wrapper and pom first for dependency caching
COPY pom.xml .
COPY src ./src

# Install Maven and build the fat JAR
RUN apk add --no-cache maven && \
    mvn clean package -DskipTests

# ---- Runtime Stage ----
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy the fat JAR from the build stage
COPY --from=builder /app/target/hello-javalin-1.0.0.jar app.jar

# Non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

EXPOSE 8080

ENV PORT=8080

ENTRYPOINT ["java", "-jar", "app.jar"]
