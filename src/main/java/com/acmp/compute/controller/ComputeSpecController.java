package com.acmp.compute.controller;

import com.acmp.compute.dto.SpecResponse;
import com.acmp.compute.service.ComputeSpecService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 算力规格管理接口。
 *
 * <p>Spec 是租户申请资源和推理服务部署时使用的唯一资源套餐。
 */
@RestController
@RequestMapping("/api/v1/specs")
@RequiredArgsConstructor
public class ComputeSpecController {

    private final ComputeSpecService specService;

    /**
     * 查询全部 Spec，可按资源池类型过滤。
     */
    @GetMapping
    public ResponseEntity<List<SpecResponse>> list(
            @RequestParam(required = false) String specType) {
        return ResponseEntity.ok(specService.list(specType));
    }

    /**
     * 查询一个 Spec 的完整资源参数。
     */
    @GetMapping("/{id}")
    public ResponseEntity<SpecResponse> getById(@PathVariable String id) {
        return ResponseEntity.ok(specService.getById(id));
    }

    /**
     * 删除未被租户配额和推理服务使用的 Spec，并释放对应 Node/GPU 入池归属。
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        specService.delete(id);
        return ResponseEntity.noContent().build();
    }

}
