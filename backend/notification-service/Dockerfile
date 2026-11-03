# Build từ repository root để cài nexus-common vào Maven repo trong cùng image build.
# Không phụ thuộc artifact có sẵn trên máy dev; cache khóa khi ghi để tránh build song song ghi hỏng.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY backend/common ./common
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -B --no-transfer-progress -f common/pom.xml install -DskipTests
COPY backend/notification-service ./service
WORKDIR /workspace/service
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -B --no-transfer-progress package -DskipTests

# Runtime không chứa Maven/source/credential; chạy tài khoản không có quyền root.
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=build /workspace/service/target/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
