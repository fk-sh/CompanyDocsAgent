# CompanyDocsAgent — 企业文档智能问答系统

基于 Spring Boot 3、LangChain4j、DeepSeek、Elasticsearch、MySQL 与 RocketMQ 的企业知识库智能问答系统。系统支持多 Agent 编排、混合检索、文档摄入、会话记忆、权限控制、MCP 工具调用和管理后台接口。

## 功能特性

- **多 Agent 编排**：OrchestratorAgent 负责意图识别、任务规划与调度，支持知识库问答、天气查询、闲聊和多意图任务。
- **混合检索**：向量召回、BM25 关键词召回、元数据召回并行执行，通过 RRF 融合、粗排、精排和父子 Chunk 扩展返回上下文。
- **多查询改写**：对检索请求进行扩展与合并，提升复杂问题的召回覆盖率。
- **文档摄入**：支持普通上传和分块上传，解析文档后进行内容提取、父子切割、Embedding 生成并写入 Elasticsearch。
- **异步摄入**：优先通过 RocketMQ 投递文档摄入任务；生产者不可用时回退为本地异步摄入。
- **文档权限**：文档支持 `DEPARTMENT` / `COMPANY` 可见性，检索时结合当前用户信息进行过滤。
- **用户认证与管理**：支持注册、登录、JWT 当前用户识别、默认管理员初始化和管理员用户管理。
- **会话记忆**：会话和消息持久化到 MySQL，并结合用户偏好记忆和语义记忆构建上下文。
- **SSE 流式输出**：支持 Server-Sent Events 实时返回回答片段。
- **MCP 工具调用**：提供 MCP 感知对话接口，LLM 可自主发现并调用工具，例如天气查询。
- **监控指标**：暴露 Actuator、Health、Prometheus 指标接口。
- **前端静态资源**：内置 `static/index.html`，并映射本地 `frontend/` 目录。

## 技术栈

| 类别 | 技术 | 版本/说明 |
|------|------|-----------|
| 语言 | Java | 21 |
| 框架 | Spring Boot | 3.3.5 |
| AI 框架 | LangChain4j | 0.36.2 |
| LLM | DeepSeek | `deepseek-chat` |
| 向量/检索 | Elasticsearch Java Client | 8.15.3 |
| 数据库 | MySQL | 8.x |
| ORM | MyBatis-Plus | 3.5.9 |
| 缓存 | Redis + Caffeine | Redis 用于数据缓存，Caffeine 用于本地缓存 |
| 文档解析 | Apache Tika | 3.1.0 |
| 消息队列 | RocketMQ | 2.3.0 starter |
| MCP | Model Context Protocol SDK | 0.18.2 |
| 监控 | Spring Boot Actuator + Micrometer Prometheus | - |

## 环境要求

- JDK 21+
- Maven 3.8+
- MySQL 8.0+
- Elasticsearch 8.x
- Redis 7.x
- RocketMQ NameServer / Broker（文档摄入异步队列，可选但推荐）
- 本地 Embedding 服务，默认地址：`http://localhost:11434`
- DeepSeek API Key

## 快速开始

### 1. 克隆项目

```bash
git clone <repo-url>
cd CompanyDocsAgent
```

### 2. 初始化数据库

创建数据库：

```sql
CREATE DATABASE IF NOT EXISTS agent DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

执行表结构脚本：

```bash
mysql -uroot -p agent < src/main/resources/db/schema-memory.sql
```

当前脚本会创建：

- `users`：系统用户表
- `documents`：文档元数据表
- `agent_sessions`：会话表
- `agent_messages`：消息表

### 3. 修改开发配置

编辑 `src/main/resources/application-dev.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/agent?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
    username: root
    password: your_password

  data:
    redis:
      host: localhost
      port: 6379

  elasticsearch:
    uris: http://localhost:9200

deepseek:
  base-url: https://api.deepseek.com/v1
  api-key: ${DEEPSEEK_API_KEY}
  model-name: deepseek-chat

embedding:
  base-url: http://localhost:11434
  model-name: dengcao/Qwen3-Embedding-0.6B:Q8_0
  dimension: 1024

rocketmq:
  name-server: ${ROCKETMQ_NAME_SERVER:localhost:9876}
