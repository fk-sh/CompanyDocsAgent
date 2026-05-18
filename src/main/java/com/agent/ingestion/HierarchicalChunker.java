package com.agent.ingestion;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 滑动窗口文本切割工具，按固定大小 + 重叠策略将长文本切为多段。
 * <p>
 * 切割策略：
 * <ul>
 *   <li><b>短内容（≤512 字）</b>：直接返回单段</li>
 *   <li><b>长内容（＞512 字）</b>：按 512 字符窗口滑动切割，相邻窗口重叠 64 字符</li>
 * </ul>
 * <p>
 * 重叠窗口的作用：防止关键信息恰好落在切割边界上。例如句子 "XYZ 公司 Q3 营收增长 30%"，
 * 如果 "30%" 被切到下一个 Chunk，检索时可能无法关联。64 字符重叠相当于约 20-30 个汉字，
 * 能覆盖大多数短句的边界情况。
 * <p>
 * 当前仅被 {@link ParentChildChunker} 内部调用 {@link #splitWithOverlap(String, int, int)}，
 * 不再直接产出 Chunk 对象。
 */
@Component
public class HierarchicalChunker {

    public static final int DEFAULT_CHUNK_SIZE = 512;
    public static final int DEFAULT_OVERLAP = 64;

    private final int chunkSize;
    private final int overlap;

    public HierarchicalChunker() {
        this(DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP);
    }

    public HierarchicalChunker(int chunkSize, int overlap) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public int getOverlap() {
        return overlap;
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
    public List<String> splitWithOverlap(String text, int size, int overlap) {
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
            start += (size - overlap);
        }

        return result;
    }
}
