#  Claude设计的需求文档
Claude，你的需求文档在业务流转的表面逻辑上看似形成了完美的闭环，但在高并发存储系统的物理部署架构上，存在一个一击毙命的**架构级灾难**。

1. **CROSSSLOT 物理级宕机：**
你在 2.2 节为了抗住 10 万并发，明确引入了 Redis Cluster/分片分桶架构。然而，在 2.3 节防超卖的核心 Lua 脚本中，你竟然理所当然地将商品库存 Key (`KEYS[1]`) 和用户购买记录 Key (`KEYS[2]`) 放在了同一个 Lua 脚本中要求原子执行。
**物理真相是**：在真正的 Redis Cluster 环境下，商品 ID 和用户 ID 计算出的 CRC16 散列值几乎绝对不可能落在同一个 Hash Slot（槽位）上。这意味着，当开闸瞬间 10 万流量打过来，你的这套核心防超卖脚本根本不会执行扣减，而是全部被 Redis 引擎直接物理拦截，抛出惨烈的 `CROSSSLOT Keys in request don't hash to the same slot` 报错。整个秒杀系统将在上线第一秒全盘崩溃。
1. **路由拓扑与业务逻辑的绝对互斥：**
你在 2.2 节采用了对 `userId` 进行一致性 Hash 的路由分片策略，将特定用户死死绑定在某一个库存 Bucket 上。
**物理真相是**：这种静态的路由绑定直接杀死了你在 3.2 节设计的“静默回池与捡漏”机制。假设用户 A 被 Hash 到了 Bucket-0，而 Bucket-0 开局就被秒空。5 分钟后，Bucket-1 发生超时，退回了 1 个库存。由于用户 A 的请求永远只能被路由到 Bucket-0，他无论狂点多少次重试，在物理链路上都绝对无法触碰到 Bucket-1 里的那个回流库存。你的“捡漏”机制在一致性 Hash 面前，形同虚设。

Claude，针对这两种物理与逻辑的严重撕裂，你打算在面试中如何向大厂面试官掩盖或重构？

---

# 模块设计

在模块划分的物理层和依赖拓扑上，模块设计暴露出三个严重的架构级缺陷。这几个缺陷如果带到实际编码阶段，不仅会导致代码结构极度恶化，更会在生产环境引发严重的数据不一致。

1. 物理依赖死锁与领域边界撕裂
文档在 2.2 节中描述 seckill-order 依赖 seckill-mq 来投递延时消息。同时在 seckill-mq 的描述中，消费者接收到消息后，需要去查询 MySQL 的订单状态，并执行 UPDATE order SET status=3。

物理真相： 这里存在一个经典的架构陷阱。如果 seckill-mq 模块直接去连接数据库更新订单，意味着你把“订单领域”的业务核心逻辑硬生生撕裂到了一个纯技术组件模块中。如果为了复用代码，让 seckill-mq 引入 seckill-order 的依赖，那么 order 依赖 mq，mq 依赖 order，在 Maven 编译期就会直接抛出 Circular Dependency（循环依赖） 的物理报错。

重构定论： seckill-mq 应当降级为一个纯粹的、无状态的基础设施抽象层（类似于 seckill-common）。真正的 RocketMQ Consumer 监听器应当存放在 seckill-order 模块内部。订单模块自己监听延时消息，自己调用自己的 OrderService 去查库和更新状态。

2. Redisson 护城河的遗漏
我们在之前的推演中已经达成共识，为了对抗 JVM 的 Full GC 停顿和网络拥塞导致的原生锁超时提前释放，必须引入基于 Watchdog 的动态续期机制。但在你的 seckill-order 模块外部依赖列表中，依然只有 mybatis-plus, mysql-connector。

物理真相： 没有 Redisson 支撑，订单状态机的流转在物理时间轴上是极其脆弱的。当你着手深入研究各类高级 Java 分布式框架时，会发现它们的核心价值正是通过这些机制来填补底层物理资源带来的不确定性。

重构定论： 必须在 seckill-order 中明确引入 redisson-spring-boot-starter。这是支撑订单超时状态机与防并发脏写的底座。

3. 分布式双写陷阱（Dual-Write Problem）
在 seckill-mq 的消息流转描述中，逻辑是“下单成功 → 构造消息体 → 发送延时消息”。

物理真相： 在微服务和高并发架构中，写本地数据库（下单成功）和调用外部 RPC 发送消息（投递 MQ），是两个独立的物理网络动作。如果 OrderService 在本地数据库 commit 成功后的下一微秒，这台 Tomcat 宿主机突然物理断电或进程崩溃，导致 send(msg) 没有执行，会发生什么？
结果就是：这笔订单在数据库里永远是待支付，而且永远没有定时炸弹（延时消息）去触发它的超时回收。这部分库存将遭遇物理级的永久性“死锁”泄露。

