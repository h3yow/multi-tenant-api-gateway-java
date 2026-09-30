package com.github.h3yow.multitenantapigatewayjava.ratelimit;

/**
 * 固定窗口判定结果。
 *
 * @param allowed      是否放行
 * @param limit        窗口配额
 * @param remaining    放行后窗口剩余额度（拒绝时为 0）
 * @param windowStart  窗口起始 epoch 毫秒
 * @param windowEnd    窗口结束 epoch 毫秒（额度刷新时刻）
 */
public record RateLimitWindow(boolean allowed,
                              int limit,
                              int remaining,
                              long windowEnd) {
}
