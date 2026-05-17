package com.agent.ingestion;

/**
 * 内容块类型枚举，标识文档摄入管道中识别出的内容类别。
 * <p>
 * 多模态文档中可能混合出现文本、图片描述、表格和代码块，
 * 不同的内容类型在切割和向量化策略上可以有不同的处理逻辑。
 */
public enum ContentType {

    /** 纯文本段落，包括标题和正文 */
    TEXT,

    /** VLM（视觉语言模型）生成的图片文字描述 */
    IMAGE_DESCRIPTION,

    /** 结构化表格，以 Markdown 表格格式存储 */
    TABLE,

    /** 代码块 */
    CODE
}
