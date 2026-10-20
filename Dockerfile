# 1단계: 빌드 (Maven + JDK 21). 이 레포에는 mvnw 가 없어 Maven 이미지를 쓴다
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# pom.xml 만 먼저 복사해 의존성 다운로드를 캐시한다
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests

# 2단계: 실행 (JRE만 담는다)
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/business-mcp-*.jar app.jar

# 기본값(127.0.0.1)이면 컨테이너 밖에서 접속할 수 없어 0.0.0.0 으로 연다
ENV SERVER_ADDRESS=0.0.0.0

EXPOSE 8101
ENTRYPOINT ["java", "-jar", "app.jar"]
