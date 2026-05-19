# 文档摄入管道 —— 面试级工作流程复盘

> **目标受众**：面试前快速回顾整个链路的设计思路、每个方法的职责、边界条件和关键决策。
> 读完本文你能讲清楚：**"从用户上传一份 PDF 到能检索出 Chunk 的全过程"**。

---

## 一、一句话概括

> 文档摄入管道将任意格式的原始文件（PDF/Word/Markdown）转化为可检索的 Chunk 列表。
> 核心路径分 **4 步**：解析 → 内容提取 → 去重裁剪 → 父子切割。
> 采用**由父生成子**的切割策略，父子关系在切割时天然建立，无需事后匹配。

---

## 二、宏观流程图

```
原始文件 (.pdf / .docx / .md)
        │
        ▼
┌───────────────────────────────────────────────────────────────────┐
│                     IngestionService                              │
│                                                                   │
│  ingest(Document, Path)                                           │
│    │                                                              │
│    ├── 步骤 1: parseDocument()          → 原始文本 (String)        │
│    │                                                     │
│    ├── 步骤 2: extractTextBlocks()      → List<ContentBlock>      │
│    │                                            │(TEXT + 章节信息) │
│    ├── 步骤 3: extractAllContent()       → List<ContentBlock>     │
│    │           ├ 执行非 TEXT 提取器       (TEXT+TABLE+CODE+IMG,   │
│    │           └ splitByOverlaps() 去重   无区间重叠)              │
│    │                                                     │
│    └── 步骤 4: parentChildChunker.chunk()                         │
│                  │                                                │
│                  ├── buildParentChunks()     → 父 Chunk (2048字)  │
│                  │    + ParentWithBlocks 偏移映射                  │
│                  │                                                │
│                  └── splitParentIntoChildren() → 子 Chunk (512字) │
│                       ├ lookupContentType()   回查类型             │
│                       └ lookupSectionInfo()   回查章节             │
│                                                                   │
│  输出：List<Chunk>（子 Chunk + 父 Chunk，父子已链接）               │
└───────────────────────────────────────────────────────────────────┘
```

---

## 三、管线 4 步逐一剖析

### 第 1 步：文档解析 —— `parseDocument()`

**职责**：将不同格式的原始文件统一转为 Markdown 格式的纯文本字符串。

**实现**：职责链模式。Spring 自动注入 `List<DocumentParser>`（所有 `@Component` 实现类），遍历找到第一个 `supports(fileType)==true` 的解析器执行。

| Parser | 支持格式 | 核心库 | 关键处理 |
|--------|---------|--------|---------|
| `PdfDocumentParser` | `.pdf` | Apache PDFBox | `Loader.loadPDF()` + `PDFTextStripper`，按位置排序，启用附加格式 |
| `WordDocumentParser` | `.docx/.doc` | Apache POI | Heading 样式 → Markdown `#` 标题；表格 → Markdown `\|` 表格 |
| `MarkdownDocumentParser` | `.md/.txt` | `Files.readString()` | 直接读取，不转换 |

**面试要点**：
- 所有 Parser 输出统一为 Markdown 格式，下游不感知原始文件格式（策略模式）
- 新增文件格式只需加一个 `@Component implements DocumentParser`，零侵入

---

### 第 2 步：文本内容提取 —— `extractTextBlocks()`

**职责**：将原始 Markdown 文本按段落切割，识别标题层级，构建章节结构。

**核心实现**：`TextContentExtractor.extract()`
- 用 `\R\R+`（连续换行符）切段落
- 用 `^(#{1,6})\s+(.+)$` 正则识别标题行 → `isHeader=true`，记录 `sectionTitle` + `sectionLevel`
- 普通段落继承最近一个标题的 `sectionTitle`

**面试要点**：TEXT 提取器**必须第一个执行**，因为 Table/Code/ImageDescription 提取器需要章节信息来为自己的块打标签。

---

### 第 3 步：多模态提取 + 去重裁剪 —— `extractAllContent()`

#### 3.1 执行非 TEXT 提取器

在 TEXT 块基础上，依次执行：

| 提取器 | 识别方式 | 核心逻辑 |
|--------|---------|---------|
| `TableContentExtractor` | 逐行扫描全文 | 行以 `\|` 开头 → 暂存；第二行为分隔行 → 确认完整表格 |
| `CodeContentExtractor` | 正则 ` ```(\w*)\n([\s\S]*?)``` ` | 提取语言标签 + 代码内容 |
| `ImageDescriptionExtractor` | 关键词匹配 | `[图*]`、`[image*]`、`figure` 开头的段落 |

#### 3.2 去重裁剪 —— `splitByOverlaps()`（关键！）

**为什么需要去重**：TEXT 提取器按段落切分全文，代码块和表格文本也被切成了 TEXT 块。CODE/TABLE 提取器又独立识别了同一段内容。如果不去重，同一段文字会在 ContentBlock 列表中出现两次。

