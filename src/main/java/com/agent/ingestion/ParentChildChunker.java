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
        List<Chunk> allChunks = new ArrayList<>();

        // 第 1 步：拼接 ContentBlock → 父 Chunk（同时记录每个父 Chunk 由哪些 ContentBlock 组成）
        List<ParentWithBlocks> parentsWithBlocks = buildParentChunks(documentId, blocks);
        for (ParentWithBlocks pwb : parentsWithBlocks) {
            allChunks.add(pwb.parent);// 添加当前父 Chunk 到结果列表
        }

        // 第 2 步：每个父 Chunk 内部用滑动窗口拆分为子 Chunk
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

        // buffer: 累积当前父 Chunk 的文本内容
        StringBuilder buffer = new StringBuilder();
        // currentBlocks: 当前父 Chunk 包含的 ContentBlock 列表
        List<ContentBlock> currentBlocks = new ArrayList<>();
        // currentRanges: 每个 ContentBlock 在 buffer 中的 [start, end) 偏移范围
        List<int[]> currentRanges = new ArrayList<>();
        // parentIndex: 父 Chunk 的序号，用于生成 parentIndex 元数据
        int parentIndex = 0;
        // currentSection: 当前章节标题，用于检测章节切换边界
        String currentSection = "";
        // parentGlobalStart: 当前父 Chunk 在整个文档中的起始偏移
        int parentGlobalStart = 0;

        for (ContentBlock block : blocks) {
            if (block.isEmpty()) {
                continue;
            }

            String section = block.getSectionTitle();// 获取当前块的章节标题
            // 过滤无章节的块
            String content = block.getContent();

            if (buffer.length() == 0) {
                parentGlobalStart = block.getStartOffset();
            }

            // 条件 1：章节切换边界
            if (section != null && !section.isEmpty()
                    && !section.equals(currentSection) && buffer.length() > 0) {
                result.add(buildParentWithBlocks(documentId, buffer.toString(),
                        parentIndex++, parentGlobalStart, currentBlocks, currentRanges));// 生成当前父 Chunk
                // 重置当前父 Chunk 的数据，准备生成下一个父 Chunk
                buffer = new StringBuilder();
                currentBlocks = new ArrayList<>();
                currentRanges = new ArrayList<>();
            }

            // 更新当前章节标题
            // 过滤无章节的块
            currentSection = (section != null && !section.isEmpty()) ? section : currentSection;
            if (buffer.length() == 0) {
                // 重置当前父 Chunk 的起始偏移
                parentGlobalStart = block.getStartOffset();
            }

            // 条件 2：大小超限
            // 如果当前父 Chunk 大小超过阈值，且不是第一个块，就生成当前父 Chunk
            if (buffer.length() + content.length() > PARENT_CHUNK_SIZE && buffer.length() > 0) {
                result.add(buildParentWithBlocks(documentId, buffer.toString(),
                        parentIndex++, parentGlobalStart, currentBlocks, currentRanges));
                buffer = new StringBuilder();
                currentBlocks = new ArrayList<>();
                currentRanges = new ArrayList<>();
                parentGlobalStart = block.getStartOffset();
            }

            // 追加内容（非首个块前加 \n\n 分隔），同时记录偏移范围
            int blockLocalStart = buffer.length();
            if (buffer.length() > 0) {
                buffer.append("\n\n");
                blockLocalStart = buffer.length();  // 分隔符之后才是 block 内容的开始
            }
            buffer.append(content);

            currentBlocks.add(block);
            currentRanges.add(new int[]{blockLocalStart, blockLocalStart + content.length()});
        }

        // 末尾剩余
        if (buffer.length() > 0) {
            result.add(buildParentWithBlocks(documentId, buffer.toString(),
                    parentIndex++, parentGlobalStart, currentBlocks, currentRanges));
        }

        return result;
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
                -1  //-1代表以后进行向量化的时候不会以向量的形式存储进向量数据库，只是作为文本进行存储，不会被向量化
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

        List<String> segments = hierarchicalChunker.splitWithOverlap(
                parentContent, CHILD_CHUNK_SIZE, OVERLAP);

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

            // 下一段起始：步进 = CHILD_CHUNK_SIZE - OVERLAP
            segStart += (CHILD_CHUNK_SIZE - OVERLAP);
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
