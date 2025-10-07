# Aerial Gateway

这是一个基于 Spring Cloud Gateway 的微服务网关，专为 Kubernetes 环境设计，使用 Kubernetes 原生服务发现机制。

## 特性

- 使用 Kubernetes 服务发现替代传统的服务注册中心（如 Nacos、Eureka）
- 支持本地开发和 Kubernetes 集群部署
- 配置了跨域支持
- 提供灵活的路由配置
- 支持动态JWT白名单配置

## 配置文件说明

### application.yaml
主配置文件，适用于 Kubernetes 环境，启用了 Kubernetes 服务发现。

### application-local.yaml
本地开发环境的配置文件，禁用了 Kubernetes 依赖，直接指向本地运行的服务实例。

## 运行方式

### 本地运行
```bash
./mvnw spring-boot:run -Dspring.profiles.active=local
```

或者在 IDE 中运行 [AerialGatewayMain](file:///d:/DinoStark/Projects/CodeSpaces/CodeRaider/aerial-gateway/src/main/java/stark/coderaider/aerial/AerialGatewayMain.java) 类，并设置 active profile 为 `local`。

### Kubernetes 环境运行
在 Kubernetes 环境中部署时，使用默认配置：
```bash
./mvnw spring-boot:run
```

## 路由配置

网关配置了以下路由规则：

1. `/api/spike/**` -> `spike` 服务
2. `/api/image/**` -> `image-server` 服务
3. `/api/mocha-bean/**` -> `mocha-bean` 服务

在 Kubernetes 环境中，这些服务名称会自动解析为相应的 Kubernetes 服务。
在本地环境中，需要确保对应的服务在指定端口运行，或修改 [application-local.yaml](file:///d:/DinoStark/Projects/CodeSpaces/CodeRaider/aerial-gateway/src/main/resources/application-local.yaml) 中的配置。

## 跨域配置

项目已配置跨域支持，允许所有来源、头部和方法的请求。可根据实际需求进行调整。

## JWT动态白名单配置

项目支持通过Redis动态配置JWT白名单URL，无需重启服务即可生效。

### 配置说明

1. `ignore_urls:keys` - 管理所有服务白名单key的集合
2. `ignore_urls:service:{service_name}` - 各个服务的白名单URL集合

### 使用方法

1. 将需要管理白名单的服务key添加到`ignore_urls:keys`集合中
2. 为每个服务创建对应的白名单key，格式为`ignore_urls:service:{service_name}`
3. 在服务的白名单key中添加需要豁免的URL路径

例如：
```bash
# 添加服务key到管理集合
redis-cli SADD ignore_urls:keys ignore_urls:service:spike ignore_urls:service:image

# 为spike服务添加白名单URL
redis-cli SADD ignore_urls:service:spike /api/spike/public/** /api/spike/health

# 为image服务添加白名单URL
redis-cli SADD ignore_urls:service:image /api/image/public/** /api/image/thumbnail/*
```

配置更新后会通过Redis订阅机制实时推送并生效。

## 构建和部署

### 构建项目
```bash
# Linux/Mac
./build.sh

# Windows
build.bat
```

或者手动构建：
```bash
./mvnw clean package
```

### 构建 Docker 镜像
```bash
docker build -t aerial-gateway:latest .
```

### 本地测试运行
```bash
docker run -p 9525:9525 aerial-gateway:latest
```

## 部署到 Kubernetes

在 Kubernetes 中部署时，请确保：

1. 网关服务与后端服务在同一命名空间中
2. 后端服务正确暴露为 Kubernetes Service
3. RBAC 权限已正确配置，允许网关查询 Kubernetes 服务信息

使用以下命令部署到 Kubernetes：
```bash
kubectl apply -f k8s-deployment.yaml
```

## 依赖说明

项目使用以下主要依赖：

- Spring Boot 3.3.4
- Spring Cloud 2024.0.2
- Spring Cloud Gateway
- Spring Cloud LoadBalancer
- Spring Cloud Kubernetes