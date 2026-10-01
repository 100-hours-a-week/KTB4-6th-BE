# syntax=docker/dockerfile:1

# agent와 이미지에 포함되는 SDK 모듈의 버전을 동일하게 유지한다.
ARG SENTRY_SDK_VERSION=8.59.0

# ---------- Build Stage ----------
FROM eclipse-temurin:25-jdk-jammy AS build
ARG SENTRY_SDK_VERSION
ENV SENTRY_SDK_VERSION=${SENTRY_SDK_VERSION}
WORKDIR /app

# 의존성 파일을 먼저 복사해 빌드 캐시 활용
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./

# build.gradle을 변경하지 않고 이미지 빌드에만 SDK 버전 설정을 적용한다.
RUN <<'SH'
cat > /tmp/sentry-sdk-version.init.gradle <<'GRADLE'
gradle.beforeProject { project ->
    project.pluginManager.withPlugin("io.sentry.jvm.gradle") {
        project.extensions.getByName("sentry").autoInstallation.sentryVersion.set(System.getenv("SENTRY_SDK_VERSION"))
    }
}
GRADLE
SH

RUN ./gradlew --init-script /tmp/sentry-sdk-version.init.gradle dependencies --no-daemon || true

COPY src src

# 테스트는 CI에서 실행하고 여기서는 실행 JAR 생성
RUN ./gradlew --init-script /tmp/sentry-sdk-version.init.gradle bootJar --no-daemon -x test

# ---------- Runtime Stage ----------
FROM eclipse-temurin:25-jre-jammy AS runtime
ARG SENTRY_SDK_VERSION
WORKDIR /app

# 애플리케이션을 root 대신 spring 사용자로 실행
RUN groupadd -r spring && useradd -r -g spring spring

COPY --from=build /app/build/libs/*.jar app.jar

# spring이 agent를 읽을 수 있도록 설정
# 파일 수정 권한은 root만 유지
ADD --chmod=644 https://repo1.maven.org/maven2/io/sentry/sentry-opentelemetry-agent/${SENTRY_SDK_VERSION}/sentry-opentelemetry-agent-${SENTRY_SDK_VERSION}.jar /app/sentry-opentelemetry-agent.jar

# Sentry 초기화는 Spring Boot SDK에서 수행
ENV SENTRY_AUTO_INIT=false

# 별도 Collector를 사용하지 않으므로 OTLP 전송 비활성화
ENV OTEL_LOGS_EXPORTER=none \
    OTEL_METRICS_EXPORTER=none \
    OTEL_TRACES_EXPORTER=none

USER spring
# 실제 실행 사용자가 agent를 읽지 못하면 이미지 빌드 단계에서 중단한다.
RUN test -r /app/sentry-opentelemetry-agent.jar
EXPOSE 8080

# agent와 애플리케이션 경로를 절대 경로로 지정
ENTRYPOINT ["java", "-javaagent:/app/sentry-opentelemetry-agent.jar", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
