demo视频：通过百度网盘分享的文件：NovelPlayer.mp4
链接: https://pan.baidu.com/s/13olLSZLyOVAO2mbNHSlYoA?pwd=kaya 提取码: kaya 

# NovelPlayer

NovelPlayer 是一个小说转剧本工作台。它把长篇小说原文或 TXT 文件拆分成章节，通过可切换的 mock AI 或 DeepSeek 兼容接口生成结构化剧本，再导出为可继续编辑、校验和归档的 YAML 初稿。

项目采用前后端分离的单仓库结构：

- 前端提供首页、改编工作台、章节识别、生成进度、YAML 编辑器和结构树。
- 后端负责项目管理、章节拆分、异步生成任务、AI 调用、剧本校验、YAML 导出和数据持久化。
- Docker Compose 一次启动前端、后端和 MySQL，默认 mock AI，无需 API Key 即可跑通完整流程。

## 目录

- [功能特性](#功能特性)
- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [快速启动](#快速启动)
- [本地开发](#本地开发)
- [Docker 部署](#docker-部署)
- [配置说明](#配置说明)
- [接入 DeepSeek](#接入-deepseek)
- [使用流程](#使用流程)
- [后端 API](#后端-api)
- [生成流程](#生成流程)
- [YAML 输出格式](#yaml-输出格式)
- [测试与质量检查](#测试与质量检查)
- [数据库与迁移](#数据库与迁移)
- [常见问题](#常见问题)
- [开发注意事项](#开发注意事项)

## 功能特性

- 小说原文输入：支持直接粘贴正文，也支持上传 `.txt` 文件。
- 文件编码识别：前端优先按 UTF-8 读取，失败后尝试 GB18030。
- 章节拆分：支持 `第一章`、`第1章`、`第十回`、`Chapter 1`、`CHAPTER 1` 等标题；没有标题时按空行退化拆分。
- 项目建档：提交标题和原文后，后端保存项目与章节摘要。
- 异步生成：创建生成任务后前端轮询任务状态，后台继续执行生成流水线。
- 多阶段 AI 管线：章节摘要、故事圣经、场景规划、分场草稿、最终组装、JSON 序列化、YAML 导出。
- mock AI：默认开启，适合演示、联调和无模型 Key 的本地开发。
- DeepSeek 接入：通过 Spring AI OpenAI 兼容客户端访问 DeepSeek Chat Completions。
- YAML 编辑：生成完成后可在工作台直接查看和编辑 YAML。
- 结构树：根据当前 YAML 内容实时展示 metadata、characters、locations、scenes、blocks 等层级。
- YAML 下载：可下载项目最近一次生成的剧本文件。
- Docker Compose：包含 nginx 前端、Spring Boot 后端和 MySQL 8。

## 技术栈

### 后端

- Java 21
- Spring Boot 3.5.7
- Spring AI 1.1.7
- Spring Web
- Spring Data JPA
- Bean Validation
- Actuator
- Flyway
- MySQL Connector/J
- Jackson YAML
- Maven

### 前端

- Vue 3
- Vue Router 4
- Vite 7
- TypeScript
- Element Plus
- `yaml` 解析库
- nginx 静态托管与 API 反向代理

### 基础设施

- MySQL 8.0
- Docker
- Docker Compose

## 项目结构

```text
NovelPlayer/
├─ backend/                         Spring Boot 后端
│  ├─ pom.xml
│  └─ src/
│     ├─ main/java/com/novelplayer/
│     │  ├─ ai/                     AI 客户端抽象、DeepSeek 实现、mock 实现
│     │  ├─ application/            应用服务、章节解析、生成管线、YAML 导出
│     │  ├─ config/                 应用配置属性
│     │  ├─ domain/                 领域对象与状态枚举
│     │  ├─ infra/                  JPA Repository
│     │  └─ web/                    REST Controller、DTO、异常处理
│     └─ main/resources/
│        ├─ application.yml         通用配置
│        ├─ application-local.example.yml
│        ├─ application-test.yml
│        ├─ db/migration/           Flyway 迁移脚本
│        └─ prompts/                AI Prompt 与输出契约
├─ frontend/                        Vue/Vite 前端
│  ├─ package.json
│  ├─ vite.config.ts
│  ├─ nginx.conf
│  └─ src/
│     ├─ api/                       前端 API 封装与类型
│     ├─ app/                       路由与应用外壳
│     ├─ features/workspace/        工作台组件、组合式逻辑和模型
│     ├─ pages/                     首页和工作台页面
│     └─ styles/                    样式
├─ database/
│  └─ init-mysql.sql                本地 MySQL 初始化脚本
├─ docs/
│  └─ script-yaml-schema.md         剧本 YAML Schema 说明
├─ Dockerfile                       后端多阶段构建镜像
├─ docker-compose.yml               前端、后端、MySQL 编排
├─ .env.example                     Docker Compose 环境变量示例
└─ README.md
```

## 快速启动

最快方式是使用 Docker Compose。默认会开启 mock AI，不需要 DeepSeek API Key。

### 1. 准备环境变量

Windows PowerShell:

```powershell
Copy-Item .env.example .env
```

macOS / Linux:

```bash
cp .env.example .env
```

### 2. 启动服务

```bash
docker compose up --build
```

### 3. 打开页面

```text
http://localhost:8080
```

Compose 默认启动三个服务：

| 服务 | 说明 | 宿主机访问 |
| --- | --- | --- |
| `frontend` | nginx 托管前端静态资源，并把 `/api` 代理到后端 | `http://localhost:8080` |
| `app` | Spring Boot 后端，只在 Compose 网络中暴露 `8080` | 通过 frontend 代理访问 |
| `mysql` | MySQL 8.0 数据库 | `localhost:3307` |

停止服务：

```bash
docker compose down
```

同时删除数据库卷：

```bash
docker compose down -v
```

## 本地开发

本地开发建议前后端分别启动：后端监听 `8080`，前端 Vite 监听 `5173` 并代理 `/api` 到后端。

### 环境要求

- JDK 21
- Maven 3.9+
- Node.js 20+，建议使用与 Vite 7 兼容的 LTS 或更新版本
- npm
- MySQL 8.0

### 1. 初始化 MySQL

首次本地启动后端前，先创建数据库和应用账号：

```bash
mysql -u root -p < database/init-mysql.sql
```

默认创建：

```text
database: novel_player
username: novel
password: novel_pass
```

业务表结构不在 `database/init-mysql.sql` 中创建。后端启动后，Flyway 会自动执行：

```text
backend/src/main/resources/db/migration/V1__init.sql
```

### 2. 准备后端本地配置

Windows PowerShell:

```powershell
Copy-Item backend\src\main\resources\application-local.example.yml backend\src\main\resources\application-local.yml
```

macOS / Linux:

```bash
cp backend/src/main/resources/application-local.example.yml backend/src/main/resources/application-local.yml
```

然后按本机 MySQL 和 AI 配置修改：

```text
backend/src/main/resources/application-local.yml
```

`application-local.yml` 是本地私有配置文件，不应提交到仓库。

### 3. 启动后端

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

如果只想临时覆盖数据库账号密码，Windows PowerShell 示例：

```powershell
$env:SPRING_DATASOURCE_USERNAME="your-user"
$env:SPRING_DATASOURCE_PASSWORD="your-password"
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

健康检查：

```text
http://localhost:8080/actuator/health
```

### 4. 启动前端

```bash
cd frontend
npm install
npm run dev
```

打开：

```text
http://localhost:5173
```

前端开发代理默认配置：

```env
VITE_API_PROXY_TARGET=http://localhost:8080
```

如果后端不是 `8080`，可以复制并修改：

```bash
cd frontend
cp .env.example .env
```

Windows PowerShell:

```powershell
cd frontend
Copy-Item .env.example .env
```

## Docker 部署

Docker 相关文件：

```text
docker-compose.yml      服务编排、端口、环境变量、volume
Dockerfile              后端镜像，多阶段 Maven 构建 + JRE 运行
frontend/Dockerfile     前端镜像，构建 Vue 静态资源并交给 nginx
frontend/nginx.conf     SPA 路由、/api 反向代理、上传大小和超时
.env.example            Compose 环境变量示例
.env                    Compose 实际读取的环境变量，本地私有
```

### 默认端口

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `FRONTEND_HOST_PORT` | `8080` | 宿主机访问前端的端口 |
| `SERVER_PORT` | `8080` | 后端容器内监听端口 |
| `MYSQL_HOST_PORT` | `3307` | 宿主机访问 MySQL 的端口 |

### 常用命令

构建并启动：

```bash
docker compose up --build
```

后台启动：

```bash
docker compose up --build -d
```

查看日志：

```bash
docker compose logs -f
```

只看后端日志：

```bash
docker compose logs -f app
```

重建后端：

```bash
docker compose build app
docker compose up -d app
```

进入 MySQL：

```bash
docker compose exec mysql mysql -u novel -p novel_player
```

## 配置说明

### Spring 配置文件

| 文件 | 用途 |
| --- | --- |
| `backend/src/main/resources/application.yml` | 通用配置，包含端口、profile 默认值、Spring AI 通用开关、Actuator 和业务参数 |
| `backend/src/main/resources/application-local.example.yml` | 本地开发配置示例 |
| `backend/src/main/resources/application-local.yml` | 本地开发私有配置，需要自行复制创建，不提交 |
| `backend/src/main/resources/application-test.yml` | 测试和 Docker 容器环境配置 |

默认 profile 是 `local`。也就是说，在 IDE 中直接启动 `NovelPlayerApplication` 时会尝试读取 `application-local.yml`。

Docker Compose 会通过环境变量设置：

```env
SPRING_PROFILES_ACTIVE=test
```

### 根目录 `.env`

`.env.example` 当前包含以下主要变量：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | 后端服务端口 |
| `FRONTEND_HOST_PORT` | `8080` | 前端宿主机端口 |
| `SPRING_PROFILES_ACTIVE` | `test` | 容器使用的 Spring profile |
| `NOVEL_PLAYER_MINIMUM_CHAPTERS` | `3` | 项目最低章节数 |
| `NOVEL_PLAYER_MOCK_AI` | `true` | 是否使用 mock AI |
| `NOVEL_PLAYER_GENERATION_PIPELINE_MODE` | `staged` | 生成管线模式，见下文 |
| `NOVEL_PLAYER_CHAPTER_DIGEST_CONCURRENCY` | `4` | 章节摘要并发数 |
| `NOVEL_PLAYER_SCENE_DRAFT_CONCURRENCY` | `2` | 分场草稿并发数 |
| `SPRING_AI_MODEL_CHAT` | `none` | Spring AI 聊天模型开关 |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` | DeepSeek OpenAI 兼容地址 |
| `DEEPSEEK_API_KEY` | 空 | DeepSeek API Key |
| `DEEPSEEK_MODEL` | `deepseek-v4-pro` | 默认模型名 |
| `DEEPSEEK_TEMPERATURE` | `0.4` | 模型温度 |
| `NOVEL_PLAYER_DEEPSEEK_THINKING_MODE` | `disabled` | DeepSeek thinking 参数模式 |
| `MYSQL_HOST_PORT` | `3307` | MySQL 宿主机端口 |
| `MYSQL_DATABASE` | `novel_player` | 数据库名 |
| `MYSQL_USER` | `novel` | 应用数据库用户 |
| `MYSQL_PASSWORD` | `novel_pass` | 应用数据库密码 |
| `MYSQL_ROOT_PASSWORD` | `root_pass` | MySQL root 密码 |

不要把真实 `.env`、`application-local.yml` 或 API Key 提交到仓库。

### 生成管线模式

`NOVEL_PLAYER_GENERATION_PIPELINE_MODE` 支持：

| 值 | 说明 |
| --- | --- |
| `staged` | 默认，多阶段生成：章节摘要、故事圣经、场景规划、分场草稿、最终组装 |
| `legacy` | 旧链路，一次性生成剧本文档 |

推荐使用 `staged`。它更利于展示进度、缓存中间结果和定位失败阶段。

### 并发配置

| 变量 | 建议 |
| --- | --- |
| `NOVEL_PLAYER_CHAPTER_DIGEST_CONCURRENCY` | 章节摘要彼此独立，可根据模型限流和数据库连接池调大或调小 |
| `NOVEL_PLAYER_SCENE_DRAFT_CONCURRENCY` | 分场草稿并发会影响上下文连续性和模型压力，默认 `2` |

当 `NOVEL_PLAYER_SCENE_DRAFT_CONCURRENCY=1` 时，分场草稿严格串行，更适合追求上下文连续性的生成。

## 接入 DeepSeek

项目默认使用 mock AI：

```env
NOVEL_PLAYER_MOCK_AI=true
SPRING_AI_MODEL_CHAT=none
```

这样无需 Key 也能启动和演示完整流程。

### Docker Compose 接入 DeepSeek

修改根目录 `.env`：

```env
NOVEL_PLAYER_MOCK_AI=false
SPRING_AI_MODEL_CHAT=openai
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_API_KEY=your-key
DEEPSEEK_MODEL=deepseek-v4-pro
DEEPSEEK_TEMPERATURE=0.4
NOVEL_PLAYER_DEEPSEEK_THINKING_MODE=disabled
```

重启：

```bash
docker compose up --build
```

### 本地后端接入 DeepSeek

方式一：修改 `application-local.yml`。

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

方式二：使用环境变量临时覆盖。

Windows PowerShell:

```powershell
$env:NOVEL_PLAYER_MOCK_AI="false"
$env:SPRING_AI_MODEL_CHAT="openai"
$env:DEEPSEEK_API_KEY="your-key"
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

macOS / Linux:

```bash
export NOVEL_PLAYER_MOCK_AI=false
export SPRING_AI_MODEL_CHAT=openai
export DEEPSEEK_API_KEY=your-key
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

### DeepSeek thinking 参数

`NOVEL_PLAYER_DEEPSEEK_THINKING_MODE` 当前支持：

| 值 | 行为 |
| --- | --- |
| `disabled` | 请求中注入 `thinking.type=disabled` |
| `default` | 不注入 thinking 字段，保留服务端默认行为 |

## 使用流程

### Web 工作台

1. 打开首页，进入 `工作台`。
2. 选择入口：
   - `即时输入转剧本`：直接粘贴小说正文。
   - `上传文件转剧本`：上传 `.txt` 小说文件。
3. 填写或确认作品标题。
4. 点击 `识别章节`，后端创建项目并保存章节。
5. 在 `确认提交` 中选择：
   - 剧本类型：短剧、影视剧、舞台剧。
   - 风格：悬疑、写实、轻喜、古风。
   - 对白密度：`0` 到 `100`。
   - 旁白保留：`0` 到 `100`。
6. 点击 `生成剧本`。
7. 在 `生成日志` 中查看后台任务阶段。
8. 生成完成后，在 `YAML 初稿` 中查看和编辑内容。
9. 在 `结构树` 中检查 YAML 层级。
10. 点击 `下载 YAML` 保存剧本文件。

注意：工作台里的 `暂停生成` 当前只是暂停前端轮询，后台任务仍会继续运行。

### 输入文本要求

- 建议至少 3 章，默认最低章节数由 `NOVEL_PLAYER_MINIMUM_CHAPTERS` 控制。
- 推荐使用明确章节标题，例如：

```text
第一章 雨夜
正文...

第二章 旧信
正文...

第三章 归来
正文...
```

- 也支持英文标题：

```text
Chapter 1 The Rain
...

Chapter 2 The Letter
...
```

- 如果没有章节标题，系统会按空行拆成多个章节块。

## 后端 API

后端基础地址：

```text
http://localhost:8080
```

Docker Compose 下建议通过前端 nginx 访问：

```text
http://localhost:8080/api
```

本地前端开发时，Vite 会把 `/api` 代理到后端。

### API 总览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/projects` | 创建项目并拆分章节 |
| `GET` | `/api/projects/{projectId}` | 查询项目详情 |
| `POST` | `/api/projects/{projectId}/generation-jobs` | 创建生成任务 |
| `GET` | `/api/generation-jobs/{jobId}` | 查询生成任务状态 |
| `GET` | `/api/jobs/{jobId}` | 旧路径兼容，查询生成任务状态 |
| `GET` | `/api/projects/{projectId}/scripts/latest` | 查询项目最近一次剧本文档 |
| `GET` | `/api/projects/{projectId}/scripts/latest/download` | 下载项目最近一次 YAML |

### 创建项目

请求：

```http
POST /api/projects
Content-Type: application/json
```

```json
{
  "title": "雨夜的信",
  "sourceText": "第一章 雨夜\n这里是第一章正文...\n\n第二章 旧信\n这里是第二章正文...\n\n第三章 归来\n这里是第三章正文..."
}
```

响应字段：

| 字段 | 说明 |
| --- | --- |
| `id` | 项目 ID |
| `title` | 作品标题 |
| `status` | 项目状态 |
| `chapters` | 已识别章节列表 |
| `createdAt` | 创建时间 |
| `updatedAt` | 更新时间 |

项目状态：

| 状态 | 说明 |
| --- | --- |
| `DRAFT` | 草稿态，预留 |
| `READY` | 已创建且章节已拆分，可以开始生成 |
| `GENERATING` | 正在生成 |
| `COMPLETED` | 最近一次生成完成 |
| `FAILED` | 最近一次生成失败 |

### 创建生成任务

请求：

```http
POST /api/projects/{projectId}/generation-jobs
Content-Type: application/json
```

```json
{
  "format": "web_drama",
  "tone": "suspense",
  "dialogueDensity": 70,
  "narrationRetention": 30,
  "additionalInstructions": "保留悬疑感，场景节奏更紧凑。"
}
```

参数说明：

| 字段 | 约束 | 说明 |
| --- | --- | --- |
| `format` | 非空 | 剧本形式，例如 `web_drama`、`screenplay`、`stage_play` |
| `tone` | 非空 | 整体风格，例如 `suspense`、`realistic`、`comedy`、`period` |
| `dialogueDensity` | `0` 到 `100` | 对白密度 |
| `narrationRetention` | `0` 到 `100` | 旁白保留度 |
| `additionalInstructions` | 最多 4000 字符 | 用户补充改编要求 |

响应状态码是 `202 Accepted`，表示任务已进入后台处理。

生成任务状态：

| 状态 | 说明 |
| --- | --- |
| `PENDING` | 已创建但尚未开始 |
| `RUNNING` | 正在执行 |
| `SUCCEEDED` | 成功完成 |
| `FAILED` | 执行失败 |

### 查询生成任务

```http
GET /api/generation-jobs/{jobId}
```

响应示例：

```json
{
  "id": 1,
  "projectId": 1,
  "status": "RUNNING",
  "currentStage": "scene_draft",
  "errorMessage": null,
  "createdAt": "2026-06-08T10:00:00+08:00",
  "finishedAt": null,
  "progress": {
    "total": 8,
    "completed": 3,
    "failed": 0
  }
}
```

常见阶段名：

| 阶段 | 说明 |
| --- | --- |
| `generation_input` | 记录输入快照 |
| `script_generation` | 调度生成管线 |
| `staged_script_generation` | 多阶段生成入口 |
| `legacy_script_generation` | 旧链路生成 |
| `chapter_digest` | 章节摘要聚合阶段 |
| `chapter_digest:{index}` | 指定章节摘要 |
| `story_bible` | 故事圣经 |
| `scene_plan` | 场景规划 |
| `scene_draft` | 分场草稿聚合阶段 |
| `scene_draft:{sceneId}` | 指定场景草稿 |
| `script_assembly` | 最终组装 |
| `serializing_json` | 序列化 JSON |
| `exporting_yaml` | 导出 YAML |
| `saving_snapshot` | 保存剧本文档 |

### 查询最新剧本

```http
GET /api/projects/{projectId}/scripts/latest
```

响应字段：

| 字段 | 说明 |
| --- | --- |
| `id` | 剧本文档 ID |
| `projectId` | 项目 ID |
| `schemaVersion` | YAML Schema 版本 |
| `validationStatus` | `VALID` 或 `INVALID` |
| `yamlContent` | YAML 文本 |
| `createdAt` | 创建时间 |

### 下载 YAML

```http
GET /api/projects/{projectId}/scripts/latest/download
```

响应：

```text
Content-Type: application/x-yaml; charset=UTF-8
Content-Disposition: attachment; filename="script-{projectId}.yaml"
```

### curl.exe 示例

Windows PowerShell 中建议使用 `curl.exe`，避免调用到 `Invoke-WebRequest` 别名。

```powershell
curl.exe -X POST http://localhost:8080/api/projects `
  -H "Content-Type: application/json" `
  -d "{\"title\":\"雨夜的信\",\"sourceText\":\"第一章 雨夜\n正文一\n\n第二章 旧信\n正文二\n\n第三章 归来\n正文三\"}"
```

```powershell
curl.exe -X POST http://localhost:8080/api/projects/1/generation-jobs `
  -H "Content-Type: application/json" `
  -d "{\"format\":\"web_drama\",\"tone\":\"suspense\",\"dialogueDensity\":70,\"narrationRetention\":30,\"additionalInstructions\":\"节奏紧凑。\"}"
```

```powershell
curl.exe http://localhost:8080/api/generation-jobs/1
```

## 生成流程

默认 `staged` 管线大致如下：

1. 记录生成输入快照。
2. 对每个章节生成摘要，提取人物、地点、冲突、时间线信息。
3. 汇总章节摘要，生成故事圣经。
4. 根据故事圣经规划剧本场景。
5. 为每个场景生成动作、对白、转场和备注。
6. 组装最终剧本文档。
7. 校验角色、地点、场景引用和结构字段。
8. 序列化为 JSON。
9. 导出 YAML。
10. 保存剧本文档快照。

模型不会直接输出最终 YAML。后端会要求模型返回结构化 JSON，解析为 Java 模型并校验后，再统一导出 YAML。这样可以降低格式漂移和引用错误。

## YAML 输出格式

YAML Schema 当前版本为 `1.0`。完整说明见：

```text
docs/script-yaml-schema.md
```

顶层结构：

```yaml
schema_version: "1.0"
metadata: {}
adaptation: {}
characters: []
locations: []
scenes: []
revision_notes: []
```

核心字段：

| 字段 | 说明 |
| --- | --- |
| `metadata` | 标题、语言、来源章节数、生成时间 |
| `adaptation` | 剧本类型、风格、logline、主题 |
| `characters` | 角色表，对白通过 `speaker_id` 引用角色 |
| `locations` | 地点表，场景通过 `location_id` 引用地点 |
| `scenes` | 剧本主体，每个元素是一场戏 |
| `revision_notes` | AI 改编时的删减、合并和转化说明 |

场景内容块支持：

| 类型 | 说明 |
| --- | --- |
| `action` | 动作或场面描写 |
| `dialogue` | 对白，需要 `speaker_id` |
| `transition` | 转场 |
| `note` | 创作备注 |

## 测试与质量检查

### 后端测试

```bash
cd backend
mvn test
```

### 前端类型检查和构建

```bash
cd frontend
npm install
npm run build
```

`npm run build` 会先执行：

```text
vue-tsc --noEmit
```

然后执行：

```text
vite build
```

### Docker 构建检查

```bash
docker compose build
```

## 数据库与迁移

### 本地初始化脚本

```text
database/init-mysql.sql
```

负责：

- 创建 `novel_player` 数据库。
- 创建 `novel` 应用用户。
- 授权 `novel_player.*`。
- 设置 utf8mb4 字符集和排序规则。

### Flyway 迁移

```text
backend/src/main/resources/db/migration/V1__init.sql
```

负责创建业务表。后端配置中：

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
    baseline-on-migrate: true
```

含义：

- 表结构由 Flyway 管理。
- Hibernate 只校验实体与表结构，不自动改表。
- 如果本地库曾经手工建过表，`baseline-on-migrate` 可以让 Flyway 接管已有结构。

## 常见问题

### 1. 启动后端提示找不到 `application-local.yml`

默认 profile 是 `local`。先复制本地配置：

```powershell
Copy-Item backend\src\main\resources\application-local.example.yml backend\src\main\resources\application-local.yml
```

或显式指定其他 profile。

### 2. MySQL 连接失败

检查：

- MySQL 是否启动。
- `novel_player` 数据库是否存在。
- `application-local.yml` 中账号密码是否正确。
- Docker Compose 中宿主机端口是否被占用。

本地默认连接：

```text
jdbc:mysql://localhost:3306/novel_player
username: novel
password: novel_pass
```

Docker 默认宿主机端口：

```text
localhost:3307
```

### 3. Flyway 报 `Found non-empty schema(s) but no schema history table`

说明数据库里已有表，但不是由 Flyway 初始化。当前 `local` 和 `test` 配置已开启 `baseline-on-migrate`。如果仍有问题，可以在确认数据无用后删除本地库并重新初始化：

```sql
DROP DATABASE novel_player;
```

然后重新执行：

```bash
mysql -u root -p < database/init-mysql.sql
```

### 4. 没有 DeepSeek API Key 能不能运行

可以。默认配置为：

```env
NOVEL_PLAYER_MOCK_AI=true
SPRING_AI_MODEL_CHAT=none
```

这会走 mock AI，适合演示完整流程。

### 5. 接入 DeepSeek 后启动失败

检查：

- `NOVEL_PLAYER_MOCK_AI=false`
- `SPRING_AI_MODEL_CHAT=openai`
- `DEEPSEEK_API_KEY` 已设置。
- `DEEPSEEK_BASE_URL` 可访问。
- 模型名与当前 DeepSeek 账号支持的模型一致。

### 6. 前端请求 `/api` 失败

本地开发时确认：

- 后端在 `http://localhost:8080`。
- `frontend/.env` 中 `VITE_API_PROXY_TARGET` 指向正确地址。
- Vite 开发服务需要重启才能读取新的 `.env`。

Docker 部署时确认：

- `frontend/nginx.conf` 中 `/api` 代理到 `http://app:8080`。
- `app` 容器健康启动。
- `docker compose logs -f app` 没有后端错误。

### 7. 上传 TXT 后内容乱码

前端会尝试 UTF-8 和 GB18030。仍乱码时，建议把 TXT 另存为 UTF-8 后再上传。

### 8. 生成任务看起来停住了

前端通过轮询查询任务状态，默认每约 1.6 秒查询一次。可以检查：

- `docker compose logs -f app`
- 当前 `currentStage`
- DeepSeek 是否限流或超时。
- `NOVEL_PLAYER_CHAPTER_DIGEST_CONCURRENCY` 和 `NOVEL_PLAYER_SCENE_DRAFT_CONCURRENCY` 是否过高。

### 9. `暂停生成` 会停止后端任务吗

不会。它只暂停前端轮询。后台任务仍会继续执行，点击继续后会恢复查询。

### 10. 为什么勾选部分章节后 API 仍没有章节范围

当前 `GenerationRequest` 只有生成风格和密度等参数，尚未包含章节选择字段。前端已有章节选择交互，但后端生成请求目前按项目内已拆分章节执行。若要真正支持部分章节生成，需要扩展请求 DTO、服务层和生成输入快照。

## 开发注意事项

- `application-local.yml`、根目录 `.env`、真实 API Key 和本地密码都不要提交。
- 后端 Controller 只做 Web 入参与应用层对象转换，业务逻辑放在 application 层。
- AI 输出优先走结构化 JSON，再由后端校验和导出 YAML。
- 新增数据库结构时使用 Flyway 迁移脚本，不要依赖 Hibernate 自动建表。
- 新增生成阶段时，应统一维护 `GenerationStageNames`，避免在多个服务中手写阶段名。
- 前端 API 类型集中在 `frontend/src/api/types.ts`，接口调用集中在 `frontend/src/api/projectApi.ts`。
- YAML Schema 变更时，需要同步更新 `docs/script-yaml-schema.md`、后端校验逻辑和前端结构树展示。
