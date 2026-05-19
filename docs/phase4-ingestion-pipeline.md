# Phase 4：文档摄入管道 — 架构说明

## 一、类关系总图

```
┌──────────────────────────────────────────────────────────────────────────┐
│                            IngestionService                              │
│                          （管道总控 / 唯一入口）                           │
│                                                                          │
│  ingest(Document, Path) → List<Chunk>                                   │
│                                                                          │
│  持有：List<DocumentParser>（Spring 自动注入所有 @Component 实现）         │
│  持有：List<ContentExtractor>（Spring 自动注入所有 @Component 实现）       │
│  持有：ParentChildChunker                                               │
│          │                                                               │
│          ├── buildParentChunks()    → 拼接 ContentBlock → 父 Chunk       │
│          └── splitParentIntoChildren() → 父 Chunk 内滑动窗口 → 子 Chunk   │
│              └── HierarchicalChunker.splitWithOverlap()                  │
└──────┬──────────────────┬────────────────────────────────┬──────────────┘
       │                  │                                │
       ▼                  ▼                                ▼
┌──────────────┐  ┌──────────────────┐  ┌──────────────────────────────────┐
│ 阶段1: 解析   │  │  阶段2: 内容提取  │  │        阶段3: 切割               │
│              │  │                  │  │                                  │
│ DocumentParser│  │ ContentExtractor │  │ 自顶向下：先父后子                 │
│   <interface> │  │   <interface>    │  │                                  │
│       │       │  │       │          │  │ 1. ContentBlock → 父 Chunk        │
│  ┌────┼────┐  │  │  ┌────┼────┐     │  │    (章节/大小边界切分)            │
│  │    │    │  │  │  │    │    │     │  │ 2. 父 Chunk → 子 Chunk            │
│  ▼    ▼    ▼  │  │  ▼    ▼    ▼     │  │    (512字滑动窗口+64重叠)         │
│ Pdf Word  Md  │  │ Text Table Code  │  │ 3. 偏移映射回查 → 继承类型+章节    │
│              │  │    ImageDesc      │  │ 4. 切割时直接设 parentChunkId     │
│              │  │                  │  │   (无需事后 content.contains 匹配) │
└──────────────┘  │ + 去重裁剪：      │  └──────────────────────────────────┘
                  │   CODE/TABLE块    │
                  │   替换TEXT块重叠   │
                  │   区间            │
                  └──────────────────┘
```

## 二、数据模型关系

```
                              ┌──────────────────┐
                              │   ContentType    │
                              │     (enum)       │
                              ├──────────────────┤
                              │ TEXT             │
                              │ IMAGE_DESCRIPTION│
                              │ TABLE            │
                              │ CODE             │
                              └────────┬─────────┘
                                       │ 被引用
              ┌────────────────────────┼────────────────────────┐
              ▼                        ▼                        ▼
┌──────────────────────┐  ┌──────────────────────┐  ┌──────────────────────┐
│    ContentBlock      │  │    Chunk.ContentType  │  │   Chunk              │
│    (中间产物)         │  │    (core包 enum)     │  │   (最终产出)          │
├──────────────────────┤  ├──────────────────────┤  ├──────────────────────┤
│ type: ContentType    │  │ TEXT                 │  │ id: String           │
│ content: String      │  │ IMAGE_DESCRIPTION    │  │ documentId: String   │
│ startOffset: int     │  │ TABLE                │  │ parentChunkId: String│
│ endOffset: int       │  │ CODE                 │  │ contentType: enum    │
│ sectionTitle: String │  └──────────────────────┘  │ content: String      │
│ sectionLevel: int    │                            │ chunkIndex: int      │
│ pageNumber: int      │                            │ startOffset: int     │
│ metadata: Map        │                            │ endOffset: int       │
└──────────────────────┘                            │ embedding: float[]   │
                  │                                 │ metadata: Map        │
                  │  多个 ContentBlock              └──────────────────────┘
                  │  先拼接为父 Chunk                          │
                  │  再在父 Chunk 内切子 Chunk                 │
                  └──────────────────────────────────────────────┘
```

## 三、完整数据流（自顶向下版）

