package com.agent.memory.preference;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class PreferenceDocument {

    private String id;
    private String userId;
    private String content;
    private String category;
    private String key;
    private String value;
    private List<Float> embedding;
    private String createdAt;
    private String updatedAt;

    public PreferenceDocument() {
    }

    public PreferenceDocument(String id, String userId, String content, String category, String key, String value,
                              List<Float> embedding, String createdAt, String updatedAt) {
        this.id = id;
        this.userId = userId;
        this.content = content;
        this.category = category;
        this.key = key;
        this.value = value;
        this.embedding = embedding;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}