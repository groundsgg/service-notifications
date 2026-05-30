# syntax=docker/dockerfile:1.9

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
      token="$(cat /run/secrets/github_token)"; \
      ./gradlew --no-daemon --stacktrace \
        -Pgithub.user="${GITHUB_USER}" \
        -Pgithub.token="${token}" \
        quarkusBuild -x test \
    '

FROM eclipse-temurin:25-jre

ARG BUILD_VERSION
ARG BUILD_COMMIT
ARG BUILD_AT

LABEL org.opencontainers.image.title="service-notifications" \
      org.opencontainers.image.version="${BUILD_VERSION}" \
      org.opencontainers.image.revision="${BUILD_COMMIT}" \
      org.opencontainers.image.created="${BUILD_AT}"

WORKDIR /app
RUN useradd --system --uid 1000 app
COPY --from=build /workspace/build/quarkus-app/ /app/
USER 1000
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]
