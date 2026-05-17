package com.agent.ingestion;

import com.agent.core.Chunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 分层切割器，将内容块按固定窗口 + 重叠策略切割为子 Chunk。
 * <p>
 * 切割策略：
 * <ul>
 *   <li><b>短内容（≤512 字）</b>：整个内容块直接作为一个 Chunk，不做切割</li>
 *   <li><b>长内容（＞512 字）</b>：按 512 字符窗口滑动切割，相邻窗口重叠 64 字符</li>
 *   <li>每个 Chunk 继承所属内容块的类型（TEXT/TABLE/CODE/IMAGE_DESCRIPTION）和章节信息</li>
 * </ul>
 * <p>
 * 重叠窗口的作用：防止关键信息恰好落在切割边界上。例如句子 "XYZ 公司 Q3 营收增长 30%"，
 * 如果 "30%" 被切到下一个 Chunk，检索时可能无法关联。64 字符重叠相当于约 20-30 个汉字，
 * 能覆盖大多数短句的边界情况。
 * <p>
 * 该切割器产出的 Chunk 称为"子 Chunk"，由 {@link ParentChildChunker} 进一步
 * 构建父 Chunk 并建立父子链接关系。
 */
@Slf4j
@Component
public class HierarchicalChunker {

    /** 默认子 Chunk 大小（字符数） */
    private static final int DEFAULT_CHUNK_SIZE = 512;
    /** 默认重叠窗口（字符数） */
    private static final int DEFAULT_OVERLAP = 64;

    //也支持自定义Chunk 和 重叠窗口
    private final int chunkSize;
    private final int overlap;

    public HierarchicalChunker() {
        this(DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP);
    }

    public HierarchicalChunker(int chunkSize, int overlap) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    /**
     * 将内容块列表切割为子 Chunk 列表。
     *
     * @param documentId 所属文档 ID
     * @param blocks     待切割的内容块列表（已按 startOffset 排序）
     * @return 切割后的子 Chunk 列表，含章节/类型元数据
     */
    public List<Chunk> chunk(String documentId, List<ContentBlock> blocks) {
        List<Chunk> chunks = new ArrayList<>();
        int chunkIndex = 0;  // 全局 Chunk 序号（跨内容块递增）

        for (ContentBlock block : blocks) {
            if (block.isEmpty()) {
                continue;
            }

            String sectionTitle = block.getSectionTitle();
            String content = block.getContent();

            if (content.length() <= chunkSize) {
                // 短内容：一个内容块 = 一个 Chunk
                //设置这个块的基础信息
                Chunk chunk = createChunk(documentId, block, content, chunkIndex++,
                        block.getStartOffset(), block.getEndOffset());
                //如果存在章节标题，则向 Chunk 的元数据中添加 sectionTitle 和 sectionLevel。
                if (sectionTitle != null && !sectionTitle.isEmpty()) {
                    chunk.addMetadata("sectionTitle", sectionTitle);
                    chunk.addMetadata("sectionLevel", block.getSectionLevel());
                }
                chunks.add(chunk);
            } else {
                // 长内容：滑动窗口切割
                List<String> subChunks = splitWithOverlap(content, chunkSize, overlap);
                for (int i = 0; i < subChunks.size(); i++) {
                    // 计算子块在原文档中的偏移
                    int subStart = block.getStartOffset() + i * (chunkSize - overlap);
                    int subEnd = Math.min(subStart + subChunks.get(i).length(), block.getEndOffset());

                    Chunk chunk = createChunk(documentId, block, subChunks.get(i), chunkIndex++,
                            subStart, subEnd);

                    if (sectionTitle != null && !sectionTitle.isEmpty()) {
                        chunk.addMetadata("sectionTitle", sectionTitle);
                        chunk.addMetadata("sectionLevel", block.getSectionLevel());
                        chunk.addMetadata("subChunkIndex", i);
                        chunk.addMetadata("totalSubChunks", subChunks.size());
                    }
                    chunks.add(chunk);
                }
            }
        }

        log.info("Hierarchical chunking produced {} chunks for document {}", chunks.size(), documentId);
        return chunks;
    }

    /** 创建单个 Chunk，映射内容类型并设置偏移 */
    private Chunk createChunk(String documentId, ContentBlock block, String content,
                              int chunkIndex, int startOffset, int endOffset) {
        Chunk chunk = new Chunk(
                UUID.randomUUID().toString(),
                documentId,
                content,
                convertContentType(block.getType()),
                chunkIndex
        );
        chunk.setStartOffset(startOffset);
        chunk.setEndOffset(endOffset);
        return chunk;
    }

    /** 将 ingestion 包内的 ContentType 映射为 core 包的 Chunk.ContentType */
    private Chunk.ContentType convertContentType(ContentType type) {
        return switch (type) {
            case TEXT -> Chunk.ContentType.TEXT;
            case TABLE -> Chunk.ContentType.TABLE;
            case CODE -> Chunk.ContentType.CODE;
            case IMAGE_DESCRIPTION -> Chunk.ContentType.IMAGE_DESCRIPTION;
        };
    }

    /**
     * 按固定窗口大小 + 重叠步进对文本做滑动切割。
     * <p>
     * 示例：text 长度 1000，size=512，overlap=64
     * <pre>
     *   start=0      → substring(0, 512)
     *   start=448    → substring(448, 960)   // 与上一窗口重叠 64 字符
     *   start=896    → substring(896, 1000)
     * </pre>
     *
     * @param text    待切割文本
     * @param size    窗口大小（字符数）
     * @param overlap 相邻窗口重叠字符数
     * @return 切割后的文本段列表
     */
    List<String> splitWithOverlap(String text, int size, int overlap) {
        List<String> result = new ArrayList<>();
        int length = text.length();

        if (length <= size) {
            result.add(text);
            return result;
        }

        int start = 0;
        while (start < length) {
            int end = Math.min(start + size, length);
            String chunk = text.substring(start, end);
            result.add(chunk);
            start += (size - overlap);  // 步进 = 窗口大小 - 重叠量
        }

        return result;
    }
}
