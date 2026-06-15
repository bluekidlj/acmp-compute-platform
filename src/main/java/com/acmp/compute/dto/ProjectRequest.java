package com.acmp.compute.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.util.List;

@Data
public class ProjectRequest {
    @NotBlank
    private String name;
    private String description;
    private List<String> memberIds;
}
