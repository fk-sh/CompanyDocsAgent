package com.agent.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Markdown / 纯文本文档解析器。
 * <p>
 * 直接将文件内容读入内存，不做任何格式转换——因为 Markdown
 * 本身已经是下游 TextContentExtractor 直接能消费的格式。
 * 支持 .md / .markdown / .txt / .text 四种扩展名。
 */
@Slf4j
@Component
public class MarkdownDocumentParser implements DocumentParser {

    /** 支持的文件扩展名集合 */
    private static final Set<String> SUPPORTED_TYPES = Set.of("md", "markdown", "txt", "text");

    @Override
    public boolean supports(String fileType) {
        return fileType != null && SUPPORTED_TYPES.contains(fileType.toLowerCase());
    }

    @Override
    public String parse(Path filePath) throws IOException {
        log.info("Parsing Markdown/Text: {}", filePath);
        String content = Files.readString(filePath);
        log.info("Markdown/Text parsed: {} characters", content.length());
        return content;
    }
}