```
输入：原始文件 (.pdf / .docx / .md) + Document 元信息对象
│
├─ 阶段 1 ── 解析（PARSE）
│  ┌────────────────────────────────────────────────────────────────┐
│  │ IngestionService.parseDocument()                               │
│  │                                                                │
│  │ 1. 从 Document.getFileType() 或文件路径推测扩展名                │
│  │ 2. 遍历 List<DocumentParser>，调用 supports(fileType) 匹配     │
│  │    ├── PdfDocumentParser    → PDFBox Loader + PDFTextStripper  │
│  │    ├── WordDocumentParser   → Apache POI，Heading→MD标题       │
│  │    └── MarkdownDocumentParser → Files.readString() 直接读      │
│  │ 3. parser.parse(filePath) → 返回 Markdown 格式的原始文本        │
│  └────────────────────────────────────────────────────────────────┘
│                │
│                ▼  原始文本 (String, 如 621 字符)
│
├─ 阶段 2 ── 内容提取 + 去重（EXTRACT）
│  ┌────────────────────────────────────────────────────────────────┐
│  │ IngestionService.extractTextBlocks()                           │
│  │   → TextContentExtractor.extract(fullText, [])                 │
│  │                                                                │
│  │   识别 Markdown # 标题层级（sectionLevel 1~6）                  │
│  │   按连续空行切段落，每个段落标记 isHeader=true/false            │
│  │   正文段落继承最近标题的 sectionTitle                           │
│  │   输出：List<ContentBlock> (type=TEXT, 含章节信息)              │
│  └────────────────────────────────────────────────────────────────┘
│                │
│                ▼  textBlocks (章节结构已建立)
│
│  ┌────────────────────────────────────────────────────────────────┐
│  │ IngestionService.extractAllContent(rawText, textBlocks)       │
│  │                                                                │
│  │ 第 1 步：执行非 TEXT 提取器                                    │
│  │   ├── TableContentExtractor   → 逐行扫描识别 Markdown 表格     │
│  │   ├── CodeContentExtractor    → 正则匹配 ``` 代码块            │
│  │   └── ImageDescriptionExtractor → 识别 [图*]/[image*] 标记    │
│  │                                                                │
│  │ 第 2 步：去重裁剪（splitByOverlaps）                           │
│  │   CODE/TABLE 块与 TEXT 块区间重叠 → 从 TEXT 块中裁掉重叠区间    │
│  │   例：TEXT块覆盖[0,500)，CODE块覆盖[200,350)                   │
│  │       → TEXT块被切成 TEXT[0,200) + CODE[200,350) + TEXT[350,500)│
│  │                                                                │
│  │ 所有块按 startOffset 升序排序                                   │
│  │ 输出：List<ContentBlock> (无区间重叠，类型互斥)                  │
│  └────────────────────────────────────────────────────────────────┘
│                │
│                ▼  allBlocks (已排序+去重，共约 18 个 ContentBlock)
│
├─ 阶段 3 ── 切割（CHUNK）—— 自顶向下：先父后子
│  ┌────────────────────────────────────────────────────────────────┐
│  │ ParentChildChunker.chunk(documentId, allBlocks)                 │
│  │                                                                │
│  │ == 第 1 步：生成父 Chunk（buildParentChunks）==                │
│  │   遍历 ContentBlock，拼接为父 Chunk：                           │
│  │   ├── 章节切换（sectionTitle 变化）→ 切出一个父 Chunk            │
│  │   ├── 大小超限（累计 > 2048 字）→ 切出一个父 Chunk              │
│  │   └── 记录每个父 Chunk 由哪些 ContentBlock 组成（ParentWithBlocks）│
│  │       同时记录每个块在父文本中的局部偏移范围（blockRanges）      │
│  │   父 Chunk：isParent=true, chunkIndex=-1, contentType=TEXT     │
│  │                                                                │
│  │ == 第 2 步：父 Chunk 内部拆分子 Chunk（splitParentIntoChildren）==│
│  │   对每个父 Chunk 的文本执行 HierarchicalChunker.splitWithOverlap│
│  │   窗口=512, 重叠=64, 步进=448                                  │
│  │                                                                │
│  │   每个子片段通过偏移映射（lookupContentType/lookupSectionInfo）：│
│  │   ├── 回查覆盖最多的 ContentBlock → 继承 contentType（CODE/TABLE/TEXT）│
│  │   └── 回查最近的标题块 → 继承 sectionTitle + sectionLevel      │
│  │                                                                │
│  │   切割时直接设置 child.setParentChunkId(parentId)！             │
│  │   无需事后 content.contains() 模糊匹配                          │
│  └────────────────────────────────────────────────────────────────┘
│                │
│                ▼
输出：List<Chunk>（约 8 父 Chunk + 8 子 Chunk = 16 个 Chunk）
     每个子 Chunk 通过 parentChunkId 指向对应父 Chunk
     父子关系在切割时天然确定，无需事后匹配
