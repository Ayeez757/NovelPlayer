# 第一阶段：构建 Vue 前端静态资源。
FROM node:22-alpine AS frontend-build
WORKDIR /workspace/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# 第二阶段：把前端产物复制进 Spring Boot，并构建后端可执行包。
FROM maven:3.9.10-eclipse-temurin-21 AS backend-build
WORKDIR /workspace
COPY backend/pom.xml backend/pom.xml
COPY backend/src backend/src
COPY --from=frontend-build /workspace/frontend/dist backend/src/main/resources/static
RUN mvn -f backend/pom.xml -DskipTests package

# 第三阶段：只保留运行环境和最终可执行包，减小运行镜像体积。
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=backend-build /workspace/backend/target/novel-player-backend.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
