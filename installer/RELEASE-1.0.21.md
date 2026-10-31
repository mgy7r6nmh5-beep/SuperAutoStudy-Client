# SuperAutoStudy v1.0.21

发布日期：2026-10-31

这一版没加新功能，全是把 1.0.20 那批改动**跑真实课程跑出来的坑**填掉，
顺手把「待确认」这个叫法换成「答题」。

---

## 一、章节测验终于能正常跑了

### `NoClassDefFoundError: org/apache/fontbox/ttf/TTFParser`

1.0.20 里 `fontbox` 被标成 `provided` 又从 `spring-boot-maven-plugin` 的
`<excludes>` 里删掉了，两头落空 —— 编译期有、运行期没有。

后果是**凡是用了字体反爬的章节，测验全抓不到**：超星把题干和选项拆成
`<span class="font-cxsecret">` 里的一堆私用区码点，靠 `FontDecoder` 拿
`TTFParser` 解字形轮廓才能还原成汉字。类找不到 → 整个 `prepareQuiz` 抛异常 →
那一章被当成「没有测验」跳过。13.5、13.6 等 6 章就是这么丢的。

改回 `compile` 作用域，并确认 `BOOT-INF/lib/fontbox-2.0.24.jar` 真的进了 fat jar。

> 本地 `.m2` 里只有 fontbox **2.0.24** 的 jar（2.0.27 只有 pom），离线构建只能用 2.0.24。

---

## 二、AI 调用：一题一问 + 三次重试

### 一题一问

以前是把整份卷子（10~20 题）一次塞进 prompt。模型会**跳题** —— 返回的 JSON 里
条数比题目少，或者几道题的答案串行。补答脚本按 id 匹配时经常只对上一条。

现在 `AiAnswerService.fill()` 改成循环单题：每道题单独组一次 payload。
慢一点（5 题二十几秒），但不会再缺题。

### 网络重试

本机是 fake-ip 代理环境，`api.siliconflow.cn` 解析到 `198.18.0.72`，
代理换映射时 JVM 的 DNS 缓存还指着旧地址，于是：

```
ConnectException: Failed to connect to api.siliconflow.cn/198.18.0.72:443
```

一失败就是整份卷子空白。现在：

- `post()` 外层套 3 次重试，退避 2s / 4s
- `client/server.py` 起 JVM 时加 `-Dsun.net.inetaddr.ttl=60`
  `-Dsun.net.inetaddr.negative.ttl=10`，缓存一分钟就过期
- 三次全挂才把整份标成「AI 不可用，先去看设置里的接口地址 / key / 模型」

---

## 三、重试作答

「答题」页每份卷子多了个**重试**按钮。适合这几种情况：

- AI 当时没配好，抓回来的是空答案
- 想换个模型重答一遍
- 有道题答得太离谱

`POST /api/quiz/{id}/retry` 会清掉 `suggestion` / `confidence` / `reason` /
`manual` / `note` 再重新问一遍 AI。**已经提交过的卷子拒绝重试** —— 超星那边
交了就交了，本地改答案没有意义。

---

## 四、查得分

### 交完卷拿不到分

以前交完只弹个「提交成功」。想看分得自己去超星网页翻。

现在交卷接口会顺着响应里的 `"url"` 跳一跳，把落点（批阅页）写回 `pageUrl`，
然后从批阅页正文里抠 `本次成绩 100 分`。

### 已交过的卷子怎么查

交过之后 `doHomeWorkNew` 只回一句**「无效的作答记录！」** —— 做题页不认了。
必须换 `selectWorkQuestionYiPiYue`（批阅页），而且 `workAnswerId` / `enc` /
`cpi` / `classId` / `oldWorkId` / `fullScore` 这些参数全在 `paper.form` 里存着，
拼得出来。

`CourseUtil.fillScore()` 先访问一次 `stucoursemiddle` 建课程上下文
（否则 passport 直接踢去登录页），再请求批阅页，正则抠分。

### 读不到分不会静默

抠不到就把 HTML 落到 `app/quiz-dump/score-<workId>.html`，方便对着页面结构排查。

### 刷新成绩不动交卷时间

`refreshScore()` **只写 `paper_json`，不碰 `update_time`**。

这一条踩过坑：`update_time` 那一列被当成「交卷时间」在用，刷新成绩时顺手
`UPDATE ... SET update_time = NOW()`，结果界面上 12 份卷子全变成「刚刚才交的」。
后来对着 `boot.log` 里 `提交 … -> HTTP 200` 的记录逐条还原回去。

---

## 五、得分列表

「答题」页顶部新增「最近提交」面板：

```
安全与危险               2026-10-31 18:44:53        100
常用劳动安全法律         2026-10-31 18:44:52        100
新时代的劳动观           2026-10-31 18:44:51         50
```

- **按交卷时间倒序**（`ORDER BY update_time DESC`，不是按 id）
- 分数按百分制着色：≥90 绿、≥60 黄、<60 红
- 单条「查得分」+ 一键「全部查得分」
- 全部交完之后这个面板**还在**（1.0.20 的 `viewQuiz()` 空列表分支提前 return，
  把面板跳过去了）

