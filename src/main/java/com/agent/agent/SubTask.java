package com.agent.agent;

/**
 * 子任务模型，用于多意图拆分场景。
 * <p>
 * RouterAgent 将复合请求拆解为多个 SubTask，每个 SubTask 有独立的
 * intent 和 query，OrchestratorAgent 遍历执行后由 GeneratorAgent 汇总。
 */
public class SubTask {

    private final String intent;
    private final String query;

    public SubTask(String intent, String query) {
        this.intent = intent;
        this.query = query;
    }

    public String intent() {
        return intent;
    }

    public String query() {
        return query;
    }

    @Override
    public String toString() {
        return "SubTask{intent='" + intent + "', query='" + query + "'}";
    }
}
