# seckill-flash-system API 接口设计

**since 2026-05-11**

---

# 1. 通用约定

## 1.1 统一响应体

所有接口返回 JSON，结构如下：

```json
{
  "code": 0,
  "message": "success",
  "data": { }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `code` | int | 业务状态码，`0` 表示成功，非 0 表示异常 |
| `message` | string | 人类可读的描述信息 |
| `data` | object / null | 响应数据体，无数据时为 `null` |

## 1.2 全局业务状态码

### 成功

| code | message | 说明 |
|------|---------|------|
| 0 | success | 请求成功 |

### 秒杀业务码 (1xxx)

| code | message | HTTP | 说明 |
|------|---------|------|------|
| 1001 | 商品已售罄 | 200 | 库存为 0，抢购失败 |
| 1002 | 您已购买过该商品 | 200 | 全场限购一件，重复请求被拦截 |
| 1003 | 活动尚未开始 | 200 | 服务端时间未到 start_time |
| 1004 | 活动已结束 | 200 | 服务端时间超过 end_time |
| 1005 | 抢购成功 | 200 | 库存扣减成功，进入支付倒计时 |

### 网关层码 (2xxx)

| code | message | HTTP | 说明 |
|------|---------|------|------|
| 2001 | 请求过于频繁，请稍后重试 | 429 | IP/用户级限流触发 |
| 2002 | Token 无效或已过期 | 403 | 动态 URL Token 校验失败 |
| 2003 | 需要人机验证 | 403 | 流量水位超阈值，触发验证码 |

### 订单码 (3xxx)

| code | message | HTTP | 说明 |
|------|---------|------|------|
| 3001 | 订单不存在 | 404 | 订单 ID 无效 |
| 3002 | 订单已超时取消 | 200 | 支付超时，库存已回池 |
| 3003 | 订单已支付 | 200 | 重复支付的幂等返回 |

### 系统级码 (9xxx)

| code | message | HTTP | 说明 |
|------|---------|------|------|
| 9001 | 系统繁忙，请稍后重试 | 503 | Sentinel 熔断 / Redis 不可达 |
| 9002 | 参数校验失败 | 400 | 请求参数不合法 |
| 9999 | 未知错误 | 500 | 未捕获的服务端异常 |

## 1.3 公共请求头

| Header | 必填 | 说明 |
|--------|------|------|
| `Content-Type` | 是 | `application/json` |
| `X-User-Id` | 是 | 用户 ID（简化认证方案） |
| `X-Request-Id` | 否 | 客户端生成的请求唯一标识，用于幂等和链路追踪 |

---

# 2. 接口清单

| 编号 | 方法 | 路径 | 场景 | 说明 |
|------|------|------|------|------|
| API-01 | GET | `/api/server-time` | 场景 1 | 获取服务端时间 |
| API-02 | GET | `/api/activities` | 场景 1 | 查询进行中的秒杀活动 |
| API-03 | GET | `/api/products` | 场景 1 | 查询活动下的商品列表 |
| API-04 | GET | `/api/seckill/token` | 场景 1 | 获取动态抢购 Token |
| API-05 | POST | `/api/seckill` | 场景 2 | 执行秒杀 |
| API-06 | POST | `/api/captcha/verify` | 场景 4 | 提交人机验证结果 |
| API-07 | GET | `/api/order/{orderId}` | 场景 3 | 查询订单状态 |
| API-08 | POST | `/api/order/{orderId}/pay` | 场景 3 | 模拟支付 |

---

# 3. 接口详细定义

## API-01：获取服务端时间

```
GET /api/server-time
```

**请求参数**：无

**响应示例**：
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "timestamp": 1715472000000
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `data.timestamp` | long | 服务端当前时间戳（毫秒），客户端用此值校准倒计时 |

**调用时机**：
- 页面打开时调用一次，建立时间基准
- 倒计时归零前每 30 秒刷新一次（轻量级，不冲击后端）

---

## API-02：查询进行中的秒杀活动

```
GET /api/activities
```

**请求参数**：无

**响应示例**：
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "activities": [
      {
        "id": 1,
        "name": "618 数码专场",
        "startTime": 1715472000000,
        "endTime": 1715475600000,
        "status": 1
      }
    ]
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | long | 活动 ID |
| `name` | string | 活动名称 |
| `startTime` | long | 活动开始时间（毫秒时间戳） |
| `endTime` | long | 活动结束时间（毫秒时间戳） |
| `status` | int | 0-未开始 1-进行中 2-已结束 |

**业务规则**：
- 仅返回 `status IN (0, 1)` 的活动（未开始 + 进行中）
- 按 `start_time` 升序排列

---

## API-03：查询活动下的商品

```
GET /api/products?activityId={activityId}
```

**请求参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `activityId` | long | 是 | 活动 ID |

**响应示例**：
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "products": [
      {
        "id": 1,
        "name": "iPhone 16 Pro",
        "image": "https://cdn.example.com/iphone16.png",
        "originalPrice": 799900,
        "seckillPrice": 199900,
        "totalStock": 100,
        "soldOut": false
      },
      {
        "id": 2,
        "name": "台灯",
        "image": "https://cdn.example.com/lamp.png",
        "originalPrice": 19900,
        "seckillPrice": 100,
        "totalStock": 200,
        "soldOut": false
      }
    ]
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | long | 商品 ID |
| `name` | string | 商品名称 |
| `image` | string | 商品图片 URL |
| `originalPrice` | int | 原价（单位:分） |
| `seckillPrice` | int | 秒杀价（单位:分） |
| `totalStock` | int | 活动总库存（预热时写入 Redis 的值） |
| `soldOut` | boolean | 是否已售罄（O(1) 单 Key 查询，见下方业务规则） |

**业务规则**：
- 前端展示价格时：`originalPrice / 100.0` → 元
- □□□ `soldOut` 绝不使用 O(N) SUM 查询 □□□

**`soldOut` 的 O(1) 实现——单 Key 标志位 + Caffeine 本地缓存**：
```
正确方案（O(1) 单 Key + 异步写入 + 本地缓存）:
  1. Redis: {goods:{id}}:sold_out → "1" 或不存在
     → 单次 GET，O(1)，微秒级
     → 单实例无分桶，stock 本身就是单 Key——不需要 SUM

  2. 写入时机（最终一致性——允许短暂延迟）:
     seckill-engine 检测到 DECR 后 stock=0:
       → 异步 SET {goodsId}:sold_out "1" EX 60

  3. 本地 Caffeine 缓存:
     Cache<Long, Boolean> soldOutCache = Caffeine.newBuilder()
         .maximumSize(100)
         .expireAfterWrite(2, TimeUnit.SECONDS)
         .build();
     → 列表页先查本地缓存 → 未命中才查 Redis
     → 99% 流量在本地内存完成，零网络 IO

  4. 业务妥协:
     soldOut 标志有最多 2 秒延迟
     → "页面显示可抢，点进去提示售罄" — 完全可接受
     → "为了一致性把 Redis 查宕机" — 绝对不可接受
