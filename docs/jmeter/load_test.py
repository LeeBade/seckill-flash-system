#!/usr/bin/env python3
"""
Phase 4 全链路压测引擎 (标准库版本，零外部 Python 依赖)
用法:
  python3 load_test.py --scenario smoke
  python3 load_test.py --scenario seckill -c 200 -n 100000
  python3 load_test.py --scenario endurance -c 50 -n 50000
"""

import argparse
import hashlib
import hmac
import json
import os
import random
import sys
import time
import urllib.request
import urllib.parse
import urllib.error
import ssl
from base64 import urlsafe_b64encode
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from threading import Lock

# ═══════════════════════════════════════════════════════════════
# 配置
# ═══════════════════════════════════════════════════════════════

BASE_URL = os.environ.get("SECKILL_URL", "http://127.0.0.1:8080")
SECRET = os.environ.get("SECKILL_SECRET", "dev-test-key-please-change-in-prod")
GOODS_ID = 1
ACTIVITY_ID = 1
PRICE = 1
TOKEN_TTL_MS = 5000
REQUEST_TIMEOUT = 10

USER_ID_START = 10000
USER_ID_END = 110000


@dataclass
class Stats:
    lock: Lock = field(default_factory=Lock)
    total: int = 0
    success: int = 0
    sold_out: int = 0
    already_bought: int = 0
    rate_limited: int = 0
    token_invalid: int = 0
    captcha: int = 0
    system_busy: int = 0
    error: int = 0
    latencies: list = field(default_factory=list)

    def record(self, status_code: int, body: str, latency_ms: float):
        with self.lock:
            self.total += 1
            self.latencies.append(latency_ms)
            try:
                data = json.loads(body)
                code = data.get("code", -1)
            except (json.JSONDecodeError, ValueError):
                code = -1

            if code == 1005:
                self.success += 1
            elif code == 1001:
                self.sold_out += 1
            elif code == 1002:
                self.already_bought += 1
            elif code in (2001,) or status_code == 429:
                self.rate_limited += 1
            elif code in (2002,) or status_code == 403:
                self.token_invalid += 1
            elif code in (2003,):
                self.captcha += 1
            elif code in (9001,) or status_code in (500, 502, 503):
                self.system_busy += 1
            else:
                self.error += 1

    def summary(self, duration_s: float, expected_stock: int = 0) -> str:
        if self.total == 0:
            return "No requests completed"
        lats = sorted(self.latencies)
        p50 = lats[len(lats) // 2]
        p99 = lats[int(len(lats) * 0.99)]
        lines = [
            f"═══ Results ═══",
            f"  Duration:    {duration_s:.1f}s",
            f"  Total:       {self.total}",
            f"  Throughput:  {self.total / duration_s:.1f} req/s",
            f"  ───",
            f"  Success:     {self.success}",
            f"  SoldOut:     {self.sold_out}",
            f"  AlreadyBgt:  {self.already_bought}",
            f"  RateLimited: {self.rate_limited}",
            f"  TokenBad:    {self.token_invalid}",
            f"  Captcha:     {self.captcha}",
            f"  SysBusy:     {self.system_busy}",
            f"  OtherErr:    {self.error}",
            f"  ───",
            f"  Lat P50:     {p50:.1f}ms",
            f"  Lat P99:     {p99:.1f}ms",
            f"  Lat Max:     {lats[-1]:.1f}ms",
            f"  Lat Mean:    {sum(lats) / len(lats):.1f}ms",
        ]
        if expected_stock > 0:
            if self.success > expected_stock:
                lines.append(f"  ** OVERSELL: {self.success} > {expected_stock}")
            elif self.success < expected_stock:
                lines.append(f"  ** UNDERSOLD: {self.success} < {expected_stock}")
            else:
                lines.append(f"  ** Stock OK: {self.success} == {expected_stock}")
        return "\n".join(lines)


def make_token(goods_id: int, user_id: int, secret: str, ttl_ms: int) -> str:
    expire_ts = int(time.time() * 1000) + ttl_ms
    payload = f"{goods_id}:{user_id}:{expire_ts}"
    sig = hmac.new(
        secret.encode("utf-8"), payload.encode("utf-8"),
        hashlib.sha256
    ).digest()
    sig_str = urlsafe_b64encode(sig).decode("utf-8").rstrip("=")
    token_raw = f"{payload}:{sig_str}"
    return urlsafe_b64encode(token_raw.encode("utf-8")).decode("utf-8").rstrip("=")


def single_request(user_id: int, stats: Stats, use_token_endpoint: bool = False) -> None:
    ip = f"192.168.{random.randint(1, 255)}.{random.randint(1, 255)}"

    if use_token_endpoint:
        try:
            params = urllib.parse.urlencode({"goodsId": GOODS_ID, "userId": user_id})
            req = urllib.request.Request(
                f"{BASE_URL}/api/token?{params}",
                headers={"X-User-Id": str(user_id), "X-Forwarded-For": ip}
            )
            with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT) as resp:
                data = json.loads(resp.read())
                token = data.get("token", "")
        except Exception:
            stats.record(0, "", 0)
            return
    else:
        token = make_token(GOODS_ID, user_id, SECRET, TOKEN_TTL_MS)

    try:
        params = urllib.parse.urlencode({"activityId": ACTIVITY_ID, "price": PRICE})
        data_bytes = None
        req = urllib.request.Request(
            f"{BASE_URL}/api/seckill?{params}",
            data=data_bytes,
            headers={
                "X-Token": token,
                "X-User-Id": str(user_id),
                "X-Forwarded-For": ip,
            },
            method="POST",
        )
        t0 = time.monotonic()
        with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT) as resp:
            body = resp.read().decode("utf-8")
            latency = (time.monotonic() - t0) * 1000
            stats.record(resp.status, body, latency)
    except urllib.error.HTTPError as e:
        latency = (time.monotonic() - t0) * 1000
        body = e.read().decode("utf-8", errors="replace")
        stats.record(e.code, body, latency)
    except Exception as e:
        stats.record(0, str(e)[:100], 0)


