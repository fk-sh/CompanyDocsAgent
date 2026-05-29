package com.agent.ingestion;

import com.agent.core.Chunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 父子 Chunk 切割器，<b>由父生成子</b>，而非由子拼接父。
 * <p>
 * 核心流程：
 * <ol>
 *   <li>遍历 ContentBlock，按章节边界和大小上限（2048 字）拼接为<b>父 Chunk</b></li>
 *   <li>在每个父 Chunk 内部用滑动窗口（512 字 + 64 重叠）拆分为<b>子 Chunk</b></li>
 *   <li>子 Chunk 切割时直接记录 {@code parentChunkId}，并通过偏移映射
 *       从原始 ContentBlock 继承类型（CODE/TABLE/TEXT）和章节标题</li>
 * </ol>
 * <p>
 * 检索时的父子联动：
 * <pre>
 *   子 Chunk 命中 → child.getParentChunkId() → 拉取父 Chunk 完整上下文 → 送入 LLM
 * </pre>
 * 父 Chunk（~2048 字）提供完整语义背景，子 Chunk（~512 字）专注精准向量检索。
 */
@Slf4j
@Component
public class ParentChildChunker {

    private static final int CHILD_CHUNK_SIZE = 512;
    private static final int PARENT_CHUNK_SIZE = 2048;
    private static final int OVERLAP = 64;
    private static final int PARAGRAPH_LOOKBACK = 512;
    private static final float ATOMIC_BLOCK_OVERFLOW_RATIO = 1.3f;

    private final HierarchicalChunker hierarchicalChunker;

    public ParentChildChunker(HierarchicalChunker hierarchicalChunker) {
        this.hierarchicalChunker = hierarchicalChunker;
    }

    /**
     * 对内容块执行父子切割（自顶向下）。
     *
     * @param documentId 所属文档 ID
     * @param blocks     内容块列表（已按 startOffset 排序）
     * @return 子 Chunk + 父 Chunk 的合集，子 Chunk 已通过 parentChunkId 指向父 Chunk
     */
    public List<Chunk> chunk(String documentId, List<ContentBlock> blocks) {
        log.info("ParentChildChunker.chunk() called with {} blocks for document {}", blocks.size(), documentId);
        List<Chunk> allChunks = new ArrayList<>();

        // 第 1 步：拼接 ContentBlock → 父 Chunk（同时记录每个父 Chunk 由哪些 ContentBlock 组成）
        log.info("Building parent chunks...");
        List<ParentWithBlocks> parentsWithBlocks = buildParentChunks(documentId, blocks);
        log.info("Built {} parent chunks", parentsWithBlocks.size());
        for (ParentWithBlocks pwb : parentsWithBlocks) {
            allChunks.add(pwb.parent);// 添加当前父 Chunk 到结果列表
        }

        // 第 2 步：每个父 Chunk 内部用滑动窗口拆分为子 Chunk
        log.info("Splitting parents into children...");
        int childIndex = 0;
        for (ParentWithBlocks pwb : parentsWithBlocks) {
            List<Chunk> children = splitParentIntoChildren(pwb, documentId, childIndex);
            childIndex += children.size();
            allChunks.addAll(children);// 添加当前子 Chunk 到结果列表
        }

        log.info("ParentChild chunking: {} child chunks + {} parent chunks for document {}",
                childIndex, parentsWithBlocks.size(), documentId);
        return allChunks;// 返回所有子 Chunk + 父 Chunk
        // 子 Chunk 已通过 parentChunkId 指向父 Chunk
    }

    // ======================== 内部数据：父 Chunk + 其组成 ContentBlock 列表 ========================

    /**
     * 父 Chunk 与其组成 ContentBlock 的绑定结构。
     * 用于子 Chunk 切割时根据字符偏移回查对应的 ContentBlock，恢复类型和章节信息。
     */
    private static class ParentWithBlocks {
        final Chunk parent;
        /**
         * 记录父 Chunk 的组成块列表，用于子 Chunk 切割时回查原始类型和章节信息。
         * blockRanges[i] = 第 i 个 ContentBlock 在父 Chunk 文本中的 [start, end) 偏移。
         */
        final List<ContentBlock> blocks;
        final List<int[]> blockRanges;

