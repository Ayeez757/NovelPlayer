# NovelPlayer

NovelPlayer 是一个基于 Spring Boot + Spring AI 的 Web 应用，用于把 3 个章节以上的小说文本转换成可编辑的 YAML 剧本初稿。

## 技术栈

- 后端：Java 21、Spring Boot、Spring AI、Spring Data JPA、Flyway
- 前端：Vue 3、Vite、TypeScript、Element Plus
- 数据库：MySQL 8
- AI 服务：DeepSeek OpenAI 兼容聊天接口
- 部署：Docker Compose

## 配置文件

Spring 配置分成三份：

```text
backend/src/main/resources/application.yml        通用配置
backend/src/main/resources/application-local.yml  本地开发配置
backend/src/main/resources/application-local.example.yml  本地开发配置示例
backend/src/main/resources/application-test.yml   测试/容器环境配置
```

`application.yml` 只放端口、AI 通用开关、Actuator 和业务参数。

默认 profile 是 `local`。也就是说，在 IDE 里直接启动 `NovelPlayerApplication` 时，会自动读取 `application-local.yml`。

`application-local.yml` 连接本机 MySQL。这个文件只放本机私有配置，已经加入 `.gitignore` 和 `.dockerignore`，不应该提交，也不会进入 Docker 镜像。

首次本地开发时，复制示例文件：

```bash
copy backend\src\main\resources\application-local.example.yml backend\src\main\resources\application-local.yml
```

然后按自己的 MySQL 配置修改 `application-local.yml`。示例默认参数：

```yaml
url: jdbc:mysql://localhost:3306/novel_player
username: novel
password: novel_pass
```

`application-local.yml` 里也已经完整列出 AI 配置。默认使用 mock AI：

```yaml
novel-player:
  generation:
    mock-ai: true

spring:
  ai:
    model:
      chat: none
    openai:
      api-key:
      chat:
        options:
          model: deepseek-v4-pro
```

本地要接入 DeepSeek 时，把本机 `application-local.yml` 改成：

```yaml
novel-player:
  generation:
    mock-ai: false

spring:
  ai:
    model:
      chat: openai
    openai:
      api-key: your-key
```

`application-test.yml` 面向测试环境和 Docker Compose 容器环境，默认连接 Compose 内的 `mysql` 服务：

```yaml
url: jdbc:mysql://mysql:3306/novel_player
username: novel
password: novel_pass
```

`application-test.yml` 同样包含完整 AI 配置，但值主要通过 Docker 的 `.env` 注入。

所有敏感配置都可以用环境变量覆盖，不建议写死在代码仓库里。

## 本地开发

本地首次启动前，需要先执行数据库初始化脚本。该脚本会创建 `novel_player` 数据库、创建默认应用用户 `novel`，并完成授权：

```bash
mysql -u root -p < database/init-mysql.sql
```

脚本位置：

```text
database/init-mysql.sql
```

表结构不在初始化脚本里重复创建。应用启动后，Flyway 会自动执行数据库迁移脚本：

```text
backend/src/main/resources/db/migration/V1__init.sql
```

如果启动时报：

```text
Found non-empty schema(s) but no schema history table
```

说明该数据库里已经有表，但不是由 Flyway 初始化的。当前 `local` 和 `test` 配置已经启用 `baseline-on-migrate`，Flyway 会把已有结构标记为基线版本。若这是一个无用的本地脏库，也可以手动删除 `novel_player` 后重新执行 `database/init-mysql.sql`。

如果你修改了 `database/init-mysql.sql` 里的用户名或密码，需要同步修改本机的 `application-local.yml`。

启动后端：

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

如果本机 MySQL 账号密码不是默认值，可以用环境变量覆盖：

```powershell
$env:SPRING_DATASOURCE_USERNAME="你的账号"
$env:SPRING_DATASOURCE_PASSWORD="你的密码"
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

启动前端：

```bash
cd frontend
npm install
npm run dev
```

Vite 开发服务器会把 `/api` 请求代理到 `http://localhost:8080`。

## Docker 部署配置在哪里

Docker 部署配置放在项目根目录：

```text
docker-compose.yml  定义 frontend、app、mysql、端口、volume、容器环境变量
frontend/nginx.conf  nginx 静态资源托管和 /api 反向代理配置
.env                Docker Compose 运行时读取的环境变量
.env.example        环境变量示例
```

Docker 部署不使用 `application-local.yml`。容器默认启用 `test` profile，并通过 `.env` 注入数据库账号、密码、DeepSeek Key 等部署参数。

部署时复制一份 `.env`：

```bash
copy .env.example .env
```

然后按需修改 `.env`：

```env
SERVER_PORT=8080
FRONTEND_HOST_PORT=8081
SPRING_PROFILES_ACTIVE=test
NOVEL_PLAYER_MINIMUM_CHAPTERS=3
NOVEL_PLAYER_MOCK_AI=true
SPRING_AI_MODEL_CHAT=none
SPRING_AI_MODEL_EMBEDDING=none
SPRING_AI_MODEL_IMAGE=none
SPRING_AI_MODEL_MODERATION=none
SPRING_AI_MODEL_AUDIO_SPEECH=none
SPRING_AI_MODEL_AUDIO_TRANSCRIPTION=none
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_API_KEY=
DEEPSEEK_MODEL=deepseek-v4-pro
DEEPSEEK_TEMPERATURE=0.4
MYSQL_HOST_PORT=3307
MYSQL_DATABASE=novel_player
MYSQL_USER=novel
MYSQL_PASSWORD=novel_pass
MYSQL_ROOT_PASSWORD=root_pass
```

启动：

```bash
docker compose up --build
```

浏览器打开：

```text
http://localhost:8081
```

Docker Compose 会启动三个服务：

- `frontend`：nginx 托管前端静态资源，并把 `/api` 反向代理到后端。
- `app`：Spring Boot 后端，只在 Compose 网络内暴露 `8080`。
- `mysql`：MySQL 数据库，默认映射到宿主机 `3307`。

## 接入 DeepSeek

默认使用 mock AI，方便无 API Key 演示完整流程。

如果要在 Docker 中接入 DeepSeek，修改 `.env`：

```env
NOVEL_PLAYER_MOCK_AI=false
SPRING_AI_MODEL_CHAT=openai
DEEPSEEK_API_KEY=your-key
DEEPSEEK_MODEL=deepseek-v4-pro
DEEPSEEK_TEMPERATURE=0.4
```

本地开发接入 DeepSeek：

```powershell
$env:NOVEL_PLAYER_MOCK_AI="false"
$env:SPRING_AI_MODEL_CHAT="openai"
$env:DEEPSEEK_API_KEY="your-key"
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

## 架构说明

系统采用前后端分离、同仓库管理的结构：

```text
frontend/  Vue 3 WebUI
backend/   Spring Boot API 与 AI 生成流程
docs/      方案与 YAML Schema 文档
```

后端按职责分层：

```text
web          REST API、异常处理、请求/响应 DTO
application 业务编排、章节解析、生成任务、剧本校验、YAML 导出
ai          AI 客户端抽象、DeepSeek 实现、mock 实现
domain      项目、章节、生成任务、剧本文档等领域对象
infra       JPA Repository 等基础设施
```

## 生成流程

1. 拆分小说章节。
2. 分析每章内容。
3. 构建人物、地点、冲突和时间线。
4. 规划剧本场景。
5. 生成场景动作、对白和转场。
6. 校验结构化剧本文档。
7. 导出 YAML。

模型不直接输出 YAML，而是先输出结构化 JSON，并由后端解析为 Java DTO。后端完成校验后，再统一导出 YAML。
