package com.acmp.compute.scheduler;

import com.acmp.compute.dto.AuditReport;
import com.acmp.compute.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 1.0 定时对账：每 5 分钟跑一次 audit，发现孤儿/偏差打日志。
 *
 * <h2>设计取舍</h2>
 * <p>1.0 不做高并发分布式一致性，不做实时同步；本任务为**单线程串行**的轻量级对账：
 * <ul>
 *   <li>不修数据（仅报告）</li>
 *   <li>不写 DB（不写 audit_report 表）</li>
 *   <li>不告警（仅 log.warn，由运维 / 监控日志收集处理）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuotaReconcileScheduler {

    private final AuditService auditService;

    /** 每 5 分钟执行一次 */
    @Scheduled(fixedRate = 5 * 60 * 1000L, initialDelay = 60 * 1000L)
    public void reconcile() {
        try {
            AuditReport report = auditService.generate();
            if (report.getOrphanCount() > 0) {
                log.warn("⚠️ 对账发现 {} 个孤儿部署（DB running 但 K8s 无）",
                        report.getOrphanCount());
                for (AuditReport.OrphanDeployment o : report.getOrphanDeployments()) {
                    log.warn("  - 孤儿: id={} name={} ns={} reason={}",
                            o.getDeploymentId(), o.getK8sDeploymentName(),
                            o.getK8sNamespace(), o.getReason());
                }
            }
            if (report.getQuotaMismatchCount() > 0) {
                log.warn("⚠️ 对账发现 {} 个配额偏差", report.getQuotaMismatchCount());
                for (AuditReport.QuotaMismatch m : report.getQuotaMismatches()) {
                    log.warn("  - 偏差: quotaId={} reason={}", m.getQuotaId(), m.getReason());
                }
            }
            if (report.getOrphanCount() == 0 && report.getQuotaMismatchCount() == 0) {
                log.info("✓ 对账通过 ({} 个部署全部对齐)", report.getTotalDeployments());
            }
        } catch (Exception e) {
            log.error("❌ 对账任务异常", e);
        }
    }
}
