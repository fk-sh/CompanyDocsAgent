# CompanyDocsAgent — 企业文档智能问答系统

基于 Spring Boot 3 + LangChain4j + DeepSeek 的多 Agent 协作知识库问答系统。

## 功能特性

- **多 Agent 协作**：RouterAgent 意图识别 → OrchestratorAgent 编排调度 → RetrieverAgent 检索 → GeneratorAgent 生成 → ReviewerAgent 审核
- **混合检索**：向量语义检索 + BM25 关键词检索 + 元数据过滤 + RRF 融合 + 粗排精排
- **ReAct 循环**：检索不足时自动改写查询重新检索，最多 3 轮
- **Reflection 反思**：生成答案后 LLM-as-Judge 多维度审核，不通过则回退重写
- **多意图拆分**：复合请求自动拆解为子任务，逐条执行后汇总
- **三层记忆**：短期（会话历史）+ 长期（ES 语义检索）+ 用户画像（LLM 自动提取）
- **SSE 流式输出**：支持 Server-Sent Events 实时推送回答
- **文档摄入**：支持 PDF/Word/Markdown 文档解析入库（解析 → 提取 → 切割 → 向量化 → ES 存储）

## 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | Java | 21 |
| 框架 | Spring Boot | 3.3.5 |
| AI 框架 | LangChain4j | 0.36.2 |
| LLM | DeepSeek | deepseek-chat |
| 检索引擎 | Elasticsearch | 8.15.3 |
| 数据库 | MySQL | 8.x |
| 缓存 | Redis + Caffeine | - |
| 文档解析 | Apache Tika | - |
| 监控 | Micrometer + Prometheus | - |

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.8+
- MySQL 8.0+
- Elasticsearch 8.x
- Redis 7.x（可选，用于 Embedding 缓存）

### 1. 克隆项目

```bash
git clone <repo-url>
cd CompanyDocsAgent
```

### 2. 配置环境

编辑 `src/main/resources/application-dev.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/agent?serverTimezone=Asia/Shanghai
    username: root
    password: your_password

  elasticsearch:
    uris: http://localhost:9200
```

设置 DeepSeek API Key 环境变量：

```bash
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx
```

### 3. 初始化数据库

执行 `src/main/resources/db/schema-memory.sql` 创建表结构。

### 4. 启动服务

```bash
mvn spring-boot:run
```

服务启动后默认监听 `http://localhost:8080`。

## API 接口

### 对话

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/chat` | 同步对话（多 Agent 完整链路） |
| GET | `/api/v1/chat/stream?query=xxx` | SSE 流式对话 |

**POST /api/v1/chat 请求示例**：

```json
{
  "sessionId": "sess-abc123",
  "query": "公司去年的营收是多少？"
}
```

**响应示例**：

```json
{
  "sessionId": "sess-abc123",
  "query": "公司去年的营收是多少？",
  "answer": "根据财务报告，2024年公司营收为12.5亿元 [来源1]...",
  "contexts": ["[来源1] 2024年度财务报告..."],
  "latencyMs": 3200,
  "intent": "knowledge_qa"
}
```

### 会话管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/sessions` | 创建新会话 |
| GET | `/api/v1/sessions?userId=xxx` | 查询会话列表 |
| DELETE | `/api/v1/sessions/{id}` | 删除会话 |

### 文档管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/documents/upload` | 上传文档（multipart/form-data） |
| GET | `/api/v1/documents` | 文档列表 |
| DELETE | `/api/v1/documents/{id}` | 删除文档 |

### 反馈

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/feedback` | 提交用户反馈 |

### 评测

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/eval/run` | 触发评测 |
| GET | `/api/v1/eval/reports` | 评测报告列表 |

### 测试接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/test/chat` | 简单透传对话（不走多 Agent 链路） |
| GET | `/api/test/chat/stream` | 简单流式对话 |

### MCP 工具调用

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/mcp/chat` | MCP 工具感知对话（LLM 自主发现和调用工具） |

## 使用 curl 测试

```bash
# 同步对话
curl -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"query":"公司的年假政策是什么？"}'

# SSE 流式对话
curl -N http://localhost:8080/api/v1/chat/stream?query=你好

# 创建会话
curl -X POST http://localhost:8080/api/v1/sessions \
  -H "Content-Type: application/json" \
  -d '{"userId":"user-001","title":"财务咨询"}'

# 查询会话列表
curl http://localhost:8080/api/v1/sessions?userId=user-001

# 上传文档
curl -X POST http://localhost:8080/api/v1/documents/upload \
  -F "file=@/path/to/document.pdf"

# 提交反馈
curl -X POST http://localhost:8080/api/v1/feedback \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"sess-abc","rating":"good","comment":"回答准确"}'
```

## 评测

使用 Python + RAGAS 进行自动化评测：

```bash
cd docs/eval
pip install -r requirements.txt
export DEEPSEEK_API_KEY=sk-xxx
python ragas_eval.py
```

评测报告输出：
- `eval_report.json` — JSON 格式报告
- `eval_report.md` — Markdown 可读报告

### 评测指标

| 类别 | 指标 |
|------|------|
| 检索质量 | Recall@K, Precision@K, MRR, NDCG@K |
| 生成质量 | Faithfulness, AnswerRelevancy, ContextRecall, ContextPrecision |
| 端到端 | AnswerCorrectness |
| 系统质量 | 延迟(P50/P95/P99), 吞吐量 |

## 项目结构

```
src/main/java/com/agent/
├── agent/              # Agent 实现
│   ├── OrchestratorAgent.java   # 编排中枢
│   ├── RouterAgent.java         # 意图识别
│   ├── RetrieverAgent.java      # 文档检索
│   ├── GeneratorAgent.java      # 答案生成
│   ├── ReviewerAgent.java       # 质量审核
│   ├── IngestionAgent.java      # 文档摄入
│   └── WeatherAgent.java        # MCP 天气查询
├── api/                # REST 接口层
│   ├── ChatController.java      # 对话接口
│   ├── SessionController.java   # 会话管理
│   ├── FeedbackController.java  # 用户反馈
│   ├── EvalController.java      # 评测接口
│   └── GlobalExceptionHandler.java
├── dto/                # 请求/响应对象
├── core/               # 核心接口（Agent, AgentContext, Orchestrator）
├── retrieval/          # 检索子系统（混合检索、排序、查询改写）
├── memory/             # 三层记忆体系
├── llm/                # DeepSeek LLM 客户端
├── mcp/                # MCP 协议工具调用
├── ingestion/          # 文档摄入管道
├── vectordb/           # ES 向量存储
├── prompt/             # Prompt 模板管理
├── service/            # 业务服务层
└── config/             # 配置类（CORS、异步等）
```

## 架构文档

- [技术设计文档](docs/DESIGN.md)
- [多 Agent 编排详解](docs/multi-agent-orchestration.md)
- [面试讲解文档](docs/multi-agent-orchestration-interview.md)
