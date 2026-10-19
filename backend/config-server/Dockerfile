# Build JAR ở stage Maven rồi chỉ đưa JAR vào JRE; runtime chạy bằng UID không phải root.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B -q package -DskipTests
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
USER 10001
EXPOSE 8888
ENTRYPOINT ["java", "-jar", "app.jar"]
