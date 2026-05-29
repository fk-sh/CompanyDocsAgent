package com.agent.ingestion;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 滑动窗口文本切割工具，支持固定大小切割和语义边界感知切割。
 * <p>
 * 切割策略：
 * <ul>
 *   <li><b>短内容（≤指定大小）</b>：直接返回单段</li>
 *   <li><b>长内容</b>：按指定窗口大小 + 重叠策略滑动切割</li>
 *   <li><b>语义边界感知</b>：{@link #splitWithBoundaryAwareness} 在切割时寻找段落/句子/代码行边界，
 *       避免在单词或符号中间截断</li>
 * </ul>
 * <p>
 * 当前被 {@link ParentChildChunker} 内部调用。
 */
@Component
public class HierarchicalChunker {

    public static final int DEFAULT_CHUNK_SIZE = 512;
    public static final int DEFAULT_OVERLAP = 64;
    private static final int BOUNDARY_SEARCH_RANGE = 128;

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

    /**
     * 带语义边界感知的滑动窗口切割。
     * 在 targetPos 附近寻找最近的语义边界（段落结束、句子结束、行结束），
     * 避免在单词/符号中间截断。
     *
     * @param text    待切割文本
     * @param size    窗口大小（字符数）
     * @param overlap 相邻窗口重叠字符数
     * @return 切割后的文本段列表
     */
    public List<String> splitWithBoundaryAwareness(String text, int size, int overlap) {
        List<String> result = new ArrayList<>();
        int length = text.length();

        if (length <= size) {
            result.add(text);
            return result;
        }

        int start = 0;
        int minChunkSize = overlap + 1;

        while (start < length) {
            int end = Math.min(start + size, length);

            if (end < length) {
                int boundary = findNearestBoundary(text, end, BOUNDARY_SEARCH_RANGE);
                if (boundary > start + minChunkSize && boundary <= end + BOUNDARY_SEARCH_RANGE) {
                    end = boundary;
                }
            }

            result.add(text.substring(start, end));

            if (end >= length) {
                break;
            }
            start = end - overlap;
        }

        return result;
    }

    /**
     * 带类型感知的语义边界切割。对 CODE/TABLE 类型的片段采用不同的边界查找策略。
     *
     * @param text    待切割文本
     * @param size    窗口大小
     * @param overlap 重叠字符数
     * @param isCode  是否为代码类型（代码以行为边界）
     * @param isTable 是否为表格类型（表格以行结束符为边界）
     * @return 切割后的文本段列表
     */
    public List<String> splitWithTypeAwareness(String text, int size, int overlap,
                                                boolean isCode, boolean isTable) {
        List<String> result = new ArrayList<>();
        int length = text.length();

        if (length <= size) {
            result.add(text);
            return result;
        }

        int start = 0;
        int minChunkSize = overlap + 1;

        while (start < length) {
            int end = Math.min(start + size, length);

            if (end < length) {
                int boundary;
                if (isCode) {
                    boundary = findCodeBoundary(text, end, BOUNDARY_SEARCH_RANGE);
                } else if (isTable) {
                    boundary = findTableBoundary(text, end, BOUNDARY_SEARCH_RANGE);
                } else {
                    boundary = findNearestBoundary(text, end, BOUNDARY_SEARCH_RANGE);
                }
                if (boundary > start + minChunkSize && boundary <= end + BOUNDARY_SEARCH_RANGE) {
                    end = boundary;
                }
            }

            result.add(text.substring(start, end));

            if (end >= length) {
                break;
            }
            start = end - overlap;
        }

        return result;
    }

    /**
     * 在 targetPos 附近寻找最近的语义边界。
     * 优先级：双换行（段落） > 句子结束符 > 单换行
     */
    private int findNearestBoundary(String text, int targetPos, int searchRange) {
        int searchStart = Math.max(0, targetPos - searchRange);

        int paraBreak = text.lastIndexOf("\n\n", targetPos);
        if (paraBreak >= searchStart) {
            return paraBreak + 2;
        }

        for (int i = targetPos - 1; i >= searchStart; i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？') {
                return i + 1;
            }
        }

        int lastNewline = text.lastIndexOf('\n', targetPos - 1);
        if (lastNewline >= searchStart) {
            return lastNewline + 1;
        }

        return targetPos;
    }

    /**
     * 代码块边界查找：在 targetPos 之前找最近的换行符，以完整行为单位切割。
     */
    private int findCodeBoundary(String text, int targetPos, int searchRange) {
        int searchStart = Math.max(0, targetPos - searchRange);
        int lastNewline = text.lastIndexOf('\n', targetPos - 1);
        if (lastNewline >= searchStart) {
            return lastNewline + 1;
        }
        return targetPos;
    }

    /**
     * 表格边界查找：优先找表格行结束（| 后跟换行），其次找双换行，最后找单换行。
     */
    private int findTableBoundary(String text, int targetPos, int searchRange) {
        int searchStart = Math.max(0, targetPos - searchRange);

        int tableRowEnd = text.lastIndexOf("|\n", targetPos);
        if (tableRowEnd >= searchStart) {
            return tableRowEnd + 2;
        }

        int separatorLine = text.lastIndexOf("|---", targetPos);
        if (separatorLine >= searchStart) {
            int newlineAfter = text.indexOf('\n', separatorLine);
            if (newlineAfter > 0 && newlineAfter <= targetPos + searchRange) {
                return newlineAfter + 1;
            }
        }

        return findNearestBoundary(text, targetPos, searchRange);
    }
}
