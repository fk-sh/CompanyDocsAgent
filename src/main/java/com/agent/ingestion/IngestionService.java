package com.agent.ingestion;

import com.agent.core.Chunk;
import com.agent.core.Document;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 文档摄入服务，文档摄入管道的总控制器。
 * <p>
 * 串联解析 → 内容提取 → 切割三步，将一份原始文档转化为可检索的 Chunk 列表。
 * 是 Phase 4 的统一入口，后续 Phase 5 会在此基础上接入向量化和 ES 存储。
 * <p>
 * 管道流程（{@link #ingest(Document, Path, Consumer)}）：
 * <pre>
 *   Document(UPLOADED)
 *     │
 *     ├─(1) PARSING  → parseDocument()
 *     │     遍历所有 DocumentParser，按 fileType 匹配并解析为原始文本
 *     │
 *     ├─(2) CHUNKING  → extractTextBlocks() → extractAllContent() → parentChildChunker.chunk()
 *     │     先提取 TEXT 块（获取章节结构），再提取 Table/Code/ImageDescription 块，
 *     │     合并排序，最后执行父子切割
 *     │
 *     ▼
 *   List&lt;Chunk&gt;（子 Chunk ← parentChunkId → 父 Chunk）
 * </pre>
 * <p>
 * Spring 自动装配：构造器中 {@code List<DocumentParser>} 和 {@code List<ContentExtractor>}
 * 会自动收集所有 {@code @Component} 实现类，新增文件格式或内容类型无需改动此服务。
 */
@Slf4j
@Service
public class IngestionService {

    /** 所有 DocumentParser 实现（Pdf/Word/Markdown），Spring 自动注入 */
    private final List<DocumentParser> parsers;
    /** 所有 ContentExtractor 实现（Text/Table/Code/ImageDescription），Spring 自动注入 */
    private final List<ContentExtractor> extractors;
    private final ParentChildChunker parentChildChunker;

    public IngestionService(List<DocumentParser> parsers,
                            List<ContentExtractor> extractors,
                            ParentChildChunker parentChildChunker) {
        this.parsers = parsers;
        this.extractors = extractors;
        this.parentChildChunker = parentChildChunker;
    }

    /**
     * 同步摄入文档（无状态回调）。
     *
     * @param document 文档模型（含 ID、文件名、类型等元信息）
     * @param filePath 文件路径
     * @return 切割后的完整 Chunk 列表（子 Chunk + 父 Chunk）
     * @throws IOException              文件读取失败
     * @throws IllegalArgumentException 不支持的文件类型
     */
    public List<Chunk> ingest(Document document, Path filePath) throws IOException {
        return ingest(document, filePath, null);
    }

    /**
     * 同步摄入文档（带状态回调）。
     * <p>
     * 回调在每个状态切换时触发，可用于前端进度条或监控埋点。
     *
     * @param document      文档模型
     * @param filePath      文件路径
     * @param statusCallback 状态变更回调，传入当前状态（PARSING → CHUNKING）
     * @return 切割后的完整 Chunk 列表
     * @throws IOException              文件读取失败
     * @throws IllegalArgumentException 不支持的文件类型
     */
    public List<Chunk> ingest(Document document, Path filePath,
                              Consumer<Document.DocumentStatus> statusCallback) throws IOException {
        log.info("Starting ingestion for document: {}", document.getId());

        // (1) 解析阶段：文件 → 原始文本
        updateStatus(document, Document.DocumentStatus.PARSING, statusCallback);
        String rawText = parseDocument(document, filePath);

        // (2) 切割阶段：原始文本 → 内容块 → Chunk
        updateStatus(document, Document.DocumentStatus.CHUNKING, statusCallback);
        List<ContentBlock> textBlocks = extractTextBlocks(rawText);
        List<ContentBlock> allBlocks = extractAllContent(rawText, textBlocks);
        List<Chunk> chunks = parentChildChunker.chunk(document.getId(), allBlocks);

        document.setChunkCount(chunks.size());
        log.info("Ingestion completed for document {}: {} chunks", document.getId(), chunks.size());
        return chunks;
    }

    /**
     * 异步摄入（Spring @Async），通过 CompletableFuture 获取结果。
     * <p>
     * 需要在配置类上启用 {@code @EnableAsync}。
     */
    @Async
    public CompletableFuture<List<Chunk>> ingestAsync(Document document, Path filePath,
                                                       Consumer<Document.DocumentStatus> statusCallback)
            throws IOException {
        List<Chunk> chunks = ingest(document, filePath, statusCallback);
        return CompletableFuture.completedFuture(chunks);
    }

    /** 最简摄入（无状态回调），等同于 {@link #ingest(Document, Path)} */
    public List<Chunk> ingestSimple(Document document, Path filePath) throws IOException {
        return ingest(document, filePath, null);
    }

    // ======================== 私有方法 ========================

    /**
     * 匹配文档类型到对应的 DocumentParser 并解析。
     * <p>
     * 如果 Document 对象未携带 fileType，则从文件路径推测（取扩展名）。
     *
     * @throws IllegalArgumentException 当没有 Parser 支持该文件类型时
     */
    private String parseDocument(Document document, Path filePath) throws IOException {
        String fileType = document.getFileType();
        if (fileType == null) {
            fileType = detectFileType(filePath);
            document.setFileType(fileType);
        }

        // 遍历所有 Parser，找到第一个支持该类型的
        for (DocumentParser parser : parsers) {
            if (parser.supports(fileType)) {
                return parser.parse(filePath);
            }
        }

        throw new IllegalArgumentException("Unsupported file type: " + fileType
                + " for document: " + document.getId());
    }

    /** 从文件路径中提取扩展名（如 "test_doc.md" → "md"） */
    private String detectFileType(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0) {
            throw new IllegalArgumentException("Cannot detect file type from: " + fileName);
        }
        return fileName.substring(dotIndex + 1);
    }

    /**
     * 第一步提取：执行 TEXT 提取器，获得带章节层级的文本块。
     * TEXT 提取器总是第一个执行，因为其他提取器依赖其产出的章节标题信息。
     */
    private List<ContentBlock> extractTextBlocks(String rawText) {
        for (ContentExtractor extractor : extractors) {
            if (extractor.supportedType() == ContentType.TEXT) {
                return extractor.extract(rawText, List.of());
            }
        }
        return List.of();
    }

    /**
     * 第二步提取：在 TEXT 块基础上，执行所有非 TEXT 提取器（Table/Code/ImageDescription），
     * 并对重叠的 TEXT 块做去重裁剪——用更具体的类型块（CODE/TABLE）替换掉 TEXT 块中的对应区间。
     * <p>
     * 去重策略：
     * <ol>
     *   <li>把 TEXT 块和非 TEXT 块合并后按 startOffset 排序</li>
     *   <li>非 TEXT 块（CODE/TABLE）的区间覆盖部分从对应的 TEXT 块中裁掉</li>
     *   <li>裁剪后的 TEXT 片段如果非空则保留，空则丢弃</li>
     *   <li>最终按 startOffset 排序输出</li>
     * </ol>
     */
    private List<ContentBlock> extractAllContent(String rawText, List<ContentBlock> textBlocks) {
        List<ContentBlock> nonTextBlocks = new ArrayList<>();

        for (ContentExtractor extractor : extractors) {
            if (extractor.supportedType() != ContentType.TEXT) {
                nonTextBlocks.addAll(extractor.extract(rawText, textBlocks));
            }
        }

        if (nonTextBlocks.isEmpty()) {
            textBlocks.sort(Comparator.comparingInt(ContentBlock::getStartOffset));
            return textBlocks;
        }

        nonTextBlocks.sort(Comparator.comparingInt(ContentBlock::getStartOffset));

        // 对每个 TEXT 块，用非 TEXT 块将其"切碎"，去除重叠区间
        List<ContentBlock> result = new ArrayList<>();
        for (ContentBlock textBlock : textBlocks) {
            List<ContentBlock> fragments = splitByOverlaps(textBlock, nonTextBlocks);
            result.addAll(fragments);
        }

        result.addAll(nonTextBlocks);
        result.sort(Comparator.comparingInt(ContentBlock::getStartOffset));
        return result;
    }

    /**
     * 用一个非 TEXT 块列表去切割一个 TEXT 块，裁掉重叠区间。
     * <p>
     * 例如：一个 TEXT 块覆盖 [0, 500)，有一个 CODE 块覆盖 [200, 350)，
     * 则 TEXT 块被切为两个片段 [0, 200) 和 [350, 500)。
     */
    private List<ContentBlock> splitByOverlaps(ContentBlock textBlock, List<ContentBlock> nonTextBlocks) {
        List<ContentBlock> fragments = new ArrayList<>();
        int currentStart = textBlock.getStartOffset();
        int blockEnd = textBlock.getEndOffset();

        for (ContentBlock nt : nonTextBlocks) {
            int ntStart = nt.getStartOffset();
            int ntEnd = nt.getEndOffset();

            // 非 TEXT 块完全不在 TEXT 块范围内：跳过
            if (ntEnd <= currentStart || ntStart >= blockEnd) {
                continue;
            }

            // 非 TEXT 块完全在 TEXT 块内部（或部分重叠）
            int overlapStart = Math.max(currentStart, ntStart);
            int overlapEnd = Math.min(blockEnd, ntEnd);

            // 重叠区间之前的 TEXT 片段
            if (overlapStart > currentStart) {
                ContentBlock fragment = createTextFragment(textBlock, currentStart, overlapStart);
                if (fragment != null && !fragment.isEmpty()) {
                    fragments.add(fragment);
                }
            }

            // 跳过重叠区间
            currentStart = overlapEnd;
        }

        // 末尾剩余片段
        if (currentStart < blockEnd) {
            ContentBlock fragment = createTextFragment(textBlock, currentStart, blockEnd);
            if (fragment != null && !fragment.isEmpty()) {
                fragments.add(fragment);
            }
        }

        return fragments;
    }

    /**
     * 从 TEXT 块中截取 [start, end) 区间创建一个新的 TEXT ContentBlock。
     * 继承原块的章节信息，内容从 rawText 中重新截取。
     */
    private ContentBlock createTextFragment(ContentBlock original, int start, int end) {
        ContentBlock fragment = new ContentBlock(ContentType.TEXT,
                original.getContent().substring(
                        start - original.getStartOffset(),
                        end - original.getStartOffset()));
        fragment.setStartOffset(start);
        fragment.setEndOffset(end);
        fragment.setSectionTitle(original.getSectionTitle());
        fragment.setSectionLevel(original.getSectionLevel());
        fragment.addMetadata("isHeader", original.getMetadata().get("isHeader"));
        return fragment;
    }

    /** 更新 Document 状态，同时触发外部回调（如有） */
    private void updateStatus(Document document, Document.DocumentStatus status,
                               Consumer<Document.DocumentStatus> statusCallback) {
        document.setStatus(status);
        if (statusCallback != null) {
            statusCallback.accept(status);
        }
        log.debug("Document {} status: {}", document.getId(), status);
    }
}