---

## 六、「待确认」改叫「答题」

标签、按钮、toast、空状态文案全换。理由很直白：这个页早就不只是「等你确认」了 ——
它能重答、能提交、能查分，叫「答题」更贴。

`grep -c 待确认 client/web/app.js` → **0**。

---

## 七、AI 配置填一半被冲掉

「设置 → AI 作答」改了一半，切个页面回来就变回旧值。

原因：`renderAiConfig()` 每次重绘都无条件把服务端值灌回输入框，而页面重载
（客户端重启、窗口重开）会触发重绘。

修法三层：

1. 输入框内容实时写 `localStorage['sas-ai-draft']`
2. `renderAiConfig()` 开头 `if (aiFormFocused()) return;` —— 光标还在框里就不动
3. `client/server.py` 覆盖 `ai-config.json` 前先留一份 `.bak`

> 我自己也犯过一次：为了验证接口通路把整份配置 POST 回去，覆盖了用户刚存的值。
> 第 3 条就是为这种情况加的。

---

## 实现要点

| 文件 | 改了什么 |
|---|---|
| `app/pom.xml` | fontbox 改回 compile，从 repackage excludes 移除 |
| `service/ai/AiAnswerService.java` | `fill()` 改循环单题；`ask()` 内 3 次重试 |
| `common/QuizPaper.java` | 新增 `score` / `fullScore` / `scoreAt`（存 `paper_json`，不改表） |
| `utils/CourseUtil.java` | 新增 `fillScore()`；`submitQuiz()` 跟随响应 `url` 更新 `pageUrl` |
| `service/quiz/QuizService.java` | 新增 `retry()` / `refreshScore()` / `recent()` / `updatePaper()` |
| `controller/QuizController.java` | 新增 `POST /{id}/retry`、`POST /{id}/score`、`GET /recent` |
| `client/web/app.js` | AI 配置草稿、重试/查分按钮、得分面板、改名 |
| `client/web/index.html` | 标签「待确认」→「答题」 |
| `client/server.py` | 路由转发 retry/score/recent；DNS TTL 参数；配置备份 |
| `installer/pack.py` | `SKIP_REL` 补 `app/*.log`、`my.ini`、`mysql57/console.log*` |

---

## 实测

真实课程跑通 12 份卷子（交卷时间倒序，时间取自 `boot.log` 里的提交记录）：

| 课程 | 交卷时间 | 得分 |
|---|---|---|
| 安全与危险 | 18:44:53 | 100 |
| 常用劳动安全法律 | 18:44:52 | 100 |
| 新时代的劳动观 | 18:44:51 | **50** |
| 马列经典中的劳动观 | 18:44:50 | 100 |
| 劳动安全与职业健康——事故类型 | 18:39:14 | 100 |
| 传统文化中的劳动观 | 18:31:21 | 100 |
| 理解劳动与职业发展 | 18:31:20 | **75** |
| 劳动的本质、分类及作用 | 18:31:19 | **60** |
| 如何学习和进行劳动教育？ | 18:31:18 | 100 |
| 劳动安全与职业健康——职业健康 | 18:30:49 | 页面读不到分 |
| 安全与应急逃生 | 18:30:48 | 100 |
| 劳动安全事故责任 | 18:30:20 | 100 |

> 12 份里 8 份满分。三道失分的题 AI 都自评了 high —— 这是模型的自信偏差，
> 不是解析或提交链路的 bug。想稳一点还是用「全部人工确认」档。

`mvn clean package`、`node --check client/web/app.js`、`python -m py_compile client/server.py`
均通过。便携包 `testzip` 自检 `bad = None`。

---

## 升级方式

- **已经装 1.0.20 的**：设置 → 检查更新，下**客户端补丁包**（约 66 KB），
  再下完整安装包（后端 jar 也变了，fontbox 就在里面）。
- **新装的**：直接用 `SuperAutoStudy-Setup-1.0.21.exe`。

> ⚠️ **OTA 仍然不可用。** GitHub 账号 `mgy7r6nmh5-beep` 被 suspended，
> `api.github.com` 一律 403/404。要恢复得换发布源。

---

## 校验

| 文件 | 大小 | SHA256 |
|---|---|---|
| `SuperAutoStudy-Setup-1.0.21.exe` | 111.0 MB | `215b7edabb16eec8ce1cdbae7a0d7209210be8fb84ddc78833b679fb037a9876` |
| `SuperAutoStudy-portable-1.0.21.zip` | 193.4 MB | `453c718471e1d0e7499d070beae83eb83d361a7235d4b67e53650ef65747528f` |
| `SuperAutoStudy-client-1.0.21.zip` | 65.6 KB | `7443462d081d83a13e5e713713bfa0f2ca7269a99662f765565aeceeab966bb8` |

便携包与安装包内容一致（解压后约 341 MB，1511 个文件），都自带 JDK 8 +
MySQL 5.7 + Python 3.13，目标机不需要装任何运行时。
