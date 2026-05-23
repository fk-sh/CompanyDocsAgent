package com.agent.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalReportResponse {
    private String reportId;
    private String status;
    private int totalSamples;
    private int successful;
    private double avgLatencyMs;
    private String createdAt;
}
