package com.cugb.tool;

/**
 * 用于定义工具参数的元数据
 */
public record Param(
        String name,
        String type,
        String description,
        boolean required
) {}
