# syntax=docker/dockerfile:1.9

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew --no-daemon build -x test

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 1000 app
COPY --from=build /workspace/build/quarkus-app/ /app/
USER 1000
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]
