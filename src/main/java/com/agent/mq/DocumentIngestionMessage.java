package com.agent.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentIngestionMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private String documentId;

    private String taskId;

    private String fileName;

    private String fileType;

    private String filePath;

    private long fileSize;

    private long createdAt;

    @Builder.Default
    private int retryCount = 0;


    public DocumentIngestionMessage withIncrementedRetry() {
        return DocumentIngestionMessage.builder()
                .documentId(this.documentId)
                .taskId(this.taskId)
                .fileName(this.fileName)
                .fileType(this.fileType)
                .filePath(this.filePath)
                .fileSize(this.fileSize)
                .createdAt(this.createdAt)
                .retryCount(this.retryCount + 1)
                .build();
    }
}