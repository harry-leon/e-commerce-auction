# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY config ./config
COPY src ./src
# Dùng chung cache Maven giữa các service; khóa cache khi ghi để các build đồng thời không làm hỏng artifact.
# Build vẫn thất bại nếu package lỗi; test được chạy riêng bằng Maven verify ở máy dev/CI.
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -B --no-transfer-progress -Dmaven.wagon.http.retryHandler.count=3 package -DskipTests

# ---- runtime ----
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