```

## 四、各组件职责速查

### 4.1 接口

| 接口 | 文件 | 职责 |
|------|------|------|
| `DocumentParser` | `DocumentParser.java` | 定义文件解析契约：`supports(fileType)` + `parse(filePath)` |
| `ContentExtractor` | `ContentExtractor.java` | 定义内容提取契约：`supportedType()` + `extract(fullText, textBlocks)` |

### 4.2 解析器实现（阶段 1）

| 类 | 文件 | 输入 | 输出 | 支持类型 |
|----|------|------|------|----------|
| `PdfDocumentParser` | `PdfDocumentParser.java` | .pdf 文件路径 | 纯文本 | pdf |
| `WordDocumentParser` | `WordDocumentParser.java` | .docx/.doc 文件路径 | Markdown 格式纯文本 | docx, doc |
| `MarkdownDocumentParser` | `MarkdownDocumentParser.java` | .md/.txt 文件路径 | 原样纯文本 | md, markdown, txt, text |

### 4.3 提取器实现（阶段 2）

| 类 | 文件 | 识别目标 | 依赖 |
|----|------|----------|------|
| `TextContentExtractor` | `TextContentExtractor.java` | Markdown 标题层级 + 段落分割 | 无（总是第一个执行） |
| `TableContentExtractor` | `TableContentExtractor.java` | Markdown 表格（\|+\|-+\|+数据行） | TextContentExtractor 的章节信息 |
| `CodeContentExtractor` | `CodeContentExtractor.java` | Markdown 围栏代码块（\`\`\`） | TextContentExtractor 的章节信息 |
| `ImageDescriptionExtractor` | `ImageDescriptionExtractor.java` | [图*] / [image*] 标记的段落 | TextContentExtractor 的章节信息 |

### 4.4 切割器（阶段 3）

| 类 | 文件 | 职责 |
|----|------|------|
| `ParentChildChunker` | `ParentChildChunker.java` | **阶段 3 唯一入口**。自顶向下：先拼接 ContentBlock 为父 Chunk（2048 字上限 + 章节边界），再在每个父 Chunk 内用滑动窗口拆分子 Chunk（512 字 + 64 重叠），通过偏移映射从原始 ContentBlock 回查类型和章节信息；切割时直接建立 parentChunkId |
| `HierarchicalChunker` | `HierarchicalChunker.java` | 仅被 ParentChildChunker 内部调用 `splitWithOverlap()`，不直接被 IngestionService 引用 |

### 4.5 管道总控

| 类 | 文件 | 职责 |
|----|------|------|
| `IngestionService` | `IngestionService.java` | 串联解析→提取→去重→切割四步；管理 Document 状态机；提供同步/异步两个入口。只依赖 ParentChildChunker（不再直接引用 HierarchicalChunker） |

### 4.6 数据模型

| 类 | 文件 | 职责 |
|----|------|------|
| `ContentType` | `ContentType.java` | 枚举：TEXT / IMAGE_DESCRIPTION / TABLE / CODE |
| `ContentBlock` | `ContentBlock.java` | 管道中间产物：带类型的语义块，含章节元数据 |
| `Document` (core) | `core/Document.java` | 管道输入：原始文档模型，含 DocumentStatus 状态机 |
| `Chunk` (core) | `core/Chunk.java` | 管道最终产出：可检索的最小单元，含 parentChunkId |
| `ParentWithBlocks` | `ParentChildChunker.java`（内部类） | 父 Chunk 与其组成 ContentBlock 列表 + 偏移范围的绑定结构 |

## 五、关键设计决策

### 5.1 为什么不能跳过 ContentBlock 直接切 Chunk？

因为如果直接对原始文本硬切，表格和代码块可能被拦腰截断。
ContentBlock 层的作用是在**语义边界**上切割——一个完整的表格/代码块作为不可拆的整体，
短于 512 字则整个作为一个 Chunk，只有超长的才做滑动窗口切割。

### 5.2 为什么 Word 要转成 Markdown 格式？

统一化。Pdf/Word/Markdown 三种格式的文档解析后输出都是 Markdown 格式的纯文本，
下游的 TextContentExtractor、TableContentExtractor 等全部按 Markdown 语法解析，
不感知原始文件格式。

### 5.3 为什么 TEXT 提取器必须第一个执行？

Table/Code/ImageDescription 提取器需要知道"当前表格/代码块属于哪个章节"。
这个信息只能从 TEXT 提取器产出的标题块中获得。
因此 TEXT 提取器先构建出完整的章节层级树，其他提取器再按位置查表获取章节归属。

### 5.4 为什么要做 ContentBlock 去重裁剪？

TEXT 提取器按段落切分全文，会把代码和表格文本也当普通段落切进去。
CODE/TABLE 提取器又独立识别了同一段内容。如果不去重，拼接父 Chunk 时同一段文字会出现两次。

`splitByOverlaps()` 的解决方式：以 CODE/TABLE 块的区间为准，把 TEXT 块与之重叠的部分裁掉。
最终每个字符只属于一个 ContentBlock，且优先归属到更具体的类型（CODE > TABLE > TEXT）。

### 5.5 为什么先生成父 Chunk 再拆分子 Chunk？

旧版流程是"先切子 Chunk → 拼接子 Chunk 内容成父 Chunk → content.contains() 模糊匹配父子关系"。
新版改为"先拼父 Chunk → 在父 Chunk 内切子 Chunk → 切割时直接记录 parentChunkId"。

优势：
- 父 Chunk 直接从 ContentBlock 拼接，而非从子 Chunk 二次拼接，结构更清晰
- 亲子关系在切割时天然确定，不需要事后 `content.contains()` 模糊匹配（该匹配在子 Chunk 内容较短或有特殊字符时可能失败）
- 父 Chunk 的组成信息记录在 `ParentWithBlocks` 中，子 Chunk 通过偏移映射回查 ContentBlock 来继承类型和章节信息

### 5.6 Spring 自动装配机制

`IngestionService` 的构造器参数 `List<DocumentParser>` 和 `List<ContentExtractor>` 
被 Spring 自动注入所有 `@Component` 实现类：

```java
public IngestionService(List<DocumentParser> parsers,      // → 自动包含 Pdf/Word/Md
                        List<ContentExtractor> extractors,  // → 自动包含 Text/Table/Code/ImageDesc
                        ParentChildChunker parentChildChunker) { ... }
