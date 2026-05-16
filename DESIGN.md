# Java Agent 知识库问答系统 — 整体设计方案

## 一、项目概述

基于 Java 的智能 Agent 项目，实现一个知识库问答系统，支持多模态文档（文本、图片、表格、代码）的离线摄入、在线检索召回、多轮记忆对话，并具备自动化质量评估能力。

---

## 二、整体架构

```
用户请求
  │
  ▼
┌──────────────────────────────────────────────────────────────────┐
│                     Spring Boot 3.x + LangChain4j                 │
└────────────────────────────┬─────────────────────────────────────┘
                             │
        ┌────────────────────┼────────────────────┐
        ▼                    ▼                     ▼
┌───────────────┐  ┌─────────────────┐  ┌──────────────────┐
│   大模型       │  │   检索引擎(ES)   │  │   关系数据库       │
│               │  │                 │  │                  │
│ DeepSeek      │  │ 向量检索(KNN)   │  │ MySQL 8.x        │
│ V4-Pro        │  │ 全文检索(BM25)  │  │ 会话/用户/评估    │
│ (DeepSeek API)│  │ 元数据过滤      │  │ Prompt模板       │
│               │  │ (三位一体)      │  │ 文档索引          │
└───────────────┘  └─────────────────┘  └──────────────────┘
                             │
        ┌────────────────────┼────────────────────┐
        ▼                    ▼                     ▼
┌───────────────┐  ┌─────────────────┐  ┌──────────────────┐
│   缓存         │  │   消息队列       │  │   可观测性         │
│               │  │                 │  │                  │
│ Redis +       │  │ RocketMQ        │  │ Prometheus       │
│ Caffeine      │  │ (异步任务解耦)   │  │ + Grafana        │
│ 双层缓存       │  │                  │  │                  │
└───────────────┘  └─────────────────┘  └──────────────────┘
                             │
                             ▼
┌──────────────────────────────────────────────────────────────────┐
│                        模型层（全部本地免费部署）                     │
│                                                                    │
│  向量化模型    bge-large-zh-v1.5       (文本 → 1024维向量)          │
│  重排序模型    bge-reranker-v2-m3      (精排，Cross-Encoder)       │
│  视觉模型      Qwen2-VL-7B / MiniCPM-V (图片描述生成)              │
│  文档解析      Apache Tika + PDFBox     (PDF/Word/Markdown)        │
└──────────────────────────────────────────────────────────────────┘
```

---

## 三、中间件选型

| 序号 | 模块 | 选型 | 许可 | 用途 |
|------|------|------|------|------|
| 1 | AI 框架 | LangChain4j | Apache 2.0 | Agent/RAG 开发框架 |
| 2 | 对话模型 | DeepSeek V4-Pro | DeepSeek API | 答案生成/推理 |
| 3 | 向量+全文+元数据 | Elasticsearch 8.x | Elastic-2.0 | 一站式检索引擎 |
| 4 | 业务数据库 | MySQL 8.x | GPL | 会话/用户/评估/模板 |
| 5 | 分布式缓存 | Redis 7.x | BSD | Session 共享/Embedding 缓存 |
| 6 | 本地缓存 | Caffeine | Apache 2.0 | Prompt 模板等热数据 |
| 7 | 消息队列 | RocketMQ | Apache 2.0 | 异步任务解耦 |
| 8 | Embedding 模型 | bge-large-zh-v1.5 | MIT | 文本向量化 |
| 9 | Reranker 模型 | bge-reranker-v2-m3 | MIT | 检索结果精排 |
| 10 | VLM 视觉模型 | Qwen2-VL-7B | Apache 2.0 | 图片描述生成 |
| 11 | 文档解析 | Apache Tika + PDFBox | Apache 2.0 | PDF/Word/PPT 解析 |
| 12 | 容器平台 | Docker Compose | Apache 2.0 | 中间件编排 |
| 13 | 监控告警 | Prometheus + Grafana | Apache 2.0/AGPLv3 | 指标采集 + 可视化 |

---

## 四、核心模块设计

### 4.1 核心抽象层（core）

```
src/main/java/com/agent/core/
├── Agent.java              # Agent 接口
├── AgentContext.java       # 上下文（sessionId、query、variables、messages）
├── Tool.java               # 工具接口
├── Memory.java             # 记忆接口
├── Message.java            # 消息模型
├── Document.java           # 文档模型
├── Chunk.java              # 分块模型
├── Retriever.java          # 检索接口
├── RecallStrategy.java     # 召回策略接口
├── Reranker.java           # 精排接口
├── Orchestrator.java       # 编排接口
└── EmbeddingService.java   # 向量化接口
```