def main():
    parser = argparse.ArgumentParser(description="seckill-flash-system Phase 4 Load Test (stdlib)")
    parser.add_argument("--scenario", choices=["smoke", "seckill", "endurance"], default="smoke")
    parser.add_argument("--concurrency", "-c", type=int, default=50, help="并发线程数")
    parser.add_argument("--total", "-n", type=int, default=1000, help="总请求数")
    parser.add_argument("--user-pool", type=int, default=None, help="用户池大小")
    parser.add_argument("--use-token-endpoint", action="store_true", help="通过 /api/token 获取令牌")
    parser.add_argument("--expected-stock", type=int, default=0, help="预期库存 (超卖检查)")
    parser.add_argument("--stock", type=int, default=None, help="等价于 --expected-stock")
    args = parser.parse_args()

    pool_size = args.user_pool or args.total
    user_ids = random.sample(
        range(USER_ID_START, USER_ID_END),
        min(pool_size, USER_ID_END - USER_ID_START)
    )
    user_ids = (user_ids * ((args.total // len(user_ids)) + 1))[:args.total]

    print(f"═══ Phase 4 Load Test ═══")
    print(f"  Scenario:   {args.scenario}")
    print(f"  Target:     {BASE_URL}")
    print(f"  Total:      {args.total}")
    print(f"  Concurrent: {args.concurrency}")
    print(f"  User pool:  {min(pool_size, USER_ID_END - USER_ID_START)}")
    print(f"  Token mode: {'api-endpoint' if args.use_token_endpoint else 'local-sign'}")

    stats = Stats()
    t0 = time.monotonic()

    with ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        futures = [
            executor.submit(single_request, uid, stats, args.use_token_endpoint)
            for uid in user_ids
        ]
        done = 0
        for f in as_completed(futures):
            done += 1
            f.result()
            if done % max(1, args.total // 20) == 0:
                elapsed = time.monotonic() - t0
                qps = done / elapsed if elapsed > 0 else 0
                print(f"  [{done}/{args.total}] {done*100//args.total}%  "
                      f"QPS={qps:.0f}  success={stats.success}", flush=True)

    duration = time.monotonic() - t0
    expected = args.expected_stock or args.stock or 0
    print(stats.summary(duration, expected))


if __name__ == "__main__":
    main()
