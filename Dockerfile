# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:25-jdk AS build

ARG GITHUB_USER

WORKDIR /workspace

# Copy static files first to improve layer caching.
COPY gradle/ gradle/
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY src/ src/

# Use locked caches to avoid corruption on parallel builds.
RUN --mount=type=cache,target=/root/.gradle/caches,sharing=locked \
    --mount=type=cache,target=/root/.gradle/wrapper,sharing=locked \
    --mount=type=secret,id=github_token,required=true \
    /bin/sh -euc '\
      : "${GITHUB_USER:?GITHUB_USER build arg is required}"; \
      umask 077; \
      trap "rm -f /root/.gradle/gradle.properties" EXIT; \
      printf "github.token=%s\n" "$(cat /run/secrets/github_token)" > /root/.gradle/gradle.properties; \
      ./gradlew --no-daemon --stacktrace \
        -Pgithub.user="${GITHUB_USER}" \
        quarkusBuild -x test \
    '

FROM gcr.io/distroless/java25-debian13 AS runtime

ARG BUILD_VERSION
ARG BUILD_COMMIT
ARG BUILD_AT

LABEL org.opencontainers.image.title="service-notifications" \
      org.opencontainers.image.version="${BUILD_VERSION}" \
      org.opencontainers.image.revision="${BUILD_COMMIT}" \
      org.opencontainers.image.created="${BUILD_AT}"

WORKDIR /deployments/quarkus-app

USER nonroot:nonroot

COPY --from=build --chown=nonroot:nonroot /workspace/build/quarkus-app/ ./

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
