package com.agent.document;

import com.agent.auth.CurrentUser;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class DocumentService {

    private final DocumentMapper documentMapper;

    public DocumentService(DocumentMapper documentMapper) {
        this.documentMapper = documentMapper;
    }

    // 创建处理中的文档实体
    public DocumentEntity createProcessing(String documentId, String taskId, String fileName, String fileType,
                                           long fileSize, String filePath, CurrentUser user,
                                           DocumentVisibility visibility) {
        DocumentEntity entity = new DocumentEntity();
        entity.setId(documentId);
        entity.setTaskId(taskId);
        entity.setFileName(fileName);
        entity.setFileType(fileType);
        entity.setFileSize(fileSize);
        entity.setFilePath(filePath);
        entity.setUploaderId(user.getId());
        entity.setUploaderName(user.getName());
        entity.setDepartment(user.getDepartment());
        entity.setVisibility(visibility.name());
        entity.setStatus(ManagedDocumentStatus.PROCESSING.name());
        entity.setChunkCount(0);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        documentMapper.insert(entity);
        return entity;
    }

    public DocumentEntity findById(String id) {
        return documentMapper.selectById(id);
    }

    public List<DocumentEntity> listMine(String userId, int limit, int offset) {
        return documentMapper.selectList(new LambdaQueryWrapper<DocumentEntity>()
                .eq(DocumentEntity::getUploaderId, userId)
                .ne(DocumentEntity::getStatus, ManagedDocumentStatus.DELETED.name())
                .orderByDesc(DocumentEntity::getCreatedAt)
                .last("LIMIT " + Math.max(1, limit) + " OFFSET " + Math.max(0, offset)));
    }

    public List<DocumentEntity> listAll(int limit, int offset) {
        return documentMapper.selectList(new LambdaQueryWrapper<DocumentEntity>()
                .ne(DocumentEntity::getStatus, ManagedDocumentStatus.DELETED.name())
                .orderByDesc(DocumentEntity::getCreatedAt)
                .last("LIMIT " + Math.max(1, limit) + " OFFSET " + Math.max(0, offset)));
    }

    // 更新文档状态
    public void updateStatus(String id, ManagedDocumentStatus status, Integer chunkCount) {
        DocumentEntity entity = new DocumentEntity();
        entity.setId(id);
        entity.setStatus(status.name());
        entity.setUpdatedAt(LocalDateTime.now());
        if (chunkCount != null) {
            entity.setChunkCount(chunkCount);// 更新已处理块数
        } 
        documentMapper.updateById(entity);// 更新文档实体
    }
}
