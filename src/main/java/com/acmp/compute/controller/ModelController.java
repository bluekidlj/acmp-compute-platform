package com.acmp.compute.controller;

import com.acmp.compute.dto.ModelRequest;
import com.acmp.compute.dto.ModelResponse;
import com.acmp.compute.service.ModelService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 模型广场 API：管理 NFS 存储的模型文件元信息。
 */
@RestController
@RequestMapping("/api/v1/models")
@RequiredArgsConstructor
public class ModelController {

    private final ModelService modelService;

    @PostMapping
    public ResponseEntity<ModelResponse> create(@Valid @RequestBody ModelRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(modelService.create(request));
    }

    @GetMapping
    public ResponseEntity<List<ModelResponse>> list() {
        return ResponseEntity.ok(modelService.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ModelResponse> getById(@PathVariable String id) {
        return ResponseEntity.ok(modelService.getById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ModelResponse> update(@PathVariable String id, @Valid @RequestBody ModelRequest request) {
        return ResponseEntity.ok(modelService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable String id) {
        modelService.delete(id);
        return ResponseEntity.ok(Map.of("message", "模型已删除"));
    }
}