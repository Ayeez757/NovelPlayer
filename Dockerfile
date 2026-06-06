# 第一阶段：构建后端可执行包。
FROM maven:3.9.10-eclipse-temurin-21 AS backend-build
WORKDIR /workspace
COPY backend/pom.xml backend/pom.xml
COPY backend/src backend/src
RUN mvn -f backend/pom.xml -DskipTests package

# 第二阶段：只保留运行环境和最终可执行包，减小运行镜像体积。
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=backend-build /workspace/backend/target/novel-player-backend.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
