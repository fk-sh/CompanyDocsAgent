package com.agent.memory.preference;

public class PreferenceItem {
    public String content;
    public String category;
    public String key;
    public String value;
    public String action;

    public PreferenceItem() {
    }

    public PreferenceItem(String content, String category, String key, String value, String action) {
        this.content = content;
        this.category = category;
        this.key = key;
        this.value = value;
        this.action = action;
    }

    @Override
    public String toString() {
        return content;
    }
}