```

---

## API-04：获取动态抢购 Token

```
GET /api/seckill/token?goodsId={goodsId}
```

**请求参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `goodsId` | long | 是 | 商品 ID |

**请求头**：`X-User-Id` 必填

**响应示例**（成功）：
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "token": "a1b2c3d4e5f6...",
    "expireTime": 1715472005000
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `data.token` | string | 动态加密 Token，有效期 60 秒 |
| `data.expireTime` | long | Token 过期时间毫秒戳 |

**错误响应**：
```json
{
  "code": 1004,
  "message": "活动已结束",
  "data": null
}
```

**Token 结构——纯无状态 HMAC，零 Redis 操作**：
```
Token 格式: base64( goodsId + ":" + userId + ":" + expireTimestamp + ":" + signature )

signature = HMAC-SHA256( goodsId + userId + expireTimestamp , SECRET_KEY )

完整示例:
  payload = "1:888:1715472005"
  signature = HMAC-SHA256("18881715472005", "my-secret-key-32byte!!")
  token = base64("1:888:1715472005:a1b2c3d4e5f6...")
```

**签发逻辑（API-04 Controller，纯 CPU，不碰 Redis）**：
```
1. 校验: 服务端时间 ≥ 活动 start_time，否则返回 1003/1004
2. 计算 expireTimestamp = now() + 60 秒
3. 计算 HMAC-SHA256(goodsId + userId + expireTimestamp, SECRET_KEY)
4. 拼接 token = base64(goodsId + ":" + userId + ":" + expireTimestamp + ":" + signature)
5. 返回 token（纯数学运算，不写 Redis）
```

**校验逻辑（DynamicUrlFilter，纯 CPU，不碰 Redis）**：
```
1. 解码 token → 提取 goodsId, userId, expireTimestamp, signature
2. 校验 expireTimestamp > now()（过期即拒）
3. 重算 HMAC-SHA256(goodsId + userId + expireTimestamp, SECRET_KEY)
4. 比对签名（Constant-time comparison 防时序攻击）
5. 通过 → 放行；失败 → 返回 2002
```

**□ □□ 为什么不用 Redis 存储 Token □□□**：
```
HMAC 的数学本质就是"无需存储的签名验证"——服务端有密钥就能独立验证。
把 HMAC 结果存进 Redis 等于:
  - 10 万 QPS × SET EX → 10 万次 Redis 写入 → 网络 IO + 内存分配 + Dict rehash
  - 10 万 QPS × DEL → 10 万次 Redis 删除
  - 20 万次不必要的 Redis 操作在 T=0 瞬间涌入 → 与秒杀核心争抢 Redis 单线程

