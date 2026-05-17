package com.agent.ingestion;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本内容提取器，按段落切割全文本并识别 Markdown 标题层级。
 * <p>
 * 作为第一个被调用的提取器，它产出的 TEXT 类型块携带了章节标题信息（sectionTitle + sectionLevel），
 * 为后续 Table/Code/ImageDescription 提取器提供结构化上下文定位能力。
 * <p>
 * 处理逻辑：
 * <ol>
 *   <li>用连续空行（{@code \R\R+}）作为分割符，将全文本拆为段落</li>
 *   <li>对每个段落用正则 {@code ^(#{1,6})\s+(.+)$} 匹配标题行：
 *       匹配成功 → 标记 isHeader=true，更新 currentSection</li>
 *   <li>匹配失败 → 普通段落，继承上一个标题的 sectionTitle</li>
 * </ol>
 */
@Component
public class TextContentExtractor implements ContentExtractor {

    /** 匹配 Markdown 标题行，如 "# 概述"、"## 核心特性"、"### 技术细节" */
    private static final Pattern SECTION_PATTERN = Pattern.compile(
            "^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);

    /** 连续换行符（\R 兼容 \n、\r\n、\r），至少两个视为段落分割 */
    private static final Pattern BLANK_LINE_PATTERN = Pattern.compile("\\R\\R+");

    @Override
    public ContentType supportedType() {
        return ContentType.TEXT;
    }

    @Override
    // 从原始文本中提取 TEXT 类型的内容块
    // 每个段落或标题行都转换为一个 ContentBlock
    public List<ContentBlock> extract(String fullText, List<ContentBlock> existingBlocks) {
        List<ContentBlock> blocks = new ArrayList<>();

        // 统一换行符为 \n，再统一处理 \r 残余
        String normalizedText = fullText.replace("\r\n", "\n");
        String normalizedForSplit = normalizedText.replace("\r", "\n");

        // 当前追踪的章节标题和层级
        String currentSectionTitle = "";
        int currentSectionLevel = 0;

        // 按连续空行切分段落
        String[] paragraphs = BLANK_LINE_PATTERN.split(normalizedForSplit);
        int offset = 0;  // 当前段落在整个文本中的起始位置

        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                offset += paragraph.length() + 2;  // +2 补偿两个换行符
                continue;
            }

            Matcher sectionMatcher = SECTION_PATTERN.matcher(trimmed);// 匹配标题行
            if (sectionMatcher.matches()) {
                // === 标题行 ===
                // 提取标题层级数字
                int level = sectionMatcher.group(1).length();   // # 的数量 = 标题层级
                String title = sectionMatcher.group(2).trim();  // 标题文本
                currentSectionTitle = title;
                currentSectionLevel = level;

                ContentBlock headerBlock = new ContentBlock(ContentType.TEXT, trimmed);
                headerBlock.setSectionTitle(title);
                headerBlock.setSectionLevel(level);
                headerBlock.setStartOffset(offset);
                headerBlock.setEndOffset(offset + trimmed.length());
                headerBlock.addMetadata("isHeader", true);
                blocks.add(headerBlock);
            } else {
                // === 普通正文段落 ===
                // 继承当前所属章节的标题（即距离最近的上一个标题）
                ContentBlock block = new ContentBlock(ContentType.TEXT, trimmed);
                block.setSectionTitle(currentSectionTitle);
                block.setSectionLevel(currentSectionLevel);
                block.setStartOffset(offset);
                block.setEndOffset(offset + trimmed.length());
                block.addMetadata("isHeader", false);
                blocks.add(block);
            }

            offset += paragraph.length() + 2;
        }

        return blocks;
    }
}