#### Agent 接口

```java
public interface Agent {
    String name();
    String execute(AgentContext context);
    default Flux<String> executeStream(AgentContext context) {
        return Flux.just(execute(context));
    }
}
```

#### Tool 接口

```java
public interface Tool {
    String name();
    String description();   // 给 LLM 看的描述，用于 Agent 自行判断是否调用
    String execute(Map<String, Object> params);
}
```

#### Orchestrator 接口

```java
public interface Orchestrator {
    String orchestrate(AgentContext context);
    Flux<String> orchestrateStream(AgentContext context);
}
```

#### AgentContext

```java
public class AgentContext {
    private String sessionId;
    private String userQuery;
    private final Map<String, Object> variables = new ConcurrentHashMap<>();
    private final List<ChatMessage> history;
    
    public void setVariable(String key, Object value) { ... }
    public <T> T getVariable(String key) { ... }
}
```

---

### 4.2 记忆模块（Memory — 三层模型）

```
┌──────────────────────────────────────────────┐
│              Working Memory                  │  ← 工作记忆（单次推理中）
│    当前步骤的中间结果、工具调用返回值等         │
│    生命周期：一次 execute() 调用              │
├──────────────────────────────────────────────┤
│           Short-Term Memory                  │  ← 短期记忆（当前会话）
│    多轮对话历史、用户偏好、上下文信息           │
│    生命周期：一个 Session                     │
├──────────────────────────────────────────────┤
│           Long-Term Memory                   │  ← 长期记忆（跨会话）
│    用户画像、历史摘要、关键知识、经验沉淀       │
│    生命周期：永久（持久化存储）                 │
└──────────────────────────────────────────────┘
```

**设计要点**：
- **分层存储**：短期存内存、长期存 ES 向量库、摘要存 Redis
- **自动压缩**：Token 超限时，旧对话压缩成摘要释放空间
- **语义检索**：长期记忆向量化，支持"找到和当前问题相关的历史"
- **异步持久化**：记忆写入通过 RocketMQ 异步落库，不阻塞 Agent 主流程
- **统一入口**：`MemoryManager.buildContext()` 一次调用拼装好所有上下文

---

### 4.3 文档摄入管道（Ingestion Pipeline — 离线）

```
原始文档(PDF/Word/Markdown/图片等)
    │
    ▼
┌──────────────────────────────────────────────────────┐
│              Parser（解析器）                          │
│  识别文档结构：段落、标题层级、表格、图片、代码块等       │
│  输出：结构化 Document AST                            │
└──────────────────────┬───────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────┐
│           Multi-Modal Extractor（多模态提取）          │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐             │
│  │ 文本段落  │ │ 图片描述  │ │ 表格摘要  │             │
│  │(直接提取) │ │(VLM生成) │ │(结构化化) │             │
│  └──────────┘ └──────────┘ └──────────┘             │
└──────────────────────┬───────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────┐
│              Chunker（文本切割器）                      │
│  按语义边界把长文本切成多个 Chunk                       │
│  分层切割（标题 → 段落 → 句子）+ 重叠窗口               │
│  父子Chunk：小Chunk检索 + 大Chunk上下文                 │
└──────────────────────┬───────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────┐
│           Embedding（向量化）                          │
│  文本 → bge-large-zh-v1.5 → 1024维向量               │
│  图片描述 → 同样流程向量化                              │
└──────────────────────┬───────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────┐
│           Elasticsearch（向量+全文+元数据）            │
│  chunk_id, content, embedding, metadata, parent_id   │
└──────────────────────────────────────────────────────┘
```

**切割策略**：
- 按标题层级（H1 → H2 → H3）分层切割
- 段落内按句子切分，保证语义完整
- 相邻 Chunk 之间有重叠窗口（128 字符），防止关键信息被切在边界
- 父子 Chunk 关系：子 Chunk (512字符) 用于向量检索，父 Chunk (2048字符) 送入 LLM

**多模态处理**：
- 图片 → Qwen2-VL-7B 生成文字描述 → 描述文本向量化
- 表格 → 转为 Markdown 表格 + 结构化摘要，两者组合存储
- 代码 → 保留原格式 + 添加上下文说明
- 最终所有模态统一转为文本 + embedding 存入 ES