```

新增文件格式只需加一个 `@Component implements DocumentParser`，无需改动 IngestionService。
`HierarchicalChunker` 不再直接被 `IngestionService` 引用，只作为 `ParentChildChunker` 的内部依赖。

### 5.7 重叠窗口只在子 Chunk，父 Chunk 无重叠

- **子 Chunk**（512 字，64 重叠）：参与向量检索，重叠防止关键词落在边界被切分
- **父 Chunk**（2048 字，无重叠）：不参与检索，仅作为命中子 Chunk 的上下文扩展窗口，送入 LLM 时拼装 context。由于子 Chunk 之间已有重叠覆盖，父 Chunk 不需要额外重叠

## 六、文件清单

```
src/main/java/com/agent/ingestion/
├── ContentType.java               # 枚举：4 种内容类型
├── ContentBlock.java              # 中间产物：语义块模型
├── DocumentParser.java            # 接口：文档解析器
├── PdfDocumentParser.java         # 实现：PDFBox 解析 PDF
├── WordDocumentParser.java        # 实现：POI 解析 Word → Markdown
├── MarkdownDocumentParser.java    # 实现：直接读取 MD/TXT
├── ContentExtractor.java          # 接口：内容提取器
├── TextContentExtractor.java      # 实现：提取段落 + 标题层级
├── TableContentExtractor.java     # 实现：提取 Markdown 表格
├── CodeContentExtractor.java      # 实现：提取代码块
├── ImageDescriptionExtractor.java # 实现：提取图片描述段落
├── HierarchicalChunker.java       # 子 Chunk 滑动窗口切割（仅被 ParentChildChunker 内部调用）
├── ParentChildChunker.java        # 父子 Chunk 切割器（先父后子，阶段3唯一入口）
└── IngestionService.java          # 管道总控（解析→提取→去重→切割）
```

## 七、扩展点

| 扩展需求 | 做法 |
|----------|------|
| 新增文件格式（如 .pptx） | 新建 `@Component implements DocumentParser`，无需改动其他代码 |
| 新增内容类型（如视频字幕） | 新建 `@Component implements ContentExtractor`，添加对应 ContentType 枚举值 |
| 调整 Chunk 大小 | 修改 `ParentChildChunker` 中的 `CHILD_CHUNK_SIZE`（512）和 `PARENT_CHUNK_SIZE`（2048）常量 |
| 调整重叠窗口 | 修改 `ParentChildChunker` 中的 `OVERLAP`（64）常量 |
| 替换为语义切割 | 新建 `SemanticChunker`，用 Embedding 相似度断裂点替代固定窗口切割 |

---

# 附录：关键方法逻辑详解

> 以下逐方法剖析管道中最核心的 4 个逻辑链路，包含边界情况和决策细节，
> 方便后续代码复盘。

## A. 父 Chunk 拼接 —— `buildParentChunks()`

### A.1 数据结构

每个父 Chunk 不是孤立的 Chunk 对象，而是被包装在 `ParentWithBlocks` 结构中：

```
ParentWithBlocks {
    parent: Chunk           ← 父 Chunk 本身
    blocks: List<ContentBlock>  ← 该父 Chunk 由哪些 ContentBlock 拼接而成
    blockRanges: List<int[]>    ← blocks[i] 在 parent.content 中的 [start, end) 偏移
}
```

`blockRanges` 是后续子 Chunk 切割时"回查类型和章节信息"的关键桥梁。

### A.2 遍历与拼接伪代码

```
buffer = ""           // 累积文本
currentBlocks = []    // 累积 ContentBlock
currentRanges = []    // 累积偏移范围

