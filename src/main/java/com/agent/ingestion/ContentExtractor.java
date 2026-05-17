package com.agent.ingestion;

import java.util.List;

/**
 * 内容提取器接口，从解析后的原始文本中提取特定类型的内容块。
 * <p>
 * 提取器分两步工作：
 * <ol>
 *   <li>{@link TextContentExtractor} 先按段落/标题切割全文本，得到 TEXT 类型块 + 章节层级信息</li>
 *   <li>Table/Code/ImageDescription 提取器以 TEXT 块的章节信息为参照，
 *       在全文中定位表格、代码块和图片描述</li>
 * </ol>
 * 两步之间存在依赖关系：非 TEXT 提取器需要 TEXT 块的章节标题来为自身打标签。
 * <p>
 * Spring 自动注入：所有 {@code @Component} 实现类会被
 * {@link IngestionService} 的构造器自动收集，按类型调度。
 */
public interface ContentExtractor {

    /**
     * @return 当前提取器负责的内容类型
     */
    ContentType supportedType();

    /**
     * 从原始文本中提取该类型的所有内容块。
     *
     * @param fullText   解析后的完整原始文本
     * @param textBlocks TextContentExtractor 已经提取的 TEXT 类型块列表，
     *                   用于获取章节标题等结构化上下文信息
     * @return 提取到的内容块列表
     */
    List<ContentBlock> extract(String fullText, List<ContentBlock> textBlocks);
}
