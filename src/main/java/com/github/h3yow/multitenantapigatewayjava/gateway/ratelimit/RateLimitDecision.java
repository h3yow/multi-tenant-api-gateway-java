package com.github.h3yow.multitenantapigatewayjava.gateway.ratelimit;

/**
 * 一次限流判定结果。
 *
 * @param allowed        是否放行
 * @param limit          当前窗口配额
 * @param remaining      放行后剩余额度（被拒绝时为 0）
 * @param resetEpochMillis 当前固定窗口的刷新时间点（对齐后的下一个窗口起点）
 * @param windowStartEpochMillis 当前窗口起点
 */
public record RateLimitDecision(boolean allowed,
                                long limit,
                                long remaining,
                                long resetEpochMillis,
                                long windowStartEpochMillis) {
}
