package com.agent.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.ZeroByteFileException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 基于 Apache Tika 的统一文档解析器。
 * <p>
 * 使用 Tika 的 {@link AutoDetectParser} 自动识别文件 MIME 类型并匹配对应解析器，
 * 统一处理 PDF、Word、Markdown、纯文本、图片等各类文档格式。
 * <p>
 * 支持的文档格式：
 * <ul>
 *   <li>PDF (.pdf)</li>
 *   <li>Microsoft Office (.docx, .doc, .pptx, .ppt, .xlsx, .xls)</li>
 *   <li>Markdown / 纯文本 (.md, .markdown, .txt, .text)</li>
 *   <li>HTML / XML (.html, .htm, .xml)</li>
 *   <li>图片 (.png, .jpg, .jpeg, .gif, .bmp, .tiff, .tif, .webp)</li>
 *   <li>RTF (.rtf)</li>
 *   <li>OpenDocument (.odt, .ods, .odp)</li>
 *   <li>EPUB (.epub)</li>
 *   <li>CSV (.csv)</li>
 * </ul>
 * <p>
 * 对于图片文件，Tika 提取嵌入的元数据（EXIF、IPTC 等）作为文本内容。
 * 如果没有嵌入元数据，则使用文件名作为最小文本描述，确保图片可被向量化入库。
 */
@Slf4j
@Component
public class TikaDocumentParser implements DocumentParser {

    private static final int WRITE_LIMIT_MB = 100;
    private static final int WRITE_LIMIT = WRITE_LIMIT_MB * 1024 * 1024;

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "tiff", "tif", "webp"
    );

    private static final Set<String> SUPPORTED_TYPES = buildSupportedTypes();

    private static Set<String> buildSupportedTypes() {
        Set<String> types = new LinkedHashSet<>();
        types.add("pdf");
        types.add("docx");
        types.add("doc");
        types.add("pptx");
        types.add("ppt");
        types.add("xlsx");
        types.add("xls");
        types.add("md");
        types.add("markdown");
        types.add("txt");
        types.add("text");
        types.add("html");
        types.add("htm");
        types.add("xml");
        types.add("rtf");
        types.add("odt");
        types.add("ods");
        types.add("odp");
        types.add("epub");
        types.add("csv");
        types.addAll(IMAGE_EXTENSIONS);
        return Set.copyOf(types);
    }

    private final Tika tika;
    private final Parser parser;

    public TikaDocumentParser() {
        this.tika = new Tika();
        this.parser = new AutoDetectParser();
    }

    @Override
    public boolean supports(String fileType) {
        return fileType != null && SUPPORTED_TYPES.contains(fileType.toLowerCase());
    }

    @Override
    public String parse(Path filePath) throws IOException {
        String fileName = filePath.getFileName().toString();
        String fileType = extractFileType(fileName);
        log.info("Parsing document with Tika: {} (type={})", fileName, fileType);

        BodyContentHandler handler = new BodyContentHandler(WRITE_LIMIT);
        Metadata metadata = new Metadata();
        metadata.set("resourceName", fileName);

        try (InputStream is = Files.newInputStream(filePath)) {
            ParseContext context = new ParseContext();
            parser.parse(is, handler, metadata, context);

            String text = handler.toString().trim();
            String detectedType = tika.detect(filePath);

            if (text.isEmpty() && isImageFile(fileType)) {
                text = buildImageMetadataText(fileName, metadata);
            }

            log.info("Tika parsed {}: detected type={}, {} characters",
                    fileName, detectedType, text.length());
            return text;
        } catch (ZeroByteFileException e) {
            log.warn("Tika parsed empty file: {}", fileName);
            return "";
        } catch (SAXException | TikaException e) {
            throw new IOException("Tika failed to parse: " + fileName, e);
        }
    }

    private static String extractFileType(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex < 0 ? "" : fileName.substring(dotIndex + 1).toLowerCase();
    }

    private static boolean isImageFile(String fileType) {
        return IMAGE_EXTENSIONS.contains(fileType);
    }

    private static String buildImageMetadataText(String fileName, Metadata metadata) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Image] ").append(fileName);

        appendIfPresent(sb, metadata, "Image Width", "Width");
        appendIfPresent(sb, metadata, "Image Height", "Height");
        appendIfPresent(sb, metadata, "Content-Type", "ContentType");

        String date = firstNonBlank(metadata,
                "Date/Time Original", "Original Date", "Creation-Date", "dcterms:created");
        appendIfValue(sb, "Date", date);

        String camera = firstNonBlank(metadata,
                "Model", "Make", "Camera Model");
        appendIfValue(sb, "Camera", camera);

        String location = firstNonBlank(metadata,
                "GPS Latitude", "geo:lat", "Location");
        appendIfValue(sb, "Location", location);

        String description = firstNonBlank(metadata,
                "Description", "Image Description", "Caption", "Title", "dc:title", "dc:description", "Comment");
        appendIfValue(sb, "Description", description);

        if (sb.length() == ("[Image] " + fileName).length()) {
            sb.append("\nDescription: ").append(fileName);
        }

        return sb.toString();
    }

    private static void appendIfPresent(StringBuilder sb, Metadata metadata, String... keys) {
        for (String key : keys) {
            String value = metadata.get(key);
            if (value != null && !value.isBlank()) {
                sb.append("\n").append(key.split(" ")[0]).append(": ").append(value.trim());
                return;
            }
        }
    }

    private static void appendIfValue(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append("\n").append(label).append(": ").append(value.trim());
        }
    }

    private static String firstNonBlank(Metadata metadata, String... keys) {
        for (String key : keys) {
            String value = metadata.get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}