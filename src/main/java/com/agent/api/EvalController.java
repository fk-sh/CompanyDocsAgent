package com.agent.api;

import com.agent.api.dto.EvalReportResponse;
import com.agent.api.dto.EvalRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1")
public class EvalController {

    @PostMapping("/eval/run")
    public Map<String, Object> runEval(@RequestBody(required = false) EvalRequest request) {
        String reportId = UUID.randomUUID().toString().substring(0, 8);
        String datasetPath = request != null ? request.getDatasetPath() : "default";

        log.info("POST /eval/run reportId={}, datasetPath={}", reportId, datasetPath);

        log.info("Eval report {} submitted. Run eval script manually: python docs/eval/ragas_eval.py", reportId);

        return Map.of(
                "reportId", reportId,
                "status", "SUBMITTED",
                "datasetPath", datasetPath,
                "message", "评测任务已提交，请运行 Python 评测脚本获取完整报告: docs/eval/ragas_eval.py"
        );
    }

    @GetMapping("/eval/reports")
    public java.util.List<EvalReportResponse> listReports() {
        log.info("GET /eval/reports");
        return java.util.List.of(
                EvalReportResponse.builder()
                        .reportId("latest")
                        .status("查看 docs/eval/eval_report.md")
                        .totalSamples(0)
                        .successful(0)
                        .avgLatencyMs(0)
                        .createdAt("运行 ragas_eval.py 后生成")
                        .build()
        );
    }
}
