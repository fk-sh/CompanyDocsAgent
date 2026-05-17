package com.agent.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/**
 * PDF 文档解析器，使用 Apache PDFBox 提取文本。
 * <p>
 * 解析策略：
 * <ul>
 *   <li>按页面物理位置排序（setSortByPosition），而非 PDF 内部书写顺序</li>
 *   <li>启用附加格式输出（setAddMoreFormatting），在段落、标题等元素间增加额外间距，
 *       改善下游段落分割准确性</li>
 *   <li>支持 .pdf 扩展名</li>
 * </ul>
 * <p>
 * 注意：PDFBox 的文本提取对表格和多栏布局支持有限，复杂排版场景建议结合 Tika 或 OCR。
 */
@Slf4j
@Component
public class PdfDocumentParser implements DocumentParser {

    private static final Set<String> SUPPORTED_TYPES = Set.of("pdf");

    @Override
    public boolean supports(String fileType) {
        return fileType != null && SUPPORTED_TYPES.contains(fileType.toLowerCase());
    }

    @Override
    public String parse(Path filePath) throws IOException {
        log.info("Parsing PDF: {}", filePath);

        // 使用 PDFBox 3.x 的 Loader.loadPDF 方法加载 PDF 文件
        // 该方法替代了 PDFBox 2.x 中的 PDDocument.load 静态方法
        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            // 创建 PDF 文本提取器实例，它解析 PDF 的内容流并提取文本内容
            PDFTextStripper stripper = new PDFTextStripper();
            
            // 设置按页面物理位置排序，而非 PDF 内部书写顺序
            // 这样可以确保提取的文本顺序与阅读顺序一致，避免文字乱序
            stripper.setSortByPosition(true);
            
            // 启用附加格式输出，在段落、标题等元素之间增加额外间距（空行）
            // 这有助于下游的段落分割处理，提高文本分割的准确性
            stripper.setAddMoreFormatting(true);

            // 从 PDF 文档中提取全部文本内容
            String text = stripper.getText(document);
            
            // 记录解析结果：页数和字符数
            log.info("PDF parsed: {} pages, {} characters", document.getNumberOfPages(), text.length());
            
            //上边对文本提取器进行设置，这里才是正式的提取文本内容
            // 返回提取的纯文本内容
            return text;
        }
        // try-with-resources 语句确保 PDDocument 被自动关闭
        // 因为 PDDocument 实现了 AutoCloseable 接口
    }
}
