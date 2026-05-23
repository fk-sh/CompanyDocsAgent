package com.agent.agent;

import com.agent.core.Agent;
import com.agent.core.AgentContext;
import com.agent.core.Document;
import com.agent.ingestion.FullIngestionPipeline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 文档摄入 Agent，触发离线文档摄入管道。
 * <p>
 * 执行模式：固定管道（解析 → 提取 → 切割 → 向量化 → ES 存储），异步执行。
 * 仅在 {@link FullIngestionPipeline} Bean 存在时激活（ES 可用时）。
 * <p>
 * 输入（ctx 读取）：filePath
 * 输出（ctx 写入）：taskId, ingestionStatus
 */
@Slf4j
@Component("ingestionAgent")
@ConditionalOnBean(FullIngestionPipeline.class)
public class IngestionAgent implements Agent {

    private final FullIngestionPipeline ingestionPipeline;

    public IngestionAgent(FullIngestionPipeline ingestionPipeline) {
        this.ingestionPipeline = ingestionPipeline;
    }

    @Override
    public String name() {
        return "ingestion";
    }

    @Override
    public String execute(AgentContext ctx) {
        String filePath = ctx.getVariable("filePath");
        if (filePath == null || filePath.isEmpty()) {
            log.warn("IngestionAgent: no filePath provided");
            ctx.setVariable("taskId", "");
            ctx.setVariable("ingestionStatus", "FAILED: no file path");
            return "FAILED";
        }

        String taskId = UUID.randomUUID().toString().substring(0, 8);
        ctx.setVariable("taskId", taskId);
        ctx.setVariable("ingestionStatus", "PROCESSING");
        log.info("IngestionAgent started task {} for file: {}", taskId, filePath);

        try {
            Document document = ingestionPipeline.ingestToEs(Path.of(filePath));//触发文档摄入管道
            ctx.setVariable("ingestionStatus", "READY");
            ctx.setVariable("documentId", document.getId());
            log.info("IngestionAgent task {} completed, document: {}", taskId, document.getId());
        } catch (IOException e) {
            log.error("IngestionAgent task {} failed: {}", taskId, e.getMessage());
            ctx.setVariable("ingestionStatus", "FAILED: " + e.getMessage());
        }

        return taskId;
    }
}
