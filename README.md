# rc_NANGYANGFISH

Reliable HTTP Notification Service — API 通知系统 MVP。

## 1. 需求理解与验收范围

依据 [作业原文](job.txt)，实现一个供可信内部业务系统使用的异步 HTTP(S) 通知服务。业务方提交目标 URL、自定义 Header 和原始文本 Body；服务先持久化再确认接收，后台负责投递、失败重试与终态管理。**接收成功不代表供应商已处理成功。**

必做：可运行代码、可靠投递闭环、系统边界/失败策略/演进说明、AI 使用说明。技术栈不限，不要求调用大模型 API；这里的 AI 是开发辅助工具，不是通知服务运行时依赖。

增强项已实现：提交幂等、状态查询、死信重投、Bearer 鉴权、目标白名单、SSRF 基础防护和自动化测试。消息队列、多实例、可视化管理、供应商适配模板不是本版必做项。

## 2. 快速运行

需要 JDK 11+、Maven 3.6.3+；首次构建需要访问 Maven Central 或自行配置可信镜像。不需要部署数据库、Redis 或消息队列。

从仓库根目录执行：

```bash
mvn -f notification/pom.xml verify
export NOTIFY_API_TOKEN="$(openssl rand -hex 32)"
export NOTIFY_ALLOWED_ORIGINS='https://supplier.example.com'
mvn -f notification/pom.xml compile exec:java
```

请把示例域名替换为实际可访问的供应商地址。服务默认仅监听本机 8080；同一个终端的环境变量可用于下方请求。其他终端需要安全地设置同一令牌。不要将令牌提交到 Git。

启动配置见 [`NotificationApplication.java`](notification/src/main/java/notification/NotificationApplication.java)：

| 环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| NOTIFY_API_TOKEN | 必填 | 至少 32 个非空白可打印字符；所有接口均鉴权 |
| NOTIFY_ALLOWED_ORIGINS | 必填 | 逗号分隔的精确 origin，例如 https://api.example.com,http://other.example.com:8081；不能带业务路径或查询参数 |
| NOTIFY_BIND_HOST | 127.0.0.1 | 对外监听需显式设为 0.0.0.0，并配网关访问控制 |
| NOTIFY_PORT | 8080 | 1–65535 |
| NOTIFY_DB_PATH | ./data/notifications | H2 数据库路径前缀，不包含 .mv.db；相对路径基于启动工作目录 |
| NOTIFY_TIMEOUT_MS | 5000 | HTTP 连接/读/请求中止时限，100–10000 ms |
| NOTIFY_MAX_ATTEMPTS | 8 | 每轮最多尝试 1–100 次，包含首次；崩溃前认领也消耗预算 |
| NOTIFY_RETRY_BASE_MS | 1000 | 指数退避基数，至少 1 ms |
| NOTIFY_RETRY_CAP_MS | 300000 | 退避上限，不低于基数、不超过 86400000 ms |

无 Maven 命令但本机已有全部依赖缓存时，可用 [`verify-local.sh`](notification/verify-local.sh)：

```bash
bash notification/verify-local.sh          # 编译并运行相同的 JUnit 测试
bash notification/verify-local.sh run      # 读取上述环境变量并启动服务
```

该脚本只使用本机 Maven 缓存，不下载依赖；缺失会明确失败。标准构建入口仍是 Maven。脚本会进入模块目录，所以脚本启动与根目录 Maven 启动的默认相对数据目录不同；实际部署应使用固定绝对路径。

## 3. API 使用

### 提交通知

```bash
curl --fail-with-body -X POST http://127.0.0.1:8080/notifications \
  -H "Authorization: Bearer $NOTIFY_API_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"id":"crm-payment-20260912-001","url":"https://supplier.example.com/events","method":"POST","headers":{"Content-Type":"application/json","X-Event-Type":"payment"},"body":"{\"userId\":\"u-1\",\"status\":\"paid\"}"}'
```

注意：body 是原始 UTF-8 **字符串**，不是 JSON 对象；可传 JSON、XML、表单等文本，服务不做供应商格式转换。method 默认 POST，支持 POST/PUT/PATCH/DELETE/GET；GET 不支持 body。请求整体上限 128 KiB，body 上限 64 KiB，最多 32 个 Header、总长 8 KiB，URL 最长 4096 字符。Header 只接受 ASCII 可打印值，禁止 Host、Content-Length、连接控制字段及保留的 Idempotency-Key 等。

首次持久化成功返回 202 和 Location；重复提交同一 id、相同规范化请求返回 200；同一 id 不同内容返回 409。id 为 1–128 个字母、数字、下划线或连字符，业务方应包含系统/事件前缀并全局唯一。Header 名大小写与顺序会规范化；body 字符串及 URL 按原值比较，不把语义相同的不同 JSON 当作同一内容。

返回状态包含 id、state、attempts、nextAttemptAt、createdAt、updatedAt、lastHttpStatus 和 lastError。时间为 Unix 毫秒，终态 nextAttemptAt 为 0；状态接口不返回原始 Header、Body 或供应商响应正文。

