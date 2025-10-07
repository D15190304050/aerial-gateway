#!/bin/bash

# 构建项目
./mvnw clean package

# 构建 Docker 镜像
docker build -t aerial-gateway:latest .

echo "构建完成！"
echo "要运行本地测试，请执行："
echo "docker run -p 9525:9525 aerial-gateway:latest"
echo ""
echo "要部署到 Kubernetes，请执行："
echo "kubectl apply -f k8s-deployment.yaml"