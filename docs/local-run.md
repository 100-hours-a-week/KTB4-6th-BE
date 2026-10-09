명령어는 이거만 쓰면 됩니다.

cd /Users/kjh/Desktop/KTB4-6th-BE

MySQL + Redis 실행:
```
docker compose up -d mysql redis
```

Spring Boot 실행. API 서버는 8080.
```
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

상태 확인:
curl http://localhost:8081/actuator/health