**算法**（区间减法）：
```
输入: 一个 TEXT 块 [0, 500)  +  一个 CODE 块 [200, 350)

输出:
  TEXT[0, 200)   ← 重叠前的部分保留
  CODE[200, 350) ← CODE 块直接使用（类型更具体）
  TEXT[350, 500) ← 重叠后的部分保留
```

每个字符最终只属于一个 ContentBlock，优先归属于更具体的类型（CODE > TABLE > TEXT）。

**面试要点**：去重算法的本质是**区间差集**运算。`createTextFragment()` 通过 `content.substring()` 从原块中截取非重叠片段。

---

### 第 4 步：父子 Chunk 切割 —— `ParentChildChunker.chunk()`

**设计核心：由父生成子。** 先生成粗粒度父 Chunk（~2048 字），再在每个父 Chunk 内部用滑动窗口拆分子 Chunk（~512 字 + 64 重叠）。切割时直接建立 `parentChunkId`，无需事后匹配。

#### 4.1 父 Chunk 构建 —— `buildParentChunks()`

**数据结构** `ParentWithBlocks`：
```
ParentWithBlocks {
    parent: Chunk              ← 父 Chunk 本身
    blocks: [ContentBlock...]  ← 该父 Chunk 由哪些块拼接而成
    blockRanges: [int[]...]    ← blocks[i] 在 parent.content 中的 [start, end) 偏移
}
```

`blockRanges` 是后续回查类型和章节的关键桥梁。

**拼接伪代码**：
```
buffer = ""           // 累积文本
currentBlocks = []    // 累积 ContentBlock
currentRanges = []    // 累积每个块的偏移范围

for each ContentBlock:
    // 条件 1：章节切换
    if block.sectionTitle != currentSection AND buffer 非空:
        打包 buffer 为父 Chunk  ← 切！
        重置 buffer/currentBlocks/currentRanges

    // 条件 2：大小超限
    if buffer.length + block.content.length > 2048 AND buffer 非空:
        打包 buffer 为父 Chunk  ← 切！
        重置 buffer/currentBlocks/currentRanges

    if buffer 非空: buffer += "\n\n"
    blockLocalStart = buffer.length  ← 记录 block 在 buffer 中的起始位置
    buffer += block.content
    currentBlocks.add(block)
    currentRanges.add([blockLocalStart, blockLocalStart + content.length])

if buffer 非空: 打包为最后一个父 Chunk
```

**两种切割条件的语义**：

| 条件 | 触发时机 | 为什么要切 |
|------|---------|-----------|
| 章节切换 | `sectionTitle` 变化 | 保证一个父 Chunk 不跨章节，语义上下文连贯 |
| 大小超限 | `buffer + block > 2048` | 控制父 Chunk 大小，避免后续送入 LLM 超 token |

**单块超限的边界情况**：
- 条件 2 有 `&& buffer.length() > 0` 守卫
- 当 buffer 为空且单个 ContentBlock 就超过 2048 字时，该块**原封不动**成为一个超大父 Chunk
- 这是设计妥协：ContentBlock 是不可分割的语义单元（一整段代码/一张表），不能截断

**切分不影响 ContentBlock 完整性**：
```
切前: buffer = [A(800), B(1000)] = 1800字
      新块 C(500) 到来 → 1800+500=2300 > 2048

切后: 父1 = A完整 + B完整 = 1800字
      buffer 重置，buffer += C完整  ← C 完整保留，只是成了下一个父的开头
```

#### 4.2 子 Chunk 切割 —— `splitParentIntoChildren()`

**滑动窗口参数**：
- 窗口大小：512 字
- 重叠：64 字
- 步进：512 - 64 = 448

**核心过程**：
```
for each parent:
    segments = splitWithOverlap(parent.content, 512, 64)

    segStart = 0
    for each segment:
        segEnd = segStart + segment.length()

        childType   = lookupContentType(segStart, segEnd, pwb)  // 回查类型
        sectionInfo = lookupSectionInfo(segStart, segEnd, pwb)  // 回查章节

        child = new Chunk(segment, childType, ...)
        child.setParentChunkId(parentId)  ← 直接建立父子链接！

        segStart += 448 // 步进到下一个窗口
```

#### 4.3 `lookupContentType` —— 类型回查

**问题**：子片段可能跨多个不同类型的 ContentBlock，应该标 TEXT 还是 TABLE？

**策略**：取与子片段重叠字符数最多的 ContentBlock 的类型。

```
blockRanges:
  [0] [  0,300) TEXT  → overlap with [200,712) = 100 字
  [1] [300,800) TABLE → overlap with [200,712) = 412 字 ← 最多！
结果：childType = TABLE
```

#### 4.4 `lookupSectionInfo` —— 章节回查

**问题**：子 Chunk 属于哪个文档章节？

**策略**：遍历组成块，找 `segStart` 之前的、最近的、`isHeader=true` 的块。

```
blockRanges:
  [0] [  0, 50) isHeader=true  "概述"      ← segStart=200 之前最近的标题 ✓
  [1] [ 50,300) isHeader=false "概述"      ← 不是标题 ✗
  [2] [300,450) isHeader=true  "核心特性"   ← 在 segStart=200 之后 ✗
结果：sectionTitle="概述", level=1
```

