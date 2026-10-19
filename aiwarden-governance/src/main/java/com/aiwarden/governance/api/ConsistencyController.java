package com.aiwarden.governance.api;

import com.aiwarden.common.exception.NotFoundException;
import com.aiwarden.contract.governance.ConsistencyRepairResponse;
import com.aiwarden.contract.governance.ConsistencyReportResponse;
import com.aiwarden.governance.reconcile.ConsistencyReconciler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 一致性对账接口（FR-ING-06）：
 * GET  /report  最近一次对账报告（不一致清单可查）
 * POST /scan    手动触发一轮扫描（运维 / 演示用——对账的"修复录屏"从这里开始）
 * POST /repair  显式修复：残留文档补做产物清理 + 永久失败事件重驱动（ADR-006：发现与修复分离）
 */
@RestController
@RequestMapping("/api/v1/admin/consistency")
public class ConsistencyController {

    private final ConsistencyReconciler reconciler;

    public ConsistencyController(ConsistencyReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @GetMapping("/report")
    public ConsistencyReportResponse report() {
        AdminAccess.requireIdentity();
        return reconciler.latestReport()
                .map(row -> new ConsistencyReportResponse(row.id(), row.mismatchCount(),
                        row.detailsJson(), row.createdAt()))
                .orElseThrow(() -> new NotFoundException("暂无对账报告（等待首轮扫描或调用 POST /scan）"));
    }

    /** 手动触发扫描并返回最新报告（同步执行，演示规模无压力）。 */
    @PostMapping("/scan")
    public ConsistencyReportResponse scan() {
        AdminAccess.requireIdentity();
        reconciler.runOnce();
        return report();
    }

    /**
     * 最近 N 轮扫描（M4）：给「不一致清单为空」补上下文——
     * 近 N 轮是否持续在扫、每轮 mismatch 是多少，空态与故障因此可区分（FR-ADM-03）。
     */
    @GetMapping("/reports")
    public List<ConsistencyReportResponse> reports(
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        AdminAccess.requireIdentity();
        return reconciler.recentReports(limit).stream()
                .map(row -> new ConsistencyReportResponse(row.id(), row.mismatchCount(),
                        row.detailsJson(), row.createdAt()))
                .toList();
    }

    @PostMapping("/repair")
    public ConsistencyRepairResponse repair() {
        AdminAccess.requireIdentity();
        ConsistencyReconciler.RepairOutcome outcome = reconciler.repair();
        return new ConsistencyRepairResponse(outcome.repairedDocuments(), outcome.redrivenEvents());
    }
}
