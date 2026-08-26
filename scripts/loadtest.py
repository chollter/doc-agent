# -*- coding: utf-8 -*-
"""
并发压测脚本——产出 QPS / 延迟分布(P50/P95) / token 成本三组数字。

用法:
  python scripts/loadtest.py --base http://localhost:8020 --file sample-docs/tech-spec.md \
      --runs 12 --skill document-analysis

前提: 应用以 --docagent.analysis.export-enabled=false 启动（压测跳过 HITL 确认）。
"""
import argparse
import json
import statistics
import time
import urllib.error
import urllib.request
import uuid


def post_run(base, file_path, instruction, skill):
    boundary = uuid.uuid4().hex
    with open(file_path, "rb") as f:
        content = f.read()
    filename = file_path.replace("\\", "/").split("/")[-1]
    body = b"".join([
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\n\r\n".encode(),
        content, b"\r\n",
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"instruction\"\r\n\r\n{instruction}\r\n".encode(),
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"skill\"\r\n\r\n{skill}\r\n".encode(),
        f"--{boundary}--\r\n".encode(),
    ])
    req = urllib.request.Request(
        base + "/api/analysis/runs", data=body,
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))["runId"]


def get_json(base, path):
    with urllib.request.urlopen(base + path, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def percentile(sorted_values, p):
    if not sorted_values:
        return 0
    idx = min(len(sorted_values) - 1, int(round(p / 100.0 * (len(sorted_values) - 1))))
    return sorted_values[idx]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:8020")
    parser.add_argument("--file", default="sample-docs/tech-spec.md")
    parser.add_argument("--runs", type=int, default=12)
    parser.add_argument("--skill", default="document-analysis")
    parser.add_argument("--instruction", default="提炼核心内容与风险")
    parser.add_argument("--timeout-sec", type=int, default=600)
    args = parser.parse_args()

    print(f"压测: {args.runs} 个 run, skill={args.skill}, file={args.file}")
    started = {}
    submitted = []
    submit_start = time.time()
    for i in range(args.runs):
        run_id = post_run(args.base, args.file, args.instruction, args.skill)
        started[run_id] = time.time()
        submitted.append(run_id)
    submit_wall = time.time() - submit_start
    print(f"提交完成: {args.runs} 个, 提交阶段耗时 {submit_wall:.1f}s")

    done = {}
    deadline = time.time() + args.timeout_sec
    while len(done) < args.runs and time.time() < deadline:
        for run_id in submitted:
            if run_id in done:
                continue
            try:
                detail = get_json(args.base, f"/api/analysis/runs/{run_id}")
            except urllib.error.URLError:
                continue
            if detail["status"] in ("COMPLETED", "FAILED"):
                done[run_id] = detail
        time.sleep(2)

    latencies, tokens, modes, failures = [], [], {}, []
    for run_id, detail in done.items():
        latencies.append(time.time() - started[run_id] if detail["status"] == "COMPLETED" else None)
        if detail["status"] != "COMPLETED":
            failures.append((run_id, detail["status"], detail.get("lastError")))
        tokens.append(detail.get("tokensUsed") or 0)
        modes[detail.get("executionMode")] = modes.get(detail.get("executionMode"), 0) + 1

    ok = sorted(v for v in latencies if v is not None)
    total_wall = time.time() - submit_start
    print("\n===== 压测结果 =====")
    print(f"完成/失败: {len(ok)}/{len(failures)}")
    if ok:
        print(f"单run延迟(提交→完成, 含排队): P50={percentile(ok,50):.1f}s  P95={percentile(ok,95):.1f}s  "
              f"min={ok[0]:.1f}s  max={ok[-1]:.1f}s  均值={statistics.mean(ok):.1f}s")
    print(f"总墙钟: {total_wall:.1f}s  吞吐: {len(ok)/total_wall*60:.1f} run/分钟 "
          f"(单实例, 3 worker, 串行批次)")
    print(f"模式分布: {modes}")
    total_tokens = sum(tokens)
    print(f"token 合计: {total_tokens:,} (均值 {int(statistics.mean(tokens)):,}/run)")
    # qwen-plus 参考价: 输入 ¥0.8/M, 输出 ¥2/M —— 按输入:输出 ≈ 4:1 粗估
    est_cost = total_tokens * 0.8 / 1_000_000 * 0.8 + total_tokens * 2 / 1_000_000 * 0.2
    print(f"成本粗估(输入:输出=4:1): ¥{est_cost:.2f}  单run均值 ¥{est_cost/max(1,len(ok)):.4f}")
    for f in failures:
        print("  失败:", f)


if __name__ == "__main__":
    main()
