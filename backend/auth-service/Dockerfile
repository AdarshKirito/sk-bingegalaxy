# ── Build ────────────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17-alpine AS build
WORKDIR /build
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B package -pl auth-service -am -DskipTests

# ── Runtime ──────────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app
COPY --from=build /build/auth-service/target/*.jar app.jar
RUN chown appuser:appgroup app.jar
USER appuser
EXPOSE 8081
HEALTHCHECK --interval=15s --timeout=5s --start-period=120s --retries=5 \
  CMD wget --spider -q http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