---

### 4.4 检索系统（Retrieval Pipeline — 在线）

```
用户问题
    │
    ▼
① Query 改写（QueryRewriter）
   "我们上次讨论的死锁问题" → 结合记忆补全 → 多个精确子查询
    │
    ▼
② 多路召回（ES 一条查询搞定）
   ┌──────────┐  ┌──────────┐  ┌──────────┐
   │ 向量 KNN  │  │ BM25 全文 │  │ 元数据过滤 │
   └────┬─────┘  └────┬─────┘  └────┬─────┘
        │              │              │
        └──────────────┼──────────────┘
                       │ RRF 融合
                       ▼
                    候选集 ×100
                       │
                       ▼
③ 粗排（Coarse Ranking）
   score = 0.6 × 向量相似度 + 0.4 × BM25 得分 → Top30
   子Chunk命中 → 自动拉取父Chunk完整内容
                       │
                       ▼
④ 精排（Reranker）
   bge-reranker-v2-m3 Cross-Encoder 精确打分 → Top5~10
                       │
                       ▼
⑤ 后处理
   去重、截断、引用格式化 → 最终文档列表
```

**ES 混合查询示例**：
```java
// 一条查询 = BM25 + KNN + 元数据过滤 + RRF 融合
SearchRequest.of(s -> s
    .index("knowledge_chunks")
    .query(q -> q
        .bool(b -> b
            .filter(buildFilterClauses(filters))      // ① 元数据过滤
            .must(m -> m.match(ma -> ma.field("content").query(query))) // ② BM25
        )
    )
    .knn(k -> k
        .field("embedding")
        .queryVector(queryEmbedding)
        .k(topK * 3)                                  // ③ 向量 KNN
    )
    .rank(r -> r.rrf(rrf -> rrf.windowSize(60)))      // ④ RRF 融合
    .size(topK)
);
```

---

### 4.5 Agent 编排（Orchestration）

```
Phase 8：单 Agent 模式
┌──────────────────────────────────────┐
│         KnowledgeQaAgent             │
│  检索 → 拼Prompt → 调LLM → 后处理    │
└──────────────────────────────────────┘

未来扩展：多 Agent 模式
┌─────────────────┐
│  RouterAgent     │ ← 意图识别 + 路由
└────────┬────────┘
         ▼
┌─────────────────┐
│  RetrieverAgent  │ ← 调用检索
└────────┬────────┘
         ▼
┌─────────────────┐
│  GeneratorAgent  │ ← 拼Prompt + 调LLM
└────────┬────────┘
         ▼
┌─────────────────┐
│  ReviewerAgent   │ ← 质量审核
└─────────────────┘
```

**多 Agent 扩展要点**：
- Phase 2 就定义好 `Agent`、`Orchestrator`、`AgentContext` 接口
- Phase 8 的 `KnowledgeQaAgent` 内部用组件化方式写（不是上帝类）
- Agent 间通过 `context.variables`（Map）传递数据，互不感知
- 扩展时只需写新 Agent 类 + 注册到 Spring，不改任何旧代码
- `ChatService` 一行不改，`Orchestrator` 自动编排新 Agent

---

### 4.6 评估体系（Evaluation）

三层评估模型：
```
┌─────────────────────────────────────────────────────┐
│                  最终答案质量                          │
│  End-to-End: 答案准不准、有没有幻觉、引用对不对        │
├─────────────────────────────────────────────────────┤
│                  生成质量                            │
│  Generation: 流畅性、忠实度、是否使用了检索到的知识     │
├─────────────────────────────────────────────────────┤
│                  检索质量                            │
│  Retrieval: 召回了没有、召回的相关不相关、排的对不对    │
├─────────────────────────────────────────────────────┤
│                  系统质量                            │
│  System: 延迟、吞吐、成本、可用性                      │
└─────────────────────────────────────────────────────┘
```

**核心指标**：

| 分类 | 指标 | 说明 |
|------|------|------|
| 检索 | Recall@K | 相关文档被找回的比例 |
| 检索 | Precision@K | 检索结果中相关文档的比例 |
| 检索 | MRR | 第一个相关文档的排名倒数 |
| 检索 | NDCG@K | 考虑排序位置的相关性得分 |
| 生成 | Faithfulness | 答案是否完全来自检索文档，没有编造 |
| 生成 | AnswerRelevance | 答案是否紧扣问题 |
| 生成 | ContextRelevance | 检索文档与问题的相关程度 |
| 端到端 | Correctness | 用强模型对比标准答案打分（1-5分） |
| 端到端 | HallucinationRate | 答案中无法在检索文档中找到支撑的比例 |
| 端到端 | CitationAccuracy | 引用是否指向正确的文档段落 |