### 查询、重投与健康检查

```bash
curl --fail-with-body -H "Authorization: Bearer $NOTIFY_API_TOKEN" \
  http://127.0.0.1:8080/notifications/crm-payment-20260912-001
curl --fail-with-body -X POST -H "Authorization: Bearer $NOTIFY_API_TOKEN" \
  http://127.0.0.1:8080/notifications/crm-payment-20260912-001/redrive
curl --fail-with-body -H "Authorization: Bearer $NOTIFY_API_TOKEN" http://127.0.0.1:8080/health
```

重投仅允许 DEAD 状态，成功返回 202，重置本轮尝试数及最后错误，保留原始内容和 id；不存在返回 404、非 DEAD 返回 409。修改已接受的内容不支持，修正业务请求需使用新 id，操作前应核对供应商是否已经生效，避免重复业务影响。

常见错误：400 非法输入、401 鉴权失败、405 方法不允许、413 过大、415 非 JSON、503 存储不可用。提交超时或 5xx 时，调用方以**原 id 和原内容**重试，不能因响应丢失换一个 id。

## 4. 架构与关键文件

| 文件 | 职责 |
| --- | --- |
| [`pom.xml`](notification/pom.xml) | Maven 构建、固定依赖版本、运行入口 |
| [`NotificationApi.java`](notification/src/main/java/notification/NotificationApi.java) | JDK HTTP Server、鉴权、请求限制、提交/查询/重投 |
| [`NotificationStore.java`](notification/src/main/java/notification/NotificationStore.java) | H2 文件库、幂等、任务状态与到期索引、重启恢复 |
| [`DeliveryWorker.java`](notification/src/main/java/notification/DeliveryWorker.java) | 单线程后台扫描、重试退避、状态持久化 |
| [`HttpDelivery.java`](notification/src/main/java/notification/HttpDelivery.java) | Apache HttpClient 投递、超时、HTTP 分类、Retry-After |
| [`TargetPolicy.java`](notification/src/main/java/notification/TargetPolicy.java) | 精确 origin 白名单、Header 校验、连接时 DNS/IP 检查 |
| [`NotificationServiceTest.java`](notification/src/test/java/notification/NotificationServiceTest.java) | 真实文件数据库与本地 HTTP 集成测试 |

链路：业务方 → API 校验 → 数据库提交 → 202；后台每 200 ms 在上一次处理结束后扫描到期任务 → 认领并记录尝试数 → HTTP 请求 → 持久化结果。

状态流：PENDING/RETRY → IN_FLIGHT → SUCCEEDED、RETRY 或 DEAD；DEAD 经人工重投回到 PENDING。只有一个服务进程和一个投递线程；H2 文件锁排除第二个进程。数据库方法串行化，HTTP I/O 不持有数据库锁，不会让供应商超时占用接入层存储锁。无需分布式租约或外部调度器。

## 5. 可靠性与失败处理

**语义是允许重复的至少一次尝试，并设置有限自动重试预算；不是 exactly-once，也不保证永久不可用的供应商最终成功。** 在持久卷完整、服务能恢复且仍有预算的前提下，未完成任务继续投递；无法完成的进入可查询、可人工重投的 DEAD，不静默删除。

- 持久化确认：使用 H2 文件库及 WRITE_DELAY=0，自动提交完成才返回 202。并不把内存队列作为事实源；仍依赖文件系统、磁盘及备份能力，不承诺磁盘损坏或卷丢失不丢数据。
- 崩溃恢复：启动时把旧 IN_FLIGHT 转为 RETRY。即便供应商成功，也可能因本地结果未提交而重复。每次携带同一 Idempotency-Key，**只有供应商实际支持去重才能避免重复副作用**。
- 结果落库失败：运行中的 worker 保留已收到结果，优先重试落库，不立即重发 HTTP；若进程也退出，依然回到上述不确定窗口。
- 认领即计次：避免反复崩溃导致无限尝试；在最后一次认领后、发送前崩溃也可能直接耗尽预算。下一次认领记录 ATTEMPT_BUDGET_EXHAUSTED 并转 DEAD；该状态下 attempts 可比最大预算多 1，但不会再发 HTTP，需人工判断重投。
- 2xx 成功；408、429、5xx、网络错误/超时重试；其他 4xx 和 3xx 直接 DEAD。禁用自动重定向、客户端隐式重试及 Cookie，所有业务重试由 worker 统一管理。2xx 中的业务失败码不解析，供应商异步处理结果不追踪。
- 退避为 min(基数乘 2 的尝试次数减一次方, 上限) 的一半至全值随机抖动。Retry-After 支持秒数和 HTTP 日期，最多等待 24 小时，取其与退避值的较大值；达到次数上限不再自动重试。
- 查询和状态日志用于排障，日志不记录目标 URL、凭据、请求正文或供应商正文；本版仅保存最后一次结果，不提供每次尝试的审计历史。

## 6. 系统边界、安全与取舍

解决：持久化接入、异步 HTTP(S) 投递、受控失败重试、接入幂等、恢复与人工补偿入口。业务方仍需确保事件确实提交给本服务；业务事务与调用间的原子性属于业务方 outbox 的职责。

