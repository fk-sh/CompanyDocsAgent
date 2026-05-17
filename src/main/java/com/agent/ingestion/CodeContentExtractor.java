package com.agent.ingestion;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码块提取器，从全文本中识别 Markdown 格式的围栏代码块（{@code ```}）。
 * <p>
 * 正则 {@code ```(\w*)\n([\s\S]*?)```} 匹配：
 * <ul>
 *   <li>group(1)：语言标签（如 java、python、sql），可选，为空时记为 "plain"</li>
 *   <li>group(2)：代码内容，使用非贪婪匹配 + DOTALL 模式跨行</li>
 * </ul>
 * <p>
 * 注意：只匹配一次，可识别文档中所有不重叠的代码块。
 */
@Component
public class CodeContentExtractor implements ContentExtractor {

    /** 匹配围栏代码块：```语言\n代码内容```，[\s\S]*? 实现跨行非贪婪匹配 */
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
            "```(\\w*)\\n([\\s\\S]*?)```",
            Pattern.MULTILINE);

    /**
     * 返回此提取器支持的类型。
     */
    @Override
    public ContentType supportedType() {
        return ContentType.CODE;
    }

    @Override
    public List<ContentBlock> extract(String fullText, List<ContentBlock> textBlocks) {
        List<ContentBlock> codeBlocks = new ArrayList<>();

        Matcher matcher = CODE_BLOCK_PATTERN.matcher(fullText);

        while (matcher.find()) { // 遍历所有匹配的代码块
            String language = matcher.group(1).trim();   // 语言标签
            String code = matcher.group(2).trim();        // 代码内容
            int start = matcher.start();                  // 在原文中的起始偏移

            // 根据代码块位置，向前查找所属章节标题
            String sectionTitle = findCurrentSectionTitle(textBlocks, start);

            ContentBlock block = new ContentBlock(ContentType.CODE, code);
            block.setStartOffset(start);
            block.setEndOffset(matcher.end());
            block.setSectionTitle(sectionTitle);
            block.addMetadata("language", language.isEmpty() ? "plain" : language);

            codeBlocks.add(block);
        }

        return codeBlocks;
    }

    /**
     * 根据字符偏移位置，向前查找最近的章节标题。
     * 遍历 TEXT 提取器产出的所有标题块，找到 startOffset <= position 且 isHeader=true 的最大块。
     */
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
