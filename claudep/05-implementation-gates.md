# 05｜实施阶段与验收门禁

## 总则

- 每个阶段独立分支/工作树、独立报告、独立回滚点；
- 先本地假服务，再隔离集成环境，最后真机；
- 任何真实模型请求必须单独批准次数、模型和目的；
- skipped、通道失败和 preflight 失败不能记作通过；
- 未通过当前阶段，不开启下一阶段能力开关。

## Gate CP0：设计冻结（本目录）

交付：架构、协议、安全边界、接入图、Gate 和决策记录。

完成条件：

- Claude、手机、Gateway、Worker、MCP 的凭证/数据归属无冲突；
- v1 协议具备版本、幂等、终态、取消和恢复；
- 明确第一版无工具/附件/后台任务；
- 无需选择 DNS、证书供应商等部署参数即可开始本地实现。

## Gate CP1：纯文本 Provider

实现：

- `ProviderSetting.ClaudeP`、设置页、配对 credential store；
- fake Gateway + WSS v1；
- catalog、文本 stream、reasoning 摘要、usage、cancel、receipt；
- Gateway/Worker 最小实现，固定 CLI 版本与安全 argv；
- `new` 单轮模式，不启用工具与附件。

验证：

- Provider 序列化/备份升级；
- 配对、撤销、错误 origin、签名失败、token 过期；
- 流式顺序、唯一终态、取消、断线重放；
- 相同 request ID 重放、不同指纹冲突；
- stderr/Secret/prompt 日志脱敏；
- Worker 子进程和 active run 收口；
- Android 单元、UI、instrumented/fake gateway 测试全绿；
- 零模型 VPS smoke 后，申请 1 次真实模型 READY/固定文本验收。

用户价值：可以在 RikkaHub 使用 Claude P 进行纯文本聊天。

## Gate CP2：连续会话

实现：

- remote thread/branch binding；
- new/resume/fork/rebuild planner；
- App/Worker 重启恢复；
- 同线程并发门禁；
- session missing/invalidated 安全降级；
- quota/auth status 安全枚举。

验证：

- new→resume；
- fork session 与来源不同且不覆盖来源 binding；
- 编辑、重生成、分支；
- 错设备/线程/分支/session 全拒绝；
- cancel 幂等和 cancel race；
- Worker/Gateway SIGKILL 后唯一终态；
- rebuild 不产生重复历史；
- 每项真实模型验收次数单独授权且失败不自动重试。

用户价值：日常连续对话、分支和恢复可用。

## Gate CP3：手机工具与 MCP 桥

实现：

- 工具 snapshot 与 bridge namespace；
- per-generation 临时 MCP bridge；
- `tool.requested`/approval/result 双向协议；
- 复用 RikkaHub local/MCP executor；
- 写工具一次性审批、防重放、断线 fail-closed；
- 工具图片结果的有界处理。

验证：

- 只读工具无需写批准但仍受 Agent/Generation 绑定；
- 写工具未批准拒绝；
- 错设备、Agent、Generation、call ID、参数、过期批准拒绝；
- 重复 tool call 不产生双副作用；
- approval、执行、持久化和 UI 状态一致；
- 手机 MCP OAuth Token 从未出现在 Gateway；
- 手机断线、Gateway/Worker 重启、MCP 超时和取消；
- Claude CLI 无内置工具、无第二 namespace、无危险参数；
- 确定性 fake 模型闭环通过后，再申请 1 次真实 Claude 工具闭环。

用户价值：Claude P 能安全使用手机工具与用户已连接的 MCP。

## Gate CP4：图片与文档

实现：

- 临时分块上传、内容哈希/MIME/大小验证；
- Generation 绑定 handle；
- Worker sandbox 挂载/复制策略；
- 终态清理与 reconciler；
- 模型能力和不支持输入的前置拒绝。

验证：

- 正常图片/文档；
- 空文件、超限、扩展名欺骗、截断、额外字节；
- 跨设备/Generation handle；
- 上传取消、断网、Worker crash；
- 文件不可进入日志、全局 Claude home、公共缓存；
- 清理幂等且不误删；
- 模型不支持多模态时 dispatch 前拒绝；
- 真机只使用非敏感测试附件。

用户价值：Claude P 支持安全多模态输入。

## Gate CP5：真机、升级与发布

验证矩阵：

- 新安装、升级、备份/恢复；
- Android 后台/前台、网络切换、进程被杀；
- 多设备与设备撤销；
- Gateway/Worker 重启和版本升级；
- Claude 登录过期；
- new/resume/fork/cancel；
- MCP OAuth、审批、重复调用；
- 图片/文档与清理；
- 额度不可用；
- 限流和资源耗尽；
- release 构建混淆与网络安全配置；
- 回滚到不含 Claude P 的旧版本时不损坏其他 Provider。

完成后才允许默认展示或正式发布。Claude P 初始必须 disabled，由用户主动配对并启用。

## 各 Gate 通用退出证据

- 基线祖先链与 Git 洁净；
- typecheck/compile、lint、单元、UI、instrumented 测试可靠 exit 0；
- Gateway/Worker 单元与协议契约测试；
- 构建产物哈希；
- 服务 health/ready；
- active Generation/run/Claude child 为 0；
- 临时目录、fixture、测试设备和授权精确清理；
- Secret marker 为 0；
- 中间失败与最终通过分开记录；
- 未授权的模型调用数为 0。

