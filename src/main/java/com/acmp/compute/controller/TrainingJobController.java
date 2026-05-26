package com.acmp.compute.controller;

import com.acmp.compute.dto.TrainingJobRequest;
import com.acmp.compute.service.TrainingJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/training-jobs")
@RequiredArgsConstructor
public class TrainingJobController {

    private final TrainingJobService trainingJobService;

    @PostMapping
    public ResponseEntity<Map<String, String>> submit(
            @PathVariable String workspaceId,
            @Valid @RequestBody TrainingJobRequest request) {
        String jobName = trainingJobService.submit(workspaceId, request);
        return ResponseEntity.status(201).body(Map.of("jobName", jobName, "message", "已提交"));
    }
}
