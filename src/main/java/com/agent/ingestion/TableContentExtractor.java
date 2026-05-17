package com.agent.ingestion;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 表格内容提取器，从全文本中逐行扫描识别 Markdown 表格。
 * <p>
 * 识别算法：
 * <ol>
 *   <li>逐行扫描全文本，判断每行是否以 {@code |} 开头（{@link #isTableRow}）</li>
 *   <li>连续多行以 {@code |} 开头者暂存为候选表格</li>
 *   <li>检查候选表格的第二行是否为分隔行（如 {@code |---|---|---|}），
 *       满足条件则确认为一个完整的 Markdown 表格（{@link #isCompleteTable}）</li>
 *   <li>跳过只有一行或第二行不是分隔行的伪表格（如引用中出现的 {@code |} 字符）</li>
 * </ol>
 * <p>
 * 注意：只识别标准的 Markdown 表格格式（表格行 + 分隔行 + 数据行），
 * 不处理 HTML {@code <table>} 标签。
 */
@Component
public class TableContentExtractor implements ContentExtractor {

    @Override
    public ContentType supportedType() {
        return ContentType.TABLE;
    }

    @Override
    public List<ContentBlock> extract(String fullText, List<ContentBlock> textBlocks) {
        List<ContentBlock> tables = new ArrayList<>();

        // \R 匹配任意行分隔符（\n、\r\n、\r），跨平台兼容
        String[] lines = fullText.split("\\R");

        List<String> currentTable = new ArrayList<>();   // 暂存连续以 | 开头的行
        int tableStartOffset = -1;                       // 当前候选表格在原文的起始偏移
        int currentOffset = 0;                           // 当前扫描到的字符偏移

        for (String line : lines) { // 逐行扫描全文本
            String trimmed = line.trim();
            int lineLength = line.length() + 1;  // +1 补偿行分隔符

            if (isTableRow(trimmed)) {
                // 表格行：暂存到候选列表
                if (currentTable.isEmpty()) {
                    tableStartOffset = currentOffset;  // 记录表格起始位置
                }
                currentTable.add(line);
            } else {
                // 非表格行：检查缓存是否构成完整表格
                if (isCompleteTable(currentTable)) {
                    tables.add(buildTableBlock(currentTable, tableStartOffset, textBlocks));
                }
                currentTable.clear();
            }

            currentOffset += lineLength;
        }

        // 文件末尾如果还有未处理的表格
        if (isCompleteTable(currentTable)) {
            tables.add(buildTableBlock(currentTable, tableStartOffset, textBlocks));
        }

        return tables;
    }

    /** 判断一行是否为表格行（以 | 开头且包含表格式结构） */
    private boolean isTableRow(String line) {
        return line.startsWith("|") && (line.endsWith("|") || line.contains("|"));
    }

    /**
     * 判断候选行列表是否构成一个完整的 Markdown 表格。
     * 至少需要 2 行，且第 2 行必须是分隔行（如 |---|:---:|---|）。
     */
    private boolean isCompleteTable(List<String> lines) {
        if (lines.size() < 2) {
            return false;
        }
        return isSeparatorRow(lines.get(1).trim());
    }

    /**
     * 判断一行是否为 Markdown 表格分隔行。
     * 分隔行特征：以 | 开头，去除所有 - 和 : 后为空（每个单元格只包含 --- 和可选的 : 对齐标记）。
     *
     * @param line 如 "| ---- | :---: | --- |"
     */
    private boolean isSeparatorRow(String line) {
        if (!line.startsWith("|")) {
            return false;
        }
        // 去掉两端 |，按 | 拆分单元格
        String inner = line.substring(1, line.length() - 1);
        for (String cell : inner.split("\\|")) {
            // 去掉 - 和 : 后如果还有内容，说明不是纯分隔行
            String cleaned = cell.trim().replaceAll("[-:]+", "");
            if (!cleaned.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** 构建一个 Table 类型的 ContentBlock */
    private ContentBlock buildTableBlock(List<String> tableLines, int startOffset,
                                         List<ContentBlock> textBlocks) {
        // 用 \n 拼接，保持 Markdown 表格格式
        String tableContent = String.join("\n", tableLines);
        // 查找该表格所属的章节标题
        String sectionTitle = findCurrentSectionTitle(textBlocks, startOffset);

        ContentBlock block = new ContentBlock(ContentType.TABLE, tableContent);
        block.setStartOffset(startOffset);
        block.setEndOffset(startOffset + tableContent.length());
        block.setSectionTitle(sectionTitle);
        block.addMetadata("rowCount", tableLines.size() - 1);  // 减 1 去掉分隔行

        return block;
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
