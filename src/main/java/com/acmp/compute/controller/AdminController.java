package com.acmp.compute.controller;

import com.acmp.compute.dto.IssueCredentialRequest;
import com.acmp.compute.dto.IssueCredentialResponse;
import com.acmp.compute.dto.PhysicalClusterRegisterRequest;
import com.acmp.compute.dto.PhysicalClusterResponse;
import com.acmp.compute.dto.ResourcePoolCreateRequest;
import com.acmp.compute.dto.ResourcePoolResponse;
import com.acmp.compute.service.AdminPhysicalClusterService;
import com.acmp.compute.service.AdminResourcePoolService;
import com.acmp.compute.service.ResourcePoolService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * 管理员 API 控制器：物理集群注册、资源池创建、凭证发放等管理员操作。
 * 所有接口需要 PLATFORM_ADMIN 角色。
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class AdminController {

    private final AdminPhysicalClusterService adminPhysicalClusterService;
    private final ResourcePoolService resourcePoolService;
    private final AdminResourcePoolService adminResourcePoolService;

    /**
     * 注册新的物理集群。
     * POST /api/v1/admin/physical-clusters
     * 
     * 请求体示例：
     * {
     *   "name": "beijing-cluster-01",
     *   "description": "北京机房主集群",
     *   "kubeconfigBase64": "base64编码的完整kubeconfig"
     * }
     */
    @PostMapping("/physical-clusters")
    public ResponseEntity<PhysicalClusterResponse> registerPhysicalCluster(
            @Valid @RequestBody PhysicalClusterRegisterRequest request) {
        PhysicalClusterResponse resp = adminPhysicalClusterService.registerCluster(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    /**
     * 创建部门逻辑资源池。
     * POST /api/v1/admin/resource-pools
     * 
     * 请求体示例：
     * {
     *   "physicalClusterId": "uuid-of-cluster",
     *   "name": "财务部资源池",
     *   "departmentCode": "finance",
     *   "departmentName": "财务部",
     *   "gpuSlots": 8,
     *   "cpuCores": 48,
     *   "memoryGiB": 256,
     *   "maxPods": 50
     * }
     * 
     * 该接口自动完成：
     * - 创建 Namespace（dept-{departmentCode}-{随机8位}）
     * - 创建 ResourceQuota
     * - 创建 ServiceAccount
     * - 创建 Role（部门级权限）
     * - 创建 RoleBinding
     * - 创建 Volcano Queue
     */
    @PostMapping("/resource-pools")
    public ResponseEntity<ResourcePoolResponse> createResourcePool(
            @Valid @RequestBody ResourcePoolCreateRequest request) {
        ResourcePoolResponse resp = resourcePoolService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    /**
     * 为部门用户发放 K8s 访问凭证。
     * POST /api/v1/admin/resource-pools/{poolId}/issue-credential
     * 
     * 请求体示例：
     * {
     *   "username": "zhangsan-finance",
     *   "expireDays": 30
     * }
     * 
     * 响应示例：
     * {
     *   "kubeconfig": "...完整的kubeconfig内容...",
     *   "namespace": "dept-finance-abc123",
     *   "clusterName": "beijing-cluster-01",
     *   "serviceAccountName": "sa-dept-finance",
     *   "message": "凭证已生成，有效期30天，用户: zhangsan-finance"
     * }
     */
    @PostMapping("/resource-pools/{poolId}/issue-credential")
    public ResponseEntity<IssueCredentialResponse> issueCredential(
            @PathVariable String poolId,
            @Valid @RequestBody IssueCredentialRequest request) {
        IssueCredentialResponse resp = adminResourcePoolService.issueCredential(poolId, request);
        return ResponseEntity.ok(resp);
    }

    /**
     * 为指定工作空间的成员签发 kubeconfig。
     * POST /api/v1/admin/workspaces/{workspaceId}/issue-credential
     */
    @PostMapping("/workspaces/{workspaceId}/issue-credential")
    public ResponseEntity<IssueCredentialResponse> issueCredentialForWorkspace(
            @PathVariable String workspaceId,
            @Valid @RequestBody IssueCredentialRequest request) {
        IssueCredentialResponse resp =
                adminResourcePoolService.issueCredentialForWorkspace(workspaceId, request);
        return ResponseEntity.ok(resp);
    }
}