        ParentWithBlocks(Chunk parent, List<ContentBlock> blocks, List<int[]> blockRanges) {
            this.parent = parent;
            this.blocks = blocks;
            this.blockRanges = blockRanges;
        }
    }

    // ======================== 第 1 步：生成父 Chunk ========================

    private List<ParentWithBlocks> buildParentChunks(String documentId, List<ContentBlock> blocks) {
        List<ParentWithBlocks> result = new ArrayList<>();
        if (blocks.isEmpty()) {
            return result;
        }

        StringBuilder buffer = new StringBuilder();
        List<ContentBlock> currentBlocks = new ArrayList<>();
        List<int[]> currentRanges = new ArrayList<>();
        int parentIndex = 0;
        String currentSection = "";
        int parentGlobalStart = 0;

        for (ContentBlock block : blocks) {
            if (block.isEmpty()) {
                continue;
            }

            String section = block.getSectionTitle();
            String content = block.getContent();

            if (buffer.length() == 0) {
                parentGlobalStart = block.getStartOffset();
            }

            if (section != null && !section.isEmpty()
                    && !section.equals(currentSection) && buffer.length() > 0) {
                flushParentBuffer(documentId, result, buffer, currentBlocks, currentRanges,
                        parentIndex++, parentGlobalStart);
                parentGlobalStart = block.getStartOffset();
            }

            currentSection = (section != null && !section.isEmpty()) ? section : currentSection;
            if (buffer.length() == 0) {
                parentGlobalStart = block.getStartOffset();
            }

            boolean isAtomic = block.getType() == ContentType.CODE
                    || block.getType() == ContentType.TABLE;

            if (isAtomic && buffer.length() > 0
                    && buffer.length() + content.length() > PARENT_CHUNK_SIZE * ATOMIC_BLOCK_OVERFLOW_RATIO) {
                flushParentBuffer(documentId, result, buffer, currentBlocks, currentRanges,
                        parentIndex++, parentGlobalStart);
                parentGlobalStart = block.getStartOffset();
                currentSection = (section != null && !section.isEmpty()) ? section : "";
            }

            int blockLocalStart = buffer.length();
            if (buffer.length() > 0) {
                buffer.append("\n\n");
                blockLocalStart = buffer.length();
            }
            buffer.append(content);

            currentBlocks.add(block);
            currentRanges.add(new int[]{blockLocalStart, blockLocalStart + content.length()});

            while (buffer.length() > PARENT_CHUNK_SIZE) {
                int splitPos = findParagraphSplitPosition(buffer.toString());
                if (splitPos > 0 && splitPos < buffer.length()) {
                    splitParentAtParagraph(documentId, result, buffer, currentBlocks, currentRanges,
                            parentIndex++, parentGlobalStart, splitPos);
                    parentGlobalStart += splitPos;
                } else {
                    flushParentBuffer(documentId, result, buffer, currentBlocks, currentRanges,
                            parentIndex++, parentGlobalStart);
                    parentGlobalStart += PARENT_CHUNK_SIZE;
                    break;
                }
            }
        }

        if (buffer.length() > 0) {
            flushParentBuffer(documentId, result, buffer, currentBlocks, currentRanges,
                    parentIndex++, parentGlobalStart);
        }

        return result;
    }

    private int findParagraphSplitPosition(String text) {
        int searchStart = Math.max(0, PARENT_CHUNK_SIZE - PARAGRAPH_LOOKBACK);
        int searchEnd = Math.min(text.length(), PARENT_CHUNK_SIZE);
        int lastBoundary = -1;
        int pos = searchStart;
        while (pos < searchEnd) {
            int idx = text.indexOf("\n\n", pos);
            if (idx == -1 || idx >= searchEnd) {
                break;
            }
            lastBoundary = idx + 2;
            pos = idx + 2;
        }
        if (lastBoundary > 0) {
            return lastBoundary;
        }
        pos = searchStart;
        while (pos < searchEnd) {
            int idx = text.indexOf("\n", pos);
            if (idx == -1 || idx >= searchEnd) {
                break;
            }
            lastBoundary = idx + 1;
            pos = idx + 1;
        }
        return lastBoundary > 0 ? lastBoundary : -1;
    }

