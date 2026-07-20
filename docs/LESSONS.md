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

- 现象：评测 JWT、沙箱 header 和入口 liveness 都已通过时，真实 evaluation chat 仍返回 403；另一个“丢失 auth provisioning 响应”的崩溃窗口测试最初也没有真正到达 Auth，因为 Java `RestClient` 使用 chunked request body，而测试代理只转发 `Content-Length`，可能形成错误的响应丢失证据。完整 CI 的首次实现头还暴露既有 CB-091 脚本没有为新增 conversation-store 参数显式传生产 `sandbox_id=None`。首次完整 diff 独立复核又发现 closeout 只更新了路线状态、未同步叙述性 current truth；清理 worker 的实际排序少了复合索引中间列，但测试 EXPLAIN 用的是另一条更有利的排序；新 CB-104 规格还把“权威查询确认不存在”和“查询不可用导致未知”混写成同一种终态 drop。首轮 final-head CI 随后发现三个发布态缺口：OBO 协议增加可选 `sandbox_id` 后，两个文件中的四个测试 stub 静态签名未同步；拒绝测试把合成错误凭据写成 curl `--user` 字面量，进入 commit 历史后被 Gitleaks 正确扫描为 credential 模式；真实集成把 janitor 压到每秒一次、最多三次，GitHub 上 Auth 重启约 5.5 秒时，worker 会在恢复前耗尽次数并正确退到等待身份自然到期，导致紧随其后的 completion retry 仍为 503。
- 证据链接：[slice PR #37](https://github.com/ChanTso/citybuddy/pull/37)、[implementation commit `b91dbf4`](https://github.com/ChanTso/citybuddy/commit/b91dbf4990575be84912cdae2dbb1242e78bf7a7)
- 根因：持久 conversation owner 复核仍把 `support_session.sandbox_id IS NULL` 当成生产专用不变量，没有改为与当前调用上下文精确相等；故障代理又把 HTTP body framing 的实现细节误当作固定协议，既未解码 chunked body，也没有记录上游状态来证明请求真实完成。接口签名扩展则遗漏了仓库内一个直接构造 durable turn 的集成调用者。closeout、生产查询和测试证据分别手工维护，造成状态叙述、SQL 形状和索引证明漂移；未来规格又没有区分“权威否定结果”与“权威源暂时无法回答”。结构协议的可选参数仍属于所有实现者必须满足的签名，而 secret scan 的 git 模式检查整个可达历史，不能把“合成测试值”或“当前文件已删除”当作不会命中的理由。恢复测试还把“次数有界”误写成短于真实依赖重启时间的尝试窗口，使机器速度而非状态机决定结果。
- 解决：conversation 事务内二次复核改为 persisted sandbox 与当前 sandbox 精确相等，生产路径显式传 `None`，评测路径传 JWT/header/session 已验证的 sandbox。响应丢失代理增加有界 chunked 解码、固定长度上游转发和上游状态证据；真实测试证明 Auth 已持久化唯一 handle 后代理才丢弃响应，Commerce 重启再用同一 provisioning key 恢复 handle 并撤销。随后重新运行 CB-101、CB-091 和完整 `make ci`。复核修正同步路线 phase/current truth；让 worker 按 `(cleanup_due_at, lifecycle_state,sandbox_id)` 排序，并对同一条带 `FOR UPDATE SKIP LOCKED` 的实际查询做 EXPLAIN、拒绝 filesort；固定 DEAD 沙箱不得 reset 的 409 回归；CB-104 明确只有权威确认 inactive/absent 或确定性无效上下文才终态 drop，查询超时、鉴权失败或不可用只能按既有有界重试/dead-letter 收敛。final-head 修正把全部 OBO stub 签名对齐可选 sandbox，错误管理凭据改为运行时随机值，并只按 Gitleaks 报告的单一历史 fingerprint 忽略已确认的合成假阳性；真实集成的 janitor 仍使用有限五次尝试，但以五秒间隔覆盖真实 JVM 重启窗口。完整 Python CI、178 tests、全历史 secret scan 和 CB-101 真实集成都重新通过。
- 结论：新增隔离维度时，入口授权、持久 owner 复核、结构协议实现者和所有旧调用者必须一起升级为“精确上下文相等”，不能只放开外层 token 校验。崩溃窗口测试也必须证明故障发生在目标持久提交之后；测试代理若不支持真实客户端采用的 HTTP framing，得到的只是连接失败，不是所声称的响应丢失。索引证据必须解释生产实际 SQL，而非测试专用的近似查询；安全状态机也必须区分“确认不存在”和“暂时不知道”，否则 fail-closed 会被错误实现成不可逆的假死判定。Secret fixture 应从一开始就避免 credential 形状；若历史已产生确定假阳性，只能精确忽略单一 fingerprint，不能放宽规则或目录。恢复测试的有限重试窗口必须覆盖被测依赖的真实重启上界，否则它验证的是 runner 速度而不是恢复语义。

## CB-102 — Commerce evaluation state, audit, and version APIs

- 现象：新增 V011 审计引用表后，首次真实集成的统一 grant job 仍把它判断为未知 schema 状态，没有给 `commerce_app` 应有的精确 `SELECT, INSERT`；修正后，evaluation profile 又因两个 `@Transactional` 服务类被声明为 `final` 而无法创建 Spring CGLIB 代理。审计写失败 fixture 最初用 bootstrap 账户直接做业务表故障注入，却没有激活该账户的非默认 grant role；完整 `make ci` 随后还发现新增 Python 合同测试把任意 JSON 标成 `dict[str, object]`，使 mypy 无法证明嵌套字段访问安全。独立终审最后发现 effect 查询只按类型排序，带 `LIMIT` 时没有稳定全序；前两版恢复证据又分别错误假设测试行必须占首尾、隐藏 correlation key 可由公开时间位置反推。
- 证据链接：[slice PR #38](https://github.com/ChanTso/citybuddy/pull/38)、[implementation commit `2181aea`](https://github.com/ChanTso/citybuddy/commit/2181aea)
- 根因：grant 状态机的精确表组合没有随新迁移一起扩展；直接构造对象的测试不能覆盖 Spring 运行时代理约束。故障注入混淆了“持有可委派 role”与“当前会话已激活权限”，类型测试又把运行时已验证的 JSON 结构留在过宽的静态类型中。
- 解决：把 V011 表加入 migration-state 检测，并只增加审计引用表的精确运行时 `SELECT, INSERT`；移除事务服务的 `final`，保留真实 profile 启动证据。故障注入改用集成环境既有 MySQL root 管理通道，临时撤销并恢复 `commerce_app` 对审计引用表的精确 `INSERT` 权限，实际失败请求仍由最小权限业务身份执行；合同测试先运行时验证对象形状，再收窄类型。effect 统一为公开时间优先的完整总序 `created_at, effect_type, correlation_key`，OpenAPI 明示同一顺序，静态合同锁定 SQL；真实 MySQL 插入两条完全相同 `created_at`、只由 correlation key 区分的平局记录，验证尾键顺序并重复调用比较完整响应。专属真实集成、`make python-ci` 和完整 `make ci` 均重新通过；并发 Docker 事件流没有 `oom`，所有 137 都有明确测试 teardown `kill` 前因。
- 结论：新增表不仅要写迁移和静态 grant 文本，还必须进入全部受支持 schema 组合的精确升级状态机；事务代理能力必须由真实框架启动证明。受控故障注入可使用管理身份布置故障，但业务路径仍必须用运行时身份执行，且不能假装未激活的 role 已生效。动态 JSON 的运行时验证和静态类型收窄必须成对出现。排序稳定性测试必须包含真正的平局数据，且实现 SQL、可执行断言和公开 schema 必须表达同一个完整总序；否则只靠可区分时间戳的测试无法行使尾键，也会把契约漂移伪装成数据库抖动。

## CB-103 — Agent evaluation evidence API

- 现象：首次真实集成尝试通过现有评测沙箱 session 写入 feedback fixture，生产边界按既有规则正确返回 403；这说明证据脚本为了制造可读数据，意外要求了本切片明确禁止新增的评测 feedback 写行为。随后自查又发现，`support_event` 与 `retrieval_decision` 各自格式合法时仍可能对同一 trace 的 outcome 或 index version 给出不同说法。独立终审进一步证明初稿只校验首尾事件，会放行中间 `TURN_FAILED` 或与 turn truth 冲突的 `AGENT_OUTCOME`；PyMySQL 返回的无时区时间会被序列化为不满足 OpenAPI `date-time` 的字符串，而真实 checker 只查字段存在；所谓日志脱敏证据也只扫描了凭据，没有扫描注入的用户文本、feedback comment、provider 输入和 retrieval 私有字段标记。后续完整复核先发现 decoded non-ASCII `str` 会让 `compare_digest` 抛 `TypeError`，修成 bytes 比较后又用原始 `Basic é` 证明 `b64decode(str)` 在解码前抛出未捕获 `ValueError`，两者都会把本应固定 401 的无效凭据变成 500。
- 证据链接：[slice PR #39](https://github.com/ChanTso/citybuddy/pull/39)、[implementation commit `088e9d2`](https://github.com/ChanTso/citybuddy/commit/088e9d2)、[lifecycle/time/redaction recovery `fab6689`](https://github.com/ChanTso/citybuddy/commit/fab6689)、[decoded Basic recovery `05a61b2`](https://github.com/ChanTso/citybuddy/commit/05a61b2)、[total Basic parsing recovery `776f31e`](https://github.com/ChanTso/citybuddy/commit/776f31e)
- 根因：只读投影的集成 fixture 混淆了“读取既有权威事实”与“扩张生产写入入口以便造数”；逐表、逐行和首尾验证只能证明局部结构有效，不能证明跨记录生命周期或重叠事实一致。数据库时间类型还丢失了会话时区语义，测试使用的 aware fixture 掩盖了真实驱动行为；响应脱敏断言也被误当成了服务日志脱敏证据。鉴权输入校验则按已知坏输入和异常实例逐个补洞，没有以“所有凭据解析失败都必须收敛”为边界关闭整个异常类。
- 解决：保留既有 chat/feedback 行为不变，由真实 MySQL 管理 fixture 写入一条已存在 feedback truth，再证明 API 仅投影 rating/time 且隐藏 comment。证据读取把 retrieval event 与 `retrieval_decision` 的重叠事实交叉校验，并要求 completed 记录以一致的 `AGENT_OUTCOME → ASSISTANT_RESPONSE → TURN_COMPLETED` 收敛、failed 记录只保留合法 accepted-to-failed 序列，任何中间或冲突终态返回 409。MySQL 连接固定 UTC 会话，naive 驱动值显式转换为 aware UTC，wire checker 要求 RFC 3339 offset；真实集成分别篡改冲突 outcome 和中间 terminal，并扫描所有相关服务日志中的每个 CB-103 私密 marker。Basic 边界先限制 header 长度，再把 token 显式编码为 ASCII bytes，并以一个 `ValueError` 总异常边界同时覆盖原始非 ASCII 与 `binascii.Error` 等 Base64 解析失败；成功解析后仅做 bytes 拆分和常量时间比较。真实恶意 header 电池覆盖 raw/decoded 非 ASCII、非法 Base64、非 UTF-8、缺冒号、空值、错误 scheme、超长值、控制字符与 NUL，全部只返回固定 401，响应和日志没有内部异常。专属集成、190 项 Python CI 和包构建通过。
- 结论：只读评测切片不能为了制造证据而扩张业务写行为；fixture 应从拥有真值的持久边界布置。跨表证据视图不仅要验证标识关联和单行 schema，还必须校验所有重叠事实与完整生命周期。数据库 timestamp 必须同时固定会话时区和 wire offset，aware 单元 fixture 不能替代真实驱动证据；响应与日志是两个不同泄漏面，必须分别注入标记并分别扫描。输入校验类缺陷必须用总异常边界关闭，而非枚举坏输入；网络凭据保持 bytes，并以按类构造的恶意输入电池证明所有失败路径收敛。

## CB-104 — Asynchronous evaluation-entry inventory and production-only closure

- 现象：原规格要求在异步消费者上证明 sandbox liveness guard 的 redelivery、完成竞态、restart、liveness outage 和幂等 drop/archive，但可执行盘点发现当前六类异步或表面异步路径中没有任何一条能由合法 evaluation 请求触发。早期恢复稿只能直接调用 coordinator、插入数据库 fixture 或手造带 sandbox 的消息来测试 guard，绕过了真实入口并形成假绿。
- 证据链接：[slice PR #40](https://github.com/ChanTso/citybuddy/pull/40)、[implementation commit `7eb9f0d`](https://github.com/ChanTso/citybuddy/commit/7eb9f0d)
- 根因：横切守卫切片被排在第一条可守卫的真实 evaluation 异步载体之前，因而没有合法 producer、消息 schema 或消费者真值边界可供端到端取证；把不存在的入口 mock 出来会把“守卫函数能工作”误报成“系统路径受保护”。
- 解决：经所有者批准，把 CB-104 缩窄为精确六路径的可执行盘点、真实 evaluation token 对真实 production controller 的拒绝、当前 payload 非承载，以及三个真实 Broker consumer 在 handler 前拒绝保留 sandbox 属性。无法真实取证的 guard 实现和 mock 测试全部移除；完整 liveness guard 及 redelivery、竞态、restart、outage、幂等 drop/archive 证据冻结到首个引入 evaluation 可达异步载体的未来切片，并要求在同一切片交付。
- 结论：横切安全守卫必须绑定首个真实载体及其真实入口、Broker 和权威状态取证，不能作为无载体的独立前置切片；不可达性也要通过真实入口证明，mock、直接 repository/coordinator 调用和手造消息不能证明系统边界。

## CB-105 — Sandbox-bound idempotent mock-payment callbacks

- 现象：初版只校验已经存在的 audit 行；第二版反向查询仍限定 `entity_type='PAYMENT_CALLBACK'`；全称逐行版又遗漏了公开的 `created_at` 与承载游标顺序的 `sequence_id`。独立复核分别通过删行、移出过滤范围、跨类型伪重复和单列时间/序列篡改持续找到正常 200。加入 `LEGACY_CUTOFF` 后，复核又证明没有可验证水位线的自报 legacy 会永久豁免迁移后孤儿行，迁移前同意图重放会把旧时间与 `now` 比较而固定 409；同时应用捕获时间和数据库分配自增序号不在同一临界区，两个合法事务可自行制造永久 409。只记录计数、最大序列、边界行和最大时间的水位线仍可被“删除较低序列真行、在同一空洞插入摘要自洽伪行”绕过，因为这些聚合值没有承诺完整成员集合。第八轮再以“删除 audit + 篡改 callback `intent_hash`”证明：把 intent、跨表一致性等有效性谓词放进业务真值枚举器，会让已成功支付整体退出枚举范围并重新得到正常 200。迁移中断检查还发现，过早发布 `AWAITING` 或只在成功路径撤权，会让未完成 DDL 被误当成可恢复屏障，或在 `POPULATING`、未知阶段和 `SEALED/history=false` 窗口残留临时权限。终审继续发现，若 exact-grant job 在两条表权限已提交后被中断，复用正常 `grant-access` 作 cleanup 会在 `AWAITING` 阶段再次授权，而非撤权。恢复过程中还在真实独立复核与 GitHub final-head CI 发生前填写了完成记录。
- 证据链接：[slice PR #41](https://github.com/ChanTso/citybuddy/pull/41)、双向对账提交 `b35336f`
- 根因：每版完整性谓词都带着隐式范围限定；限定外的行或列不属于校验责任范围。测试若沿用实现已有过滤器和字段集合选择破坏样本，会重复制造同一类假绿。所谓内部不变式又只写了校验器，没有证明所有合法写入路径能维持它；升级测试只从空库开始，遗漏真实历史行。过程上还把预期中的未来 closeout 证据写成了已经发生的事实。
- 解决：对账遍历沙箱内每一 audit 行并按共享封闭枚举派发，每个白名单类型独立枚举业务真值。支付对账从 `PAID` order、`STANDARD_PAYMENT` ledger、终态成功 callback 和声明为 `PAYMENT_CALLBACK` 的 audit 四面分别枚举稳定 order key，先要求四个键集相等，再逐键断言 intent、身份、owner、金额、币种、版本、ledger 内容和全部 audit 列；所有 JOIN/WHERE 只保留 sandbox 范围、稳定键和定义该面的终态分类，其余有效性谓词移到枚举后断言。真实矩阵按每个曾出现在 JOIN/WHERE 中的物理列/行故障建模，覆盖 41 个单项一致性故障注入与全部 820 个两两组合；每次注入都确认恰好改变一行，state 与 audit 均以 409 拒绝。所有公开或承载游标、排序、时间语义的列强制二分：`created_at` 由业务事件时间在同一事务显式写入业务真值与 audit 并精确匹配；V013 以固定列序、UTF-8 字节长度前缀、显式 NULL 标记和 UTC epoch 微秒规范对每条 legacy 行取 SHA-256，再按 `sequence_id` 链式滚动为完整集合摘要，连同格式标识、行数、cutoff 与时间写入运行时只读单行表。对账和历史重放都重算整个集合承诺，聚合值只作为快速失败条件；迁移前同意图重放从既有 audit 时间重建 observation。产品与 payment 的 audit 生产者都在同一事务锁定 `eval_sandbox` 行，锁内把候选时间向沙箱最新 audit 时间单调钳制后再分配序号，使 `sequence_id` 顺序不变式由机制保证。真实升级使用两个 sandbox、每个多条 legacy 行；矩阵覆盖低序列删行、同空洞自洽替换、逐列篡改、跨 sandbox 重分布、历史重放、12 路合法并发零逆序、两类业务 audit 全部锚定列、未知类型及人为序列逆序。V013 只有在全部前缀 DDL 完成后才发布固定 `AWAITING` 屏障，仅临时授予 migration 对 audit 源表的 `SELECT` 和水位表的 `INSERT`；Make 编排在 preflight、prepare、grant 和第二段迁移的每个失败/结果调用专用 `V013_FORCE_REVOKE`，该路径不依赖 phase，也不重放普通授权。grant preflight 对 `POPULATING`、未知阶段和 `SEALED/history=false` 也先撤权再拒绝。真实取证在 `AWAITING` 两条表权限均已提交后终止 grant 客户端，编排非零退出且专用清理撤销两权限；连同其他阶段的真实进程中断、`SHOW GRANTS` 与实际拒绝读写，证明每个窗口及终态都没有残留权限。承诺时间改用 SQL/JVM 共用的 epoch 微秒，避免 JDBC 会话时区污染规范。完成记录回退为未完成，今后只在复核、final-head CI 和真实 closeout 发生后填写。
- 结论：单向有效性过滤检测不到缺失行；完整性校验的每个隐式范围限定都是一个漏洞类，类型过滤器和被排除的内容列都必须通过全称对账关闭。有效性谓词出现在枚举器中会把不一致变成不存在；枚举只用稳定键，有效性只做断言；多真值面集合相等是组合篡改的唯一解。声明的不变式必须由机制保证——事件时间与序号分配不在同一临界区时，顺序一致性只是概率现象。计数、最大值和边界行是聚合统计而不是集合承诺；固定集合的完整性必须承诺完整内容，任何仅聚合的水位线都可被删一换一绕过。序列化规范本身也必须消除分隔符、NULL、编码和时区自由度。未来表授权缺口不能用跨所有者数据库级 DML 填平；阶段性最小权限必须由真实终态授权与拒绝路径证明，不能相信执行日志或观察不到目标账户授权的元数据视图；中断 grant job 的 cleanup 必须是无条件撤权，不能复用会按 phase 授权的正常命令。升级兼容必须以真实历史行跨迁移取证。完成记录先于事实同样是假证据，不能把计划中的 commit、review 或 CI 写成已完成。挂账的已知缺陷会在依赖该路径的后续切片 required CI 上到期，挂账时必须同时定义到期处置顺序；连续出现相似状态码时仍要按失败断言、响应来源和时间证据重新归因，不能把“已知问题”当作免检标签。本次主评测身份只存活 60 秒，而新增一致性故障注入矩阵把后续 liveness 与 support-session 使用推迟到四分钟以后，固定 403/401 实为过期身份而非存活分类边界；长路径 fixture 改用合同内 3600 秒生命期，同时保留独立 60 秒到期 fixture。

## Maintenance — Evaluation audit-unavailability classification

- 现象：PR #40 的一次 final-head 真实集成在只撤销 `commerce_app` 对审计引用表 `INSERT` 的一致性故障注入下，本应返回 503，却得到固定 `403 {"error":"Forbidden"}`。原始时间证据显示 `sandbox-main` 在失败前约五秒才 reset，且中途 liveness 已返回 204，因此不是 60 秒夹具过期；但当时固定 403 同时覆盖 OBO 授权和两处 sandbox 存活拒绝，保留日志没有记录实际生产者，无法事后把推测升级为确定根因。
- 证据链接：[maintenance PR #42](https://github.com/ChanTso/citybuddy/pull/42)、[integration and diagnostics commit `f2ed3f2`](https://github.com/ChanTso/citybuddy/commit/f2ed3f2)
- 根因：单次状态码断言只能证明分类错误，不能证明由哪个边界产生；把相似的 403 直接沿用为同一个已知问题会掩盖夹具到期、授权拒绝、权威存活拒绝和依赖不可用之间的差异。挂账时若没有定义在后续 required CI 复现时的顺序处置和必须保留的权威状态，后续切片只能重新猜测。
- 解决：CB-105 已让所有 audit 生产者在同一事务中先以 `FOR UPDATE` 锁定相同 `eval_sandbox` 行，再选择锚定事件时间和分配序号；本维护 lane 不再改生产行为，而用真实 MySQL 权限故障和 HTTP 边界锁定该机制。撤销审计引用写权限期间并发执行 16 个 product-tool 请求与 16 个 registry-liveness 请求，前者全部固定返回 503，后者全部保持 204；`performance_schema` 的 `commerce_app` 表权限拒绝计数相对水位恰好增加 17，证明原单请求与全部并发请求都到达被撤权的 audit `INSERT`，而非由其他 503 假绿。随后从权威数据库证明沙箱仍 `ACTIVE` 且未过期，每个失败 operation 在 audit reference 和 product observation 中均零残留。任何复现都会在清理前输出响应体、沙箱生命周期/到期/version、有效运行时 grants、1142 拒绝计数和 commerce 日志尾部。
- 结论：挂账的已知缺陷会在依赖它路径的后续切片 required CI 上到期；挂账时应同时定义到期处置顺序。相同状态码不是相同根因，分类证据必须同时固定实际断言、响应、时间、权威持久状态和服务生产者；若旧证据不足以确定生产者，应诚实保留未知，并用更新后的机制与并发真实集成建立可重复回归，而不是补写未经证明的根因。

## CB-110 — FAQ publication truth and transactional Outbox

- 现象：FAQ 发布的首版幂等承诺遗漏 question/answer 与 Outbox aggregate 内容列，发布转换也缺少 `DRAFT` 前置守卫；修复后，孤儿枚举器又把 `aggregate_type`、`event_type` 等受承诺列留在 `WHERE`，使数据损坏被静默解释为不存在。基于 SQL/Java 文本的静态谓词扫描可被函数包裹与 helper 间接调用绕开；改用 schema 驱动行为矩阵后，它先于独立复核抓到 `published_version` 只验证集合成员、未精确锚定最新已生效 command 的漏检。最后，复核指出矩阵虽然枚举全部物理列，却只在 `PUBLISHED` 状态取样，遗漏合法 `DRAFT` 状态的数据一致性责任域。
- 证据链接：[slice PR #43](https://github.com/ChanTso/citybuddy/pull/43)、[状态 × schema 闭包提交 `3d0e347`](https://github.com/ChanTso/citybuddy/commit/3d0e347c1ae355748d654efacf877ce64be20947)
- 根因：发布类幂等与对账没有先定义可枚举的完整责任域；受承诺列进入枚举过滤器会把不一致变成不存在，集合成员资格又弱于与最新权威 command 的相等性。散文规则、人工分类表和源码正则都无法对语法变形或隐藏调用作静态完备性承诺；仅枚举 schema 列仍未覆盖状态机这一正交轴。
- 解决：发布命令以长度前缀规范序列化摘要承诺全部内容承载列，状态转换以条件更新和聚合根锁保证同意图重放、冲突拒绝与并发单推进。新增不可变 draft command，使每次合法 draft 推进都有真值锚；current source 在 `DRAFT` 下精确匹配最新 draft command 的 question、answer、revision 和事件时间，在 `PUBLISHED` 下精确匹配最新已生效 publication command。双向枚举仅以稳定键定位，类型、所有权和内容全部在枚举后断言。真实集成从 `information_schema` 取得 11 个 source、9 个 publication command、8 个 draft command 与 10 个 Outbox 物理列，并在状态机唯一可达的 `DRAFT`、`PUBLISHED` 两种状态下执行状态 × 38 列行为矩阵；schema 新列、状态枚举漂移、缺 command、孤儿或任一承诺列损坏均失败关闭。
- 结论：发布类幂等承诺同样必须覆盖全部内容承载列；CB-105 类在新业务表面首次复发即被复核抓住，说明清单门在生效，但同一类跨表面复发也说明规则必须落到可执行行为证据。对代码文本的静态完备性承诺不可闭合；可闭合的全集只有 schema，把“证明写不出坏代码”换成“证明坏数据必被观察到”。本轮 schema 驱动矩阵先于复核发现逃逸：集合成员资格是比相等性弱的锚，回滚伪装是其标准逃逸。完备性必须定义在可枚举的地面真值上（状态机 × schema）；第二个正交维度的出现是要求封闭全集、而非继续逐维追补的信号。`docs/REVIEW_CHECKLIST.md` 已覆盖该新类；本次 closeout 没有其他尚未登记的复发缺陷类。
