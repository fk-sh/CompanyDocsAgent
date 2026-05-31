package com.agent.upload;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ChunkUploadManager {

    private static final Path TEMP_DIR = Path.of(System.getProperty("java.io.tmpdir"), "agent-chunks");

    private final Map<String, UploadSession> sessions = new ConcurrentHashMap<>();

    private final Map<String, String> fileHashToUploadId = new ConcurrentHashMap<>();

    private static final long SESSION_TTL_MS = 24 * 60 * 60 * 1000;

    public static class UploadSession {
        public final String uploadId;
        public final String fileName;
        public final String fileHash;
        public final long fileSize;
        public final int totalChunks;
        public final int chunkSize;
        public final Path tempFile;
        public final boolean[] receivedChunks;
        public volatile String status;
        public final long createdAt;

        UploadSession(String uploadId, String fileName, String fileHash, long fileSize,
                       int totalChunks, int chunkSize, Path tempFile) {
            this.uploadId = uploadId;
            this.fileName = fileName;
            this.fileHash = fileHash;
            this.fileSize = fileSize;
            this.totalChunks = totalChunks;
            this.chunkSize = chunkSize;
            this.tempFile = tempFile;
            this.receivedChunks = new boolean[totalChunks];
            this.status = "UPLOADING";
            this.createdAt = Instant.now().toEpochMilli();
        }

        int receivedCount() {
            int count = 0;
            for (boolean b : receivedChunks) {
                if (b) count++;
            }
            return count;
        }

        boolean isComplete() {
            return receivedCount() == totalChunks;
        }
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(TEMP_DIR);
    }

    /**
     * 根据 fileHash 查找已有会话或创建新会话。
     * 如果存在匹配且未过期的会话，返回该会话及其已完成的分片列表。
     *
     * @return ResumeInfo 包含 uploadId 和已完成的分片索引列表
     */
    public ResumeInfo findOrCreateSession(String fileHash, String fileName, long fileSize,
                                           int totalChunks, int chunkSize) throws IOException {
        evictExpiredSessions();

        String existingUploadId = fileHashToUploadId.get(fileHash);
        if (existingUploadId != null) {
            UploadSession existing = sessions.get(existingUploadId);
            if (existing != null && existing.fileSize == fileSize && existing.totalChunks == totalChunks) {
                List<Integer> completedChunks = getCompletedChunkIndices(existing);
                log.info("Resume existing session: uploadId={}, file={}, completed={}/{}",
                        existingUploadId, fileName, completedChunks.size(), totalChunks);
                return new ResumeInfo(existingUploadId, false, completedChunks);
            } else {
                fileHashToUploadId.remove(fileHash);
                if (existing != null) {
                    cleanupTempFile(existing.tempFile);
                    sessions.remove(existingUploadId);
                }
            }
        }

        String uploadId = java.util.UUID.randomUUID().toString().replace("-", "");
        Path tempFile = TEMP_DIR.resolve(uploadId + "_" + fileName);
        try (RandomAccessFile raf = new RandomAccessFile(tempFile.toFile(), "rw")) {
            raf.setLength(fileSize);
        }

        UploadSession session = new UploadSession(uploadId, fileName, fileHash, fileSize,
                totalChunks, chunkSize, tempFile);
        sessions.put(uploadId, session);
        fileHashToUploadId.put(fileHash, uploadId);

        log.info("Chunk upload session created: uploadId={}, file={}, totalChunks={}, size={}",
                uploadId, fileName, totalChunks, fileSize);
        return new ResumeInfo(uploadId, true, List.of());
    }

    private List<Integer> getCompletedChunkIndices(UploadSession session) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < session.totalChunks; i++) {
            if (session.receivedChunks[i]) {
                indices.add(i);
            }
        }
        return indices;
    }

    private void evictExpiredSessions() {
        long now = Instant.now().toEpochMilli();
        List<String> toRemove = new ArrayList<>();
        for (UploadSession session : sessions.values()) {
            if (now - session.createdAt > SESSION_TTL_MS) {
                toRemove.add(session.uploadId);
            }
        }
        for (String id : toRemove) {
            UploadSession session = sessions.remove(id);
            if (session != null) {
                fileHashToUploadId.remove(session.fileHash);
                cleanupTempFile(session.tempFile);
            }
        }
        if (!toRemove.isEmpty()) {
            log.info("Evicted {} expired upload sessions", toRemove.size());
        }
    }

    public static class ResumeInfo {
        public final String uploadId;
        public final boolean isNew;
        public final List<Integer> completedChunks;

        ResumeInfo(String uploadId, boolean isNew, List<Integer> completedChunks) {
            this.uploadId = uploadId;
            this.isNew = isNew;
            this.completedChunks = completedChunks;
        }
    }

    // 写入分片数据到临时文件
    public void writeChunk(String uploadId, int chunkIndex, byte[] data) throws IOException {
        UploadSession session = sessions.get(uploadId);//根据 uploadId 查找会话
        if (session == null) {
            throw new IllegalArgumentException("Upload session not found: " + uploadId);
        }
        if (chunkIndex < 0 || chunkIndex >= session.totalChunks) {
            throw new IllegalArgumentException("Invalid chunk index: " + chunkIndex);
        }

        // 计算分片在临时文件中的偏移量
        long offset = (long) chunkIndex * session.chunkSize;
        try (RandomAccessFile raf = new RandomAccessFile(session.tempFile.toFile(), "rw")) {
            raf.seek(offset);//定位到分片在临时文件中的偏移量
            raf.write(data);//写入分片数据
        }

        session.receivedChunks[chunkIndex] = true;//标记分片已接收
        log.debug("Chunk {}/{} written for uploadId={}, progress={}/{}",
                chunkIndex + 1, session.totalChunks, uploadId,
                session.receivedCount(), session.totalChunks);
    }

    public UploadSession getSession(String uploadId) {
        return sessions.get(uploadId);
    }

    public Map<String, Object> getProgress(String uploadId) {
        UploadSession session = sessions.get(uploadId);
        if (session == null) {
            return Map.of("error", "session not found");
        }
        return Map.of(
                "uploadId", session.uploadId,
                "fileName", session.fileName,
                "totalChunks", session.totalChunks,
                "receivedChunks", session.receivedCount(),
                "completedChunks", getCompletedChunkIndices(session),
                "progress", (int) (session.receivedCount() * 100.0 / session.totalChunks),
                "status", session.status,
                "complete", session.isComplete()
        );
    }

    public UploadSession complete(String uploadId) throws IOException {
        UploadSession session = sessions.get(uploadId);
        if (session == null) {
            throw new IllegalArgumentException("Upload session not found: " + uploadId);
        }
        if (!session.isComplete()) {
            throw new IllegalStateException("Upload not complete: " + session.receivedCount()
                    + "/" + session.totalChunks + " chunks received");
        }
        session.status = "MERGING";
        log.info("Chunk upload merge starting: uploadId={}, file={}, chunks={}",
                uploadId, session.fileName, session.totalChunks);
        return session;
    }

    public void removeSession(String uploadId) {
        UploadSession session = sessions.remove(uploadId);
        if (session != null) {
            fileHashToUploadId.remove(session.fileHash);
        }
    }

    public void cleanupTempFile(Path tempFile) {
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException e) {
            log.warn("Failed to cleanup temp file: {}", tempFile, e);
        }
    }
}