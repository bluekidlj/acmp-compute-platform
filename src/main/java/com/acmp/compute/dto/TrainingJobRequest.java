package com.acmp.compute.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.List;

/**
 * 提交训练任务请求。
 * 训练任务也走"规格 + 副本数"模型：spec 决定 GPU 资源键、cpu/mem、nodeSelector、tolerations。
 */
@Data
public class TrainingJobRequest {
    @NotBlank
    private String jobName;

    @NotBlank
    private String image;

    /** 任务副本数（VolcanoJob 的 task.replicas） */
    @NotNull @Min(1)
    private Integer replicas;

    /** 算力规格名（compute_spec.name） */
    @NotBlank
    private String specName;

    /** 启动命令（可选） */
    private List<String> command;
}
