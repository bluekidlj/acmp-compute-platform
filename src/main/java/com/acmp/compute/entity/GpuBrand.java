package com.acmp.compute.entity;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * GPU 品牌枚举。name() 即前后端传输的字符串值。
 */
public enum GpuBrand {
    NVIDIA("NVIDIA GPU"),
    HYGON("海光 DCU"),
    HUAWEI_ASCEND("华为昇腾 910B");

    private final String label;

    GpuBrand(String label) { this.label = label; }

    @JsonValue
    public String getValue() { return name(); }

    public String getLabel() { return label; }
}
