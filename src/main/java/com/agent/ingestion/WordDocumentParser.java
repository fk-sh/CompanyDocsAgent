package com.agent.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Word 文档解析器（.docx / .doc），使用 Apache POI 提取段落和表格。
 * <p>
 * 解析策略：
 * <ul>
 *   <li>段落按 Word 样式名（style）区分标题与正文：
 *       样式名以 "Heading" 开头 → 转为 Markdown 标题（# ~ ######），层级从样式名中提取数字</li>
 *   <li>表格逐行读取，转为 Markdown 表格格式（\| head \| → \|---\| → \| data \|）</li>
 *   <li>图片、图表等非文本元素当前版本跳过，后续可扩展 VLM 描述</li>
 * </ul>
 * <p>
 * 最终输出是 Markdown 格式的纯文本，与 MarkdownDocumentParser 的输出一致，
 * 下游 ContentExtractor 和 Chunker 可以统一处理。
 */
@Slf4j
@Component
public class WordDocumentParser implements DocumentParser {

    private static final Set<String> SUPPORTED_TYPES = Set.of("docx", "doc");

    @Override
    public boolean supports(String fileType) {
        return fileType != null && SUPPORTED_TYPES.contains(fileType.toLowerCase());
    }

    @Override
    public String parse(Path filePath) throws IOException {
        log.info("Parsing Word document: {}", filePath);
        StringBuilder sb = new StringBuilder();

        try (InputStream is = Files.newInputStream(filePath);
             XWPFDocument document = new XWPFDocument(is)) {

            // === 第 1 步：处理段落 ===
            // 遍历文档中的所有段落
            List<XWPFParagraph> paragraphs = document.getParagraphs();
            for (XWPFParagraph paragraph : paragraphs) {
                String style = paragraph.getStyle();   // Word 样式名，如 "Heading1"、"Normal"
                String text = paragraph.getText(); // 段落文本内容，可能包含换行符

                if (text == null || text.isBlank()) { // 跳过空段落
                    continue;
                }

                // Heading 样式 → Markdown 标题（# 数量 = 标题层级）
                if (style != null && style.startsWith("Heading")) {
                    int level = extractHeadingLevel(style); // 提取标题层级数字
                    sb.append("#".repeat(level > 0 ? level : 1))
                            .append(" ").append(text).append("\n\n");
                } else {
                    sb.append(text).append("\n\n");
                }
            }

            // === 第 2 步：处理表格 ===
            // 遍历文档中的所有表格，转换为 Markdown 表格格式
            List<XWPFTable> tables = document.getTables();
            for (int i = 0; i < tables.size(); i++) {
                XWPFTable table = tables.get(i);
                sb.append(convertTableToMarkdown(table, i + 1)).append("\n\n");
            }
        }

        log.info("Word document parsed: {} characters", sb.length());
        return sb.toString();
    }

    /**
     * 从 Word 样式名中提取标题层级数字。
     * <p>
     * 例如 "Heading1" → 1，"Heading2" → 2。
     * 解析失败时默认返回 1，避免丢失标题语义。
     */
    private int extractHeadingLevel(String style) {
        try {
            return Integer.parseInt(style.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * 将 Apache POI 表格对象转为 Markdown 表格格式。
     * <p>
     * 转换规则：
     * <ul>
     *   <li>表格前加 "### 表格 N" 三级标题标识</li>
     *   <li>第一行视为表头</li>
     *   <li>第二行生成分隔线（|---|---|）</li>
     *   <li>后续行为数据行</li>
     *   <li>单元格内换行符替换为空格，避免破坏 Markdown 表格结构</li>
     * </ul>
     */
    private String convertTableToMarkdown(XWPFTable table, int tableIndex) {
        StringBuilder md = new StringBuilder();
        md.append("\n### 表格 ").append(tableIndex).append("\n\n");

        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) {
            return md.toString();
        }

        // 表头行
        XWPFTableRow headerRow = rows.get(0);
        md.append("| ");
        for (XWPFTableCell cell : headerRow.getTableCells()) {
            md.append(cell.getText().replace("\n", " ")).append(" | ");
        }
        md.append("\n");

        // 分隔行
        md.append("| ");
        for (int j = 0; j < headerRow.getTableCells().size(); j++) {
            md.append("--- | ");
        }
        md.append("\n");

        // 数据行（从第 2 行开始，index=1）
        for (int i = 1; i < rows.size(); i++) {
            XWPFTableRow row = rows.get(i);
            md.append("| ");
            for (XWPFTableCell cell : row.getTableCells()) {
                md.append(cell.getText().replace("\n", " ")).append(" | ");
            }
            md.append("\n");
        }

        return md.toString();
    }
}
