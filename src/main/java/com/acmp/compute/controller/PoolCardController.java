package com.acmp.compute.controller;

import com.acmp.compute.dto.PoolCardRequest;
import com.acmp.compute.dto.PoolCardResponse;
import com.acmp.compute.entity.PoolCard;
import com.acmp.compute.service.PoolCardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/v1/pools/{poolId}/cards")
@RequiredArgsConstructor
public class PoolCardController {

    private final PoolCardService poolCardService;

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<PoolCardResponse> addCard(
            @PathVariable String poolId,
            @Valid @RequestBody PoolCardRequest req) {
        PoolCard card = poolCardService.addCard(poolId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(PoolCardResponse.from(card));
    }

    @DeleteMapping("/{cardId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    public ResponseEntity<java.util.Map<String, String>> removeCard(
            @PathVariable String poolId,
            @PathVariable String cardId,
            @RequestParam(defaultValue = "false") boolean force) {
        poolCardService.removeCard(poolId, cardId, force);
        return ResponseEntity.ok(java.util.Map.of("message", "已删除"));
    }

    @GetMapping
    public ResponseEntity<PoolCardResponse.ListResponse> listCards(@PathVariable String poolId) {
        return ResponseEntity.ok(poolCardService.listByPool(poolId));
    }
}