    private void splitParentAtParagraph(String documentId, List<ParentWithBlocks> result,
                                         StringBuilder buffer,
                                         List<ContentBlock> currentBlocks,
                                         List<int[]> currentRanges,
                                         int parentIndex, int globalStart, int splitPos) {
        String parentText = buffer.substring(0, splitPos);
        String remaining = buffer.substring(splitPos);

        List<ContentBlock> parentBlocks = new ArrayList<>();
        List<int[]> parentRanges = new ArrayList<>();
        List<ContentBlock> remainBlocks = new ArrayList<>();
        List<int[]> remainRanges = new ArrayList<>();

        for (int i = 0; i < currentRanges.size(); i++) {
            int[] range = currentRanges.get(i);
            if (range[1] <= splitPos) {
                parentBlocks.add(currentBlocks.get(i));
                parentRanges.add(range);
            } else if (range[0] >= splitPos) {
                remainBlocks.add(currentBlocks.get(i));
                remainRanges.add(new int[]{range[0] - splitPos, range[1] - splitPos});
            } else {
                parentBlocks.add(currentBlocks.get(i));
                parentRanges.add(new int[]{range[0], splitPos});
                remainBlocks.add(currentBlocks.get(i));
                remainRanges.add(new int[]{0, range[1] - splitPos});
            }
        }

        result.add(buildParentWithBlocks(documentId, parentText, parentIndex, globalStart,
                parentBlocks, parentRanges));

        buffer.setLength(0);
        buffer.append(remaining);
        currentBlocks.clear();
        currentBlocks.addAll(remainBlocks);
        currentRanges.clear();
        currentRanges.addAll(remainRanges);
    }

    private void flushParentBuffer(String documentId, List<ParentWithBlocks> result,
                                    StringBuilder buffer,
                                    List<ContentBlock> currentBlocks,
                                    List<int[]> currentRanges,
                                    int parentIndex, int globalStart) {
        if (buffer.length() == 0) {
            return;
        }
        result.add(buildParentWithBlocks(documentId, buffer.toString(), parentIndex, globalStart,
                currentBlocks, currentRanges));
        buffer.setLength(0);
        currentBlocks.clear();
        currentRanges.clear();
    }

    private ParentWithBlocks buildParentWithBlocks(String documentId, String content, int index,
                                                    int startOffset,
                                                    List<ContentBlock> blocks,
                                                    List<int[]> ranges) {
        Chunk parent = new Chunk(
                UUID.randomUUID().toString(),
                documentId,
                content,
                Chunk.ContentType.TEXT,
                -1
        );
        parent.setStartOffset(startOffset);
        parent.setEndOffset(startOffset + content.length());
        parent.addMetadata("isParent", true);
        parent.addMetadata("parentIndex", index);
        parent.addMetadata("childCount", 0);
        return new ParentWithBlocks(parent, new ArrayList<>(blocks), new ArrayList<>(ranges));
    }

    // ======================== 第 2 步：父 Chunk → 子 Chunk ========================

    /**
     * 在一个父 Chunk 的内部文本上执行滑动窗口切割，生成子 Chunk。
     * <p>
     * 每个子 Chunk 通过偏移映射从原始 ContentBlock 继承：
     * <ul>
     *   <li>内容类型（TEXT/TABLE/CODE/IMAGE_DESCRIPTION）</li>
     *   <li>章节标题（sectionTitle）</li>
     * </ul>
     */
    private List<Chunk> splitParentIntoChildren(ParentWithBlocks pwb, String documentId,
                                                 int startChildIndex) {
        List<Chunk> children = new ArrayList<>();
        Chunk parent = pwb.parent;
        String parentContent = parent.getContent();
        String parentId = parent.getId();
        int baseOffset = parent.getStartOffset();

        boolean hasCode = pwb.blocks.stream().anyMatch(b -> b.getType() == ContentType.CODE);
        boolean hasTable = pwb.blocks.stream().anyMatch(b -> b.getType() == ContentType.TABLE);

        List<String> segments;
        if (hasCode || hasTable) {
            segments = hierarchicalChunker.splitWithTypeAwareness(
                    parentContent, CHILD_CHUNK_SIZE, OVERLAP, hasCode, hasTable);
        } else {
            segments = hierarchicalChunker.splitWithBoundaryAwareness(
                    parentContent, CHILD_CHUNK_SIZE, OVERLAP);
        }

        int segStart = 0;  // 当前 segment 在父 Chunk 文本中的起始位置
        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i);
            int segEnd = segStart + segment.length();

