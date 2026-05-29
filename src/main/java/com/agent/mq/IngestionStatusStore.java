package com.agent.mq;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class IngestionStatusStore {

    private final Map<String, Map<String, Object>> store = new ConcurrentHashMap<>();

    public void create(String documentId, Map<String, Object> info) {
        store.put(documentId, info);
    }

    public void update(String documentId, String status, Integer chunkCount) {
        Map<String, Object> info = store.computeIfAbsent(documentId, k -> new ConcurrentHashMap<>());
        info.put("status", status);
        if (chunkCount != null) {
            info.put("chunkCount", chunkCount);
        }
        info.put("updatedAt", Instant.now().toEpochMilli());
    }

    @SuppressWarnings("unchecked")
    public void recordRetryAttempt(String documentId, int retryCount, String reason) {
        Map<String, Object> info = store.computeIfAbsent(documentId, k -> new ConcurrentHashMap<>());
        info.put("lastRetryCount", retryCount);
        info.put("lastFailureReason", reason);
        info.put("lastFailedAt", Instant.now().toEpochMilli());

        List<Map<String, Object>> history = (List<Map<String, Object>>) info
                .computeIfAbsent("failureHistory", k -> new ArrayList<>());
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("retryCount", retryCount);
        entry.put("reason", reason);
        entry.put("timestamp", Instant.now().toEpochMilli());
        history.add(entry);
    }

    public void markDeadLettered(String documentId, String reason) {
        Map<String, Object> info = store.computeIfAbsent(documentId, k -> new ConcurrentHashMap<>());
        info.put("status", "DEAD_LETTERED");
        info.put("deadLetterReason", reason);
        info.put("deadLetteredAt", Instant.now().toEpochMilli());
        info.put("updatedAt", Instant.now().toEpochMilli());
    }

    public void resetForRetry(String documentId) {
        Map<String, Object> info = store.get(documentId);
        if (info != null) {
            info.remove("deadLetterReason");
            info.remove("deadLetteredAt");
            info.remove("failureHistory");
            info.remove("lastRetryCount");
            info.remove("lastFailureReason");
            info.remove("lastFailedAt");
            info.put("status", "RETRYING");
            info.put("retrySubmittedAt", Instant.now().toEpochMilli());
            info.put("updatedAt", Instant.now().toEpochMilli());
        }
    }

    public List<Map<String, Object>> findDeadLettered() {
        return store.values().stream()
                .filter(info -> "DEAD_LETTERED".equals(info.get("status")))
                .toList();
    }

    public Map<String, Object> get(String documentId) {
        return store.get(documentId);
    }

    public List<Map<String, Object>> all() {
        return List.copyOf(store.values());
    }

    public Map<String, Object> remove(String documentId) {
        return store.remove(documentId);
    }

    public int size() {
        return store.size();
    }
}