**评测方式**：
- **离线自动化评测**：每天定时跑金标数据集，生成评测报告
- **A/B 对比**：版本发布前和上一版本对比，指标退化则拦下
- **线上反馈收集**：用户点赞/点踩 + 隐式信号（复制、追问、重新提问）
- **LLM-as-Judge**：用强模型当裁判，评估忠实度、相关性、幻觉率

---

## 五、端到端执行流程

```
👤 用户提问："我们上次讨论的死锁问题的解决方案是什么？"
    │
    ▼
① 会话重建 & 记忆加载
   MemoryManager.buildContext(sessionId, userQuery)
   → SummaryMemory（Redis）获取上次对话摘要
   → VectorMemory（ES向量检索）获取3条相关历史记忆
   → ConversationMemory（内存）获取最近20条对话
   → 拼装完整 messages 列表
    │
    ▼
② 意图识别 & 路由
   Orchestrator 分析意图 → 识别为「知识库查询」→ 进入 RAG Pipeline
    │
    ▼
③ Query 改写
   QueryRewriter 结合对话历史补全和拆解
   "我们上次讨论的死锁问题" → ["Java多线程死锁的解决方案", "tryLock超时机制", "资源排序法"]
    │
    ▼
④ 多路召回
   ES 一条查询：BM25全文 + KNN向量 + 元数据过滤 + RRF融合 → 候选集×100
    │
    ▼
⑤ 粗排
   加权融合得分 → Top30，子Chunk命中自动拉父Chunk
    │
    ▼
⑥ 精排
   bge-reranker-v2-m3 Cross-Encoder → Top5
    │
    ▼
⑦ Prompt 构建
   System Prompt + 记忆上下文 + 检索到的知识(带引用标记) + 用户问题
    │
    ▼
⑧ LLM 推理 & 流式生成
   DeepSeek V4-Pro → SSE 流式推送给前端
    │
    ▼
⑨ 后处理 & 记忆更新
   引用格式化 → ConversationMemory.add(用户+回答)
   → RocketMQ 异步 → VectorMemory 持久化
   → Token 用量检查 → 超限则触发 compact()
    │
    ▼
📤 返回给用户（带引用来源）
```

---

## 六、项目模块结构

