package com.agent.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentResponse {
    private String documentId;
    private String fileName;
    private String status;
    private String taskId;
    private long size;
    private String createdAt;
    private String uploaderId;
    private String uploaderName;
    private String department;
    private String visibility;
    private Integer chunkCount;
}
