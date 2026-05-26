package com.acmp.compute.controller;

import com.acmp.compute.dto.SpecRequest;
import com.acmp.compute.dto.SpecResponse;
import com.acmp.compute.service.ComputeSpecService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 算力规格管理 API。
 */
@RestController
@RequestMapping("/api/v1/specs")
@RequiredArgsConstructor
public class ComputeSpecController {

    private final ComputeSpecService specService;

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<SpecResponse> create(@Valid @RequestBody SpecRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(specService.create(request));
    }

    @GetMapping
    public ResponseEntity<List<SpecResponse>> list() {
        return ResponseEntity.ok(specService.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<SpecResponse> getById(@PathVariable String id) {
        return ResponseEntity.ok(specService.getById(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, String>> delete(@PathVariable String id) {
        specService.delete(id);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }
}
