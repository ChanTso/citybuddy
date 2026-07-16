# Slice lessons

This file records only factual pitfalls supported by merged pull-request, commit, or test evidence. It is not a route-status, contract, acceptance, test, or review source. Each slice records an explicit `none` when its merged evidence contains no substantive pitfall.

## CB-010 — MySQL migration and access foundation

- 现象：MySQL 要求转授权限的账号自身持有该权限及 `GRANT OPTION`，与原先“bootstrap 绝不具备业务数据能力”的绝对表述不能同时成立。
- 证据链接：[slice PR #6](https://github.com/ChanTso/citybuddy/pull/6)、[approved contract correction PR #5](https://github.com/ChanTso/citybuddy/pull/5)
- 根因：MySQL 的授权模型要求授权者先拥有被转授权限；不能靠一个完全没有该权限的账号完成授权。
- 解决：把可转授权限放入非默认角色，只允许固定清单的一次性 grant job 显式启用并清除该角色，同时验证 `activate_all_roles_on_login=OFF`、执行前 `CURRENT_ROLE()=NONE` 和拒绝路径先失败后不变更授权。
- 结论：数据库安全边界必须符合数据库引擎真实授权语义；用短时、非默认角色和固定清单缩小能力窗口，不能依赖不可能实现的绝对描述。

## CB-011 — Dual Redis runtime foundation

- 现象：none
- 证据链接：[slice PR #9](https://github.com/ChanTso/citybuddy/pull/9)
- 根因：none
- 解决：none
- 结论：合并证据未记录实质踩坑。

## CB-012 — Elasticsearch and IK runtime foundation

- 现象：最初的缺失 IK 拒绝测试通过“修改现有容器再重启”构造故障；Linux Compose 会重建容器，导致测试可能没有验证预期的无 IK 实例，形成 false-green 风险。
- 证据链接：[slice PR #10](https://github.com/ChanTso/citybuddy/pull/10)、[portable rejection commit `cefbe53`](https://github.com/ChanTso/citybuddy/commit/cefbe539b349f95131b366040967ca46e6cc88a8)
- 根因：故障注入依赖 Compose 对已修改容器的复用行为，而该行为跨平台不稳定。
- 解决：使用独立 fault project 启动同一 digest 的官方 Elasticsearch 镜像但不安装 IK，并验证容器 digest、unhealthy 状态、插件目录和插件清单。
- 结论：基础设施拒绝测试应创建可独立证明的故障实例，不应依赖容器是否被复用的实现细节。

## CB-013 — RocketMQ Broker and Proxy foundation

- 现象：初稿把探针 ACK 后的一次未重复读取解释成消息只投递一次，并提前把 RocketMQ standalone round-trip 接入 CB-014 才拥有的 aggregate target。
- 证据链接：[slice PR #12](https://github.com/ChanTso/citybuddy/pull/12)、[review correction commit `4ac2e60`](https://github.com/ChanTso/citybuddy/commit/4ac2e60a6ae425e4186f573c426c8e1085ae5c38)
- 根因：清理性的 ACK 被扩大成了 delivery-semantics 证明，同时实现越过了当前切片的 aggregate-wiring 边界。
- 解决：探针只证明一个唯一标识的消息在单批次中恰好匹配一次，ACK 仅用于清理并删除“ACKNOWLEDGED/secondDelivery”语义；aggregate target 留给 CB-014。
- 结论：探针只能声明它真实观察到的行为，清理动作不能升级为可靠性协议证明，后续切片拥有的编排也不能提前落地。

## CB-014 — Aggregate runtime integration closure

- 现象：独立评审发现 MySQL probe 仍继承默认 Redis host ports，可能与开发者已运行拓扑冲突；README 也遗漏了完整集成路径实际需要的 Docker Compose、OpenSSL 和 `sha256sum`。
- 证据链接：[slice PR #14](https://github.com/ChanTso/citybuddy/pull/14)、[isolation and prerequisites commit `d8597cd`](https://github.com/ChanTso/citybuddy/commit/d8597cd4ddcf26a1a0eb80f3d04cec83662d0cbb)
- 根因：聚合测试新增了跨组件依赖，但端口隔离和本地工具前置条件没有同步扩展到完整拓扑。
- 解决：隔离 MySQL probe 使用到的 MySQL、Redis、Elasticsearch 和 RocketMQ host ports，并补全实际工具前置条件；重新运行组件、aggregate 和完整 CI。
- 结论：聚合测试的隔离面与依赖清单必须覆盖它间接启动的每个组件，而不只是测试名称对应的主组件。

## CB-020 — Identity, JWKS, and JIT OBO chain

- 现象：合并 PR 只证明跨 Auth、Agent、Commerce 的身份链经过两轮有界评审/修复后 `NO BLOCKER`，但没有保留可链接的逐条 finding，因此不能可靠回填更具体的技术坑。
- 证据链接：[slice PR #16](https://github.com/ChanTso/citybuddy/pull/16)
- 根因：none；现有合并证据不足以对两轮 finding 作更细归因。
- 解决：两轮修订后重新运行 identity integration 与完整 `make ci`，并覆盖 token 模式、issuer/audience、actor/session、scope、密钥刷新/轮换和跨用户拒绝路径。
- 结论：仅记录能够由合并证据证明的评审循环和最终覆盖，不反向猜测已修复 finding 的具体内容。

## CB-030 — Product catalog cache

- 现象：新增 Commerce `V002` migration 后，aggregate runtime test 仍硬编码 Commerce 只有一个 migration，导致首轮完整 `make ci` 失败。
- 证据链接：[slice PR #17](https://github.com/ChanTso/citybuddy/pull/17)
- 根因：测试把会随仓库增长的 migration 数量写成常量，没有从受版本控制的 migration 文件推导期望值。
- 解决：用已检入的 `V*__*.sql` 文件数量计算期望 migration 数，重跑 targeted runtime integration、完整 diff 独立复核和完整 `make ci`。
- 结论：对仓库内可枚举资产的数量断言应从同一权威资产集合推导，避免新增合法文件时产生维护性失败。

## CB-040 — Standard ordering and MySQL inventory

- 现象：真实 MySQL 证据暴露两个设计问题：实现原本需要未获授权的 migration `ALTER` 才能提供额外 inventory version，且 `SELECT ... FOR UPDATE` 所在 idempotency lock table 缺少 MySQL 实际要求的精确 `UPDATE` 权限；随后评审还补齐了稳定 malformed-body 错误映射、错误 issuer/audience/type 路由拒绝和 idempotency guard 基数断言。
- 证据链接：[slice PR #22](https://github.com/ChanTso/citybuddy/pull/22)、[closeout evidence](https://github.com/ChanTso/citybuddy/pull/22#issuecomment-4981130003)
- 根因：最初设计没有完全贴合现有 schema 可用权威值和 MySQL 锁定读取的真实权限语义，部分路由与并发守卫拒绝证据也不够直接。
- 解决：直接使用权威 stock 值作为 optimistic inventory version，为锁表授予最小所需 `UPDATE`，并补齐错误映射、token 拒绝和 per-key/cardinality assertions 后重跑完整 CI。
- 结论：事务设计与最小权限必须用真实数据库执行验证；框架或 SQL 表面语义不能替代引擎实际权限和锁行为。

## CB-050 — Seckill activity allocation and versioned projection

- 现象：Java `Instant` 可携带纳秒精度，而 MySQL `TIMESTAMP(6)` 只保留微秒；首次写入后若继续用未归一化输入构造 Redis projection，existing-key rebuild 会把同一事实误判为冲突。
- 证据链接：[slice PR #23](https://github.com/ChanTso/citybuddy/pull/23)
- 根因：Redis projection 使用了写前输入精度，而 MySQL 权威事实已按列精度归一化，两个表示不再逐字相同。
- 解决：提交后重新读取 MySQL 中的权威事实再投影，并用 sub-microsecond 输入验证 existing-key 幂等 rebuild。
- 结论：跨存储投影必须从持久化后的权威表示生成，特别要显式处理时间和数值精度收缩。

## CB-051 — Seckill reservation and atomic admission

- 现象：独立评审发现四项 admission/rebuild invariant 缺口，以及 oversized TTL 可能在失败前留下 partial write 的风险。
- 证据链接：[slice PR #24](https://github.com/ChanTso/citybuddy/pull/24)
- 根因：Lua 的原子性不能自动保证输入数值、TTL 上限、版本关系和 rebuild marker 的业务完整性；边界校验不完整时，脚本仍可能原子地写入错误或部分状态。
- 解决：补齐 identity/version/number/TTL 与 corrupt-marker 校验，在任何写入前拒绝不安全 CJSON integer、sub-millisecond/oversized TTL、partial/version-lag state，并以真实 Redis/MySQL 重跑集成与完整 CI。
- 结论：原子脚本只保证“操作不可分”，不保证“决定正确”；所有可能影响写入集合的边界必须在首次 mutation 前验证完毕。

## CB-060 — RocketMQ transaction admission and seckill order creation

- 现象：Broker 配置 `transactionCheckMax=3`，真实重复 `UNKNOWN` drill 却只观察到 2 次 checker callback；尝试通过应用订阅 `TRANS_CHECK_MAX_TIME_TOPIC` 取证时，RocketMQ Proxy 返回 `40002 cannot access system topic`。因此应用既不能把 callback 次数当终止协议，也不能通过 Proxy 读取系统终止主题。
- 证据链接：[slice PR #25](https://github.com/ChanTso/citybuddy/pull/25)、[approved contract clarification `5edb73c`](https://github.com/ChanTso/citybuddy/commit/5edb73c009f570d9e68ef6e6e9efd2b6fe1e31b8)、[deadline convergence `54999ee`](https://github.com/ChanTso/citybuddy/commit/54999ee03903455dc3e41a370489ca1f4d72cf4f)、[checker evidence `750dfdf`](https://github.com/ChanTso/citybuddy/commit/750dfdf7097e129405896358063c6d64ab90d39d)、[marker validation recovery `52939b0`](https://github.com/ChanTso/citybuddy/commit/52939b0d8af1a9d5dba62ea9f1cf5cf2b38faa02)
- 根因：Broker 的终止时机并没有通过应用可依赖的 callback-count 协议暴露，系统终止主题又属于 Proxy 禁止应用访问的系统边界；把实测次数或消息 payload 当 durable truth 会让重启和竞争结果不可靠。
- 解决：废弃两个未提交恢复方案——应用消费系统主题，以及按 checker callback 计数/修改消息 payload 终止。最终在 MySQL 持久化一次性 `transaction_resolution_due_at`，由 Broker check window 配置推导上限并加安全余量且重启不重算；索引化 worker 以 32 行有界批量处理到期 `PENDING`，Redis Lua CAS 让 admission/timeout 第一个合法决定胜出并写 durable marker，再幂等收敛 MySQL。checker 恢复为只读 marker 的纯映射，不可读 Redis 返回 `UNKNOWN`，Broker 终态由独立 `mqadmin` 系统主题取证。
- 结论：`3` 与实测 `2` 只是诊断差异，绝不能固化为应用协议。应用终态应由自己的持久截止时间和 durable CAS 决定，Broker 终态则由独立管理面取证；两者职责分离后，admission/timeout 两种竞争顺序、Redis 成功/MySQL 失败重启、三个崩溃窗口、ADMITTED 不被覆盖和 timeout 无 order/ledger 都可收敛并被真实测试证明。

## CB-061 — Delayed unpaid cancellation and ledger restoration

- 现象：首轮真实 migration 证据发现，终态行缺少 `decision_code` 仍能通过 V007 的组合 `CHECK`；后续真实延迟集成又暴露 2 秒到期窗口过紧，以及把 durable MySQL 状态与 Redis projection 断言混在一起会污染下一用例。完整 diff 自检还发现，取消后恢复活动配额不等于允许同一用户再次下单；独立复核进一步发现，按 MySQL 当前计数绝对覆盖 Redis 会漏掉“Redis 已 durable ADMITTED、MySQL 仍 PENDING”的并发扣减，可能放大剩余额度。
- 证据链接：[slice PR #27](https://github.com/ChanTso/citybuddy/pull/27)、[implementation commit `ebdceab`](https://github.com/ChanTso/citybuddy/commit/ebdceab)
- 根因：MySQL `CHECK` 表达式结果为 `UNKNOWN` 时不会拒绝该行，组合条件没有显式要求终态决定非空；异步测试把调度抖动当作精确时钟，并混淆了 durable truth 与可失败 projection；配额恢复和用户唯一性是两个独立不变量，而 Redis durable marker 又可能暂时领先 MySQL 状态，不能被 MySQL 快照当作不存在。
- 解决：在终态约束中增加显式 `decision_code IS NOT NULL`；使用稳定到期余量并拆分 MySQL/Redis 证据，增加受控事务中途异常的完整回滚验证；CANCELLED reservation 不再计入已占活动配额，但 rebuild 仍重建用户 marker。每次取消同时持久化自己的 projection version，提交后由 Redis Lua 只执行相邻版本的原子增量恢复，保留并发 admission 已产生的扣减；缺失或跨版本不做绝对覆盖，而是失败后通过完整 MySQL rebuild 收敛。真实测试覆盖 durable-marker/MySQL-PENDING 与 MySQL 已提交、Redis 尚未迁移两个窗口。
- 结论：数据库约束必须按 SQL 三值逻辑写出非空前提；异步证据要给真实调度留余量并分离权威状态与投影；“恢复额度”和“保留一次购买资格已使用”必须分别建模，跨存储恢复还必须保留可能领先于 MySQL 的 durable 决定。
