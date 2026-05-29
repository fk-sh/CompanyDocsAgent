package com.agent.memory;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class EpisodicMemoryDocument {

    private String id;
    private String sessionId;
    private String summary;
    private List<String> topicTags;
    private List<String> keyEntities;
    private List<String> intentSequence;
    private List<Float> embedding;
    private String createdAt;

    public EpisodicMemoryDocument() {
    }

    public EpisodicMemoryDocument(String id, String sessionId, String summary,
                                   List<String> topicTags, List<String> keyEntities,
                                   List<String> intentSequence, List<Float> embedding,
                                   String createdAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.summary = summary;
        this.topicTags = topicTags;
        this.keyEntities = keyEntities;
        this.intentSequence = intentSequence;
        this.embedding = embedding;
        this.createdAt = createdAt;
    }
}