            // 回查：这个 segment 落在哪个 ContentBlock 里（取覆盖最多的）
            Chunk.ContentType childType = lookupContentType(segStart, segEnd, pwb);
            String[] sectionInfo = lookupSectionInfo(segStart, segEnd, pwb);

            int globalStart = baseOffset + segStart;
            int globalEnd = baseOffset + segEnd;

            Chunk child = new Chunk(
                    UUID.randomUUID().toString(),
                    documentId, // 子 Chunk 所属文档 ID
                    segment, // 子 Chunk 的文本内容
                    childType, // 子 Chunk 的内容类型
                    startChildIndex + i // 子 Chunk 的索引，从 startChildIndex 开始
            );
            child.setStartOffset(globalStart);// 子 Chunk 的起始偏移量
            child.setEndOffset(globalEnd);// 子 Chunk 的结束偏移量
            child.setParentChunkId(parentId);// 子 Chunk 的父 Chunk ID
            child.addMetadata("parentIndex", parent.getMetadata().get("parentIndex"));// 子 Chunk 的父 Chunk 索引
            child.addMetadata("subChunkIndex", i);// 子 Chunk 的索引，从 startChildIndex 开始
            child.addMetadata("totalSubChunks", segments.size());// 子 Chunk 总数量
            if (sectionInfo[0] != null) {
                child.addMetadata("sectionTitle", sectionInfo[0]);// 子 Chunk 的章节标题
                child.addMetadata("sectionLevel", Integer.parseInt(sectionInfo[1]));
            }

            children.add(child);

            segStart = segEnd - OVERLAP;
        }

        parent.addMetadata("childCount", children.size());
        return children;
    }

    /**
     * 根据子 Chunk 片段在父文本中的偏移范围，回查对应的 ContentBlock，
     * 取与之重叠字符数最多的 ContentBlock 的类型。
     */
    //就是判断该子Chunk的内容类型
    private Chunk.ContentType lookupContentType(int segStart, int segEnd, ParentWithBlocks pwb) {
        int bestOverlap = 0;
        ContentType bestType = ContentType.TEXT;

        for (int i = 0; i < pwb.blocks.size(); i++) {
            int[] range = pwb.blockRanges.get(i);
            int overlap = Math.min(segEnd, range[1]) - Math.max(segStart, range[0]);
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                bestType = pwb.blocks.get(i).getType();
            }
        }

        return switch (bestType) {
            case TEXT -> Chunk.ContentType.TEXT;
            case TABLE -> Chunk.ContentType.TABLE;
            case CODE -> Chunk.ContentType.CODE;
            case IMAGE_DESCRIPTION -> Chunk.ContentType.IMAGE_DESCRIPTION;
        };
    }

    /**
     * 根据子 Chunk 片段在父文本中的偏移范围，回查最近的章节标题信息。
     * 遍历父 Chunk 的所有组成块，找到 segStart 之前最近的标题块。
     *
     * @return [sectionTitle, sectionLevel] 或 [null, "0"]
     */
    private String[] lookupSectionInfo(int segStart, int segEnd, ParentWithBlocks pwb) {
        String title = null;
        int level = 0;

        for (int i = 0; i < pwb.blocks.size(); i++) {
            ContentBlock block = pwb.blocks.get(i);
            int[] range = pwb.blockRanges.get(i);
            // 该 block 在 segment 起始位置之前，且是标题块
            if (range[0] <= segStart
                    && Boolean.TRUE.equals(block.getMetadata().get("isHeader"))) {
                title = block.getSectionTitle();
                level = block.getSectionLevel();
            }
        }

        return new String[]{title, String.valueOf(level)};
    }
}