```
agent-kb-qa/
├── pom.xml
├── docker-compose.yml
├── Dockerfile
├── README.md
├── src/main/java/com/agent/
│   ├── AgentApplication.java              # Spring Boot 启动类
│   │
│   ├── core/                               # 核心抽象层（Phase 2）
│   │   ├── Agent.java
│   │   ├── AgentContext.java
│   │   ├── Tool.java
│   │   ├── Memory.java
│   │   ├── Message.java
│   │   ├── Document.java
│   │   ├── Chunk.java
│   │   ├── Retriever.java
│   │   ├── RecallStrategy.java
│   │   ├── Reranker.java
│   │   ├── Orchestrator.java
│   │   └── EmbeddingService.java
│   │
│   ├── llm/                               # LLM 接入层（Phase 3）
│   │   ├── DeepSeekConfig.java
│   │   ├── DeepSeekChatClient.java
│   │   └── DeepSeekStreamingClient.java
│   │
│   ├── ingestion/                         # 文档摄入管道（Phase 4）
│   │   ├── DocumentParser.java
│   │   ├── ContentBlock.java
│   │   ├── ContentType.java
│   │   ├── ContentExtractor.java
│   │   ├── extractors/
│   │   │   ├── TextExtractor.java
│   │   │   ├── ImageExtractor.java
│   │   │   ├── TableExtractor.java
│   │   │   └── CodeExtractor.java
│   │   ├── chunking/
│   │   │   ├── ChunkingStrategy.java
│   │   │   ├── HierarchicalChunker.java
│   │   │   └── ParentChildChunker.java
│   │   ├── IngestionService.java
│   │   └── FullIngestionPipeline.java
│   │
│   ├── embedding/                         # 向量化（Phase 5）
│   │   ├── EmbeddingServiceImpl.java
│   │   ├── BgeEmbeddingConfig.java
│   │   └── EmbeddingCache.java
│   │
│   ├── store/                             # ES 向量存储（Phase 5）
│   │   ├── VectorStore.java
│   │   ├── ElasticsearchVectorStore.java
│   │   ├── EsDocumentMapper.java
│   │   └── EsIndexInitializer.java
│   │
│   ├── retrieval/                         # 检索系统（Phase 6）
│   │   ├── HybridRetriever.java
│   │   ├── QueryRewriter.java
│   │   ├── QueryRewriterImpl.java
│   │   ├── recall/
│   │   │   ├── VectorRecallStrategy.java
│   │   │   ├── KeywordRecallStrategy.java
│   │   │   └── MetadataRecallStrategy.java
│   │   ├── ranking/
│   │   │   ├── CoarseRanker.java
│   │   │   └── FineRanker.java
│   │   ├── RetrievalResult.java
│   │   └── ParentChildResolver.java
│   │
│   ├── memory/                            # 记忆模块（Phase 7）
│   │   ├── MemoryManager.java
│   │   ├── shortterm/
│   │   │   └── ConversationMemory.java
│   │   ├── longterm/
│   │   │   ├── VectorMemory.java
│   │   │   └── SummaryMemory.java
│   │   ├── working/
│   │   │   └── WorkingMemory.java
│   │   └── store/
│   │       ├── MysqlSessionStore.java
│   │       └── MysqlMessageStore.java
│   │
│   ├── orchestrator/                      # Agent 编排（Phase 8）
│   │   ├── SequentialOrchestrator.java
│   │   └── RouterOrchestrator.java
│   │
│   ├── agent/                             # Agent 实现（Phase 8）
│   │   ├── KnowledgeQaAgent.java
│   │   ├── PromptBuilder.java
│   │   └── PostProcessor.java
│   │
│   ├── prompt/                            # Prompt 管理（Phase 8）
│   │   ├── PromptTemplate.java
│   │   ├── PromptRegistry.java
│   │   └── templates/
│   │       └── qa-system-prompt.txt
│   │
│   ├── service/                           # 服务层（Phase 8）
│   │   └── ChatService.java
│   │
│   ├── evaluation/                        # 评估体系（Phase 9）
│   │   ├── EvalSample.java
│   │   ├── EvalResult.java
│   │   ├── EvalReport.java
│   │   ├── EvalDatasetManager.java
│   │   ├── EvaluatorAgent.java
│   │   ├── metrics/
│   │   │   ├── RetrievalMetricsCalculator.java
│   │   │   ├── GenerationMetricsCalculator.java
│   │   │   └── EndToEndMetricsCalculator.java
│   │   ├── EvaluationPipeline.java
│   │   └── AlertService.java
│   │
│   ├── api/                               # REST API（Phase 10）
│   │   ├── ChatController.java
│   │   ├── IngestionController.java
│   │   ├── SessionController.java
│   │   ├── FeedbackController.java
│   │   ├── EvalController.java
│   │   ├── dto/
│   │   │   ├── ChatRequest.java
│   │   │   ├── ChatResponse.java
│   │   │   ├── IngestionRequest.java
│   │   │   └── FeedbackRequest.java
│   │   └── GlobalExceptionHandler.java
│   │
│   └── config/                            # 配置层（Phase 10）
│       ├── RocketMqConfig.java
│       ├── RedisConfig.java
│       ├── EsConfig.java
│       └── CorsConfig.java
│
├── src/main/resources/
│   ├── application.yml
│   ├── application-dev.yml
│   └── data/eval/
│       └── golden_samples.json
│
└── src/test/java/com/agent/
    └── ...                                # 各模块测试
```

---

## 七、从零开始构建步骤

### Phase 1：项目骨架搭建

**目标**：Spring Boot 3 项目跑起来，依赖配置好，Docker Compose 能一键启动所有中间件。

**产出**：
- `pom.xml`：Maven 依赖（LangChain4j、ES、MySQL、Redis、RocketMQ、Prometheus）
- `docker-compose.yml`：ES + MySQL + Redis + RocketMQ + Prometheus + Grafana
- `AgentApplication.java`：Spring Boot 启动类
- `application.yml` + `application-dev.yml`：基础配置
- 空测试类验证项目能启动

**验证**：`mvn clean package` 成功，Docker Compose 全部容器健康运行。

---

### Phase 2：核心接口抽象层