for each ContentBlock in allBlocks:

    // ===== 检查点 1：章节切换 =====
    if block.sectionTitle != currentSection AND buffer 非空:
        打包 buffer 为一个父 Chunk  ← 切！
        buffer/currentBlocks/currentRanges 重置

    // ===== 检查点 2：大小超限 =====
    if buffer.length + block.content.length > 2048 AND buffer 非空:
        打包 buffer 为一个父 Chunk  ← 切！
        buffer/currentBlocks/currentRanges 重置

    // ===== 追加内容 =====
    if buffer 非空: buffer += "\n\n"   // 块间分隔
    blockLocalStart = buffer.length     // 记录 block 在 buffer 中的起始位置
    buffer += block.content
    currentBlocks.add(block)
    currentRanges.add([blockLocalStart, blockLocalStart + block.content.length])

// ===== 末尾处理 =====
if buffer 非空: 打包 buffer 为最后一个父 Chunk
```

### A.3 两种切割条件的语义差异

| 条件 | 触发时机 | 语义 |
|------|---------|------|
| 章节切换 | `sectionTitle` 变化 | 保证一个父 Chunk 内不跨章节，语义上下文连贯 |
| 大小超限 | `buffer + block > 2048` | 控制父 Chunk 大小，避免送入 LLM 时超 token |

### A.4 单块超限的边界情况

```java
if (buffer.length() + content.length() > PARENT_CHUNK_SIZE && buffer.length() > 0)
```

注意 `&& buffer.length() > 0`。当 buffer 为空（即当前块是父 Chunk 的第一个块）且该块本身就 > 2048 字时，条件不触发，该块将**原封不动**成为一个超过 2048 字的父 Chunk。

这是设计妥协：ContentBlock 是不可分割的语义单元（一整段代码、一整张表格），不能从中截断。如果硬截，送入 LLM 的代码/表格不完整，危害更大。

### A.5 切分不影响 ContentBlock 完整性

当条件触发时，新的 ContentBlock 不会被截断——它只是从"上一个父 Chunk 的末尾"变成了"下一个父 Chunk 的开头"：

```
切分前：  buffer = [A(800字), B(1000字)] = 1800 字
         block C(500字) 到来 → 1800+500=2300 > 2048

