package com.acmp.compute.controller;

import com.acmp.compute.dto.ModelRequest;
import com.acmp.compute.dto.ModelResponse;
import com.acmp.compute.service.ModelService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/models")
@RequiredArgsConstructor
public class ModelController {

    private final ModelService modelService;

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<ModelResponse> create(@Valid @RequestBody ModelRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(modelService.create(req));
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
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<ModelResponse> update(@PathVariable String id, @Valid @RequestBody ModelRequest req) {
        return ResponseEntity.ok(modelService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, String>> delete(@PathVariable String id) {
        modelService.delete(id);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }
}
