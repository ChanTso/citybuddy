# A｜有限库存准入、成单与清空

citybuddy_commit=69be167a3df030bf45795c49f444d6e7c24d0423
label=closedloopA_69be167_20260905T171447Z_r10_q600

本轮在停止前台使用后执行。其他工作仅轻量文件读写，没有并行构建、测试、模型调用或其他应用服务。此前受干扰的 closedloop_ac19913 系列不参与本轮结论或对照。

## 工作负载和环境

MacBook Pro M4，Docker 8 CPU / 14,638,391,296 bytes，commerce 限 4 CPU；VM 内 k6。使用原版 setup_bench_env.sh、run_ladder.sh、seckill_ladder.js，不修改调度、batch、delay或应用代码。合并提交与此前已构建源码树完全相同，复用现有 JAR；两份 JAR SHA-256 及完整被测提交记录在 setup/environment。

正式一轮，无摸底。1 个活动 bench-activity-0、商品 bench-product，库存/额度各 600，650 个用户；10 请求/秒 × 60 秒，数量1，预期版本1。原脚本50预分配/200最大VU。新 transaction/timeout/catalog topic 与 group，suffix=closedloopA-69be167-20260905T171447Z。

正向预热为0。仅setup登录与只读JWKS/catalog readiness；正式前SQL预约/订单为0，库存/额度600，Redis旧intent keys、全局/活动handoff均0。没有隐藏正向预热积压。

## HTTP 原始结果

- 完成601次：600 ADMITTED（HTTP 201），原k6时长边界多出的1次 EXHAUSTED（HTTP 409）。dropped_iterations点合计0。
- k6原生 http_req_failed=1/601，对应上述售罄409；没有其他HTTP状态或业务决策，不能把该原生指标改写成0。
- 全部601次HTTP：p50=10.427084ms，p99=26.225ms，max=123.836084ms。
- 第一条HTTP完成：2026-09-05T17:20:14.613212297Z；最后一条：2026-09-05T17:21:14.489101838Z。以下“停压后”以最后HTTP完成为起点，不以runner退出时刻代替。

## SQL 业务闭环与订单窗口

600个reservation全部为ORDERED/ADMITTED，形成600个UNPAID订单和600条SECKILL_ORDER_CREATE账本；inventory_delta与activity_quota_delta各-600，MySQL库存与Redis剩余额度均为0。配置allocated_quota仍为600。

订单/预约绑定错误、准入无订单、重复用户、重复预约、订单创建账本差错、额外账本、订单创建越过付款截止，SQL均为0；无CANCELLED或UNFULFILLED。

首预约持久化：17:20:14.596577Z；首订单创建：17:20:17.999783Z；末订单创建：17:22:05.903500Z。首预约至末订单为111.3069秒，600÷111.3069=本批处理均值5.3905单/秒。这是有限批次完整处理窗口的平均数，不是持续成单容量或上限。

按登记口径，首预约向下取整秒得到t0=17:20:14Z；固定60秒订单创建窗口：

| UTC窗口（左闭右开） | 订单数 | 分母 | 窗口均值 |
| --- | ---: | ---: | ---: |
| 17:20:14–17:21:14 | 320 | 60秒 | 5.3333单/秒 |
| 17:21:14–17:22:14 | 280 | 60秒 | 4.6667单/秒 |

SQL预约created_at→订单created_at等待（600笔、nearest-rank分位）：p50=28.139秒，p95=58.462秒，p99=62.905秒，max=65.501秒；负差值0。此段仅表示预约持久化之后的等待，不是从客户端发送或MQ入队开始的精确端到端延迟。

## 停压清空与复查

- 17:22:01.536096Z仍有24笔待成单；17:22:06.634222Z首次观察600/600全成单。因此待成单清空落在停压后约47–52秒的采样区间。SQL末订单创建距最后HTTP完成约51.414秒，是跨容器墙上时钟差。
- 17:22:06.634222Z尚有24笔timeout待发送；17:22:11.741427Z首次全部SENT，Redis全局/活动handoff均0。因此包含timeout发送待办的业务清空落在停压后约52–57秒的采样区间。
- 17:24:38.956769Z显式topic consumerProgress：四队列broker/consumer offset分别152/155/135/158，合计600；Consume Diff Total=0，Consume Inflight Total=0。没有测量逐条ACK时刻，不把这一后验MQ读数倒推成精确的首次MQ清空时间。
- 17:25:37.812645Z稳定复查仍600/600、UNPAID/SENT、handoff0；库存/账本一致。
- 15分钟未付款关单消息发送到本轮隔离timeout topic，其未来定时投递不属于应立即清空的事务积压。

## 恢复与采用建议

最早付款截止17:35:17.997653Z。bench commerce/auth于17:26:43Z停止，17:26:44Z完成移除和原demo公开签名metadata事务恢复；恢复值与ignored备份逐字段一致。未修改私钥或普通demo业务，未通过付款、取消或删消息制造清空。commerce日志WARN/ERROR行数均为0，结束时源码干净、完整SHA不变。

这轮支持“有限库存600笔获准全部成单，订单、库存和配额账本一致，事务积压最终清空”。5.39单/秒及47–52秒应保留为带分母的测量事实与面试边界，不包装成高吞吐亮点；不因此覆盖既有售罄拒绝3,000/s结果，不在A阶段优化或重跑挑数。

[原始证据归档](closedloop-A-20260905-raw.tar.gz)包含同 label 的 k6 summary/points/console/cpu、ladder steps、setup/environment、SQL 查询及原始输出、Redis/MQ 命令及读数；每份结果保留完整被测 SHA。原件未经改写，归档时另保留本地副本。
