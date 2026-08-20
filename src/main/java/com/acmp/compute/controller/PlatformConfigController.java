package com.acmp.compute.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/platform/config")
public class PlatformConfigController {

    @Value("${acmp.features.innovation-lab-enabled:false}")
    private boolean innovationLabEnabled;

    @GetMapping("/features")
    public ResponseEntity<Map<String, Boolean>> features() {
        return ResponseEntity.ok(Map.of("innovationLabEnabled", innovationLabEnabled));
    }
}