```

### 4. 设置环境变量

Linux/macOS：

```bash
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx
export ROCKETMQ_NAME_SERVER=localhost:9876
```

Windows PowerShell：

```powershell
$env:DEEPSEEK_API_KEY="sk-xxxxxxxxxxxxxxxx"
$env:ROCKETMQ_NAME_SERVER="localhost:9876"
```

### 5. 启动服务

```bash
mvn spring-boot:run
```

服务默认监听：

```text
http://localhost:8080
```

首次启动时，如果数据库中不存在管理员账号，系统会自动创建默认管理员：

| 字段 | 默认值 |
|------|--------|
| 用户名 | `admin` |
| 密码 | `admin123` |
| 姓名 | `系统管理员` |
| 部门 | `技术部` |
| 角色 | `ADMIN` |

可通过环境变量或 JVM 参数覆盖：

- `APP_INIT_DEFAULT_ADMIN_USERNAME`
- `APP_INIT_DEFAULT_ADMIN_PASSWORD`
- `APP_INIT_DEFAULT_ADMIN_NAME`
- `APP_INIT_DEFAULT_ADMIN_DEPARTMENT`

## 认证说明

大部分接口会从请求头读取 JWT：

```http
Authorization: Bearer <token>
```

认证拦截器会解析 Token 并注入当前用户。未携带 Token 的请求不会被拦截器直接拒绝，但调用需要当前用户的接口时会抛出未授权异常。

## API 接口

### 认证

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/auth/register` | 注册用户 |
| POST | `/api/v1/auth/login` | 登录并返回 JWT |
| GET | `/api/v1/auth/me` | 获取当前登录用户 |

登录请求示例：

```json
{
  "account": "admin",
  "password": "admin123"
}
```

登录响应示例：

```json
{
  "token": "<jwt-token>",
  "user": {
    "id": "...",
    "username": "admin",
    "name": "系统管理员",
    "department": "技术部",
    "role": "ADMIN"
  }
}
```

### 对话

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/chat` | 同步对话，执行多 Agent 链路 |
| GET | `/api/v1/chat/stream?query=xxx&sessionId=xxx` | SSE 流式对话 |

请求示例：

```json
{
  "sessionId": "sess-abc123",
  "query": "公司的年假政策是什么？"
}
```

响应示例：

```json
{
  "sessionId": "sess-abc123",
  "query": "公司的年假政策是什么？",
  "answer": "根据知识库文档，公司年假政策为...",
  "contexts": ["[来源1] ..."],
  "latencyMs": 3200
}
```

### 会话管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/sessions` | 创建新会话 |
| GET | `/api/v1/sessions?limit=20&offset=0` | 查询当前用户会话列表 |
| GET | `/api/v1/sessions/{sessionId}/messages?limit=100` | 查询会话消息 |
| DELETE | `/api/v1/sessions/{sessionId}` | 删除当前用户会话 |

创建会话请求示例：

```json
{
  "title": "财务制度咨询"
}
```

### 文档管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/documents/upload` | 普通文件上传，参数 `file`，可选 `visibility` |
| GET | `/api/v1/documents` | 查询内存状态中的文档列表 |
| GET | `/api/v1/documents/mine?limit=100&offset=0` | 查询当前用户上传的文档 |
| GET | `/api/v1/documents/{id}` | 查询文档状态详情 |
| GET | `/api/v1/documents/failed` | 查询失败/死信文档 |
| POST | `/api/v1/documents/{id}/retry` | 重试失败文档摄入 |
| DELETE | `/api/v1/documents/{id}` | 删除文档状态记录 |

文档可见性：

| 值 | 说明 |
|----|------|
| `DEPARTMENT` | 仅同部门用户可检索 |
| `COMPANY` | 公司内用户可检索 |

普通上传示例：

```bash
curl -X POST "http://localhost:8080/api/v1/documents/upload?visibility=DEPARTMENT" \
  -H "Authorization: Bearer <token>" \
  -F "file=@/path/to/document.pdf"
```