不解决：供应商业务事务/去重、严格顺序、延迟 SLA、供应商动态签名/OAuth 刷新、复杂模板、二进制附件、多租户权限隔离、多实例高可用。这些需要真实业务约束，不为 MVP 引入泛化平台。

选择现有 Java 11、JDK HTTP Server、JDBC/H2，沿用仓库代码，不为了框架偏好切换到 Spring Boot。H2 让演示无需基础设施，同时具备事务提交和索引；纯内存无崩溃恢复，自己写文件 WAL 会承担更多恢复与并发正确性工作。Apache HttpClient 用于可控制的 DNS 解析、HTTP 方法、超时和重定向策略；未再增加调度或容错框架。

不引入 MQ：当前数据库既存任务又当队列，避免双写一致性、部署及排障负担。替代方案是 MySQL/PostgreSQL 任务表轮询；真正需要队列削峰时，再引入事务 outbox + MQ，消费者仍须幂等。不是所有可靠投递系统都必须第一天使用 Kafka/Redis/分布式锁。

安全默认：必须配置令牌和精确 origin 白名单；发送前重验策略，连接时校验 DNS 解析结果，显式校验 IP 字面量，拦截常见回环、私网、链路本地、ULA 和 CGNAT 地址；不追随重定向。仅测试代码可允许本机接收器，生产无关闭检查的环境开关。该策略不是完整网络隔离方案，部署仍需出口防火墙、可信 DNS、TLS 网关、鉴权和限流；内部 API 默认明文 HTTP 仅绑定回环。

已知限制：一个慢供应商会延迟其他供应商投递；HTTP 中止不保证能打断底层操作系统 DNS 阻塞。健康检查能反映数据库/worker 异常，但没有“长期无进展”看门狗。任务内容（可能含供应商密钥）以明文存于数据卷，需文件权限、磁盘加密和备份访问控制。数据无自动清理、无队列容量上限及积压告警，不应未经治理直接开放给不受信任客户端或大流量生产。

演进顺序：先测量吞吐、失败率、积压年龄并增加报警/容量限制/保留期；再引入供应商维度公平调度、限流熔断和有限并发；需要多实例时迁移共享数据库并增加原子认领、租约及 fencing token；业务量证明必要后再采用 outbox/MQ、凭据托管、供应商适配层和审计。删除幂等记录会缩短去重窗口，保留策略必须与业务方约定。

## 7. AI 使用说明（协作事实与作者责任）

AI 提供了需求拆解、已有实现审查、可靠性边界分析、安全修复、集成测试和文档草稿；这不是人工独立编写的项目。

本次协作中未继续采用的 AI 方案：此前提出 Spring Boot 3.5.4 新骨架、租约抢占、独立投递记录表和统计接口。检查当前工作区后，实际已有轻量 Java/H2 实现；本版保留单进程状态机和最后结果，不再为了方案完整性更换技术栈或增加分布式组件。对响应成功语义也不包装成“必达”或“恰好一次”，而是明确不确定窗口及人工补偿。

可核实的人为决策来自提交者的执行要求：以本地代码而非远端为准、保留已有变更、选择最小必要改动、要求实际测试、不允许擅自提交推送。具体重试参数、单进程设计与安全策略属于 AI 基于现有代码提出/整理的工程实现，**尚不能声称提交者已独立决定或审核认可**。正式提交作业前，作者应亲自核对上述取舍，并补充自己实际采纳/拒绝的建议及理由；本说明不伪造人工决策经历。

## 8. 验证范围

测试使用真正 H2 文件存储与 HTTP socket；仅供应商是测试接收器，不替换生产发送逻辑。覆盖异步提交、文本与 Header 透传、状态脱敏、并发幂等/冲突、503 后恢复、429 等待、4xx/重定向、超时、预算耗尽/重投、重开数据库恢复、成功后未落库的重复窗口、结果写入故障、鉴权/大小限制/SSRF 及后台调度。恢复测试模拟崩溃边界后重开数据库，不等同于实际断电测试。

本次实际验证：本地脚本执行 16 个测试，全部通过；Maven verify 执行相同 16 个测试，0 失败、0 错误、0 跳过，并成功生成普通 JAR（非自包含可执行 JAR，运行请使用上文入口）。启动真实应用进程后，鉴权健康检查返回 HTTP 200 和 healthy=true，随后正常停止进程。未进行真实供应商凭据联调、TLS 端到端测试、压力测试或断电测试。

验证环境的 Maven 不在 PATH，使用了已安装 Maven 的绝对路径；首次离线构建因缺少 Surefire JUnit provider 和默认打包插件失败。固定打包插件版本，并使用构建目录内的临时 Maven Central 镜像配置完成 verify；未修改用户全局设置或把镜像作为项目运行依赖。

提交注意：保留了任务开始前已存在的暂存区和 IDE 文件变更；已有暂存的构建产物不会因新增忽略规则自动移出暂存区。正式提交前请自行审核暂存内容。本次未执行 Git 提交或推送。