#### 4.5 父 Chunk 特征

```java
Chunk parent = new Chunk(id, docId, content, Chunk.ContentType.TEXT, -1);
//                                                        chunkIndex = -1  ↑
```

`chunkIndex = -1` 是一个**哨兵值**，到 Phase 5 向量化时：
- 子 Chunk（chunkIndex ≥ 0）→ 调 Embedding 生成 1024 维向量 → 存入 ES 用于 KNN 检索
- 父 Chunk（chunkIndex = -1）→ **跳过 Embedding**，只存文本 → 按 `_id` 精确查询即可

两种 Chunk 都存 ES，只是父 Chunk 不写入 `embedding` 字段。

---

## 四、核心数据结构速查

| 类 | 层级 | 核心字段 | 作用 |
|----|------|---------|------|
| `ContentType` | 枚举 | TEXT/TABLE/CODE/IMAGE_DESC | 标记内容类别 |
| `ContentBlock` | 中间产物 | type, content, startOffset, sectionTitle, metadata | 管道中语义最完整的单元 |
| `Chunk` | 最终产出 | id, documentId, parentChunkId, content, contentType, chunkIndex, embedding | 最小检索单元 |
| `ParentWithBlocks` | 内部结构 | parent, blocks, blockRanges | 父 Chunk + 组成块 + 偏移映射 |

---

## 五、检索时的父子联动

```
用户提问
  │
  ▼ KNN 语义检索（在子 Chunk 的 embedding 上搜索 ES）
  │
命中子 Chunk-5: "Q3 营收增长 30%...", parentChunkId = "abc123"
  │
  ▼ 按 _id 精确查询 ES
  │
拉取父 Chunk "abc123": [完整 2048 字上下文，含前后段落因果和数据背景]
  │
  ▼ 拼装 Prompt
  │
{ context: 父 Chunk 完整内容, question: 用户问题 }  →  送入 LLM
```

---

## 六、面试自检清单

| # | 问题 | 你的回答要点 |
|---|------|------------|
| 1 | 管道分几步？ | 4 步：解析→内容提取→去重→父子切割 |
| 2 | 为什么 Word 要转 Markdown？ | 统一格式，下游不感知原始文件类型（策略模式） |
| 3 | 为什么需要 ContentBlock？ | 防止硬切导致表格/代码截断——在语义边界上切 |
| 4 | 为什么 TEXT 提取器必须先执行？ | 其他提取器依赖它的章节标题信息来打标签 |
| 5 | 为什么要去重裁剪？ | TEXT 提取器和 CODE/TABLE 提取器的结果有重叠区间，需要做区间差集 |
| 6 | 父子切割流程是什么？ | ① ContentBlock → 父 Chunk（章节/大小边界切分） ② 父 Chunk 文本 → 子 Chunk（512窗口+64重叠） ③ 切割时直接设 parentChunkId |
| 7 | 父 Chunk 拼接的两个触发条件？ | 章节切换（保证语义连贯）、大小超限（控制 ≤2048 字） |
| 8 | 单块超过 2048 字怎么办？ | 原封不动保留（设计妥协——不可截断语义单元），`buffer.length()>0` 守卫阻止条件触发 |
| 9 | childType 怎么确定的？ | `lookupContentType`——取与子片段重叠字符数最多的 ContentBlock 的类型 |
| 10 | sectionTitle 怎么确定的？ | `lookupSectionInfo`——取 segStart 之前最近的标题块 |
| 11 | 父子 Chunk 的存储策略？ | 都存 ES；子存向量用于 KNN 检索，父不存向量按 ID 查询；通过 chunkIndex=-1 区分 |
| 12 | 为什么父 Chunk 也要存 ES？ | 查询链路短（不跨系统）、生命周期同步、字段兼容 |
| 13 | `splitWithOverlap` 步进怎么算？ | `步进 = 窗口 - 重叠 = 512 - 64 = 448` |
| 14 | 新增文件格式怎么做？ | 加一个 `@Component implements DocumentParser`——Spring 自动注入，零改动 |
| 15 | IngestionService 依赖哪些组件？ | `List<DocumentParser>` + `List<ContentExtractor>` + `ParentChildChunker`，均 Spring 自动注入 |

---

## 七、一句话面试压缩版

> "文档摄入管道将 PDF/Word/Markdown 统一转为 Markdown 文本，然后分步提取文本段落（带章节层级）、表格、代码和图片描述，做区间去重避免内容重复，最后以**由父生成子**的方式切割：先用 ContentBlock 拼接 2048 字的父 Chunk（章节切换或大小超限时切分），再在每个父 Chunk 内部用 512 字滑动窗口 + 64 字重叠拆分子 Chunk，切割时直接将 parentChunkId 设好。父子都存 ES，子参与向量检索，父按 ID 查出来作为 LLM 的上下文窗口。"
