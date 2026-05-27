package com.acmp.compute.service;

import com.acmp.compute.dto.ModelDeploymentRequest;
import com.acmp.compute.dto.ModelDeploymentResponse;
import com.acmp.compute.entity.ComputeSpec;
import com.acmp.compute.entity.GpuBrand;
import com.acmp.compute.entity.ModelDeployment;
import com.acmp.compute.entity.Workspace;
import com.acmp.compute.exception.BadRequestException;
import com.acmp.compute.exception.ForbiddenException;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.k8s.KubernetesClientManager;
import com.acmp.compute.mapper.ComputeSpecMapper;
import com.acmp.compute.mapper.ModelDeploymentMapper;
import com.acmp.compute.mapper.WorkspaceMapper;
import com.acmp.compute.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ModelDeploymentService 单元测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelDeploymentServiceTest {

    @Mock
    private ModelDeploymentMapper modelDeploymentMapper;
    @Mock
    private WorkspaceMapper workspaceMapper;
    @Mock
    private ComputeSpecMapper computeSpecMapper;
    @Mock
    private KubernetesClientManager clientManager;
    @Mock
    private QuotaService quotaService;
    @Mock
    private PoolMetadataService poolMetadataService;

    @InjectMocks
    private ModelDeploymentService service;

    private static final String WORKSPACE_ID = "ws-001";
    private static final String POOL_ID = "pool-001";
    private static final String NAMESPACE = "ws-ns-001";
    private static final String USER_ID = "user-001";
    private static final String CLUSTER_ID = "cluster-001";

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.clearContext();
        UserPrincipal principal = UserPrincipal.builder()
                .id(USER_ID)
                .username("testuser")
                .passwordHash("password")
                .role("PLATFORM_ADMIN")
                .build();
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, "password", Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private Workspace mockWorkspace() {
        return Workspace.builder()
                .id(WORKSPACE_ID)
                .resourcePoolId(POOL_ID)
                .name("test-workspace")
                .namespace(NAMESPACE)
                .primaryClusterId(CLUSTER_ID)
                .createdBy(USER_ID)
                .status("active")
                .createdAt(Instant.now())
                .build();
    }

    private ModelDeploymentRequest validRequest() {
        ModelDeploymentRequest req = new ModelDeploymentRequest();
        req.setName("qwen3-deployment");
        req.setReplicas(2);
        req.setGpuCount(1);
        req.setCpuCores(4);
        req.setMemoryGib(16);
        req.setGpuType("nvidia-a100-80g-1/4");
        req.setImage("vllm/vllm-openai:latest");
        req.setModelSource("with_weights");
        req.setModelIdOrPath("/models/Qwen3-14B");
        req.setModelName("Qwen3-14B");
        req.setEnvVars(Map.of("MODEL_NAME", "Qwen3-14B"));
        req.setCommand("[\"python\", \"-m\", \"vllm.entrypoints.openai.api_server\"]");
        req.setArgs("--model /models/Qwen3-14B --host 0.0.0.0 --port 8000");
        return req;
    }

    // ─────────────────────────── ensureComputeSpec 测试 ───────────────────────────

    @Test
    @DisplayName("ensureComputeSpec - 复用已有 ComputeSpec")
    void ensureComputeSpec_reuseExisting() {
        String specName = "auto-nvidia-a100-80g-1/4-1g-4c-16g";
        ComputeSpec existing = ComputeSpec.builder()
                .id("spec-existing")
                .name(specName)
                .displayName("1/4")
                .gpuBrand(GpuBrand.NVIDIA)
                .defaultGpuCount(1)
                .defaultCpuCores(4)
                .defaultMemoryGib(16)
                .nodeSelector("{\"pool\":\"nvidia-a100-80g-1/4\"}")
                .build();

        ModelDeploymentRequest req = validRequest();

        when(computeSpecMapper.findByName(specName)).thenReturn(Optional.of(existing));

        ComputeSpec result = invokeEnsureComputeSpec(req);

        assertEquals("spec-existing", result.getId());
        verify(computeSpecMapper, never()).insert(any());
    }

    @Test
    @DisplayName("ensureComputeSpec - 自动创建新的 ComputeSpec（从 GpuSplitSpec 推导）")
    void ensureComputeSpec_createNewFromGpuSplitSpec() {
        String specName = "auto-nvidia-a100-80g-1/4-1g-4c-16g";
        ModelDeploymentRequest req = validRequest();

        when(computeSpecMapper.findByName(specName)).thenReturn(Optional.empty());
        doNothing().when(computeSpecMapper).insert(any(ComputeSpec.class));

        ComputeSpec result = invokeEnsureComputeSpec(req);

        assertNotNull(result.getId());
        assertEquals(specName, result.getName());
        assertEquals(GpuBrand.NVIDIA, result.getGpuBrand());
        assertEquals(1, result.getDefaultGpuCount());
        assertEquals(4, result.getDefaultCpuCores());
        assertEquals(16, result.getDefaultMemoryGib());
        assertEquals(20480, result.getDefaultGpumemMb()); // from GpuSplitSpec
        assertEquals(25, result.getDefaultGpucores());      // from GpuSplitSpec
        assertEquals("{\"pool\":\"nvidia-a100-80g-1/4\"}", result.getNodeSelector());
        assertTrue(result.getResourceQuotaKey().startsWith("platform.io/"));

        ArgumentCaptor<ComputeSpec> captor = ArgumentCaptor.forClass(ComputeSpec.class);
        verify(computeSpecMapper).insert(captor.capture());
        assertEquals(specName, captor.getValue().getName());
    }

    @Test
    @DisplayName("ensureComputeSpec - 未知 gpuType 使用默认 NVIDIA")
    void ensureComputeSpec_unknownGpuType_defaultToNVIDIA() {
        ModelDeploymentRequest req = validRequest();
        req.setGpuType("custom-unknown-gpu");

        String specName = "auto-custom-unknown-gpu-1g-4c-16g";
        when(computeSpecMapper.findByName(specName)).thenReturn(Optional.empty());
        doNothing().when(computeSpecMapper).insert(any(ComputeSpec.class));

        ComputeSpec result = invokeEnsureComputeSpec(req);

        // 未知类型默认走 NVIDIA，不走 UNKNOWN
        assertEquals(GpuBrand.NVIDIA, result.getGpuBrand());
        assertEquals(16384, result.getDefaultGpumemMb()); // 默认值
        assertEquals(50, result.getDefaultGpucores());    // 默认值
    }

    // ─────────────────────────── 部署流程测试 ───────────────────────────

    private PhysicalCluster mockTargetCluster() {
        return PhysicalCluster.builder()
                .id(CLUSTER_ID)
                .name("test-cluster")
                .build();
    }

    @Test
    @DisplayName("deployBySpec - 成功部署（自动创建 ComputeSpec）")
    void deployBySpec_success_autoCreateSpec() throws Exception {
        ModelDeploymentRequest req = validRequest();
        Workspace ws = mockWorkspace();
        String specName = "auto-nvidia-a100-80g-1/4-1g-4c-16g";

        when(workspaceMapper.findById(WORKSPACE_ID)).thenReturn(Optional.of(ws));
        when(workspaceMapper.findMemberIds(WORKSPACE_ID)).thenReturn(List.of(USER_ID));
        when(computeSpecMapper.findByName(specName)).thenReturn(Optional.empty());
        doNothing().when(computeSpecMapper).insert(any(ComputeSpec.class));
        doNothing().when(quotaService).validateBothLevelQuotas(anyString(), anyString(), anyString(), anyInt());
        doNothing().when(quotaService).deductBothLevelQuotas(anyString(), anyString(), anyString(), anyInt());
        when(poolMetadataService.pickClusterForSpec(eq(POOL_ID), any(ComputeSpec.class))).thenReturn(mockTargetCluster());
        when(modelDeploymentMapper.insert(any(ModelDeployment.class))).thenReturn(1);
        when(modelDeploymentMapper.update(any(ModelDeployment.class))).thenReturn(1);
        doNothing().when(clientManager).createVllmDeploymentAndService(anyString(), anyString(), anyString());

        ModelDeploymentResponse resp = service.deployBySpec(POOL_ID, WORKSPACE_ID, req);

        assertNotNull(resp);
        assertEquals(WORKSPACE_ID, resp.getWorkspaceId());
        assertEquals(POOL_ID, resp.getResourcePoolId());
        assertEquals("qwen3-deployment", resp.getName());
        assertEquals(2, resp.getReplicas());
        assertEquals("running", resp.getStatus());
        assertNotNull(resp.getK8sDeploymentName());
        assertTrue(resp.getK8sDeploymentName().startsWith("vllm-"));
        assertTrue(resp.getK8sServiceName().endsWith("-svc"));
        assertTrue(resp.getServiceUrl().contains(".svc.cluster.local:8000"));
        assertTrue(resp.getK8sDeploymentName().length() <= 50);
        assertTrue(resp.getK8sServiceName().length() <= 50);

        verify(quotaService).validateBothLevelQuotas(eq(POOL_ID), eq(WORKSPACE_ID), anyString(), eq(2));
        verify(quotaService).deductBothLevelQuotas(eq(POOL_ID), eq(WORKSPACE_ID), anyString(), eq(2));
        verify(clientManager).createVllmDeploymentAndService(eq(CLUSTER_ID), eq(NAMESPACE), anyString());
    }

    @Test
    @DisplayName("deployBySpec - 复用已有 ComputeSpec 不重复插入")
    void deployBySpec_reuseExistingSpec_noInsert() throws Exception {
        ModelDeploymentRequest req = validRequest();
        Workspace ws = mockWorkspace();
        String specName = "auto-nvidia-a100-80g-1/4-1g-4c-16g";

        ComputeSpec existing = ComputeSpec.builder()
                .id("spec-existing")
                .name(specName)
                .gpuBrand(GpuBrand.NVIDIA)
                .defaultGpuCount(1)
                .defaultGpumemMb(20480)
                .defaultGpucores(25)
                .defaultCpuCores(4)
                .defaultMemoryGib(16)
                .nodeSelector("{\"pool\":\"nvidia-a100-80g-1/4\"}")
                .tolerations("[{\"key\":\"nvidia.com/gpu\",\"operator\":\"Exists\",\"effect\":\"NoSchedule\"}]")
                .resourceQuotaKey("platform.io/" + specName)
                .build();

        when(workspaceMapper.findById(WORKSPACE_ID)).thenReturn(Optional.of(ws));
        when(workspaceMapper.findMemberIds(WORKSPACE_ID)).thenReturn(List.of(USER_ID));
        when(computeSpecMapper.findByName(specName)).thenReturn(Optional.of(existing));
        doNothing().when(quotaService).validateBothLevelQuotas(anyString(), anyString(), anyString(), anyInt());
        doNothing().when(quotaService).deductBothLevelQuotas(anyString(), anyString(), anyString(), anyInt());
        when(poolMetadataService.pickClusterForSpec(eq(POOL_ID), any(ComputeSpec.class))).thenReturn(mockTargetCluster());
        when(modelDeploymentMapper.insert(any(ModelDeployment.class))).thenReturn(1);
        when(modelDeploymentMapper.update(any(ModelDeployment.class))).thenReturn(1);
        doNothing().when(clientManager).createVllmDeploymentAndService(anyString(), anyString(), anyString());

        ModelDeploymentResponse resp = service.deployBySpec(POOL_ID, WORKSPACE_ID, req);

        assertNotNull(resp);
        assertEquals("spec-existing", resp.getSpecId());
        verify(computeSpecMapper, never()).insert(any()); // 不插入新 spec
    }

    @Test
    @DisplayName("deployBySpec - 工作空间不存在抛异常")
    void deployBySpec_workspaceNotFound() {
        ModelDeploymentRequest req = validRequest();
        when(workspaceMapper.findById(WORKSPACE_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                service.deployBySpec(POOL_ID, WORKSPACE_ID, req));
    }

    @Test
    @DisplayName("deployBySpec - poolId 不匹配抛异常")
    void deployBySpec_poolIdMismatch() {
        ModelDeploymentRequest req = validRequest();
        Workspace ws = mockWorkspace();
        when(workspaceMapper.findById(WORKSPACE_ID)).thenReturn(Optional.of(ws));

        assertThrows(BadRequestException.class, () ->
                service.deployBySpec("wrong-pool-id", WORKSPACE_ID, req));
    }

    @Test
    @DisplayName("deployBySpec - 无权限访问工作空间抛异常")
    void deployBySpec_forbidden() {
        ModelDeploymentRequest req = validRequest();
        when(workspaceMapper.findById(WORKSPACE_ID)).thenReturn(Optional.of(mockWorkspace()));
        when(workspaceMapper.findMemberIds(WORKSPACE_ID)).thenReturn(List.of("other-user"));

        assertThrows(ForbiddenException.class, () ->
                service.deployBySpec(POOL_ID, WORKSPACE_ID, req));
    }

    @Test
    @DisplayName("deployBySpec - K8s 提交失败时配额回滚")
    void deployBySpec_k8sFailure_rollbackQuota() throws Exception {
        ModelDeploymentRequest req = validRequest();
        Workspace ws = mockWorkspace();
        String specName = "auto-nvidia-a100-80g-1/4-1g-4c-16g";

        when(workspaceMapper.findById(WORKSPACE_ID)).thenReturn(Optional.of(ws));
        when(workspaceMapper.findMemberIds(WORKSPACE_ID)).thenReturn(List.of(USER_ID));
        when(computeSpecMapper.findByName(specName)).thenReturn(Optional.empty());
        doNothing().when(computeSpecMapper).insert(any(ComputeSpec.class));
        doNothing().when(quotaService).validateBothLevelQuotas(anyString(), anyString(), anyString(), anyInt());
        doNothing().when(quotaService).deductBothLevelQuotas(anyString(), anyString(), anyString(), anyInt());
        when(poolMetadataService.pickClusterForSpec(eq(POOL_ID), any(ComputeSpec.class))).thenReturn(mockTargetCluster());
        when(modelDeploymentMapper.insert(any(ModelDeployment.class))).thenReturn(1);
        when(modelDeploymentMapper.update(any(ModelDeployment.class))).thenReturn(1);
        doThrow(new RuntimeException("K8s API error"))
                .when(clientManager).createVllmDeploymentAndService(anyString(), anyString(), anyString());
        doNothing().when(quotaService).rollbackBothLevelQuotas(anyString(), anyString(), anyString(), anyInt());

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                service.deployBySpec(POOL_ID, WORKSPACE_ID, req));

        assertTrue(thrown.getMessage().contains("vLLM 部署失败"));
        verify(quotaService).rollbackBothLevelQuotas(eq(POOL_ID), eq(WORKSPACE_ID), anyString(), eq(2));
    }

    // ─────────────────────────── 删除部署测试 ───────────────────────────

    @Test
    @DisplayName("delete - 成功删除部署并回滚配额")
    void delete_success() throws Exception {
        ModelDeployment record = ModelDeployment.builder()
                .id("deploy-001")
                .workspaceId(WORKSPACE_ID)
                .resourcePoolId(POOL_ID)
                .specId("spec-001")
                .name("test-deployment")
                .replicas(2)
                .status("running")
                .k8sDeploymentName("vllm-test-deploy")
                .k8sServiceName("vllm-test-deploy-svc")
                .build();

        when(workspaceMapper.findById(WORKSPACE_ID)).thenReturn(Optional.of(mockWorkspace()));
        when(workspaceMapper.findMemberIds(WORKSPACE_ID)).thenReturn(List.of(USER_ID));
        when(modelDeploymentMapper.findById("deploy-001")).thenReturn(Optional.of(record));
        doNothing().when(clientManager).deleteDeployment(anyString(), anyString(), anyString());
        doNothing().when(clientManager).deleteService(anyString(), anyString(), anyString());
        doNothing().when(quotaService).rollbackBothLevelQuotas(anyString(), anyString(), anyString(), anyInt());
        when(modelDeploymentMapper.deleteById("deploy-001")).thenReturn(1);

        service.delete(WORKSPACE_ID, "deploy-001");

        verify(clientManager).deleteDeployment(CLUSTER_ID, NAMESPACE, "vllm-test-deploy");
        verify(clientManager).deleteService(CLUSTER_ID, NAMESPACE, "vllm-test-deploy-svc");
        verify(quotaService).rollbackBothLevelQuotas(POOL_ID, WORKSPACE_ID, "spec-001", 2);
        verify(modelDeploymentMapper).deleteById("deploy-001");
    }

    @Test
    @DisplayName("delete - K8s 删除失败时仍回滚配额并删 DB")
    void delete_k8sFailure_stillRollbackQuota() throws Exception {
        ModelDeployment record = ModelDeployment.builder()
                .id("deploy-001")
                .workspaceId(WORKSPACE_ID)
                .resourcePoolId(POOL_ID)
                .specId("spec-001")
                .name("test-deployment")
                .replicas(2)
                .status("running")
                .k8sDeploymentName("vllm-test-deploy")
                .k8sServiceName("vllm-test-deploy-svc")
                .build();

        when(workspaceMapper.findById(WORKSPACE_ID)).thenReturn(Optional.of(mockWorkspace()));
        when(workspaceMapper.findMemberIds(WORKSPACE_ID)).thenReturn(List.of(USER_ID));
        when(modelDeploymentMapper.findById("deploy-001")).thenReturn(Optional.of(record));
        doThrow(new RuntimeException("K8s delete error"))
                .when(clientManager).deleteDeployment(anyString(), anyString(), anyString());
        doNothing().when(clientManager).deleteService(anyString(), anyString(), anyString());
        doNothing().when(quotaService).rollbackBothLevelQuotas(anyString(), anyString(), anyString(), anyInt());
        when(modelDeploymentMapper.deleteById("deploy-001")).thenReturn(1);

        service.delete(WORKSPACE_ID, "deploy-001");

        verify(quotaService).rollbackBothLevelQuotas(POOL_ID, WORKSPACE_ID, "spec-001", 2);
        verify(modelDeploymentMapper).deleteById("deploy-001");
    }

    @Test
    @DisplayName("delete - status=failed 时不回滚配额")
    void delete_failedStatus_noRollback() {
        ModelDeployment record = ModelDeployment.builder()
                .id("deploy-001")
                .workspaceId(WORKSPACE_ID)
                .resourcePoolId(POOL_ID)
                .specId("spec-001")
                .name("test-deployment")
                .replicas(2)
                .status("failed")
                .k8sDeploymentName("vllm-test-deploy")
                .k8sServiceName("vllm-test-deploy-svc")
                .build();

        when(workspaceMapper.findById(WORKSPACE_ID)).thenReturn(Optional.of(mockWorkspace()));
        when(workspaceMapper.findMemberIds(WORKSPACE_ID)).thenReturn(List.of(USER_ID));
        when(modelDeploymentMapper.findById("deploy-001")).thenReturn(Optional.of(record));
        when(modelDeploymentMapper.deleteById("deploy-001")).thenReturn(1);

        service.delete(WORKSPACE_ID, "deploy-001");

        verify(quotaService, never()).rollbackBothLevelQuotas(anyString(), anyString(), anyString(), anyInt());
        verify(modelDeploymentMapper).deleteById("deploy-001");
    }

    // ─────────────────────────── 列表查询测试 ───────────────────────────

    @Test
    @DisplayName("listByWorkspace - 返回部署列表")
    void listByWorkspace_success() {
        ModelDeployment record = ModelDeployment.builder()
                .id("deploy-001")
                .workspaceId(WORKSPACE_ID)
                .name("test-deployment")
                .status("running")
                .build();

        when(workspaceMapper.findMemberIds(WORKSPACE_ID)).thenReturn(List.of(USER_ID));
        when(modelDeploymentMapper.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(record));

        List<ModelDeploymentResponse> result = service.listByWorkspace(WORKSPACE_ID);

        assertEquals(1, result.size());
        assertEquals("deploy-001", result.get(0).getId());
    }

    // ─────────────────────────── Helper ───────────────────────────

    private ComputeSpec invokeEnsureComputeSpec(ModelDeploymentRequest request) {
        try {
            var method = ModelDeploymentService.class.getDeclaredMethod("ensureComputeSpec", ModelDeploymentRequest.class);
            method.setAccessible(true);
            return (ComputeSpec) method.invoke(service, request);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}