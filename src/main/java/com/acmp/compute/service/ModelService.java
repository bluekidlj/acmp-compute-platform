package com.acmp.compute.service;

import com.acmp.compute.dto.ModelRequest;
import com.acmp.compute.dto.ModelResponse;
import com.acmp.compute.entity.Model;
import com.acmp.compute.exception.BadRequestException;
import com.acmp.compute.exception.ResourceNotFoundException;
import com.acmp.compute.mapper.ModelMapper;
import com.acmp.compute.util.NfsStoragePathResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 模型广场服务：管理模型文件的元信息。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelService {

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
        String fullPath = NfsStoragePathResolver.resolve(storagePath, request.getName());
        Model model = Model.builder()
                .id(UUID.randomUUID().toString())
                .name(request.getName())
                .displayName(request.getDisplayName())
                .description(request.getDescription())
                .modelSource(request.getModelSource() != null ? request.getModelSource() : "with_weights")
                .storageBackend(backend)
                .storagePath(storagePath)
                .fileSizeMb(request.getFileSizeMb())
                .build();
        modelMapper.insert(model);
        log.info("✓ 新增模型: {} (backend={}, path={})", model.getName(), backend, fullPath);
        return toResponse(model, fullPath);
    }

    public ModelResponse getById(String id) {
        Model model = modelMapper.findById(id);
        if (model == null) {
            throw new ResourceNotFoundException("模型不存在: " + id);
        }
        String fullPath = NfsStoragePathResolver.resolve(model.getStoragePath(), model.getName());
        return toResponse(model, fullPath);
    }

    public ModelResponse getByName(String name) {
        Model model = modelMapper.findByName(name);
        if (model == null) {
            throw new ResourceNotFoundException("模型不存在: " + name);
        }
        String fullPath = NfsStoragePathResolver.resolve(model.getStoragePath(), model.getName());
        return toResponse(model, fullPath);
    }

    public List<ModelResponse> list() {
        List<ModelResponse> result = new ArrayList<>();
        List<Model> models = modelMapper.findAll();
        for (Model model : models) {
            String fullPath = NfsStoragePathResolver.resolve(model.getStoragePath(), model.getName());
            result.add(toResponse(model, fullPath));
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
        Model updated = Model.builder()
                .id(id)
                .name(request.getName())
                .displayName(request.getDisplayName())
                .description(request.getDescription())
                .modelSource(request.getModelSource() != null ? request.getModelSource() : existing.getModelSource())
                .storageBackend(backend)
                .storagePath(storagePath)
                .fileSizeMb(request.getFileSizeMb())
                .build();
        modelMapper.update(updated);
        String fullPath = NfsStoragePathResolver.resolve(storagePath, request.getName());
        log.info("✓ 更新模型: {}", updated.getName());
        return toResponse(updated, fullPath);
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

    private ModelResponse toResponse(Model model, String fullPath) {
        return ModelResponse.builder()
                .id(model.getId())
                .name(model.getName())
                .displayName(model.getDisplayName())
                .description(model.getDescription())
                .modelSource(model.getModelSource())
                .storageBackend(model.getStorageBackend())
                .storagePath(model.getStoragePath())
                .fileSizeMb(model.getFileSizeMb())
                .createdAt(model.getCreatedAt())
                .updatedAt(model.getUpdatedAt())
                .build();
    }
}