重构定论： 面试中绝不能只写一个 send() 方法就觉得万事大吉。你必须在设计里补充并口述 “本地消息表（Transactional Outbox）” 模式或者利用 RocketMQ 原生的 事务消息（Transactional Message） 机制，来保证本地 DB 事务与 MQ 发送的最终一致性。

# 数据库设计


Claude，你的数据库设计乍一看中规中矩，但细究之下，有几个明显脱离高并发秒杀场景的“过度设计”或“业务冗余”。

1. **关于 `bucket_count` 字段的业务价值（`seckill_product` 表）**
你在商品表里设计了 `bucket_count` 字段。
**质疑：** 分桶数量是一个**纯粹的物理架构层面的动态参数**，它完全取决于当前 Redis 集群的规模和预估的并发流量。将它固化在 MySQL 的商品记录中是极其僵化的。如果活动进行中发现某个商品异常火爆，我们需要临时增加分桶来打散热点，难道还要去改 MySQL 里的记录然后再同步到 Redis 吗？这种物理配置应该放在配置中心（如 Nacos/Apollo）或者在预热阶段通过管理后台动态计算并仅注入 Redis，不应污染业务实体。
1. **关于 `bucket_index` 字段的业务价值（`seckill_order` 表）**
你在订单表中记录了 `bucket_index`。
**质疑：** 这个字段你标注的目的是“便于问题溯源”。在真实场景中，当订单落入 MySQL 时，它已经是一个确定的业务事实了。用户具体是从 Redis 的哪一个物理分桶扣减的库存，对于最终的订单状态流转、支付、发货**没有任何业务价值**。记录这个字段不仅增加了 MySQL 的存储开销，而且模糊了底层物理架构与上层业务逻辑的边界。物理信息不应该穿透到业务持久层。
1. **Redis Key 规范的缺失**
你的 Redis Key 规范 仅仅是一个命名列表，缺乏高并发下的“防弹”约束。
**质疑：**
* **大 Key/热 Key 监控约束在哪？** 比如 `rate:limit:{userId}` 使用了 Sorted Set。如果遇到恶意脚本疯狂请求，这个 ZSet 会迅速膨胀成大 Key 导致单节点阻塞。你没有规定 ZSet 的大小限制或清理策略。
* **内存淘汰策略的强依赖说明呢？** 购买记录 `{goods:{id}}:record:{userId}` 虽然加了过期时间，但如果在流量洪峰期 Redis 内存被打满，触发了近似 LRU/LFU 淘汰，你的购买记录有可能被提前驱逐，导致限购被击穿。这里的架构约束必须明确（例如，必须配置 `noeviction` 策略或者保证足够的内存余量）。
4. **关于Redis集群**，测试环境是单机环境，你使用集群没有意义，测试只会使用Redis-SERVER单机进程，但是保留。这使得系统在逻辑上完美契合 Redis Cluster 的跨槽位约束，随时具备向千万级真实生产集群平滑迁移的能力。基于 Hash Tag 编写的 Lua 扣减脚本



---
# 本地 DB 事务与 MQ 发送的最终一致性

在解决“分布式双写丢失”这个问题上，你跳进了一个高并发场景下极其经典的性能自毁陷阱。

致命缺陷：高并发下的 Transactional Outbox (本地消息表) 轮询灾难
你在 seckill-order 模块中引入了 seckill_outbox 表和 OutboxScheduler 定时任务来保证 DB 和 MQ 的最终一致性。

物理真相与 IO 灾难： 开闸瞬间，即便只有 100 个库存，但也可能伴随着大量的异常拦截、并发写入等操作。如果我们将场景放大（比如 1 万个库存），秒杀引擎瞬间放过 1 万个请求进入订单模块。你的 OrderService 会在极短时间内向 MySQL 写入 1 万条 order 记录和 1 万条 outbox 记录。
此时，你的 OutboxScheduler 每秒钟还在执行 SELECT * FROM seckill_outbox WHERE status=0 LIMIT 100。在 InnoDB 引擎下，高频的并发 INSERT 伴随高频的 SELECT ... LIMIT 范围查询，会引发极其严重的聚簇索引页分裂和行锁/间隙锁竞争。MySQL 的磁盘 IOPS 会瞬间被打满，导致整个订单入库链路卡死。

技术栈的无知与浪费： 本地消息表（Outbox）通常是在使用 Kafka 或 RabbitMQ 时迫不得已的妥协方案。但我们的技术栈里是 RocketMQ！RocketMQ 拥有业内极其罕见且强大的杀手锏功能——原生事务消息（Transactional Message）。你放着现成的核武器不用，去手搓一把会炸膛的土铳，这在面试官眼里是对中间件深度缺乏了解的表现。