**目标**：定义整个系统的骨架接口，不写实现，只定义契约。

**产出**：
- `Agent.java`、`AgentContext.java`
- `Tool.java`、`Memory.java`、`Message.java`
- `Document.java`、`Chunk.java`
- `Retriever.java`、`RecallStrategy.java`、`Reranker.java`
- `Orchestrator.java`、`EmbeddingService.java`

**验证**：纯接口，编译通过即可。不涉及任何外部依赖调用。

---

### Phase 3：LLM 接入层

**目标**：打通 DeepSeek V4-Pro，实现最基础的文本对话。

**产出**：
- `DeepSeekConfig.java`：配置类
- `DeepSeekChatClient.java`：封装 LangChain4j 的 ChatLanguageModel
- `DeepSeekStreamingClient.java`：封装流式对话能力
- `SimpleChatService.java`：最简单的问答服务
- 集成测试验证

**验证**：发一条消息 "你好" 能收到 DeepSeek 的正常回复。

---

### Phase 4：文档摄入管道

**目标**：实现 PDF 解析、多模态提取、文本切割全流程。

**产出**：
- `DocumentParser.java`：PDF/Word/Markdown 解析器
- `ContentBlock.java`、`ContentType.java`：内容块模型
- `ContentExtractor.java` + 四个提取器实现类
- `HierarchicalChunker.java`、`ParentChildChunker.java`：切割策略
- `IngestionService.java`：摄入流程总控
- 集成测试：PDF → Chunks

**验证**：往测试资源放一个 PDF，跑测试能看到正确的 Chunk 列表。

---

### Phase 5：向量化与 ES 存储

**目标**：实现 Embedding 服务 + ES 向量存储，把 Chunk 存进 ES。

**产出**：
- `EmbeddingServiceImpl.java`：调用 bge-large-zh-v1.5
- `EmbeddingCache.java`：Caffeine 缓存
- `ElasticsearchVectorStore.java`：ES 的 upsert/search/delete
- `EsIndexInitializer.java`：启动时创建索引和 mapping
- `FullIngestionPipeline.java`：串联 Phase 4+Phase 5

**验证**：上传 PDF → ES 里能看到对应的 chunks 数据和向量。

---

### Phase 6：检索系统

**目标**：实现查询改写 → 多路召回 → 粗排 → 精排的完整检索链路。

**产出**：
- `HybridRetriever.java`：BM25 + KNN + RRF 融合
- `QueryRewriterImpl.java`：利用 LLM 改写/拆解查询
- 三个召回策略实现类
- `CoarseRanker.java`、`FineRanker.java`
- `ParentChildResolver.java`：子 Chunk 命中 → 拉父 Chunk
- 检索集成测试

**验证**：ES 中有数据后，输一个问题 → 返回 Top5 文档，人工判断相关性。

---

### Phase 7：记忆模块

**目标**：实现三层记忆体系，支持多轮对话。

**产出**：
- `MemoryManager.java`：统一管理入口
- `ConversationMemory.java`：短期记忆（滑动窗口 + 自动压缩）
- `VectorMemory.java`：长期记忆（ES 向量检索）
- `SummaryMemory.java`：长期记忆（Redis 会话摘要）
- `MysqlSessionStore.java`、`MysqlMessageStore.java`：MySQL 持久化
- MySQL 建表 SQL

**验证**：同一个 session 多次对话，Agent 能记住上文。

---

### Phase 8：Agent 编排与 RAG Pipeline

**目标**：把检索结果 + 记忆上下文 + Prompt 模板拼装，走完整 RAG 流程。

**产出**：
- `SequentialOrchestrator.java`、`RouterOrchestrator.java`
- `KnowledgeQaAgent.java`：核心 RAG Agent（内部组件化，可拆）
- `PromptBuilder.java`、`PostProcessor.java`
- `PromptTemplate.java`、`PromptRegistry.java`
- `ChatService.java`：对外统一入口
- 端到端集成测试

**验证**：上传 PDF → 问里面问题 → 答案准确、有引用来源。

---

### Phase 9：评估体系

**目标**：实现自动化评测 Pipeline，量化检索和生成质量。

**产出**：
- `EvalSample.java`、`EvalResult.java`、`EvalReport.java`
- `EvalDatasetManager.java`：数据集管理
- `EvaluatorAgent.java`：评测 Agent
- 三个指标计算器：检索指标、生成指标、端到端指标
- `EvaluationPipeline.java`：定时跑评测
- `AlertService.java`：指标恶化告警
- `golden_samples.json`：金标数据集
- MySQL 评测报告表

