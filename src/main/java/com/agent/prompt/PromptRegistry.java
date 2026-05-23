package com.agent.prompt;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 模板注册中心，集中管理所有 PromptTemplate。
 * <p>
 * 各 Agent 在初始化时将自身需要的模板注册到此中心，
 * 后续可通过模板名称查找并渲染。
 */
public class PromptRegistry {

    private final Map<String, PromptTemplate> templates = new ConcurrentHashMap<>();

    public void register(PromptTemplate template) {
        templates.put(template.name(), template);
    }

    public void registerAll(PromptTemplate... templates) {
        for (PromptTemplate t : templates) {
            register(t);
        }
    }

    public Optional<PromptTemplate> get(String name) {
        return Optional.ofNullable(templates.get(name));
    }

    /**
     * 获取模板并渲染。
     *
     * @param name      模板名称
     * @param variables 变量映射
     * @return 渲染后的 Prompt 字符串
     * @throws IllegalArgumentException 模板不存在时
     */
    public String render(String name, Map<String, String> variables) {
        PromptTemplate template = get(name)
                .orElseThrow(() -> new IllegalArgumentException("Prompt template not found: " + name));
        return template.render(variables);
    }

    public Map<String, PromptTemplate> allTemplates() {
        return Collections.unmodifiableMap(templates);
    }

    public void clear() {
        templates.clear();
    }

    public int size() {
        return templates.size();
    }
}