### 分块上传

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/documents/chunk/init` | 初始化分块上传 |
| POST | `/api/v1/documents/chunk/{uploadId}` | 上传单个分块 |
| GET | `/api/v1/documents/chunk/{uploadId}/progress` | 查询上传进度 |
| POST | `/api/v1/documents/chunk/{uploadId}/complete` | 合并分块并提交摄入 |

初始化请求示例：

```json
{
  "fileName": "manual.pdf",
  "fileSize": 10485760,
  "totalChunks": 5,
  "chunkSize": 2097152,
  "fileHash": "sha256-or-md5"
}
```

### 管理员接口

所有管理员接口都需要当前用户角色为 `ADMIN`。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/admin/users?limit=50&offset=0` | 用户列表 |
| GET | `/api/v1/admin/users/{id}` | 用户详情 |
| POST | `/api/v1/admin/users` | 创建用户 |
| PUT | `/api/v1/admin/users/{id}` | 更新用户资料/密码/角色 |
| PUT | `/api/v1/admin/users/{id}/status` | 更新用户状态 |
| DELETE | `/api/v1/admin/users/{id}` | 软删除用户 |
| GET | `/api/v1/admin/documents?limit=50&offset=0` | 全量文档列表 |
| PUT | `/api/v1/admin/documents/{id}/status` | 更新文档状态 |
| DELETE | `/api/v1/admin/documents/{id}` | 删除文档并清理 ES Chunk |

用户状态：`ACTIVE` / `DISABLED` / `DELETED`

文档状态：`PROCESSING` / `READY` / `FAILED` / `DISABLED` / `DELETED`

### 反馈

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/feedback` | 提交用户反馈 |

请求示例：

```json
{
  "sessionId": "sess-abc",
  "messageId": "msg-001",
  "rating": "good",
  "comment": "回答准确"
}
```

### 评测

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/eval/run` | 提交评测任务 |
| GET | `/api/v1/eval/reports` | 查询评测报告占位信息 |

当前接口只提交/返回评测占位信息，完整 RAGAS 评测需运行 `docs/eval/ragas_eval.py`。

### MCP 工具感知对话

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/mcp/chat` | MCP 工具感知对话 |
| GET | `/api/mcp/chat?message=xxx` | MCP 工具感知对话 |

请求示例：

```json
{
  "message": "北京今天天气怎么样？"
}
```

响应示例：

```json
{
  "answer": "北京当前天气...",
  "toolCalls": "调用工具：weather_query，参数：..."
}
```

## 常用 curl 示例

```bash
# 登录
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"account":"admin","password":"admin123"}'

# 同步问答
curl -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"query":"公司的年假政策是什么？"}'

# 流式问答
curl -N "http://localhost:8080/api/v1/chat/stream?query=你好" \
  -H "Authorization: Bearer <token>"

# 创建会话
curl -X POST http://localhost:8080/api/v1/sessions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"title":"财务咨询"}'

# 查询当前用户文档
curl http://localhost:8080/api/v1/documents/mine \
  -H "Authorization: Bearer <token>"

# 上传文档
curl -X POST "http://localhost:8080/api/v1/documents/upload?visibility=COMPANY" \
  -H "Authorization: Bearer <token>" \
  -F "file=@/path/to/document.pdf"

# MCP 对话
curl -X POST http://localhost:8080/api/mcp/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"杭州天气怎么样？"}'
```

## 文档摄入流程

```text
上传文件
  ├─ 普通上传：/documents/upload
  └─ 分块上传：init → upload chunk → progress → complete
        │
        ▼
保存临时文件与文档元数据
        │
        ├─ RocketMQ 可用：发送 DocumentIngestionMessage
        └─ RocketMQ 不可用：本地异步摄入
        │
        ▼
Tika 解析 PDF / Word / Markdown / 文本
        │
        ▼
内容提取：TEXT / TABLE / CODE / IMAGE
        │
        ▼
Parent-Child Chunk 切割
        │
        ▼
调用 Embedding 服务生成 1024 维向量
        │
        ▼
写入 Elasticsearch agent_chunks 索引
        │
        ▼
文档状态更新为 READY
```

## 问答链路

```text
用户问题
  │
  ▼
MemoryManager 构建上下文
  │
  ▼
