package com.agent.ingestion;

import com.agent.llm.DeepSeekChatClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
public class StructuredContentConverter {

    private static final String TABLE_CONVERT_PROMPT = """
            你是一个文档结构化数据转换助手。你的任务是将表格内容转换为流畅的自然语言描述文本。

            转换规则：
            1. 保留表格中的所有关键信息和数据
            2. 使用自然语言描述表格的结构和内容
            3. 对于数值型数据，保留原始数值
            4. 如果表格有标题或表头，先说明表格的主题
            5. 输出应该是连贯的段落，而不是列表形式
            6. 控制输出长度在原表格内容的1-2倍之间，不要过度扩展
            7. 不要添加"根据表格"、"如表所示"等冗余表述

            直接输出转换后的纯文本内容，不要任何前缀或解释。
            """;

    private static final String IMAGE_CONVERT_PROMPT = """
            你是一个文档图片描述增强助手。你的任务是将图片的元数据和基础描述转换为更详细、更有用的自然语言描述。

            增强规则：
            1. 基于提供的图片信息，生成一段清晰、详细的描述
            2. 如果有图表类型的信息（如"图1：架构图"），说明图表的内容和用途
            3. 描述应该有助于理解该图片在文档中的作用
            4. 保持客观、准确的描述风格
            5. 控制输出长度在100-300字之间

            直接输出增强后的描述文本，不要任何前缀或解释。
            """;

    private static final int MAX_CONTENT_LENGTH = 1800;

    private final DeepSeekChatClient llm;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public StructuredContentConverter(DeepSeekChatClient llm) {
        this.llm = llm;
    }

    public List<ContentBlock> convert(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return blocks;
        }

        List<ContentBlock> convertedBlocks = new ArrayList<>();
        List<ConvertTask> tasks = new ArrayList<>();

        for (ContentBlock block : blocks) {
            if (block.getType() == ContentType.TABLE) {
                tasks.add(new ConvertTask(block, ConvertType.TABLE));
            } else if (block.getType() == ContentType.IMAGE_DESCRIPTION) {
                tasks.add(new ConvertTask(block, ConvertType.IMAGE));
            } else {
                convertedBlocks.add(block);
            }
        }

        if (tasks.isEmpty()) {
            return convertedBlocks;
        }

        log.info("StructuredContentConverter: converting {} blocks ({} tables, {} images)",
                tasks.size(),
                tasks.stream().filter(t -> t.type == ConvertType.TABLE).count(),
                tasks.stream().filter(t -> t.type == ConvertType.IMAGE).count());

        List<CompletableFuture<ContentBlock>> futures = new ArrayList<>();
        for (ConvertTask task : tasks) {
            futures.add(CompletableFuture.supplyAsync(() -> convertBlock(task), executor));
        }

        for (CompletableFuture<ContentBlock> future : futures) {
            try {
                ContentBlock converted = future.join();
                if (converted != null && !converted.isEmpty()) {
                    convertedBlocks.add(converted);
                }
            } catch (Exception e) {
                log.warn("StructuredContentConverter: conversion failed, keeping original: {}", e.getMessage());
            }
        }

        return convertedBlocks;
    }

    private ContentBlock convertBlock(ConvertTask task) {
        ContentBlock original = task.block();
        String content = original.getContent();

        if (content == null || content.isBlank()) {
            return original;
        }

        try {
            String convertedText;

            switch (task.type()) {
                case TABLE:
                    convertedText = convertTable(content);
                    break;
                case IMAGE:
                    convertedText = convertImageDescription(content);
                    break;
                default:
                    return original;
            }

            if (convertedText != null && !convertedText.isBlank()) {
                ContentBlock converted = new ContentBlock(ContentType.TEXT, convertedText);
                converted.setStartOffset(original.getStartOffset());
                converted.setEndOffset(original.getStartOffset() + convertedText.length());
                converted.setSectionTitle(original.getSectionTitle());
                converted.setSectionLevel(original.getSectionLevel());
                converted.setPageNumber(original.getPageNumber());
                converted.addMetadata("originalType", original.getType().name());
                converted.addMetadata("convertedByLLM", true);

                log.debug("Converted {} block: {} chars → {} chars (section: {})",
                        task.type(), content.length(), convertedText.length(), original.getSectionTitle());

                return converted;
            }
        } catch (Exception e) {
            log.warn("Failed to convert {} block in section '{}': {}", 
                    task.type(), original.getSectionTitle(), e.getMessage());
        }

        return original;
    }

    private String convertTable(String tableContent) {
        String truncated = truncateContent(tableContent);
        String prompt = "请将以下表格内容转换为自然语言描述：\n\n" + truncated;

        String result = llm.chat(TABLE_CONVERT_PROMPT, prompt);

        if (result != null && !result.isBlank()) {
            result = result.trim();
            if (result.length() > MAX_CONTENT_LENGTH) {
                result = result.substring(0, MAX_CONTENT_LENGTH) + "...";
            }
            return result;
        }

        return tableContent;
    }

    private String convertImageDescription(String imageContent) {
        String truncated = truncateContent(imageContent);
        String prompt = "请将以下图片描述信息转换为更详细的自然语言描述：\n\n" + truncated;

        String result = llm.chat(IMAGE_CONVERT_PROMPT, prompt);

        if (result != null && !result.isBlank()) {
            result = result.trim();
            if (result.length() > MAX_CONTENT_LENGTH) {
                result = result.substring(0, MAX_CONTENT_LENGTH) + "...";
            }
            return result;
        }

        return imageContent;
    }

    private String truncateContent(String content) {
        if (content.length() <= MAX_CONTENT_LENGTH) {
            return content;
        }
        return content.substring(0, MAX_CONTENT_LENGTH) + "\n...(内容已截断)";
    }

    private enum ConvertType {
        TABLE, IMAGE
    }

    private record ConvertTask(ContentBlock block, ConvertType type) {}

}