切分后：  父 Chunk 1 = A完整 + B完整 = 1800 字
         buffer 重置为空
         buffer += C完整                              ← C 完整保留
         父 Chunk 2 = C完整 + 后续块...
```

---

## B. 子 Chunk 切割 —— `splitParentIntoChildren()`

### B.1 滑动窗口参数

```
窗口大小 (CHILD_CHUNK_SIZE) = 512
重叠量   (OVERLAP)          = 64
步进                        = 512 - 64 = 448
```

一个 1000 字的父 Chunk 被切为 3 段：

```
segStart=0      → [   0, 512)    ← 窗口 1
segStart=448    → [ 448, 960)    ← 窗口 2，与窗口 1 重叠 [448,512) 即 64 字
segStart=896    → [ 896,1000)    ← 窗口 3，与窗口 2 重叠 [896,960)
```

### B.2 起始偏移计算（core 逻辑）

```java
int segStart = 0;  // 当前窗口在父 Chunk 文本中的起始位置（局部坐标）

for each segment:
    segEnd = segStart + segment.length()

    // 类型回查：segStart ~ segEnd 落在哪个 ContentBlock 区间里
    childType = lookupContentType(segStart, segEnd, pwb)

    // 章节回查：segStart 之前最近的标题块
    sectionInfo = lookupSectionInfo(segStart, segEnd, pwb)

    // 全局偏移 = 父 Chunk 全局起始 + 局部偏移
    globalStart = parentStartOffset + segStart
    globalEnd   = parentStartOffset + segEnd

    child.setParentChunkId(parentId)  ← 切割时直接建立父子链接！

    segStart += 448  // 步进到下一个窗口
```

### B.3 `lookupContentType` — 类型回查策略

对父 Chunk 的组成块列表，计算每个块的区间 [range[0], range[1]) 与子片段 [segStart, segEnd) 的**重叠字符数**，取重叠最多的 ContentBlock 的类型：

```
blockRanges:
  [0]  [  0, 300)  TEXT    ← 与 [200, 712) 重叠 100 字
  [1]  [300, 800)  TABLE   ← 与 [200, 712) 重叠 412 字 ← 最多！
  [2]  [800,1200)  TEXT    ← 与 [200, 712) 重叠 0 字

结果：childType = TABLE
```

### B.4 `lookupSectionInfo` — 章节回查策略

遍历组成块，找到 `segStart` 之前最近的标题块（isHeader=true）：

```
blockRanges:
  [0]  [  0,  50)  isHeader=true   sectionTitle="概述"     ← segStart=200 之前最近标题
  [1]  [ 50, 300)  isHeader=false  sectionTitle="概述"
  ...
  [k]  [400, 450)  isHeader=true   sectionTitle="核心特性" ← 在 segStart=200 之后，不选

结果：sectionTitle="概述", sectionLevel=1
```

子 Chunk 的章节信息继承最近一个标题，而非它所在的 ContentBlock——因为这个 ContentBlock 可能就是无标题的正文段落。

---

## C. ContentBlock 去重裁剪 —— `splitByOverlaps()`

### C.1 为什么会有重叠

TEXT 提取器按段落切分全文，代码块和表格文本也被切成 TEXT 块。CODE/TABLE 提取器又独立识别了同一段文字。如果不去重，构建父 Chunk 时同一段内容会出现两次。

### C.2 去重算法

输入：一个 TEXT 块 + 所有非 TEXT 块（CODE/TABLE）

```
currentStart = textBlock.startOffset
blockEnd     = textBlock.endOffset

