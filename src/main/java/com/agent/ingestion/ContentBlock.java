package com.agent.ingestion;

import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

/**
 * 文档内容块模型，摄入管道的中间产物。
 * <p>
 * 该模型定义了文档内容的基本单位，即"内容块"。
 * 文档经 Parser 解析为原始文本后，由 ContentExtractor 从中识别出一个个"内容块"。
 * 每个块携带其类型（文本/表格/代码/图片描述）、在原文档中的位置偏移、
 * 以及所属章节标题等结构化元信息。这些内容块随后交给 Chunker 切割为可检索的 Chunk。
 * <p>
 * 关键字段：
 * <ul>
 *   <li>{@code type} — 内容类别，决定后续切割和向量化策略</li>
 *   <li>{@code startOffset / endOffset} — 在原文档中的字符偏移，用于追溯来源</li>
 *   <li>{@code sectionTitle / sectionLevel} — 所属章节信息，从 Markdown # 标题或 Word Heading 样式提取</li>
 *   <li>{@code metadata} — 扩展元数据，如 isHeader、language、rowCount 等</li>
 * </ul>
 */
@Getter
@Setter
public class ContentBlock {

    /** 内容块类型（文本/表格/代码/图片描述） */
    private ContentType type;

    /** 内容块的文本内容 */
    private String content;

    /** 在原文档中的起始字符偏移（从 0 开始） */
    private int startOffset;

    /** 在原文档中的结束字符偏移 */
    private int endOffset;

    /** 所属章节标题（如"概述"、"核心特性"），无章节时为空字符串 */
    private String sectionTitle;

    /** 所属章节层级（1=一级标题, 2=二级标题, ..., 0=无标题） */
    private int sectionLevel;

    /** 所在页码（PDF 解析时填充，从 1 开始） */
    private int pageNumber;

    /**
     * 扩展元数据。
     * <p>
     * 常见 key：isHeader（是否标题行）、language（代码语言）、
     * rowCount（表格行数）、subChunkIndex（子块序号）等。
     */
    private final Map<String, Object> metadata = new HashMap<>();

    public ContentBlock() {
    }

    public ContentBlock(ContentType type, String content) {
        this.type = type;
        this.content = content;
    }

    /** @return 内容文本的字符长度 */
    public int contentLength() {
        return content != null ? content.length() : 0;
    }

    /** @return true 表示内容为空或仅包含空白字符 */
    public boolean isEmpty() {
        return content == null || content.isBlank();
    }

    /** 添加一条扩展元数据 */
    public void addMetadata(String key, Object value) {
        metadata.put(key, value);
    }

    @Override
    public String toString() {
        return "ContentBlock{" +
                "type=" + type +
                ", contentLength=" + contentLength() +
                ", sectionTitle='" + sectionTitle + '\'' +
                ", pageNumber=" + pageNumber +
                '}';
    }
}