OrchestratorAgent
  ├─ 快速规则识别闲聊/天气
  ├─ LLM 判断是否多意图
  └─ 生成 Plan
        │
        ├─ chitchat → GeneratorAgent
        ├─ weather → WeatherAgent
        └─ knowledge_qa → RetrieverAgent → GeneratorAgent
                          │
                          ├─ QueryRewriter 扩展查询
                          ├─ 多路召回：向量 / BM25 / 元数据
                          ├─ RRF 融合
                          ├─ 粗排 + 精排
                          └─ ParentChildResolver 扩展上下文
```

## 项目结构

```text
src/main/java/com/agent/
├── AgentApplication.java        # Spring Boot 启动类
├── agent/                       # Agent 实现与任务计划
│   ├── OrchestratorAgent.java   # 意图识别、任务规划、多 Agent 编排
│   ├── RetrieverAgent.java      # 知识库检索 Agent
│   ├── GeneratorAgent.java      # 回答生成 Agent
│   ├── WeatherAgent.java        # 天气查询 Agent
│   └── Plan.java                # 意图计划模型
├── api/                         # REST API
│   ├── ChatController.java      # 对话、文档上传、文档状态、分块上传
│   ├── SessionController.java   # 会话管理
│   ├── AdminController.java     # 管理员用户/文档管理
│   ├── FeedbackController.java  # 用户反馈
│   ├── EvalController.java      # 评测入口
│   ├── McpChatController.java   # MCP 工具感知对话
│   └── dto/                     # API DTO
├── auth/                        # 登录、注册、JWT、当前用户、权限异常
├── user/                        # 用户实体、服务、默认管理员初始化
├── document/                    # 文档元数据、状态、权限可见性
├── upload/                      # 分块上传管理
├── mq/                          # RocketMQ 生产者、消费者、状态存储、死信处理
├── core/                        # Agent、Retriever、Chunk、Document、Memory 等核心模型
├── retrieval/                   # 混合检索、召回策略、排序、查询改写、父子 Chunk 解析
├── ingestion/                   # 文档解析、内容提取、Chunk 切割、全链路摄入
├── vectordb/                    # Embedding、Elasticsearch 索引与向量存储
├── memory/                      # 会话记忆、消息存储、长期/偏好记忆
├── llm/                         # DeepSeek 同步与流式客户端
├── mcp/                         # MCP 客户端、工具与服务配置
├── service/                     # MCP 感知对话服务
└── config/                      # Web、CORS、异步等配置

src/main/resources/
├── application.yml              # 基础配置
├── application-dev.yml          # 开发环境配置
├── db/schema-memory.sql         # MySQL 表结构
└── static/index.html            # 静态页面

frontend/
└── index.html                   # 本地前端页面，通过 /frontend/** 映射
```

## 监控接口

Actuator 暴露以下端点：

| 路径 | 说明 |
|------|------|
| `/actuator/health` | 健康检查 |
| `/actuator/info` | 应用信息 |
| `/actuator/prometheus` | Prometheus 指标 |

## 配置文件摘要

主配置 `application.yml`：

- 默认激活 `dev` profile
- 服务端口 `8080`
- Multipart 限制：单文件 `10MB`，请求 `50MB`
- 静态资源位置：`classpath:/static/`
- `/frontend/**` 映射到项目根目录下 `frontend/`

开发配置 `application-dev.yml`：

- MySQL：`jdbc:mysql://localhost:3306/agent`
- Redis：`localhost:6379`
- Elasticsearch：`http://localhost:9200`
- DeepSeek：环境变量 `DEEPSEEK_API_KEY`
- Embedding：`http://localhost:11434`
- RocketMQ：环境变量 `ROCKETMQ_NAME_SERVER`，默认 `localhost:9876`

## 注意事项

- 默认开发配置中可能包含本地数据库密码，请按实际环境修改，不要提交真实生产凭据。
- 需要当前用户的接口必须携带有效 `Authorization: Bearer <token>`。
- 文档检索质量依赖 Elasticsearch 索引、Embedding 服务和文档摄入状态。
- 如果 RocketMQ 不可用，上传接口会尝试本地异步摄入；如果摄入管道未装配，文档会处于待处理状态。
- 默认管理员密码仅用于本地初始化，首次登录后应立即修改。
