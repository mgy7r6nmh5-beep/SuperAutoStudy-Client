# SuperAutoStudy v1.0.20

发布日期：2026-10-31

上一版（1.0.19）把章节测验接了回来，但策略是写死的：抓到题就挂「待确认」，
必须人来点提交。这一版把策略交回给用户，并且允许**完全关掉答题**。

---

## 新增：答题策略五档

「设置 → AI 作答 → 答题策略」下拉框，改完立即生效，不用重启后端。

| 策略 | 行为 | 什么时候用 |
|---|---|---|
| **全部人工确认**（默认） | 抓题 + AI 出答案 + 落库，挂「待确认」等你逐题过 | 想拿高分 |
| **自动提交 · 全部 high** | 每道题都过校验、且 AI 自评全是 high 才自动交；有一道没把握就整份退回待确认 | 无人值守，最稳 |
| **自动提交 · ≥ medium** | 门槛放宽，「大概率对」的也一起交掉 | 不想天天盯 |
| **自动提交 · 能答就交** | 含 AI 自评 low 的题 | 完全不想管 |
| **关闭答题** | 遇到 workid 直接跳过，和 v1.0.8 之前一样 | 只想刷视频 |

### 为什么是五档，不是一个开关加一个阈值

真正决定风险的不是「自不自动」，而是**多没把握才肯交**。把门槛直接摆出来，
比让用户自己组合「自动提交 ☑ + 置信度 ≥ ?」两个控件清楚得多。

代码上也更省事：`QuizMode.minConfidenceRank()` 对不自动交的档返回 **4**，
比任何置信度档都高，于是判定处一句 `rank(conf) < minRank` 就覆盖了全部五种情况，
不用再单独判模式。

### 交失败不会静默消失

自动提交被服务端拒了，记录退回 `待确认` 状态，备注写「AI 自动提交被服务端拒绝，
请人工确认后重试」，你还能手工补一次。

### 关闭答题后

- 遇到 `workid` 直接跳过，日志打印 `[skip]答题功能已关闭，跳过[workid]: ...`
- 「待确认」页会一直是空的
- 课程照常刷，只是测验留着不做

---

## 实现要点

- 新增 `com.tihai.constant.QuizMode`：取值、合法性校验、置信度门槛、人话标签都收在这里
- `AiAnswerService.Config` 加 `answer_mode`，每次抓题前重读 `app/ai-config.json`
- `QuizService.prepare()` 加自动提交分支
- `SuperStarTaskServiceImpl.prepareQuiz()` 里判 `off` —— 放这一处就管住两个调用点
- `client/server.py` 的 `ANSWER_MODES` 元组必须和 `QuizMode` 一字不差，两边都加了注释

---

## 测试

- **门槛判定**：反射调私有方法 `confidentEnough(QuizPaper, int)`，9 组用例
  （全 high / 含 medium / 含 low / 空置信度 / 空答案 / 不自动交档）全部符合预期
- **配置读写**：5 档往返一致；写非法值 `bogus_mode` 不覆盖原值；
  只改模型时 `api_key` 与 `answer_mode` 都保留；对外接口只回掩码
- 前端 `node --check`、`client/server.py` 语法检查、`mvn clean package` 均通过

> 未做「真实自动提交」的端到端验证 —— 那会真的交掉一份卷子，而章节测验通常
> 只有一次机会，交错了没有补救。策略判定逻辑是纯函数，已单独测过；
> 真正提交走的是 1.0.19 已经验证过 100 分的那条链路，没有改动。

---

## 升级方式

- **已经装 1.0.19 的**：设置 → 检查更新，下 **客户端补丁包**（约 63 KB），
  再下完整安装包（这一版后端 jar 也变了）。
- **新装的**：直接用 `SuperAutoStudy-Setup-1.0.20.exe`。

> ⚠️ **OTA 仍然不可用。** GitHub 账号 `mgy7r6nmh5-beep` 被 suspended，
> `api.github.com` 一律 403/404。要恢复得换发布源。

---

## 校验

| 文件 | 大小 | SHA256 |
|---|---|---|
| `SuperAutoStudy-Setup-1.0.20.exe` | 109.5 MB | `160dcccc21caf33b4cddf065b8470654fa30db4d343d15311f7aafd18b1cc76e` |
| `SuperAutoStudy-portable-1.0.20.zip` | 191.9 MB | `346463890bc48e075f7dd3a57800fdf3dcdf568ddeffabfeb09f6c4a1fad1846` |
| `SuperAutoStudy-client-1.0.20.zip` | 62.8 KB | `94e35a319e007b630b0f3ea1ad1e3f106a1f3ad60bdaab8fc3749e5a6aed8f3f` |

便携包与安装包内容一致（约 339 MB 解压后，1515 个文件），都自带 JDK 8 +
MySQL 5.7 + Python 3.13，目标机不需要装任何运行时。
