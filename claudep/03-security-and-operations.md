# 03｜安全、凭证与运维

## 1. 安全目标

1. 攻陷 Android Provider 配置不能直接取得 Claude OAuth。
2. 攻陷 Gateway 公网处理层不能读取 Claude OAuth 文件或执行任意命令。
3. 恶意模型输出不能绕过手机工具审批。
4. 重连、超时和进程崩溃不能重复执行模型请求或写工具。
5. 日志、崩溃报告、备份与同步不能包含 Token、prompt、附件正文或工具 Secret。
6. 一个设备不能访问另一个设备/用户的 session、事件或附件。

第一版的服务端部署是单一 Claude 账号所有者边界。不得通过一个 Claude 登录向互不信任的多名用户提供公共中转；若未来支持多用户，必须使用独立 OS 身份、独立 Claude 凭证、独立 session/文件根目录和完整租户隔离重新评审。

## 2. 主要威胁与控制

| 威胁 | 必须控制 |
|---|---|
| 配对码泄露 | 高熵、短 TTL、单次消费、服务端只存哈希、完成后通知已有设备 |
| bearer token 被截获 | 短期 access token + Keystore 私钥 nonce 签名刷新 |
| 请求重放 | request ID、单调序号、时间窗、设备绑定、请求指纹 |
| 任意 CLI 参数注入 | 类型化 argv builder；客户端永不传 argv/env/cwd/path |
| Claude 内置工具越权 | `--tools ""`、空 setting sources、禁 slash command、strict MCP config |
| 危险权限绕过 | 永禁 `--dangerously-skip-permissions` 及同类参数 |
| 其他 MCP namespace 混入 | 只允许单一 `mcp__rikkahub_bridge__*`，工具 snapshot 精确校验 |
| Prompt/工具参数进日志 | 固定字段结构化日志，只记录长度、计数、安全枚举和哈希短前缀 |
| stderr 泄密 | 有界内存 allowlist 分类，原文不出 Worker |
| session 串线 | thread/branch/device 完整绑定，resume/fork 前原子校验 |
| 自动重试产生双调用 | dispatch 后禁止自动模型 retry，先查 receipt |
| 工具重复副作用 | call ID + 参数指纹 + approval nonce + executionStartedAt/终态 |
| 手机断线 | 工具等待超时 fail-closed；不转为 VPS 本地执行 |
| 附件路径穿越 | 随机服务端文件名、固定根目录、realpath 校验、拒绝软链/特殊文件 |
| Gateway SSRF | 不接受任意上游 URL；Claude 与 quota endpoint 编译期/配置 allowlist |

## 3. Claude Worker 启动策略

最终 argv 必须由单一纯函数构造并经过结构化测试。Phase 1 预期安全形态：

- 固定绝对 binary 路径；
- `-p`；
- stream-json 输入/输出；
- 明确模型 alias；
- `--tools ""`；
- `--setting-sources ""`；
- `--disable-slash-commands`；
- `--permission-mode dontAsk`；
- 不加载用户/项目 MCP 和 CLAUDE.md；
- `shell=false` 等价进程启动；
- 无危险权限参数。

Phase 3 仅新增：

- `--strict-mcp-config`；
- 指向本 Generation root-owned/受控临时配置的 `--mcp-config`；
- 唯一 `--allowedTools mcp__rikkahub_bridge__*`。

不得移除 `--tools ""` 来“顺便”启用内置工具。

## 4. OS 和文件权限

- Gateway、Worker、Claude 子进程使用不同的服务/权限边界；
- 公网 Gateway 不能遍历 Claude home 或 `/etc/yanlan` 类 Secret 目录；
- Worker socket 使用专用 group 与 `0660`，不对公网监听；
- runtime 目录 root-owned，不允许服务用户修改 binary；
- 临时目录每 Generation 唯一、`0700`、终态精确删除；
- systemd 使用 `NoNewPrivileges`、`PrivateTmp`、`ProtectSystem=strict`、受限 `ReadWritePaths`、资源和进程数上限；
- 不把服务用户加入能够读取其他应用 Secret 的宽泛 supplementary group。

## 5. 许可与产品标识

- RikkaHub Agent1 继承 AGPL-3.0；在仓库内实现或从其代码派生的 Gateway/协议组件必须保留许可和相应源码义务；
- “Claude P”界面应注明这是用户自托管的非官方集成，不得暗示由 Anthropic 或 RikkaHub 上游提供；
- 不得把个人 Claude 登录包装成面向第三方的共享收费 API；
- 发布前重新核对届时有效的 Claude Code 使用条款、支持平台和认证要求；
- 二维码、日志和截图不得暴露订阅账号标识。

## 6. 版本与供应链

- Claude Code 使用明确版本与 SHA-256，升级必须独立兼容性审计；
- Gateway/Worker/Android 协议 ABI 单独版本化；
- Worker readyz 同时报告 expected/actual 版本的安全字段；
- 版本不符时模型 invocation 为 0；
- 自动更新关闭，升级通过受控部署完成；
- Android release 构建不得信任调试 CA 或明文网络配置。

## 7. 审计事件

允许记录：

- device paired/revoked；
- connection authenticated/failed 安全分类；
- generation accepted/dispatched/terminal；
- session new/resume/fork/rebuild；
- tool requested/approved/denied/executed；
- attachment staged/ready/deleted；
- worker restart/recovery；
- protocol/version gate。

审计不得记录：prompt、回答正文、推理正文、工具参数/结果正文、文件名原文、OAuth、Authorization header、Cookie、完整 session ID。关联 ID 使用 scoped opaque ID 或 HMAC。

## 8. 数据保留

- 事件正文缓冲：仅为断线重连保留短 TTL；
- receipt：保留安全终态和计数，不保留内容；
- session binding：用户撤销设备或删除 Provider 时可删除；
- 工具等待状态：终态即清理，仅留安全审计；
- 附件：最短必要 TTL，终态/过期 reconciler 清理；
- Gateway 数据不得进入 RikkaHub WebDAV 备份；Android 配对元数据进入备份时不包含不可导出私钥和 access token。

## 9. 运维门禁

每次部署前后至少检查：

- Git/构建物/版本哈希；
- Backend/Gateway/Worker active 与 health/ready；
- Worker `active_runs=0`；
- Claude child=0；
- active Generation/session binding 无异常；
- socket owner/group/mode；
- 临时目录和过期 receipt；
- Secret marker=0；
- 回滚点存在且校验通过。

真实模型 smoke 必须按“次数 + 模型 + 目的”单独授权。零模型门禁失败立即停止，不得修后同轮继续消耗授权。

## 10. 撤销与恢复

- 手机可撤销自身设备，VPS 管理员可撤销任意设备；
- 撤销后 access token、刷新证明、WSS、active Generation 和工具等待全部失效；
- Claude OAuth 失效只影响 Worker，不自动删除手机配对；
- Worker/Gateway 重启后 active run 收敛为 completed/cancelled/failed/interrupted 之一，不能永远 active；
- 恢复程序幂等，不能重新执行未知状态写工具；
- 删除 Provider 默认仅删除手机配对信息，是否同时撤销 VPS 设备须明确确认。
