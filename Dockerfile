# Runtime image shared by every bingo-* service. The jar is built beforehand by Maven:
#
#   mvn -DskipTests package
#   docker build --build-arg JAR=bingo-wallet/bingo-wallet-service/target/bingo-wallet-service-0.1.0-SNAPSHOT.jar -t bingo-wallet .
#
# OpenTelemetry Java agent (required by default):
#   the build copies otel/opentelemetry-javaagent.jar from the build context (repo root) and enables it via
#   JAVA_TOOL_OPTIONS. Obtain it once (see deploy/README.md), e.g.
#     curl -L -o otel/opentelemetry-javaagent.jar \
#       https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/2.31.1/opentelemetry-javaagent-2.31.1.jar
#   To build an image without the agent:  --build-arg OTEL_AGENT=false
#   (needs BuildKit - the default builder since Docker 23 - which skips the unused otel-true stage,
#    so the missing jar is never read).
#
# JVM tuning at deploy time: set JDK_JAVA_OPTIONS (replaces BOTH defaults below, so repeat what you keep, e.g.
# "-XX:+UseG1GC -XX:MaxRAMPercentage=60" for the 1.5 GiB pods of the test environment).
# Do not overwrite JAVA_TOOL_OPTIONS unless you keep the -javaagent flag in it.

ARG OTEL_AGENT=true

FROM eclipse-temurin:25-jre AS base
# Fixed numeric uid/gid so Kubernetes runAsNonRoot / runAsUser can verify it.
RUN groupadd --system --gid 10001 bingo \
 && useradd --system --uid 10001 --gid bingo --home-dir /app --no-create-home --shell /usr/sbin/nologin bingo \
 && mkdir -p /app /data/applogs \
 && chown 10001:10001 /data/applogs
# G1 explicitly: with < 2 CPUs or < 1792 MB the JVM would otherwise pick SerialGC. Heap = 75% of the memory limit
# (6 GiB of an 8 GiB pod); small pods need a lower share for metaspace, threads and the OTel agent.
# Platform time zone is UTC+8 everywhere (Asia/Manila has no DST). The services also pin it in main()
# via BingoTime.applyJvmDefault(), so a missing TZ can never silently shift DATETIME values.
ENV TZ=Asia/Manila \
    JDK_JAVA_OPTIONS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0"
WORKDIR /app

FROM base AS otel-true
COPY otel/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
# Traces only: metrics are scraped from /actuator/prometheus and logs go to stdout (Huawei LTS).
ENV JAVA_TOOL_OPTIONS="-javaagent:/otel/opentelemetry-javaagent.jar" \
    OTEL_METRICS_EXPORTER=none \
    OTEL_LOGS_EXPORTER=none

FROM base AS otel-false

FROM otel-${OTEL_AGENT}
ARG JAR
RUN test -n "${JAR}" || (echo "missing --build-arg JAR=<module>/target/<artifactId>-<version>.jar" >&2; exit 1)
# Owned by root and read-only for the runtime user.
COPY ${JAR} /app/app.jar
USER 10001:10001
# Logs go to stdout only (collected by Huawei LTS); no log files inside the container.
# The root filesystem is read-only in Kubernetes and only /tmp is writable: user.home=/tmp gives the Nacos client
# a place for its local config / service snapshots (${user.home}/nacos) and its log files.
ENTRYPOINT ["java", "-Duser.home=/tmp", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
