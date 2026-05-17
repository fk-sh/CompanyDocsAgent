package com.agent.ingestion;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 图片描述提取器，从全文本中识别以特定标记开头的图片描述段落。
 * <p>
 * 识别规则：行内容包含 {@code [图*}、{@code [image*}、{@code 图 }、{@code figure}
 * 等关键词时，将该行及其后续连续非空行视为图片描述块，直到遇到空行或识别到新的图片标记。
 * <p>
 * 当前版本通过关键词启发式匹配——因为 PDF 中图片被提取时通常表现为
 * "[图 1]"、"[image: chart]" 等文本占位符。后续 Phase 会接入 VLM（Qwen2-VL-7B）
 * 对原始图片文件生成描述，替换这些占位符内容。
 * <p>
 * 注意：该提取器依赖 TEXT 提取器先执行以获取章节标题上下文。
 */
@Component
public class ImageDescriptionExtractor implements ContentExtractor {

    @Override
    public ContentType supportedType() {
        return ContentType.IMAGE_DESCRIPTION;
    }

    @Override
    public List<ContentBlock> extract(String fullText, List<ContentBlock> textBlocks) {
        List<ContentBlock> imageBlocks = new ArrayList<>();

        String[] lines = fullText.split("\n");
        StringBuilder currentDescription = new StringBuilder();
        String currentSection = "";
        int startOffset = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // 检测图片标记行
            if (isImageMarker(line)) {
                // 如果之前有未完成的图片描述，先保存
                if (currentDescription.length() > 0) {
                    ContentBlock block = createImageBlock(
                            currentDescription.toString(),
                            startOffset,
                            startOffset + currentDescription.length(),
                            currentSection);
                    imageBlocks.add(block);
                }

                // 开始新的图片描述
                currentDescription = new StringBuilder(line);
                startOffset = findOffset(fullText, i, lines);    // 计算该行在原文中的偏移
                currentSection = findCurrentSectionTitle(textBlocks, startOffset);
            } else if (currentDescription.length() > 0) {
                if (line.trim().isEmpty()) {
                    // 空行：当前图片描述结束
                    ContentBlock block = createImageBlock(
                            currentDescription.toString(),
                            startOffset,
                            startOffset + currentDescription.length(),
                            currentSection);
                    imageBlocks.add(block);
                    currentDescription = new StringBuilder();
                } else {
                    // 继续追加到当前图片描述
                    currentDescription.append("\n").append(line);
                }
            }
        }

        // 处理文件末尾最后一段图片描述
        if (currentDescription.length() > 0) {
            ContentBlock block = createImageBlock(
                    currentDescription.toString(),
                    startOffset,
                    startOffset + currentDescription.length(),
                    currentSection);
            imageBlocks.add(block);
        }

        return imageBlocks;
    }

    /** 判断一行是否包含图片标记关键词（不区分大小写） */
    private boolean isImageMarker(String line) {
        String lower = line.toLowerCase();
        return lower.startsWith("[图") || lower.startsWith("[image")
                || lower.contains("图 ") || lower.contains("figure");
    }

    /** 构建 IMAGE_DESCRIPTION 类型的 ContentBlock */
    private ContentBlock createImageBlock(String content, int start, int end, String sectionTitle) {
        ContentBlock block = new ContentBlock(ContentType.IMAGE_DESCRIPTION, content);
        block.setStartOffset(start);
        block.setEndOffset(end);
        block.setSectionTitle(sectionTitle);
        return block;
    }

    /** 根据行号计算该行在原文中的字符偏移 */
    private int findOffset(String fullText, int lineIndex, String[] lines) {
        int offset = 0;
        for (int i = 0; i < lineIndex; i++) {
            offset += lines[i].length() + 1;  // +1 补偿换行符
        }
        return offset;
    }

    /** 根据字符偏移向前查找最近的章节标题 */
    private String findCurrentSectionTitle(List<ContentBlock> textBlocks, int position) {
        String title = "";
        for (ContentBlock block : textBlocks) {
            if (block.getStartOffset() <= position
                    && Boolean.TRUE.equals(block.getMetadata().get("isHeader"))) {
                title = block.getSectionTitle();
            }
        }
        return title;
    }
}