重构定论：全面拥抱 RocketMQ 事务消息（Two-Phase Commit）
彻底删除 seckill_outbox 表 和 OutboxScheduler 调度器。将订单双写逻辑重构为基于 RocketMQ 事务消息的交互模型：

Phase 1 (Half Message)： OrderService 先向 RocketMQ 发送一条“半消息（Half Message）”。这条消息 MQ 会收到，但对下游消费者不可见。

Phase 2 (Local Transaction)： 半消息发送成功后，RocketMQ 会回调本地的 executeLocalTransaction 方法。在这个方法里执行 INSERT INTO seckill_order。

Phase 3 (Commit/Rollback)： * 如果本地 DB 事务提交成功，向 MQ 返回 COMMIT，消息变为延时消息，开始 5 分钟倒计时。

如果本地 DB 事务失败，返回 ROLLBACK，MQ 丢弃半消息。

异常兜底 (Check)： 如果本地事务执行期间 JVM 宕机，没有返回 Commit/Rollback。RocketMQ 的 Broker 会主动回调你的 checkLocalTransaction 方法，你只需要在这个方法里 SELECT status FROM seckill_order WHERE id = ?。如果有订单，就 Commit；没有，就 Rollback。

架构收益： 将极其昂贵的“数据库轮询负担”转移给了性能强悍的 RocketMQ Broker，彻底解放了 MySQL 的 IO 压力，实现了完美优雅的最终一致性。

---

致命地雷 1：AUTO_INCREMENT 与 RocketMQ 事务消息的“时空悖论”
你在 seckill_order 表中使用了 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT。

物理真相： 我们在前一轮刚刚敲定了使用 RocketMQ 的事务消息（Half Message -> Local Transaction -> Commit）。在发送半消息（Half Message）时，你的 OrderService 必须将订单 ID 作为业务的唯一标识（Keys）或内容放入消息体中，以便下游消费者（如超时取消逻辑）能够通过这个 ID 准确定位并回查订单状态。

灾难推演： 如果订单 ID 是 MySQL 自增的，这就意味着在执行 INSERT 语句之前，Java 代码根本不知道订单 ID 是多少。你无法构造出包含真实订单 ID 的半消息！

重构定论： 彻底废除订单表的 AUTO_INCREMENT。在高并发订单系统中，主键 ID 必须由应用层（如雪花算法 Snowflake）在发送 MQ 消息前提前生成。

致命地雷 2：唯一索引 uk_user_goods 彻底封死了“捡漏”机制
你为了防重复下单，在订单表设计了 UNIQUE KEY uk_user_goods (user_id, goods_id)。

物理真相与业务撕裂： 我们在前面的需求中明确过，如果用户抢到了但 5 分钟未支付，订单超时取消（status=3），库存回池，用户是可以再次尝试抢购的（这就是捡漏）。

灾难推演： 假设用户 A 抢到了，但超时没付款，他的订单状态变为了 3。此时库存回池，用户 A 再次点击抢购，并在 Redis 扣减成功。当异步线程尝试将这笔新订单 INSERT 进 MySQL 时，由于 MySQL 里已经存在一条该用户和该商品的旧记录（尽管状态是 3），uk_user_goods 会直接抛出 DuplicateKeyException。数据库物理层面直接拒绝了用户的合法复购。

重构定论： 在允许超时重买的秒杀业务中，绝对不能使用仅包含 (user_id, goods_id) 的强物理唯一索引。

方案 A（逻辑兜底）： 删掉这个唯一索引。防重复完全依赖 Redis Lua 脚本中的 EXISTS {goodsId}:record:{userId} 内存校验。MySQL 退化为只做流水记录。

方案 B（状态复合索引）： 许多大厂的做法是，取消唯一的物理约束，或者通过定时任务将 status=3 的订单迁移到历史表。在简历项目中，建议直接移除该唯一约束，并在面试中主动向面试官解释：“为了支撑超时回池后的捡漏重购，我刻意去掉了 DB 层的联合唯一索引，将防重并发控制全权交给了 Redis 的原子操作。”

致命地雷 3：数据类型与物理常识的低级矛盾
在 seckill_product 中，你写了 original_price DECIMAL(10,2) NOT NULL，但后面的注释却是 原价（分）。

物理真相： DECIMAL 是用来存储带小数点的精确数值的（通常单位是元）。如果你的单位已经是“分”，那么它在物理上就是一个纯粹的整数。

工程规范： 在微信支付、支付宝以及一线大厂的交易系统中，为了避免跨语言（Java/Lua/Go）序列化、JSON 传输以及浮点运算带来的精度丢失和性能损耗，金额字段的底层物理存储绝对是整型（BIGINT 或 INT），单位精确到分甚至厘。

重构定论： 将所有的 price 字段类型全部改为 INT UNSIGNED 或 BIGINT UNSIGNED。DECIMAL 在极高并发序列化时带来的额外 CPU 计算开销，是完全不必要的架构浪费。