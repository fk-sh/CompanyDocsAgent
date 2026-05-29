package com.agent.agent;

import java.util.List;

public class Plan {

    private String intent;
    private List<String> retrievalQueries;
    private String weatherCity;
    private boolean needsRetrieval;
    private boolean isChitchat;
    private List<SubPlan> subPlans;

    public Plan() {}

    public static Plan knowledgeQa(List<String> retrievalQueries) {
        Plan p = new Plan();
        p.intent = "knowledge_qa";
        p.retrievalQueries = retrievalQueries;
        p.needsRetrieval = true;
        return p;
    }

    public static Plan chitchat() {
        Plan p = new Plan();
        p.intent = "chitchat";
        p.isChitchat = true;
        return p;
    }

    public static Plan weather(String city) {
        Plan p = new Plan();
        p.intent = "weather";
        p.weatherCity = city;
        return p;
    }

    public static Plan multiIntent(List<SubPlan> subPlans) {
        Plan p = new Plan();
        p.intent = "multi_intent";
        p.subPlans = subPlans;
        p.needsRetrieval = subPlans.stream().anyMatch(SubPlan::needsRetrieval);
        return p;
    }

    public String intent() { return intent; }
    public List<String> retrievalQueries() { return retrievalQueries; }
    public String weatherCity() { return weatherCity; }
    public boolean needsRetrieval() { return needsRetrieval; }
    public boolean isChitchat() { return isChitchat; }
    public List<SubPlan> subPlans() { return subPlans; }
    public boolean isMultiIntent() { return "multi_intent".equals(intent); }

    public void setIntent(String intent) { this.intent = intent; }
    public void setRetrievalQueries(List<String> retrievalQueries) { this.retrievalQueries = retrievalQueries; }
    public void setWeatherCity(String weatherCity) { this.weatherCity = weatherCity; }
    public void setNeedsRetrieval(boolean needsRetrieval) { this.needsRetrieval = needsRetrieval; }
    public void setChitchat(boolean chitchat) { isChitchat = chitchat; }
    public void setSubPlans(List<SubPlan> subPlans) { this.subPlans = subPlans; }

    public static class SubPlan {
        private String intent;
        private String query;
        private List<String> retrievalQueries;
        private String weatherCity;
        private boolean needsRetrieval;
        private boolean isChitchat;

        public SubPlan() {}

        public SubPlan(String intent, String query, List<String> retrievalQueries,
                       String weatherCity, boolean needsRetrieval, boolean isChitchat) {
            this.intent = intent;
            this.query = query;
            this.retrievalQueries = retrievalQueries;
            this.weatherCity = weatherCity;
            this.needsRetrieval = needsRetrieval;
            this.isChitchat = isChitchat;
        }

        public String intent() { return intent; }
        public String query() { return query; }
        public List<String> retrievalQueries() { return retrievalQueries; }
        public String weatherCity() { return weatherCity; }
        public boolean needsRetrieval() { return needsRetrieval; }
        public boolean isChitchat() { return isChitchat; }
    }
}
