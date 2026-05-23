package com.agent.prompt;

import java.util.List;
import java.util.Map;

/**
 * Prompt 模板，封装一段可参数化的提示词。
 * <p>
 * 模板包含带占位符（{name}）的模板文本和变量定义列表，
 * 通过 {@link #render(Map)} 将占位符替换为实际值后输出完整 Prompt。
 */
public class PromptTemplate {

    private final String name;
    private final String description;
    private final String template;

    public PromptTemplate(String name, String description, String template) {
        this.name = name;
        this.description = description;
        this.template = template;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public String template() {
        return template;
    }

    /**
     * 渲染模板，将 {key} 占位符替换为 variables 中的对应值。
     *
     * @param variables 变量映射表
     * @return 渲染后的完整 Prompt
     */
    public String render(Map<String, String> variables) {
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    /**
     * 使用列表顺序渲染模板，按 variables 顺序依次替换 {0}、{1} ...
     *
     * @param variables 按顺序排列的变量值列表
     * @return 渲染后的完整 Prompt
     */
    public String render(List<String> variables) {
        String result = template;
        for (int i = 0; i < variables.size(); i++) {
            result = result.replace("{" + i + "}", variables.get(i));
        }
        return result;
    }
}
