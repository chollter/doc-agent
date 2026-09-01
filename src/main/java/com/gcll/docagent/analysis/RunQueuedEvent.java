package com.gcll.docagent.analysis;

/**
 * 入队事件——start()/followUp() 把 run 写入 DB 后立即发布。
 * {@link RunQueueScheduler} 收到后即刻触发一轮认领（快路径），轮询循环是兜底。
 * <p>目的：让提交→执行之间存在一条看得见的代码链路，读代码时无需靠猜
 * "有个定时器在轮询"才能找到执行入口。
 */
public record RunQueuedEvent(String runId) {
}