抗重放不需要 Token 层拦截——核心 Lua 脚本中的
  EXISTS {goodsId}:record:{userId} 已经能保证绝对幂等。
  同一个 userId 即使重放 1000 次有效 Token，Lua 也只扣一次库存。
```

**前端行为**：
- 倒计时归零瞬间 → 调用此接口 → 获得 Token
- 拼接真实 URL：`POST /api/seckill` body=`{goodsId, userId, token}`
- Token 过期 → 重新获取

---

## API-05：执行秒杀

```
POST /api/seckill
```

**请求头**：`X-User-Id` 必填，`X-Request-Id` 建议携带

**请求体**：
```json
{
  "goodsId": 1,
  "token": "a1b2c3d4e5f6...",
  "captchaTicket": "ticket_xxx"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `goodsId` | long | 是 | 商品 ID |
| `token` | string | 是 | API-04 获取的动态 Token |
| `captchaTicket` | string | 否 | 验证码通过后下发的凭证（仅触发降级时携带） |

**响应示例**（抢购成功）：
```json
{
  "code": 1005,
  "message": "抢购成功",
  "data": {
    "orderId": 1234567890123456789,
    "status": 0,
    "payDeadline": 1715472300000
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `orderId` | long | Snowflake 生成的订单 ID |
| `status` | int | 0-待支付 |
| `payDeadline` | long | 支付截止时间（create_time + 5 分钟） |

**响应示例**（已售罄）：
```json
{
  "code": 1001,
  "message": "商品已售罄",
  "data": null
}
```

**响应示例**（已购买过）：
```json
{
  "code": 1002,
  "message": "您已购买过该商品",
  "data": null
}
```

**响应示例**（触发验证码）：
```json
{
  "code": 2003,
  "message": "需要人机验证",
  "data": {
    "challengeId": "ch_abc123",
    "challengeType": "MATH",
    "challengeQuestion": "3 + 7 = ?"
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `data.challengeId` | string | 验证码会话 ID |
| `data.challengeType` | string | `MATH` 算术题 / `SLIDER` 滑块 |
| `data.challengeQuestion` | string | 仅 MATH 类型时返回题目 |

**全链路处理流程**（服务端）：
```
1. IpRateLimitFilter   → IP 令牌桶（Caffeine getIfPresent，未知 IP 放行）
2. UserBloomFilter     → Bloom Filter 否决不存在 userId
3. RateLimitFilter     → userId ZSet 滑动窗口（Redis Lua）
4. WaterLevelFilter    → QPS 水位检测，超阈值返回 code=2003
5. DynamicUrlFilter    → Token 校验（纯 CPU HMAC 重算，无 Redis 操作）
6. Controller          → 调用 seckill-engine.execute(goodsId, userId)
7. seckill-engine      → EVALSHA seckill_deduct.lua 原子扣减
8. 返回结果            → 异步触发 RocketMQ 事务消息
```

**前端行为**：
- 收到 `code=2003` → Axios 拦截器保存请求上下文 → 弹出验证组件
- 完成验证 → 调用 API-06 → 拿到 `captchaTicket`
- 自动重放原请求（携带 `captchaTicket`）
- 前端无需感知 `X-Request-Id` 幂等机制（框架层透明）

---

## API-06：提交人机验证

```
POST /api/captcha/verify
```

**请求头**：`X-User-Id` 必填

**请求体**：
```json
{
  "challengeId": "ch_abc123",
  "answer": "10"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `challengeId` | string | 是 | API-05 返回的验证会话 ID |
| `answer` | string | 是 | 用户提交的答案 |

**响应示例**（验证通过）：
```json
{
  "code": 0,
  "message": "验证通过",
  "data": {
    "captchaTicket": "ticket_xxx"
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `data.captchaTicket` | string | 验证凭证，API-05 重放时携带，有效期 30 秒 |

**响应示例**（验证失败）：
```json
{
  "code": 0,
  "message": "验证失败",
  "data": {
    "retry": true
  }
}
```

**业务规则**：
- `challengeId` → Redis `GET captcha:{challengeId}` → 读取正确答案
- 比对答案 → 通过 → `SETEX captcha:ticket:{ticket} "{userId}:verified" EX 30` + `DEL captcha:{challengeId}`
- `captchaTicket` 一次性使用，API-05 校验后 `DEL`

---

## API-07：查询订单状态

```
GET /api/order/{orderId}
```

**路径参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `orderId` | long | 是 | 订单 ID |

**请求头**：`X-User-Id` 必填（与订单所属用户校验）

**响应示例**：
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "orderId": 1234567890123456789,
    "goodsId": 1,
    "goodsName": "iPhone 16 Pro",
    "seckillPrice": 199900,
    "status": 0,
    "statusText": "待支付",
    "createTime": 1715472000000,
    "payDeadline": 1715472300000,
    "payTime": null,
    "cancelTime": null
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `orderId` | long | 订单 ID |
| `goodsId` | long | 商品 ID |
| `goodsName` | string | 商品名称（JOIN 查询） |
| `seckillPrice` | int | 秒杀成交价（单位:分） |
| `status` | int | 0-待支付 1-已支付 2-已取消 3-超时取消 |
| `statusText` | string | 状态中文描述 |
| `createTime` | long | 下单时间（毫秒戳） |
| `payDeadline` | long | 支付截止时间（createTime + 5 分钟） |
| `payTime` | long / null | 支付成功时间 |
| `cancelTime` | long / null | 取消时间 |

**业务规则**：
- 校验订单是否属于当前 `X-User-Id`，不匹配返回 3001
- 前端根据 `status` 展示对应状态和倒计时

---

## API-08：模拟支付

```
POST /api/order/{orderId}/pay
```

**路径参数**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `orderId` | long | 是 | 订单 ID |

**请求头**：`X-User-Id` 必填

**响应示例**（支付成功）：
```json
{
  "code": 0,
  "message": "支付成功",
  "data": {
    "orderId": 1234567890123456789,
    "status": 1,
    "payTime": 1715472100000
  }
}
```

**响应示例**（订单已超时取消）：
```json
{
  "code": 3002,
  "message": "订单已超时取消，库存已回池",
  "data": {
    "orderId": 1234567890123456789,
    "status": 3
  }
}
```

**响应示例**（重复支付——幂等）：
```json
{
  "code": 3003,
  "message": "订单已支付",
  "data": {
    "orderId": 1234567890123456789,
    "status": 1,
    "payTime": 1715472100000
  }
}
```

**业务规则**：
```
1. 查询订单，校验归属
2. 若 status=0 (待支付):
   → Redisson RLock 获取锁
   → UPDATE seckill_order SET status=1, pay_time=NOW() WHERE id=? AND status=0
   → 释放锁
   → 返回支付成功
3. 若 status=1 (已支付) → 幂等返回 3003
4. 若 status=2/3 (已取消/超时) → 返回 3002
```

**注意**：此接口仅模拟支付结果，不接入真实支付渠道（支付宝/微信）。面试中需说明："真实环境中此处会对接支付宝异步通知（notify_url），并在回调中执行相同的状态流转逻辑。"

---

# 4. 前端集成说明

## 4.1 请求生命周期

```
页面打开
  ├── GET /api/server-time              → 校准客户端时钟
  ├── GET /api/activities               → 加载活动列表
  └── GET /api/products?activityId=1    → 加载商品列表

倒计时中（服务端时间驱动，每 30s 刷新 /api/server-time）
  └── 前端展示倒计时 + 商品信息 + 已售罄标记

倒计时归零 → [立即抢购] 按钮可点击
  用户点击
    ├── GET /api/seckill/token?goodsId=1  → 获取 Token
    └── POST /api/seckill                 → 发起秒杀
          ├── code=1005 → 跳转支付页
          ├── code=1001 → 提示"已售罄"（允许继续尝试捡漏）
          ├── code=1002 → 提示"已购买"
          └── code=2003 → 弹出验证码 → POST /api/captcha/verify → 重放 POST /api/seckill

支付页
  ├── GET /api/order/{orderId}           → 展示订单详情 + 倒计时
  └── 用户点击 [支付]
        └── POST /api/order/{orderId}/pay  → 支付结果
```

## 4.2 Axios 拦截器伪代码（前端参考）

```javascript
// 响应拦截器——自动处理验证码降级
axios.interceptors.response.use(
  response => response,
  async error => {
    const { code, data } = error.response.data;
    if (code === 2003) {
      // 保存原始请求
      const originalRequest = error.config;
      // 弹出验证组件
      const answer = await showCaptcha(data.challengeQuestion);
      // 提交验证
      const verifyResp = await axios.post('/api/captcha/verify', {
        challengeId: data.challengeId,
        answer: answer
      });
      // 重放原请求
      originalRequest.data.captchaTicket = verifyResp.data.data.captchaTicket;
      return axios(originalRequest);
    }
    return Promise.reject(error);
  }
);
```

---

# 5. 附录：接口与模块映射

| 接口 | Controller (seckill-web) | 核心调用链 |
|------|--------------------------|-----------|
| API-01 | `ServerTimeController` | `System.currentTimeMillis()` |
| API-02 | `ActivityController` | `seckill-product` → Mapper |
| API-03 | `ProductController` | `seckill-product` → Mapper + Redis Stock 查询 |
| API-04 | `TokenController` | HMAC-SHA256 生成（纯 CPU，零 Redis） |
| API-05 | `SeckillController` | Filter Chain（HMAC 验签 纯CPU） → `seckill-engine.execute()` → `seckill-order` (RocketMQ TM) |
| API-06 | `CaptchaController` | Redis GET/DEL |
| API-07 | `OrderController` | `seckill-order` → Mapper |
| API-08 | `OrderController` | `seckill-order` → Redisson RLock → Mapper |

---

# EOF