for each nonTextBlock (按 startOffset 排序):
    if nt 在 [currentStart, blockEnd) 之外: 跳过

    overlapStart = max(currentStart, nt.start)
    overlapEnd   = min(blockEnd, nt.end)

    // 重叠区间之前的 TEXT 片段保留
    if overlapStart > currentStart:
        产出 TEXT[currentStart, overlapStart)

    // 跳过重叠区间
    currentStart = overlapEnd

// 末尾剩余
if currentStart < blockEnd:
    产出 TEXT[currentStart, blockEnd)
```

### C.3 图示

```
TEXT 块：        [0 一一一一一一一一一一一一一一一一 500)
CODE 块：                    [200 一一一一 350)

去重后：
  TEXT[0,200)  +  CODE[200,350)  +  TEXT[350,500)
```

类型互斥、区间不重叠，排序后即为最终的 ContentBlock 列表。

---

## D. `splitWithOverlap` — 纯文本滑动切割

HierarchicalChunker 唯一对外的方法，输入文本字符串，输出切割后的字符串列表：

```java
List<String> splitWithOverlap(String text, int size, int overlap) {
    if (text.length() <= size) return [text];   // 不切

    start = 0
    while start < text.length:
        end = min(start + size, text.length)
        result.add(text[start, end))
        start += (size - overlap)   // 步进 = 窗口 - 重叠

    return result
}
```

这是一个**纯函数**，不依赖任何外部状态，不感知 Chunk/ContentBlock/Document——只做字符串切割。

---

## E. 管道路由 —— `parseDocument()`

解析器调度使用最简单的"职责链"模式：

```java
for (DocumentParser parser : parsers) {
    if (parser.supports(fileType)) {
        return parser.parse(filePath);   // 第一个匹配的执行
    }
}
throw IllegalArgumentException   // 没有 Parser 支持该类型
```

Spring 自动注入 `List<DocumentParser>` 包含所有 `@Component` 实现类（Pdf/Word/Md），新增格式只需加一个实现类。

---

## F. 完整案例走查

输入 test_doc.md（621 字符），管道各阶段产出：

```
▲ 解析
  → 621 字符 Markdown 文本

▲ 内容提取
  → ~18 个 TEXT 块（标题 + 正文）
  → 1 个 CODE 块（\`\`\`java...\`\`\`）
  → 1 个 TABLE 块（| 策略 | 权重 | 说明 |...）
  → 去重：CODE/TABLE 区间从 TEXT 块中裁掉
  → 排序后约 18 个 ContentBlock

▲ 父 Chunk 构建
  → 约 8 个父 Chunk（每个 ≤ 2048 字，不跨章节）
  → 每个携带 ParentWithBlocks 偏移映射

▲ 子 Chunk 切割
  → 每个父 Chunk 内 512 字窗口 + 64 重叠
  → 约 8 个子 Chunk
  → 每个子 Chunk 通过 lookupContentType 继承类型
  → 每个子 Chunk 通过 lookupSectionInfo 继承章节
  → 直接设置 parentChunkId

▲ 最终输出：8 父 + 8 子 = 16 个 Chunk
```

---

## G. 父子 Chunk 的存储策略差异

### G.1 `chunkIndex = -1` 标记机制

父 Chunk 的 `chunkIndex` 字段设为 `-1`，这是一个**哨兵值**，表示该 Chunk 不参与向量化：

```java
// buildParentWithBlocks() 中构造父 Chunk
Chunk parent = new Chunk(
        UUID.randomUUID().toString(),
        documentId,
        content,
        Chunk.ContentType.TEXT,
        -1   // ← 哨兵值：标记父 Chunk，跳过 Embedding
);
```

子 Chunk 的 `chunkIndex` 则是正常的递增序号（0, 1, 2, ...）。

### G.2 Phase 5 向量化阶段的判断逻辑

到 Phase 5 时，向量化服务的处理逻辑：

```
for each Chunk in chunks:
    if chunk.getChunkIndex() == -1:
        跳过 Embedding 调用       // 父 Chunk 不生成向量
    else:
        embedding = embed(chunk.content)   // 子 Chunk → 1024 维向量
        chunk.setEmbedding(embedding)
```

### G.3 ES 中的存储形态

两者**都要存入 ES**，只是字段不同：

