package com.agent.ingestion;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 文档解析器接口，将不同格式的原始文件解析为纯文本。
 * <p>
 * 每种文件格式对应一个实现类（Pdf/Word/Markdown），
 * 通过 {@link #supports(String)} 声明自己能处理的文件类型，
 * 由 {@link IngestionService} 自动匹配对应的解析器执行解析。
 * <p>
 * 新增文件格式支持：只需实现此接口并注册为 Spring {@code @Component} 即可。
 */
public interface DocumentParser {

    /**
     * 判断当前解析器是否支持指定文件类型。
     *
     * @param fileType 文件扩展名（如 "pdf"、"docx"、"md"），不区分大小写
     * @return true 表示可处理该类型
     */
    boolean supports(String fileType);

    /**
     * 解析文件，提取纯文本内容。
     * <p>
     * 对于 Word 文档，段落按 Heading 样式转为 Markdown 标题格式（# ~ ######），
     * 表格转为 Markdown 表格格式，便于下游统一处理。
     *
     * @param filePath 文件绝对路径
     * @return 解析后的纯文本（可能包含 Markdown 标记）
     * @throws IOException 文件读取失败
     */
    String parse(Path filePath) throws IOException;
}