**验证**：跑评测 Pipeline → 生成报告，能看到各项指标数值。

---

### Phase 10：API 层与部署

**目标**：对外暴露 REST API + SSE 流式接口，完善部署。

**产出**：
- `ChatController.java`：POST /chat、GET /chat/stream (SSE)
- `IngestionController.java`：POST /documents/upload
- `SessionController.java`：会话管理 CRUD
- `FeedbackController.java`：POST /feedback
- `EvalController.java`：POST /eval/run
- 请求/响应 DTO 类
- `GlobalExceptionHandler.java`：统一异常处理
- 各中间件配置类
- `Dockerfile`、完整 `docker-compose.yml`
- `README.md`

**API 设计**：
```
POST   /api/v1/chat                  # 同步对话
GET    /api/v1/chat/stream           # SSE 流式对话
POST   /api/v1/documents/upload      # 上传文档入库
GET    /api/v1/documents             # 文档列表
DELETE /api/v1/documents/{id}        # 删除文档
GET    /api/v1/sessions              # 会话列表
POST   /api/v1/sessions              # 新建会话
DELETE /api/v1/sessions/{id}         # 删除会话
POST   /api/v1/feedback              # 提交反馈
POST   /api/v1/eval/run              # 触发评测
GET    /api/v1/eval/reports          # 评测报告列表
```

**验证**：Docker Compose 一键启动，能用 curl / Postman 对话。

---

## 八、多 Agent 扩展能力

### 单 Agent → 多 Agent 演化路径

```
阶段1：单Agent（Phase 8）
┌──────────────────────────────────┐
│  ChatService → KnowledgeQaAgent │  1个Agent，逻辑全在内
└──────────────────────────────────┘

阶段2：单Agent + 多Tool
┌──────────────────────────────────┐
│  ChatService → KnowledgeQaAgent │
│                  ├─ RetrTool    │  1个Agent，N个Tool
│                  ├─ SearchTool  │
│                  └─ CalcTool    │
└──────────────────────────────────┘

阶段3：多Agent + Orchestrator（未来）
┌──────────────────────────────────┐
│  ChatService → Orchestrator     │
│                  ├─ RouterAgent │
│                  ├─ RetrAgent   │  N个Agent，各自有Tool
│                  ├─ GenAgent    │  编排器调度流程
│                  └─ ReviewAgent │
└──────────────────────────────────┘
```

### 扩展零成本的关键

1. **Phase 2 接口决定架构**：`Agent`、`Orchestrator`、`AgentContext` 一开始就是接口
2. **AgentContext.variables 是消息总线**：Agent 间通过 Map 传递数据，互不感知
3. **Phase 8 组件化写法**：`KnowledgeQaAgent` 内部用独立组件，以后拆成多个 Agent 就是把组件提升为 Agent
4. **ChatService 一行不改**：`Orchestrator` 自动编排所有注册的 Agent Bean

```java
// 扩展示例：新加一个 ReviewerAgent，其他代码零改动
@Component
public class ReviewerAgent implements Agent {
    @Override
    public String name() { return "reviewer"; }
    
    @Override
    public String execute(AgentContext ctx) {
        String draft = ctx.getVariable("answer");  // 从前一个Agent拿数据
        return llmClient.chat("评估以下回答质量：\n" + draft);
    }
}
// 注册到 Spring → Orchestrator 自动编排 → 完成！
```

---

## 九、技术要点总结

| 维度 | 策略 |
|------|------|
| **框架** | Spring Boot 3 + LangChain4j |
| **大模型** | DeepSeek V4-Pro API |
| **检索引擎** | ES 一站式：向量 KNN + BM25 全文 + 元数据过滤 + RRF 融合 |
| **文本切割** | 分层切割（标题→段落→句子）+ 重叠窗口 + 父子 Chunk |
| **多模态** | 图片用 VLM 生成描述、表格转 Markdown、代码保留原样，全部转为文本向量化 |
| **记忆** | 三层模型（工作/短期/长期），矢量检索 + 摘要 + 滑动窗口 |
| **评估** | LLM-as-Judge + 人工标注金标集 + 线上反馈闭环 |
| **多Agent** | 接口即扩展点，context.variables 传数据，ChatService 零改动 |
| **容器化** | Docker Compose 一键启动全部中间件 |
