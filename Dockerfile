# 使用官方 OpenJDK 镜像作为基础镜像
FROM openjdk:21-jdk-slim

# 设置工作目录
WORKDIR /app

# 复制 jar 文件到容器中
COPY target/aerial-gateway-0.0.1-SNAPSHOT.jar app.jar

# 暴露端口
EXPOSE 9525

# 运行应用程序
ENTRYPOINT ["java", "-jar", "app.jar"]