package com.agent.document;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("documents")
public class DocumentEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField("file_name")
    private String fileName;

    @TableField("file_type")
    private String fileType;

    @TableField("file_size")
    private Long fileSize;

    @TableField("file_path")
    private String filePath;

    @TableField("uploader_id")
    private String uploaderId;

    @TableField("uploader_name")
    private String uploaderName;

    private String department;

    private String visibility;

    private String status;

    @TableField("chunk_count")
    private Integer chunkCount;

    @TableField("task_id")
    private String taskId;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
