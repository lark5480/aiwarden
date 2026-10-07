package com.aiwarden.governance.api;

import com.aiwarden.common.exception.NotFoundException;
import com.aiwarden.contract.governance.ConsistencyRepairResponse;
import com.aiwarden.contract.governance.ConsistencyReportResponse;
import com.aiwarden.governance.reconcile.ConsistencyReconciler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 一致性对账接口（FR-ING-06）：
 * GET  /report  最近一次对账报告（不一致清单可查）
 * POST /scan    手动触发一轮扫描（运维 / 演示用——对账的"修复录屏"从这里开始）
 * POST /repair  显式修复：对残留文档补做产物清理（ADR-006：发现与修复分离）
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
        return reconciler.latestReport()
                .map(row -> new ConsistencyReportResponse(row.id(), row.mismatchCount(),
                        row.detailsJson(), row.createdAt()))
                .orElseThrow(() -> new NotFoundException("暂无对账报告（等待首轮扫描或调用 POST /scan）"));
    }

    /** 手动触发扫描并返回最新报告（同步执行，演示规模无压力）。 */
    @PostMapping("/scan")
    public ConsistencyReportResponse scan() {
        reconciler.runOnce();
        return report();
    }

    @PostMapping("/repair")
    public ConsistencyRepairResponse repair() {
        return new ConsistencyRepairResponse(reconciler.repair());
    }
}