| Chunk 类型 | chunkIndex | embedding 字段（1024 维） | content 字段 | ES 查询方式 |
|:---|:---:|:---:|:---:|---|
| 子 Chunk | 0, 1, 2, ... | ✅ 填充 | ✅ 填充 | KNN 语义检索 |
| 父 Chunk | **-1** | ❌ null / 不写入 | ✅ 填充 | 按 `_id` 精确查询 |

### G.4 为什么父 Chunk 也要存 ES

父 Chunk 不参与向量检索，但**必须在 ES 中**——否则检索链路跑不通：

```
用户输入 "Q3 营收情况"
    │
    ▼ KNN 语义检索（在子 Chunk 的 embedding 上搜索）
    │
命中子 Chunk-5: "Q3 营收增长 30%...", parentChunkId = "abc123"
    │
    ▼ 按 _id 精确查询（从 ES 中拉取父 Chunk）
    │
父 Chunk "abc123": [完整 2048 字上下文，含前后因果和数据背景]
    │
    ▼ 拼装 Prompt
    │
送入 LLM：{context: 父 Chunk 完整内容, question: 用户问题}
```

### G.5 为什么不用单独的表/存储

统一存 ES 有几个好处：
- **查询路径短**：子 Chunk 在 ES 查 → `parentChunkId` → 同一 ES 查父 Chunk，不跨系统
- **生命周期同步**：删文档时，子/父 Chunk 一次 `deleteByQuery` 全部清理
- **字段兼容**：子/父 Chunk 都是同一个 `Chunk` 模型，只是 `embedding` 字段为空

---

## H. `lookupContentType` 和 `lookupSectionInfo` 的必要性

> 子 Chunk 命中后直接按 `parentChunkId` 拉父 Chunk 送入 LLM 就行了，
> 为什么还要在子 Chunk 上打 `contentType` 和 `sectionTitle` 标签？

### H.1 核心链路不依赖这些标签

"子命中 → 拉父 → 送 LLM" 这条核心检索链路**不需要** `contentType` 和 `sectionTitle`。
父 Chunk 的完整文本已经包含了所有上下文，LLM 不需要子 Chunk 的元数据就能生成答案。
这两个标签不决定链路能否跑通——它们是用来**让检索更准、展示更好**的。

### H.2 `lookupContentType` 的收益：检索过滤 + 加权

到 Phase 6 检索时，类型标签用来微调召回策略：

```
场景 1 — 类型过滤：
  用户问："这段代码里用了什么设计模式？"
  → ES 查询时加 filter: { contentType: "CODE" }
  → 只在代码块里搜，不被大段英文注释干扰

场景 2 — 类型加权：
  用户问："表格里提到了哪些策略？"
  → CODE 类型降权，TABLE 类型升权
  → 表格相关的 Chunk 排在结果前面
```

没有类型标签，所有 Chunk 一视同仁，代码块可能因为英文单词多反而被 BM25 淹没。

### H.3 `lookupSectionInfo` 的收益：来源溯源

检索结果展示时告诉用户"这段内容来自文档的哪个章节"：

```
用户问："混合检索策略是什么"
命中子 Chunk: "采用混合检索策略，结合 BM25 全文检索和 KNN 向量检索"
    ↓
展示给用户：
    📄 来自《知识库问答系统设计文档》
    📑 章节：## 检索系统              ← sectionTitle
    📝 "采用混合检索策略，结合 BM25..."
```

没有章节标签，用户看到一堆匹配片段，但不知道这些片段在文档中的上下文位置。

### H.4 判断逻辑对比

| 方法 | 解决什么问题 | 策略 | 是否影响核心链路 |
|------|------------|------|:---:|
| `lookupContentType` | 子 Chunk 是 TEXT / TABLE / CODE / IMAGE_DESC？ | 取与子片段重叠字符数最多的 ContentBlock 的类型 | ❌ 不影响 |
| `lookupSectionInfo` | 子 Chunk 属于哪个文档章节？ | 取 segStart 之前最近的一个标题块 | ❌ 不影响 |

两者都只影响**检索阶段的排序和展示**，不影响"子命中 → 拉父 → 送 LLM"这条主链路。


