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

## CB-070 — Idempotent mock payment, authenticated callback, and payment ledger transitions

- 现象：两个相同回调同时进入事务时，旧实现先对尚不存在或刚写入的 callback 唯一键做共享读，再去更新同一 payment attempt；两个事务都持有共享/间隙锁并同时请求排他锁，真实 MySQL 集成因此稳定出现 error 1213 死锁，重复回调不能可靠收敛。完整 diff 复核又发现回调包装器把 `DuplicateKeyException` 也放进了两次尝试，超过了“只对 1213 重试”的精确边界。
- 证据链接：[slice PR #28](https://github.com/ChanTso/citybuddy/pull/28)、[implementation and recovery commit `730c029`](https://github.com/ChanTso/citybuddy/commit/730c029370aab7a1871b1d06ac5db2e13d3fccd1)、[exact retry and rejection evidence commit `d2eb96e`](https://github.com/ChanTso/citybuddy/commit/d2eb96ed8d12c1811238d458532359efb6baeb34)
- 根因：这是典型的 S→X 锁升级环：事务 A、B 都先获得兼容的共享/间隙锁，随后都等待对方释放锁以升级为写所需的排他锁，InnoDB 只能选择一个受害者回滚。共享读发生在真正的串行化点之前，使死锁成了常态竞争路径；同时，把“唯一约束拒绝”与“InnoDB 死锁受害者”合并成宽泛并发重试，会掩盖不同错误语义。
- 解决：回调事务第一步按唯一 callback correlation 对 payment attempt 执行 `SELECT ... FOR UPDATE`，相同 attempt 的回调从入口就排队；取得排他锁后再普通读取 immutable callback truth，因此不会先共同持有 S 锁再争抢 X 锁。仍保留最多两次总尝试的兜底，但只有异常链明确包含 MySQL error 1213 的 `CannotAcquireLockException` 才重试；受害事务完整回滚后，新事务重新读取已提交 callback/order/attempt/ledger truth并返回同一结果。error 1205 不重试，`DuplicateKeyException` 也只执行一次并转为稳定 409。五次真实目录运行各执行 20 轮并发重复回调，累计 100 轮均通过；受控 1213 证明只重试一次并保持一条 payment ledger movement，受控 1205 和唯一键冲突都证明一次调用后立即可见。
- 结论：`FOR UPDATE` 能解决这里的根因，不是因为它“不会死锁”，而是因为它把同一业务实体的并发者在读取派生状态前就按同一排他锁顺序串行化，消除了常见的 S→X 升级环。有限的精确 1213 重试仍需保留，因为真实事务还可能与取消等其他资源形成罕见锁环，InnoDB 的受害者回滚是可恢复并发结果；前提是重试必须从新事务重新读已提交 truth、次数有界、其他错误不伪装成死锁，并由唯一键和条件更新保证绝不产生第二次 ledger movement。

## CB-071 — Refund state machine and payment/refund reconciliation

- 现象：首轮真实 MySQL 集成中，两笔并发部分退款虽然先通过同一 payment attempt 排队，后进入的事务仍从旧的 REPEATABLE READ 快照计算已预留金额，导致两笔都被受理；修正这一点后，第二笔合法部分退款又被旧的 `(order_id, movement_type)` 唯一键拒绝。最终独立复核还发现同类窗口：reconcile 可先读到旧 PROCESSING refund，再等待并发成功提交；若锁后仍普通读取 ledger，就会把刚提交的合法 movement 误报为矛盾。
- 证据链接：[slice PR #29](https://github.com/ChanTso/citybuddy/pull/29)、[implementation and integration recovery `e159f37`](https://github.com/ChanTso/citybuddy/commit/e159f3754e95)、[reconciliation current-read recovery `b8a1964`](https://github.com/ChanTso/citybuddy/commit/b8a1964)
- 根因：InnoDB 的锁等待只负责串行化访问，不会自动刷新事务早先建立的一致性快照；任何后续普通聚合或 ledger 读仍可能看到等待前的历史视图。另一方面，支付阶段“一种 movement 每单最多一条”的旧约束被直接套到退款，但一笔付款允许多次部分退款，二者的基数不相同。
- 解决：预留金额在持有 payment/order 锁后对该 attempt 的 refund 行执行 `SELECT ... FOR UPDATE` current read，再以受锁行求和；reconcile 锁后的 payment/refund/timeout ledger 查询统一使用 `FOR SHARE` current read。受控测试让 reconcile 先建立旧快照并等待成功事务提交，随后仍返回 `CONSISTENT`。旧唯一键改为 generated conditional singleton key，只对既有下单、取消和支付 movement 保持每单单例，退款 movement 则由稳定的 `mock-refund:{refundId}` business-event key 唯一化。十轮并发超额退款均只接受一笔，两笔合法部分退款可累计成功，同时第二条 `STANDARD_PAYMENT` 仍由数据库拒绝。
- 结论：`FOR UPDATE`/`FOR SHARE` 不只表示“加锁”，在 InnoDB 中还意味着读取锁定时的当前已提交版本；涉及锁等待后的任何派生总额或关联事实时，必须逐条审计并明确选择 current read，不能只修第一个查询。数据库唯一性也应表达业务事件真实基数：支付可以每单单例，部分退款必须每个稳定 refund identity 单例，不能用一个过宽的旧约束混为一谈。

## CB-071 closeout — Narrative repository truth synchronization

- 现象：CB-071 closeout 已把路线状态更新为 `VERIFIED` 并把 CB-080 标为唯一 `READY`，但 `IMPLEMENTATION.md` 的 phase、能力总述、能力列表和未实现清单仍把退款描述为尚未实现，导致同一规范源自相矛盾并触发持续切片循环停止。
- 证据链接：[slice PR #29](https://github.com/ChanTso/citybuddy/pull/29)、[post-merge main `6b1b04a`](https://github.com/ChanTso/citybuddy/commit/6b1b04a86d2f9cb5daaa51e63fca39c4533557db)
- 根因：closeout 只同步了权威状态行、Completion record、下一片规格和既有切片 lessons，没有逐行核对同一文件中的叙述性仓库现状。
- 解决：通过独立文档维护 lane 全量审计并一次同步 `IMPLEMENTATION.md` 的版本、phase、能力总述、退款/对账能力列表和未实现清单，不改变路线、优先级、状态、合同或产品行为。
- 结论：切片 closeout 不仅要更新状态表，也要逐行复核同一规范源中的叙述性现状；否则局部正确的状态仍会与旧文本形成必须停止处理的矛盾。

## CB-080 — Support conversation, event, and evidence lifecycle

- 现象：四个相同意图请求并发进入时，初稿先共享读取 `support_session`，再争抢 conversation 写锁，真实 MySQL 出现 1213 死锁并返回 503；把 session 读取直接改成 `FOR UPDATE` 后，又因最小权限的 `agent_app` 没有 `support_session.UPDATE` 而被数据库拒绝。集成脚本还因裸 `wait` 等待长期服务进程而假性挂起，trigger 故障注入需要未授权 `SUPER`，完整 CI 最后暴露 grant job 不认识“历史局部 commerce migration + CB-080 全量 agent migration”的精确表组合。独立 closeout 复核又发现跨用户请求在最终拒绝前仍先锁了 conversation，且最初的回滚演练在第一条 event 就失败，未真实覆盖部分 evidence 写入后的整体回滚。
- 证据链接：[slice PR #31](https://github.com/ChanTso/citybuddy/pull/31)、[implementation commit `1645126`](https://github.com/ChanTso/citybuddy/commit/1645126)、[review recovery `f4bfe43`](https://github.com/ChanTso/citybuddy/commit/f4bfe43)
- 根因：事务先取得可并存的共享锁再升级排他锁，形成与 CB-070 同类的 S→X 竞争环；直接锁 session 又混淆了“读取权威 owner”与“拥有该表更新权限”。测试编排没有把一次性客户端 PID 与长期服务 PID 分开，故障注入也依赖了运行身份不应拥有的管理权限；升级状态机只枚举了旧 agent schema，未组合新增表。最终事务内 owner 复核被误当作入口越权预检，故障约束也只按输入消息命中了第一条 event。
- 解决：创建 session 时同步创建 conversation；chat 入口先从 `support_session` 校验 owner，事务内再对 conversation 行执行 `SELECT ... FOR UPDATE` 串行化并二次读取不可变 session owner，四个并发请求稳定收敛为同一响应、一条 turn 和三条 event。脚本只等待记录的 curl PID，以 migration-owned `CHECK` 约束在独立无历史会话的第二条 event 注入失败，真实证明第一条 event、turn 和 conversation position 一起回滚；同时逐个显式枚举七种历史 commerce 状态加 CB-080 agent 表的组合，未知表集继续失败关闭。
- 结论：并发幂等事务应在拥有写权限的同一业务聚合根上尽早串行化，再读取关联权威事实；不能为了加锁扩大无关表权限。入口授权顺序与事务内 TOCTOU 复核是两道不同防线，不能互相替代；事务回滚证据也必须让至少一个受保护写入先成功执行，再在后续写入失败。真实集成的进程等待、故障注入和升级矩阵必须遵守最小权限并精确区分短任务、常驻服务与每个受支持 schema 组合。

## CB-081 — Bounded agent, model routing, and ToolSpec control

- 现象：首轮远端 repository CI 把集成脚本中两个高熵的固定 `Idempotency-Key` 测试值识别为通用 API key；同时，真实链路要求在外部模型/身份/工具调用前先保存已接受 turn，又要求后续数据库失败不能留下部分 agent evidence 或让同一幂等请求重新执行工具。独立复核进一步发现，进程若在提交 `PROCESSING + USER_INPUT` 后崩溃，同一幂等请求会永久返回 409；half-open 探针若收到非瞬态 provider/schema 拒绝，也会永久占住默认唯一探针槽。修订后完整复核又发现，V004 为扩展事件类型而替换 V003 约束时意外只保留正序号与类型白名单，允许终态占用 sequence 1 或在后续序号重复 `USER_INPUT`。
- 证据链接：[slice PR #32](https://github.com/ChanTso/citybuddy/pull/32)、[首轮失败 CI](https://github.com/ChanTso/citybuddy/actions/runs/29574307542)、[fail-closed authority evidence `0c7db0c`](https://github.com/ChanTso/citybuddy/commit/0c7db0c)、[crash/circuit review recovery `e24024f`](https://github.com/ChanTso/citybuddy/commit/e24024f)、[final-migration invariant recovery `f23b9d2`](https://github.com/ChanTso/citybuddy/commit/f23b9d2)
- 根因：秘密扫描按字段名和熵启发式工作，不知道字符串只是测试相关键；而把数据库事务跨网络调用保持打开既不能提供可靠原子性，也会放大锁窗口，单次“大事务”无法同时表达“请求已被接受”和“外部执行后的证据必须全成或全败”。初稿又只处理仍活着的请求栈异常，没有为已提交的执行所有权持久化截止时间；熔断器只在成功或瞬态失败时清理 half-open 状态，遗漏了非瞬态与意外异常出口。迁移层则把“加入新事件类型”误写成整体替换约束，静态测试仍只证明旧 V003 文本，未验证最终迁移后的不变量。
- 解决：把误报值改成低熵、语义明确的测试占位符并清除含误报的草稿提交历史；turn 采用两阶段持久边界，先提交 `PROCESSING`、`USER_INPUT` 和按有限尝试预算推导的一次性截止时间，外部执行后再在一个事务中提交完整 agent events 与终态。第二阶段受控失败时回滚全部部分 agent evidence，再以独立有界事务把同一已接受 turn 收敛为 `FAILED`；过期重放在 turn 排他锁下只写一次 durable failure，且完成路径也受同一截止时间 fencing，不重跑 agent 或工具。half-open 探针占位在每次已准入调用的 `finally` 中释放，而成功和瞬态失败仍执行各自状态转换。V004 在扩展白名单的同时显式保留 `sequence = 1 ⇔ USER_INPUT`，并新增静态断言与真实 `agent_app` 写入拒绝，分别证明终态不能先于输入、输入也不能在后续序号重复。
- 结论：测试标识也要按秘密扫描规则设计；涉及网络 I/O 的 durable workflow 应显式分开“接受事实”和“完成事实”，并为进程消失后的执行所有权设置持久、可 fencing 的终止边界。有限熔断不仅要限制探针入口，还要审计成功、瞬态、非瞬态和意外异常的全部释放出口。增量迁移修改既有约束时，测试必须针对最终迁移定义和真实数据库行为，而不能只证明被替换的历史 DDL。

## CB-082 — Filtered SSE, feedback, and deterministic support end-to-end evidence

- 现象：首轮真实 MySQL migration 因 feedback 外键引用的 support-turn 关联列没有显式复合唯一键而失败；修正后，反馈事务锁定读又被最小权限 `agent_app` 以 1142 拒绝。真实身份集成还发现 SSE 路由把 owner `HTTPException` 吞成 HTTP 200 的公共 error，而旧 crash-window fixture 硬编码的 event sequence 与新 SSE turn 碰撞。action-success 词表方案经历两轮独立复核失败：首轮用 `Your cancellation is complete.` 证明窄正则绕过；归一化修正后又发现 `It has been refunded.` / `I cancelled it for you.`，并误拒 `Your order is not complete.`；第二次修正后复核仍找到 `Your refund has been issued.` / `The payment went through.`，并误拒 `No refund was processed.`。closeout 的完整 CI 又两次表现为 Elasticsearch unhealthy；现场健康记录却是连续成功、`FailingStreak=0`，容器随后突然变成 0 PID 并被移除。
- 证据链接：[slice PR #33](https://github.com/ChanTso/citybuddy/pull/33)、[implementation and integration recovery `63103c8`](https://github.com/ChanTso/citybuddy/commit/63103c8)、[first action-claim review recovery `e07d867`](https://github.com/ChanTso/citybuddy/commit/e07d867)、[completed-action/negation recovery `44a83a1`](https://github.com/ChanTso/citybuddy/commit/44a83a1)、[authoritative-channel decision `d796ac7`](https://github.com/ChanTso/citybuddy/commit/d796ac7)、[bounded defense recovery `26eb851`](https://github.com/ChanTso/citybuddy/commit/26eb851)
- 根因：MySQL 外键只认可被引用列的显式索引唯一性，锁定读取在 InnoDB 中又需要目标表的 `UPDATE` 权限。路由异常边界混淆了“已验证的授权拒绝”与“流内私有失败”，历史 fixture 也把会增长的序号当成常量。对输出过滤而言，自由自然语言的同义表达、语序、时态、否定范围和上下文无法由有限确定性词表完备分类；每次补一个短语都会留下新绕过或引入新误伤，因此它不能承担动作真值保证。CI 基础设施问题来自共享 Docker Desktop VM 当时仅分配 7.75 GiB，且另一个无关项目容器常驻；资源压力会让最大的 JVM 进程先消失，表面上却只留下任意组件的健康检查失败。
- 解决：为 support turn 增加明确复合唯一键；feedback 并发只锁已有写权限的 `support_conversation` 聚合根，再读不可变 truth。SSE 保留真实 `HTTPException`，历史 sequence 从数据库权威状态推导。Level 3 修订将真值渠道与散文彻底分离：`token` 只是非权威解释，唯一公共动作状态载体是由 ActionReceipt 派生的 `action_receipt`，后续客户端不得从散文推断状态。归一化 + 文档化有界词表仅作纵深防御：固定回归拦截 `issued` / `went through`，放行 `No refund was processed.`，wire test 证明 token 散文不能伪造第二个 receipt SSE frame，真实 fake-provider 用 `Your refund has been issued.` 取证。宿主机停止无关容器并把 Docker 内存提高到约 14 GB；重跑同时实时记录 7,473 条 `docker events`，全套 `make ci` 通过且没有 `oom` 事件，未修改 Compose、健康检查或切片代码。
- 结论：外键、锁和最小权限必须在真实数据库上整体设计；HTTP 授权拒绝与流内私有错误必须分层，顺序值应从权威状态推导。对自由文本，确定性词表不能被宣称为完备安全边界；正确结构是“真值渠道分离 + 词表降级为纵深防御”：ActionReceipt/`action_receipt` 决定状态，`token` 只负责可丢弃、不可授权的解释。共享 Docker VM 的内存压力会伪装成任意组件的健康失败；取证应在运行时持续记录容器事件，不能依赖很快被刷掉的事后事件缓冲区。

## CB-090 — Versioned hybrid knowledge index and deterministic retrieval fusion

- 现象：首轮真实 Elasticsearch bootstrap 创建索引后，立即把同一索引判定为 `incompatible_mapping`；实际 8.19.8 mapping readback 保留了 `public_metadata.properties`，但省略了该嵌套对象默认的 `type: object`。独立完整 diff 复核又发现两个 false-success 窗口：Elasticsearch 搜索即使 HTTP 200 仍可能声明 `timed_out=true` 或 shard 部分失败，而初稿只解析 `hits.hits`；真实探针又只检查融合后的命中，无法排除 dense/rewrite recall 实际为空，也因初始 corpus 只有 4 条而没有真正触发 5 条最终上限。
- 证据链接：[slice PR #34](https://github.com/ChanTso/citybuddy/pull/34)、[implementation commit `874d1cc`](https://github.com/ChanTso/citybuddy/commit/874d1cc)
- 根因：Elasticsearch 的 mapping API 返回规范化后的语义表示，不保证逐字回显请求中的默认值；相反，搜索 HTTP 状态只证明请求得到响应，不证明所有 shard 在时限内完整成功。测试层若只观察融合后的最终集合，一个 lexical 命中还会掩盖 dense leg 为空，两个最终文档也不能证明它们分别来自 original/rewrite，更不能在候选数小于上限时证明截断。
- 解决：mapping validator 继续精确校验顶层字段集合、公共 metadata 字段/类型、IK analyzer、dense-vector 维度/index/cosine 和 alias 单目标，只对已证明会规范化掉的嵌套对象默认 `type: object` 接受省略。每条 recall 响应现在还必须明确 `timed_out=false`、shard 元数据类型/范围合法、`successful=total` 且 `failed=0`，否则整次搜索收敛为 `partial_recall_failed`；固定测试覆盖超时、failed shard、缺失和异常元数据。真实探针直接观察生产 BM25/kNN 方法：用 lexical 无命中但语义轴命中的查询独立证明 kNN，用分离查询证明 original/rewrite 各自贡献，并临时写入六条严格公开 fixture，真实证明稳定 tie、dedup 和 6→5 截断后逐条清理。
- 结论：对外部系统的声明式 schema 应比较经过文档化的语义不变量，而不是要求响应逐字等于请求；搜索成功必须同时验证传输、超时和所有 shard 完整性。多阶段检索的证据必须直接观察每条 leg，并构造超过边界的候选集，否则最终结果“看起来正确”仍可能是假绿。

## CB-091 — Rerank, sufficiency calibration, and retrieval evidence

- 现象：真实集成最初暴露了几类彼此独立的假绿或不稳定窗口：只迁移 agent schema 时，统一 grant job 正确拒绝了不完整的历史 schema 集；同一进程连续执行 transient、timeout、budget 和原子回滚场景时，共享 provider circuit 的打开状态污染了后续 fixture；预算 fixture 只计算四条 recall，却漏算 alias 与 mapping 两次真实 Elasticsearch 调用；Python 二进制浮点 `0.449999…` 写入 `DECIMAL(9,8)` 后重放为 `0.45`，使“精确 replay”比较失败。自查还发现，初始 `knowledge.search` 在 Elasticsearch/alias/mapping 不可用时返回 `deny_with_feedback`，旧循环会把这条不可用反馈重新交给模型，允许模型在没有 retrieval decision 的情况下生成第二段散文。最终独立复核又证明 Pydantic 宽松 `float` 会把不可信 JSON 的 `true`/`false`/`"0.9"` 转换为 `1.0`/`0.0`/`0.9`，格式错误的 reranker 输出因此可能生成 sufficient evidence。closeout 时，原 CB-102 又因同时包含 commerce state/audit/version、agent evidence、异步 liveness 和 sandbox callback 四个跨服务结果而触发复杂度门。
- 证据链接：[slice PR #35](https://github.com/ChanTso/citybuddy/pull/35)、[implementation commit `fcf2e6d`](https://github.com/ChanTso/citybuddy/commit/fcf2e6d)、[CB-102 route decision](https://github.com/ChanTso/citybuddy/pull/35/files)
- 根因：集成脚本把“本切片所需 schema”误当成了 grant manifest 支持的“完整当前仓库 schema”；有状态 circuit/budget 是生产边界，却被测试当成无状态 helper 复用。预算断言没有从真实调用图计数，持久化比较也没有先统一到数据库的十进制定点表示。agent 循环只把带有 `RetrievalDecision` 的不足结果视为终态，遗漏了检索基础设施在无法解析物理 index 之前就必须失败关闭的路径；reranker schema 又只约束转换后的数值范围，没有在解析前约束 JSON 类型。路线层则把 API 表面、不同数据库真值、异步执行边界和支付 callback 塞进一个未开始切片，超过了可独立评审和恢复的范围。
- 解决：真实证据按 auth、commerce、agent 全部迁移历史后再应用统一最小权限 manifest；会改变 circuit 状态的独立 fixture 使用明确顺序或全新 circuit，并从 alias、mapping、四条 recall 到 reranker逐次核算同一共享预算。reranker score 先拒绝 boolean、字符串及其他非 JSON number，再在决策、持久化和 replay 前统一量化为八位十进制；固定真实 JSON 回归证明三种类型混淆均收敛为 `reranker_denied` 且不产生 evidence。真实 MySQL 测试用 root 仅安装受控失败 trigger，业务写仍由 `agent_app` 执行，证明至少一次 evidence insert 后整个终态事务回滚。初始 knowledge 不可用现在直接收敛为稳定 `retrieval_denied`，不再让模型重新生成无依据回答；固定测试证明只调用一次模型。经所有者批准，原 CB-102 按现有 CB-010 先例只做路线划分为 CB-102～105，并把完整下游依赖保守重映射到 CB-105，总承诺行为和边界不变。
- 结论：状态型韧性组件的集成 fixture 必须隔离状态并按真实调用图核算共享预算；跨数据库精确 replay 要在写入前选定统一的规范数值表示。不可信模型 JSON 必须同时验证语法、原始类型和值域，不能把框架的便利强制转换当成协议兼容。检索基础设施不可用与“检索结果不足”虽然产生证据的阶段不同，但都必须在首个不具备 grounding 真值的边界终止，不能把错误反馈交回自由文本模型。滚动规格窗口不仅是文档手续，也是提前发现过大切片的设计门；跨服务、跨真值和跨执行模型的结果应在实现前拆成顺序可审计单元。

## CB-100 — Evaluation identity provisioning and sandbox-bound token lifecycle

- 现象：真实 evaluation-profile 启动最初因事务服务类被声明为 `final` 而失败，Spring CGLIB 无法为其创建事务代理；修复启动后，集成脚本又暴露 macOS 自带 Bash 3 对空数组展开不兼容、MySQL epoch 小数被整数解析器拒绝，以及完整 Python CI 中两处新增可执行脚本清单未同步。最终独立复核还发现，`INSERT IGNORE`/条件更新之后继续普通 `SELECT` 会受 MySQL REPEATABLE READ 旧快照影响，并发同意图可能错误返回 500/409，而初稿只有顺序重放却提前声称并发收敛；首轮改成 `FOR UPDATE` 又让多个 `INSERT IGNORE` 失败者可能从共享唯一键锁同时升级排他锁，两请求测试最多一个失败者，仍无法排除 S→X 死锁。closeout 后的 final-head CI 又随机生成了首字符为 `-` 或 `_` 的合法 Base64URL handle；OpenAPI 允许这种 43 字符值，但运行时误用了要求首字符为字母数字的通用 ID 校验，导致并发撤销双方都返回 400。
- 证据链接：[slice PR #36](https://github.com/ChanTso/citybuddy/pull/36)、[implementation commit `bb05fa4`](https://github.com/ChanTso/citybuddy/commit/bb05fa4bc1c358039cc6d07e94343010b6d547d0)
- 根因：单元测试直接构造服务对象，无法证明 Spring 运行时代理可以实例化；集成脚本隐含依赖了较新的 Bash 数组行为和整数化时间输出；仓库的可执行入口存在多处显式 fail-closed allowlist，新增入口只更新 Makefile/CI 还不完整。并发路径则混淆了写操作的当前读语义与随后普通一致性读的快照语义：唯一键写能看到并发提交，事务快照却仍可能看不到胜者。opaque handle 的协议类型与一般业务标识符并不相同，复用较窄的通用校验器使生成器、OpenAPI 和消费者形成了不一致的信任边界。
- 解决：允许 Spring 对事务服务创建代理，并保留真实 profile 启动作为固定证据；脚本改为 Bash 3 安全的可选参数构造和通用数值读取，再显式比较规范化 epoch；同步所有受约束的可执行路径与 CI matrix 清单。provision 在 `INSERT IGNORE` 后使用 `SELECT ... FOR SHARE` 读取唯一键胜者或冲突绑定，既是 current read 又不升级失败者已有共享锁；revoke 从入口 `FOR UPDATE` 锁定 handle 行再决定。真实 MySQL 对 provision 各做五轮同 key/冲突 key 三请求 burst，对 revoke 各做五轮同 key/冲突 key 请求对，共 20 组并发。opaque handle 改用与生成器和 OpenAPI 完全相同的 43 字符 Base64URL 专用校验器，并以首字符 `-`、`_` 的单元测试和真实 MySQL/API fixture 固定回归。随后专属真实集成、Python 检查、完整本地 `make ci` 和 final-head GitHub Actions 结果均在 PR #36 记录。
- 结论：框架事务边界必须用真实容器启动验证，直接实例化的单元测试不能覆盖代理约束；面向系统自带 shell 的集成脚本要以实际最低版本为准；新增 CI 入口时应搜索并同步全部可执行 allowlist。InnoDB 中“写看到了唯一键冲突”不代表后续普通读会看到同一并发提交，幂等收敛需要 locking current read；但锁强度也必须匹配后续意图，只读收敛若无故从 S 升 X 会制造死锁。并发证据至少要包含两个失败者，顺序 replay 或单失败者请求对都不够。随机生成的 opaque token 必须对其完整输出字母表做边界测试；协议专用标识符不能被更窄的通用校验器悄悄收缩。

## CB-101 — Evaluation sandbox lifecycle and fail-closed enforcement

- 现象：评测 JWT、沙箱 header 和入口 liveness 都已通过时，真实 evaluation chat 仍返回 403；另一个“丢失 auth provisioning 响应”的崩溃窗口测试最初也没有真正到达 Auth，因为 Java `RestClient` 使用 chunked request body，而测试代理只转发 `Content-Length`，可能形成错误的响应丢失证据。完整 CI 的首次实现头还暴露既有 CB-091 脚本没有为新增 conversation-store 参数显式传生产 `sandbox_id=None`。首次完整 diff 独立复核又发现 closeout 只更新了路线状态、未同步叙述性 current truth；清理 worker 的实际排序少了复合索引中间列，但测试 EXPLAIN 用的是另一条更有利的排序；新 CB-104 规格还把“权威查询确认不存在”和“查询不可用导致未知”混写成同一种终态 drop。
- 证据链接：[slice PR #37](https://github.com/ChanTso/citybuddy/pull/37)、[implementation commit `b91dbf4`](https://github.com/ChanTso/citybuddy/commit/b91dbf4990575be84912cdae2dbb1242e78bf7a7)
- 根因：持久 conversation owner 复核仍把 `support_session.sandbox_id IS NULL` 当成生产专用不变量，没有改为与当前调用上下文精确相等；故障代理又把 HTTP body framing 的实现细节误当作固定协议，既未解码 chunked body，也没有记录上游状态来证明请求真实完成。接口签名扩展则遗漏了仓库内一个直接构造 durable turn 的集成调用者。closeout、生产查询和测试证据分别手工维护，造成状态叙述、SQL 形状和索引证明漂移；未来规格又没有区分“权威否定结果”与“权威源暂时无法回答”。
- 解决：conversation 事务内二次复核改为 persisted sandbox 与当前 sandbox 精确相等，生产路径显式传 `None`，评测路径传 JWT/header/session 已验证的 sandbox。响应丢失代理增加有界 chunked 解码、固定长度上游转发和上游状态证据；真实测试证明 Auth 已持久化唯一 handle 后代理才丢弃响应，Commerce 重启再用同一 provisioning key 恢复 handle 并撤销。随后重新运行 CB-101、CB-091 和完整 `make ci`。复核修正同步路线 phase/current truth；让 worker 按 `(cleanup_due_at, lifecycle_state,sandbox_id)` 排序，并对同一条带 `FOR UPDATE SKIP LOCKED` 的实际查询做 EXPLAIN、拒绝 filesort；固定 DEAD 沙箱不得 reset 的 409 回归；CB-104 明确只有权威确认 inactive/absent 或确定性无效上下文才终态 drop，查询超时、鉴权失败或不可用只能按既有有界重试/dead-letter 收敛。
- 结论：新增隔离维度时，入口授权、持久 owner 复核和所有旧调用者必须一起升级为“精确上下文相等”，不能只放开外层 token 校验。崩溃窗口测试也必须证明故障发生在目标持久提交之后；测试代理若不支持真实客户端采用的 HTTP framing，得到的只是连接失败，不是所声称的响应丢失。索引证据必须解释生产实际 SQL，而非测试专用的近似查询；安全状态机也必须区分“确认不存在”和“暂时不知道”，否则 fail-closed 会被错误实现成不可逆的假死判定。
