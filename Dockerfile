# Queryon 社区版后端镜像（D8-07 容器化部署）
# 多阶段构建：Maven 阶段只打包后端，运行阶段仅保留 JRE 与可执行 jar。
# 凭据不进入镜像：DASHSCOPE_API_KEY 等由运行时环境变量注入，application.yml 只含 ${...} 占位符。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY queryon-backend/ /build/queryon-backend/
RUN mvn -f /build/queryon-backend/pom.xml -DskipTests clean package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/queryon-backend/bootstrap/target/queryon-bootstrap-0.1.0-SNAPSHOT.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
