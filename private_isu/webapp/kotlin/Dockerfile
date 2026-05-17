# syntax=docker/dockerfile:1

FROM --platform=$BUILDPLATFORM eclipse-temurin:21.0.10_7-jdk AS build
WORKDIR /src

COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon dependencies

COPY src src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon installDist

################################################################################
FROM eclipse-temurin:21.0.10_7-jre AS final

RUN \
    --mount=type=cache,target=/var/lib/apt,sharing=locked \
    --mount=type=cache,target=/var/cache/apt,sharing=locked \
    apt-get update -qq && apt-get install -y openssl

ARG UID=10001
RUN useradd --uid ${UID} --create-home --shell /sbin/nologin appuser
USER appuser

WORKDIR /home/webapp

COPY --from=build /src/build/install/private-isu-kotlin/ ./

EXPOSE 8080

CMD ["bin/private-isu-kotlin"]
