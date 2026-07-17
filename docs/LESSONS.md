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
