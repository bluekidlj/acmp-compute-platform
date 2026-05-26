package com.acmp.compute.service;

import com.acmp.compute.entity.ComputeSpec;
import com.acmp.compute.exception.BadRequestException;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.mapper.ComputeSpecMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 配额管理服务。
 *
 * 职责：
 * - 校验双层配额（逻辑池级 + 工作空间级）
 * - 预扣配额（在 K8s 部署前锁定配额）
 * - 回滚配额（部署失败时恢复配额）
 *
 * 算法：
 * 在规格中定义 resourceQuotaKey（默认为 "platform.io/{specName}"），
 * 作为 K8s ResourceQuota 中的自定义资源名。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaService {

    private final ComputeSpecMapper computeSpecMapper;

    /**
     * 校验逻辑资源池级配额。
     *
     * @param resourcePoolId 逻辑资源池 ID
     * @param specId 规格 ID
     * @param requestedUnits 请求数量（通常等于副本数）
     * @throws BadRequestException 如果配额不足
     */
    public void validatePoolLevelQuota(String resourcePoolId, String specId, int requestedUnits) {
        List<Map<String, Object>> quotas = computeSpecMapper.findSpecQuotasByResourcePoolId(resourcePoolId);

        Map<String, Object> quota = quotas.stream()
                .filter(q -> q.get("spec_id").equals(specId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "逻辑池 " + resourcePoolId + " 中不存在规格 " + specId + " 的配额"));

        Integer totalQuota = (Integer) quota.get("total_quota");
        Integer allocatedQuota = (Integer) quota.get("allocated_quota");
        int availableQuota = totalQuota - allocatedQuota;

        if (availableQuota < requestedUnits) {
            throw new BadRequestException(
                    String.format("逻辑池级配额不足: 需要 %d 单位，可用 %d 单位", requestedUnits, availableQuota));
        }

        log.info("✓ 逻辑池配额校验通过: 池={}, 规格={}, 请求={}, 可用={}", 
                resourcePoolId, specId, requestedUnits, availableQuota);
    }

    /**
     * 校验工作空间级配额。
     *
     * @param workspaceId 工作空间 ID
     * @param specId 规格 ID
     * @param requestedUnits 请求数量（通常等于副本数）
     * @throws BadRequestException 如果配额不足
     */
    public void validateWorkspaceLevelQuota(String workspaceId, String specId, int requestedUnits) {
        List<Map<String, Object>> quotas = computeSpecMapper.findSpecQuotasByWorkspaceId(workspaceId);

        Map<String, Object> quota = quotas.stream()
                .filter(q -> q.get("spec_id").equals(specId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "工作空间 " + workspaceId + " 中不存在规格 " + specId + " 的配额"));

        Integer maxQuota = (Integer) quota.get("max_quota");
        Integer usedQuota = (Integer) quota.get("used_quota");
        int availableQuota = maxQuota - usedQuota;

        if (availableQuota < requestedUnits) {
            throw new BadRequestException(
                    String.format("工作空间级配额不足: 需要 %d 单位，可用 %d 单位", requestedUnits, availableQuota));
        }

        log.info("✓ 工作空间级配额校验通过: 工作空间={}, 规格={}, 请求={}, 可用={}", 
                workspaceId, specId, requestedUnits, availableQuota);
    }

    /**
     * 双层配额校验。
     *
     * @param resourcePoolId 逻辑资源池 ID
     * @param workspaceId 工作空间 ID
     * @param specId 规格 ID
     * @param requestedUnits 请求数量
     * @throws BadRequestException 如果任一层配额不足
     */
    public void validateBothLevelQuotas(String resourcePoolId, String workspaceId, String specId, int requestedUnits) {
        validatePoolLevelQuota(resourcePoolId, specId, requestedUnits);
        validateWorkspaceLevelQuota(workspaceId, specId, requestedUnits);
        log.info("✓ 双层配额校验通过: 池={}, 工作空间={}, 规格={}, 请求={}", 
                resourcePoolId, workspaceId, specId, requestedUnits);
    }

    /**
     * 预扣逻辑池级配额。
     *
     * 调用时机：K8s 部署成功后，立即扣减配额。
     * 失败回滚：若后续出错，调用 rollbackPoolLevelQuota 恢复。
     *
     * @param resourcePoolId 逻辑资源池 ID
     * @param specId 规格 ID
     * @param units 扣减数量
     */
    @Transactional(rollbackFor = Exception.class)
    public void deductPoolLevelQuota(String resourcePoolId, String specId, int units) {
        List<Map<String, Object>> quotas = computeSpecMapper.findSpecQuotasByResourcePoolId(resourcePoolId);

        Map<String, Object> quota = quotas.stream()
                .filter(q -> q.get("spec_id").equals(specId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("配额记录不存在"));

        Integer currentAllocated = (Integer) quota.get("allocated_quota");
        int newAllocated = currentAllocated + units;

        computeSpecMapper.updateResourcePoolSpecAllocated(resourcePoolId, specId, newAllocated);
        log.info("✓ 预扣逻辑池级配额: 池={}, 规格={}, 扣减={}, 新分配={}", 
                resourcePoolId, specId, units, newAllocated);
    }

    /**
     * 预扣工作空间级配额。
     *
     * @param workspaceId 工作空间 ID
     * @param specId 规格 ID
     * @param units 扣减数量
     */
    @Transactional(rollbackFor = Exception.class)
    public void deductWorkspaceLevelQuota(String workspaceId, String specId, int units) {
        List<Map<String, Object>> quotas = computeSpecMapper.findSpecQuotasByWorkspaceId(workspaceId);

        Map<String, Object> quota = quotas.stream()
                .filter(q -> q.get("spec_id").equals(specId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("配额记录不存在"));

        Integer currentUsed = (Integer) quota.get("used_quota");
        int newUsed = currentUsed + units;

        computeSpecMapper.updateWorkspaceSpecUsed(workspaceId, specId, newUsed);
        log.info("✓ 预扣工作空间级配额: 工作空间={}, 规格={}, 扣减={}, 新使用={}", 
                workspaceId, specId, units, newUsed);
    }

    /**
     * 双层配额预扣。
     *
     * @param resourcePoolId 逻辑资源池 ID
     * @param workspaceId 工作空间 ID
     * @param specId 规格 ID
     * @param units 扣减数量
     */
    @Transactional(rollbackFor = Exception.class)
    public void deductBothLevelQuotas(String resourcePoolId, String workspaceId, String specId, int units) {
        deductPoolLevelQuota(resourcePoolId, specId, units);
        deductWorkspaceLevelQuota(workspaceId, specId, units);
        log.info("✓ 双层配额预扣完成: 池={}, 工作空间={}, 规格={}, 扣减={}", 
                resourcePoolId, workspaceId, specId, units);
    }

    /**
     * 回滚逻辑池级配额。
     *
     * 调用时机：部署失败或被撤销时，恢复已预扣的配额。
     *
     * @param resourcePoolId 逻辑资源池 ID
     * @param specId 规格 ID
     * @param units 恢复数量
     */
    @Transactional(rollbackFor = Exception.class)
    public void rollbackPoolLevelQuota(String resourcePoolId, String specId, int units) {
        List<Map<String, Object>> quotas = computeSpecMapper.findSpecQuotasByResourcePoolId(resourcePoolId);

        Map<String, Object> quota = quotas.stream()
                .filter(q -> q.get("spec_id").equals(specId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("配额记录不存在"));

        Integer currentAllocated = (Integer) quota.get("allocated_quota");
        int newAllocated = Math.max(0, currentAllocated - units);

        computeSpecMapper.updateResourcePoolSpecAllocated(resourcePoolId, specId, newAllocated);
        log.info("✓ 回滚逻辑池级配额: 池={}, 规格={}, 恢复={}, 新分配={}", 
                resourcePoolId, specId, units, newAllocated);
    }

    /**
     * 回滚工作空间级配额。
     *
     * @param workspaceId 工作空间 ID
     * @param specId 规格 ID
     * @param units 恢复数量
     */
    @Transactional(rollbackFor = Exception.class)
    public void rollbackWorkspaceLevelQuota(String workspaceId, String specId, int units) {
        List<Map<String, Object>> quotas = computeSpecMapper.findSpecQuotasByWorkspaceId(workspaceId);

        Map<String, Object> quota = quotas.stream()
                .filter(q -> q.get("spec_id").equals(specId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("配额记录不存在"));

        Integer currentUsed = (Integer) quota.get("used_quota");
        int newUsed = Math.max(0, currentUsed - units);

        computeSpecMapper.updateWorkspaceSpecUsed(workspaceId, specId, newUsed);
        log.info("✓ 回滚工作空间级配额: 工作空间={}, 规格={}, 恢复={}, 新使用={}", 
                workspaceId, specId, units, newUsed);
    }

    /**
     * 双层配额回滚。
     *
     * @param resourcePoolId 逻辑资源池 ID
     * @param workspaceId 工作空间 ID
     * @param specId 规格 ID
     * @param units 恢复数量
     */
    @Transactional(rollbackFor = Exception.class)
    public void rollbackBothLevelQuotas(String resourcePoolId, String workspaceId, String specId, int units) {
        rollbackPoolLevelQuota(resourcePoolId, specId, units);
        rollbackWorkspaceLevelQuota(workspaceId, specId, units);
        log.info("✓ 双层配额回滚完成: 池={}, 工作空间={}, 规格={}, 恢复={}", 
                resourcePoolId, workspaceId, specId, units);
    }
}
