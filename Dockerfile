# ---------- Build Stage ----------
FROM eclipse-temurin:25-jdk-jammy AS build
WORKDIR /app

# Gradle wrapper와 설정 파일만 먼저 복사 (소스 변경과 무관하게 의존성 레이어 캐싱)
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN ./gradlew dependencies --no-daemon || true

COPY src src

# 테스트는 CI 단계에서 별도 실행한다고 가정하고 이미지 빌드 시에는 생략 (빌드 속도 최적화)
RUN ./gradlew bootJar --no-daemon -x test

# ---------- Runtime Stage ----------
FROM eclipse-temurin:25-jre-jammy AS runtime
WORKDIR /app

# 컨테이너 루트 권한 실행 방지
RUN groupadd -r spring && useradd -r -g spring spring

COPY --from=build /app/build/libs/*.jar app.jar

USER spring
EXPOSE 8080

# 컨테이너 메모리 제한(cgroup)을 인식하도록 설정 (Fargate 등에서 OOM 방지)
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
