package com.gcll.docagent.analysis;

/**
 * 子方向适配——分化要求不报"缺口"，转为"你最适合哪个子方向"的定位信息。
 */
public record VariantFit(String variantId, String name, Fit fit, String reason) {

    public enum Fit { HIGH, MEDIUM, LOW }
}
