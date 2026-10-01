# syntax=docker/dockerfile:1
# TODO(S6): pin by digest via Dependabot
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
# The contract's settings.gradle.kts reads the APP repo's version catalog at ../gradle/libs.versions.toml,
# so the contract must sit beside a `gradle/` dir holding that catalog — two build contexts supply them:
#   --build-context census-contract=<DashBuddy>/census-contract --build-context dashbuddy-gradle=<DashBuddy>/gradle
ARG CENSUS_CONTRACT_PATH=/workspace/dashbuddy/census-contract
COPY --from=census-contract / ${CENSUS_CONTRACT_PATH}/
COPY --from=dashbuddy-gradle libs.versions.toml /workspace/dashbuddy/gradle/libs.versions.toml
COPY . .
RUN ./gradlew :server:installDist --no-daemon -PcensusContractPath=${CENSUS_CONTRACT_PATH}

# TODO(S6): pin by digest via Dependabot
FROM eclipse-temurin:25-jre-alpine AS runtime
RUN addgroup -g 10001 census && adduser -D -u 10001 -G census census
WORKDIR /app
COPY --from=build /workspace/server/build/install/server /app
ARG SERVER_VERSION=dev
ENV SERVER_VERSION=${SERVER_VERSION}
ENV JAVA_OPTS="-Djava.io.tmpdir=/tmp -XX:ErrorFile=/tmp/hs_err_pid%p.log -XX:-UsePerfData"
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1
ENTRYPOINT ["/app/bin/server"]
