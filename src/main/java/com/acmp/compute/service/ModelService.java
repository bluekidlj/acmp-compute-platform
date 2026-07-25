package com.acmp.compute.service;

import com.acmp.compute.dto.ModelRequest;
import com.acmp.compute.dto.ModelResponse;
import com.acmp.compute.entity.Model;
import com.acmp.compute.exception.BadRequestException;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.mapper.ModelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 模型广场服务：管理模型文件的元信息。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelService {
    private static final Set<String> MODEL_FAMILIES =
            Set.of("DEEPSEEK", "QWEN", "GLM", "MINIMAX_M");

    private final ModelMapper modelMapper;

    @Transactional(rollbackFor = Exception.class)
    public ModelResponse create(ModelRequest request) {
        Model existing = modelMapper.findByName(request.getName());
        if (existing != null) {
            throw new BadRequestException("模型已存在: " + request.getName());
        }
        String backend = request.getStorageBackend();
        if (backend == null || backend.isEmpty()) {
            backend = "nfs";
        }
        String storagePath = request.getStoragePath();
        validateStoragePath(storagePath);
        validateModelFamily(request.getModelFamily());
        Model model = Model.builder()
                .id(UUID.randomUUID().toString())
                .name(request.getName())
                .displayName(request.getDisplayName())
                .description(request.getDescription())
                .modelFamily(request.getModelFamily())
                .modelSource(request.getModelSource() != null ? request.getModelSource() : "with_weights")
                .storageBackend(backend)
                .storagePath(storagePath)
                .fileSizeMb(request.getFileSizeMb())
                .build();
        modelMapper.insert(model);
        log.info("✓ 新增模型: {} (backend={}, path={})", model.getName(), backend, storagePath);
        return toResponse(model);
    }

    public ModelResponse getById(String id) {
        Model model = modelMapper.findById(id);
        if (model == null) {
            throw new ResourceNotFoundException("模型不存在: " + id);
        }
        return toResponse(model);
    }

    public ModelResponse getByName(String name) {
        Model model = modelMapper.findByName(name);
        if (model == null) {
            throw new ResourceNotFoundException("模型不存在: " + name);
        }
        return toResponse(model);
    }

    public List<ModelResponse> list() {
        List<ModelResponse> result = new ArrayList<>();
        List<Model> models = modelMapper.findAll();
        for (Model model : models) {
            result.add(toResponse(model));
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public ModelResponse update(String id, ModelRequest request) {
        Model existing = modelMapper.findById(id);
        if (existing == null) {
            throw new ResourceNotFoundException("模型不存在: " + id);
        }
        if (!existing.getName().equals(request.getName())) {
            Model conflict = modelMapper.findByName(request.getName());
            if (conflict != null) {
                throw new BadRequestException("模型名称已存在: " + request.getName());
            }
        }
        String backend = request.getStorageBackend();
        if (backend == null || backend.isEmpty()) {
            backend = existing.getStorageBackend();
        }
        String storagePath = request.getStoragePath();
        validateStoragePath(storagePath);
        validateModelFamily(request.getModelFamily());
        Model updated = Model.builder()
                .id(id)
                .name(request.getName())
                .displayName(request.getDisplayName())
                .description(request.getDescription())
                .modelFamily(request.getModelFamily())
                .modelSource(request.getModelSource() != null ? request.getModelSource() : existing.getModelSource())
                .storageBackend(backend)
                .storagePath(storagePath)
                .fileSizeMb(request.getFileSizeMb())
                .build();
        modelMapper.update(updated);
        log.info("✓ 更新模型: {}", updated.getName());
        return toResponse(updated);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        Model model = modelMapper.findById(id);
        if (model == null) {
            throw new ResourceNotFoundException("模型不存在: " + id);
        }
        modelMapper.deleteById(id);
        log.info("✓ 已删除模型: {}", id);
    }

    private void validateStoragePath(String storagePath) {
        if (storagePath == null || !storagePath.startsWith("/")) {
            throw new BadRequestException("模型存储路径必须是 GPU 主机上的 Linux 绝对路径");
        }
    }

    private void validateModelFamily(String modelFamily) {
        if (!MODEL_FAMILIES.contains(modelFamily)) {
            throw new BadRequestException("不支持的模型系列: " + modelFamily);
        }
    }

    private ModelResponse toResponse(Model model) {
        return ModelResponse.builder()
                .id(model.getId())
                .name(model.getName())
                .displayName(model.getDisplayName())
                .description(model.getDescription())
                .modelFamily(model.getModelFamily())
                .modelSource(model.getModelSource())
                .storageBackend(model.getStorageBackend())
                .storagePath(model.getStoragePath())
                .fileSizeMb(model.getFileSizeMb())
                .createdAt(model.getCreatedAt())
                .updatedAt(model.getUpdatedAt())
                .build();
    }
}
