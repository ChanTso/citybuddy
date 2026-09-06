# 有限库存准入到成单基线

> 2026-09-06 状态更正：用户确认，开始执行交接文档后的所有压测均受其前台使用影响，本批也在其中。本批吞吐、延迟、排队和积压清空时间不作为正式性能结论或修复前基线；以下数值及原始输出保留，不作删选。SQL仅保留为当次状态记录，正式闭环测量需在独占环境下重新执行并核对。

citybuddy_commit=ac1991316134e10269164b0e3587e9b83244388f
label=closedloop_ac19913_20260905T160911Z_r1000_q3000

MacBook Pro M4；Docker 8 CPU / 14,638,391,296 bytes；commerce 限 4 CPU。
原版 setup_bench_env.sh 与 run_ladder.sh：1 个活动、库存/额度各 3000、3500 个用户，目标 1000/s × 3 秒，无正向准入预热。只运行了这一个负载窗口；正式请求前 SQL 夹具为空，Redis 全局/本活动 handoff 及全部 intent key 计数均为 0。

## 实际结果

- 名义迭代 3000；实际完成并返回 ADMITTED 2196；k6 dropped_iterations 804；已发出请求 HTTP 失败 0。
- HTTP p50 1595.318501 ms，p99 1903.0828552 ms。按目标 3 秒计实际准入 732/s；k6 按自身含请求尾部的执行时长计 iterations rate 486.062/s。
- 第一条 HTTP 完成点 2026-09-05T16:19:23.521026301Z；最后一条 2026-09-05T16:19:27.370785719Z，来自原始 http_reqs 点。
- 最终 2196 个 reservation 全为 ORDERED + ADMITTED，2196 个 UNPAID 订单，2196 个 SECKILL_ORDER_CREATE 账本。inventory_delta 与 activity_quota_delta 各 -2196；MySQL product.stock_quantity 与 Redis remainingQuota 均为 804；activity.allocated_quota 仍为原始配置 3000。
- 订单/预约关联错误、准入无订单、重复用户、重复预约、订单账本差错、额外账本、创建时间越过付款截止，SQL 均为 0。无 CANCELLED、UNFULFILLED；本轮没有付款、退款或取消操作。
- 最后订单 created_at=2026-09-05T16:26:17.979882Z，距最后 HTTP 完成约 410.609 秒。这是 SQL 与 k6 的跨容器时钟差，不是逐条消息的单调时钟延迟。
- 16:26:17.653920Z 快照仍有 4 个待成单；16:26:22.776076Z 首次观察全部成单；16:26:33.034958Z 首次观察 timeout_dispatch 全部 SENT、Redis 全局/活动 handoff 均为 0，距最后 HTTP 完成约 425.664 秒。
- 16:28:16.867593Z 稳定复查仍为 2196/2196、全部 SENT、handoff 0。16:29:33.425941Z 完成逐关联 SQL 检查。
- 显式 topic 的 mqadmin consumerProgress 显示 4 队列 broker/consumer offset 分别为 532/570/543/551，总计 2196；Consume Diff Total=0，Consume Inflight Total=0。

## 口径与结束状态

本轮不能重申“1000/s 干净容量”，也不证明持续成单吞吐达到 1000/s。冷起窗口包含在全部统计中，没有切去坏秒。SQL 每约 5 秒采样，没有逐条 ACK 时间。

未指定 topic 的 consumerProgress 报不存在 retry topic，虽然工具退出码为 0；该真实错误和随后显式 topic 成功输出都保留在原始日志，没有把退出码当作成功依据。

15 分钟付款超时消息已发送到本轮专用 timeout topic，未来定时投递不属于本轮事务积压清空目标，不要求该 topic 清空。最早 unpaid_deadline=2026-09-05T16:34:24.470610Z；bench commerce/auth 已于 16:31:06Z 停止并移除，未支付自动取消没有污染成单统计。

原 demo 公钥 signing metadata 已在事务中恢复，与 ignored 私有备份逐字段一致；未修改私钥或 demo 业务数据。基础依赖继续运行，broker 保留环境文件中记录的 quiet healthcheck 设置。commerce 日志 WARN/ERROR 行计数均为 0。测量期间没有源码/HEAD 修改；资源交棒后主任务可以继续合并。

## 原始文件

同 label 的 k6 summary.json / points.json / console.txt / cpu.txt、ladder steps.txt，以及 seckill environment.txt / setup.txt / setup_console.txt / runner_console.txt / sql.txt / redis_mq.txt。每份新结果均有完整测量 SHA；points 在每个测量点 tags 中记录，setup 使用 CITYBUDDY_COMMIT 字段。

[完整原始输出](closedloop-interrupted-20260905-raw.tar.gz)保留本次受干扰测量；不参与有效容量或修复对照。
