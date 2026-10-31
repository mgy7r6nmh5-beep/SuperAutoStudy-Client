package com.tihai.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tihai.common.QuizQuestion;
import com.tihai.constant.QuestionTypeConstant;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用大模型给章节测验出答案。
 *
 * <p>走 OpenAI 兼容的 {@code /chat/completions}，所以 DeepSeek / 通义 / 智谱 /
 * Kimi / 本地 Ollama 填个 base_url 就能换，不需要为每家的 SDK 写一份适配。
 *
 * <p>三个刻意的设计：
 * <ol>
 *   <li><b>只返回选项字母，不返回选项文本。</b> 让模型复述原文，它经常顺手
 *       改个同义词或者删个标点，本地就匹配不上了。字母最稳。</li>
 *   <li><b>一次请求打包全部题目。</b> 分题请求既慢又贵，而且同一份卷子里
 *       前后题的语境是相关的。</li>
 *   <li><b>答不出来就认怂。</b> 校验不过的题标 {@code manual} 让人工填，
 *       绝不随机凑一个 —— 随机交卷比空着更糟，因为提交即定分。</li>
 * </ol>
 */
@Slf4j
@Service
public class AiAnswerService {

    private static final String CONFIG_FILE = "ai-config.json";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 系统提示。
     *
     * <p>小模型（7B 档）对「请不要写解释」这种软要求基本无感，必须把规则写成
     * 硬约束 + 正反例，它才会照做。所以这里刻意啰嗦：先锁死输出形态（首尾字符、
     * 不许包代码块、必须一题不漏），再逐题型规定 answer 字段长什么样。
     *
     * <p>提示词长了会多花点 token，但一份卷子本来也就几百 token，
     * 而少一次「解析失败 → 整批作废」的收益远大于那点成本。
     */
    private static final String SYSTEM_PROMPT =
            "你是大学在线课程的答题助手。用户会给你一份测验的全部题目。\n"
                    + "\n"
                    + "【输出形态，必须严格遵守】\n"
                    + "1. 只输出一个 JSON 对象。第一个字符必须是 { ，最后一个字符必须是 } 。\n"
                    + "2. 禁止用 ``` 包裹，禁止输出「好的」「以下是答案」这类开场白，禁止解释思路。\n"
                    + "3. 结构固定为：\n"
                    + "{\"answers\":[{\"id\":\"题目id\",\"answer\":\"答案\","
                    + "\"confidence\":\"high\",\"reason\":\"依据\"}]}\n"
                    + "4. answers 必须**逐题**给出，用户给几道题就回几条，id 原样抄回去，"
                    + "顺序与题目顺序一致，一道都不能漏。\n"
                    + "\n"
                    + "【answer 字段怎么写，按题型区分】\n"
                    + "- 单选 single：只写一个大写字母，例如 \"B\"。"
                    + "禁止写汉字、禁止抄选项原文、禁止写多个字母。\n"
                    + "- 多选 multiple：写所有正确项的大写字母，按字母升序连写，例如 \"ACD\"。"
                    + "多选题正确答案至少有 2 个，只写一个字母一定是错的。\n"
                    + "- 判断 judge：只写 true 或 false。"
                    + "禁止写「对」「错」「正确」「错误」「是」「否」，也不要写 A / B。\n"
                    + "- 填空 fill：只写空缺处本身，题干里已经有的字一个字都不要重复，"
                    + "也不要带题号。有多个空时按顺序用 ||| 分隔，例如 \"世界|||历史\"。\n"
                    + "- 简答 short：写一段不超过 100 字的答案，不要分点、不要换行。\n"
                    + "\n"
                    + "【另外两个字段】\n"
                    + "- reason：不超过 20 字的中文依据。\n"
                    + "- confidence：只能是 high / medium / low。拿不准就给 low，不要为了好看给 high。\n"
                    + "\n"
                    + "【例子】\n"
                    + "题目：{\"id\":\"1\",\"type\":\"single\",\"title\":\"本科阶段不少于()学时\","
                    + "\"options\":[\"A. 16\",\"B. 32\"]}\n"
                    + "正确输出：{\"answers\":[{\"id\":\"1\",\"answer\":\"B\","
                    + "\"confidence\":\"high\",\"reason\":\"本科要求32学时\"}]}\n"
                    + "题目：{\"id\":\"2\",\"type\":\"judge\",\"title\":\"劳动创造历史。\"}\n"
                    + "正确输出：{\"answers\":[{\"id\":\"2\",\"answer\":\"true\","
                    + "\"confidence\":\"medium\",\"reason\":\"纲要原文\"}]}\n"
                    + "错误输出（不要这样）：答案是B。\n"
                    + "错误输出（不要这样）：```json {\"answers\":[...]} ```\n"
                    + "错误输出（不要这样）：{\"answers\":[{\"id\":\"2\",\"answer\":\"对\"}]}";

    /**
     * 给一批题目要答案。
     *
     * <p>直接把 {@code suggestion / confidence / reason / manual / note} 写回传进来的对象，
     * 返回值就是这批题目本身，方便调用方链式处理。
     *
     * @param questions 题目（题干和选项必须已经过字体解码）
     * @param courseName 课程名，给模型一点上下文，可为 null
     * @return 同一个 list
     */
    public List<QuizQuestion> fill(List<QuizQuestion> questions, String courseName) {
        if (questions == null || questions.isEmpty()) {
            return questions;
        }

        Config cfg = Config.load();
        if (!cfg.usable()) {
            markAll(questions, "AI 未配置（缺 ai-config.json 里的 api_key）");
            return questions;
        }

        List<QuizQuestion> pending = new ArrayList<>(questions);
        // 第一轮
        ask(cfg, pending, courseName);
        // 校验不过的重试一次：模型对单题往往比批量更专注
        List<QuizQuestion> retry = new ArrayList<>();
        for (QuizQuestion q : questions) {
            if (q.isManual()) {
                retry.add(q);
            }
        }
        if (!retry.isEmpty()) {
            // 先看第一轮是怎么失败的，再决定要不要重试 —— 顺序不能反：
            // 下面清 note 的那一步会把失败原因抹掉，清完再判断就永远是「没问题」。
            //
            // 如果是 key 错、地址不通这类「请求本身发不出去」的失败，一题一问只会
            // 把同一个错误重复 N 遍，每次都等满超时。抓题跑在刷课线程上，
            // 一份 20 题的卷子能卡二十分钟，整门课的进度全停 —— 这种一律不重试。
            boolean transportBroken = false;
            for (QuizQuestion q : questions) {
                String n = q.getNote() == null ? "" : q.getNote();
                if (n.startsWith("AI 调用失败") || n.startsWith("AI 未配置")
                        || n.startsWith("构造请求失败")) {
                    transportBroken = true;
                    break;
                }
            }
            for (QuizQuestion q : retry) {
                q.setManual(false);
                q.setNote("");
            }
            if (transportBroken) {
                markAll(questions, "AI 不可用，先去看设置里的接口地址 / key / 模型");
            } else if (retry.size() == questions.size() && questions.size() > 1) {
                // 整批全挂通常意味着回复被截断、JSON 崩了（便宜的小模型很常见），
                // 这时候原样再批量问一遍大概率还是崩。改成一题一问：prompt 短、输出也短，
                // 截断的风险基本没了，代价只是多几个请求 —— 小模型按 token 算，很便宜。
                for (QuizQuestion q : retry) {
                    ask(cfg, new ArrayList<>(Collections.singletonList(q)), courseName);
                }
            } else {
                ask(cfg, retry, courseName);
            }
        }
        // 还是不行就保持 manual
        for (QuizQuestion q : questions) {
            if (q.isManual()) {
                q.setAnswer("");
                if (q.getNote() == null || q.getNote().isEmpty()) {
                    q.setNote("AI 未给出可用答案，请手动填写");
                }
            }
        }
        return questions;
    }

    private void ask(Config cfg, List<QuizQuestion> batch, String courseName) {
        String payload;
        try {
            payload = buildPayload(cfg, batch, courseName);
        } catch (Exception e) {
            markAll(batch, "构造请求失败: " + e.getMessage());
            return;
        }

        String content;
        try {
            content = post(cfg, payload);
        } catch (Exception e) {
            log.warn("AI 调用失败: {}", e.toString());
            markAll(batch, "AI 调用失败: " + brief(e.getMessage()));
            return;
        }

        JsonNode answers;
        try {
            answers = parseAnswers(content);
        } catch (Exception e) {
            markAll(batch, "AI 返回无法解析: " + brief(e.getMessage()));
            return;
        }

        for (QuizQuestion q : batch) {
            JsonNode hit = null;
            for (JsonNode n : answers) {
                if (q.getId().equals(n.path("id").asText())) {
                    hit = n;
                    break;
                }
            }
            if (hit == null) {
                q.setManual(true);
                q.setNote("AI 漏答此题");
                continue;
            }
            applyAnswer(q, hit);
        }
    }

    /** 把模型给的一条答案校验后写进题目。校验不过就标 manual。 */
    private void applyAnswer(QuizQuestion q, JsonNode node) {
        String raw = node.path("answer").asText("").trim();
        String conf = node.path("confidence").asText("").trim().toLowerCase();
        String reason = node.path("reason").asText("").trim();
        q.setConfidence(conf);
        q.setReason(brief(reason, 60));

        String checked = validate(q, raw);
        if (checked == null) {
            q.setManual(true);
            q.setSuggestion(raw);
            q.setNote("AI 答案不合法: " + brief(raw, 30));
            return;
        }
        q.setSuggestion(checked);
        q.setAnswer(checked);
        q.setManual(false);
        q.setNote("");
    }

    /**
     * 校验并规范化答案，返回 null 表示不可用。
     *
     * <p>这几条校验就是「不瞎填」的底线。模型经常在单选题里回一串解释、
     * 在判断题里回「正确」两个字，或者在多选题里只给一个字母 —— 这些都
     * 不能直接提交。
     */
    private String validate(QuizQuestion q, String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        switch (q.getAnswerType()) {
            case QuestionTypeConstant.SINGLE_CHOICE: {
                Set<String> letters = optionLetters(q);
                String a = firstLetter(raw);
                return (a != null && letters.contains(a)) ? a : null;
            }
            case QuestionTypeConstant.MULTIPLE_CHOICE: {
                Set<String> letters = optionLetters(q);
                Set<String> picked = new LinkedHashSet<>();
                for (char c : raw.toUpperCase().toCharArray()) {
                    String s = String.valueOf(c);
                    if (letters.contains(s)) {
                        picked.add(s);
                    }
                }
                if (picked.isEmpty()) {
                    return null;
                }
                // 多选题只给一个字母，多半是模型把它当单选了，宁可让人工看一眼
                if (picked.size() < 2) {
                    return null;
                }
                StringBuilder sb = new StringBuilder();
                picked.stream().sorted().forEach(sb::append);
                return sb.toString();
            }
            case QuestionTypeConstant.JUDGMENT: {
                String v = raw.trim().toLowerCase();
                if (v.startsWith("t") || v.equals("对") || v.equals("正确")
                        || v.equals("是") || v.equals("true")) {
                    return "true";
                }
                if (v.startsWith("f") || v.equals("错") || v.equals("错误")
                        || v.equals("否") || v.equals("false")) {
                    return "false";
                }
                // 有些卷子的判断题选项字母就是 true/false，模型可能回 A/B
                String a = firstLetter(raw);
                if (a != null) {
                    for (String opt : q.getOptions()) {
                        if (opt.startsWith(a + ".")) {
                            String val = opt.substring(2).trim();
                            if ("对".equals(val) || "正确".equals(val)) {
                                return "true";
                            }
                            if ("错".equals(val) || "错误".equals(val)) {
                                return "false";
                            }
                        }
                    }
                }
                return null;
            }
            default:
                // 填空 / 简答：只要不是空的就算能用
                return raw;
        }
    }

    /** 从选项里取字母集合，例如 ["A. 16","B. 32"] → {A,B}。 */
    private Set<String> optionLetters(QuizQuestion q) {
        Set<String> set = new LinkedHashSet<>();
        if (q.getOptions() == null) {
            return set;
        }
        for (String opt : q.getOptions()) {
            int dot = opt.indexOf('.');
            if (dot > 0) {
                set.add(opt.substring(0, dot).trim().toUpperCase());
            }
        }
        return set;
    }

    /** 取答案里的第一个字母，容忍 "A" / "A." / "选A" 这类写法。 */
    private String firstLetter(String raw) {
        Matcher m = Pattern.compile("[A-Za-z]").matcher(raw);
        return m.find() ? m.group().toUpperCase() : null;
    }

    private String buildPayload(Config cfg, List<QuizQuestion> batch, String courseName)
            throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", cfg.model);
        root.put("temperature", 0.2);
        root.put("stream", false);
        // 一定要显式给 max_tokens。不给的话走服务商默认值（有的只有 512），
        // 一份二十题的卷子必然被截断，模型吐出来的 JSON 是半截的，
        // 解析直接崩、整批作废。按题数估：每题留 150 token，再加 200 的余量。
        root.put("max_tokens", Math.max(600, Math.min(4096, 200 + batch.size() * 150)));

        ArrayNode msgs = root.putArray("messages");
        ObjectNode sys = msgs.addObject();
        sys.put("role", "system");
        sys.put("content", SYSTEM_PROMPT);

        StringBuilder user = new StringBuilder();
        if (courseName != null && !courseName.isEmpty()) {
            user.append("课程：").append(courseName).append("\n");
        }
        user.append("题目如下：\n");

        ArrayNode list = MAPPER.createArrayNode();
        for (QuizQuestion q : batch) {
            ObjectNode o = list.addObject();
            o.put("id", q.getId());
            o.put("type", q.getType());
            o.put("title", q.getTitle());
            if (q.getOptions() != null && !q.getOptions().isEmpty()) {
                ArrayNode opts = o.putArray("options");
                for (String s : q.getOptions()) {
                    opts.add(s);
                }
            }
        }
        user.append(list.toString());

        ObjectNode u = msgs.addObject();
        u.put("role", "user");
        u.put("content", user.toString());

        return MAPPER.writeValueAsString(root);
    }

    private String post(Config cfg, String payload) throws Exception {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(cfg.timeoutSec, TimeUnit.SECONDS)
                .readTimeout(cfg.timeoutSec, TimeUnit.SECONDS)
                .writeTimeout(cfg.timeoutSec, TimeUnit.SECONDS)
                .build();

        Request request = new Request.Builder()
                .url(cfg.endpoint())
                .header("Authorization", "Bearer " + cfg.apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(payload, JSON))
                .build();

        try (Response resp = client.newCall(request).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            if (!resp.isSuccessful()) {
                throw new IllegalStateException("HTTP " + resp.code() + " " + brief(body, 200));
            }
            JsonNode root = MAPPER.readTree(body);
            JsonNode choice = root.path("choices").path(0);
            JsonNode content = choice.path("message").path("content");
            if (content.isMissingNode() || content.asText().isEmpty()) {
                throw new IllegalStateException("响应里没有 content: " + brief(body, 200));
            }
            // 被截断时 content 是半截 JSON，后面的解析只会报一句莫名其妙的
            // "Unexpected close marker '}'"，不如在这里就把话说清楚。
            if ("length".equals(choice.path("finish_reason").asText())) {
                throw new IllegalStateException("模型回复被截断（max_tokens 不够）");
            }
            JsonNode usage = root.path("usage");
            if (!usage.isMissingNode()) {
                // 便宜模型按 token 计费，留一行账在日志里，用户能自己算花了多少
                log.info("AI 作答用量: prompt={} completion={} total={}",
                        usage.path("prompt_tokens").asInt(),
                        usage.path("completion_tokens").asInt(),
                        usage.path("total_tokens").asInt());
            }
            return content.asText();
        }
    }

    /** 从模型回复里抠出 answers 数组。容忍 ```json 包裹和前后废话。 */
    private JsonNode parseAnswers(String content) throws Exception {
        String text = content.trim();
        // 去掉 markdown 代码块
        Matcher fence = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```").matcher(text);
        if (fence.find()) {
            text = fence.group(1).trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("回复里找不到 JSON 对象");
        }
        JsonNode root = MAPPER.readTree(text.substring(start, end + 1));
        JsonNode answers = root.path("answers");
        if (!answers.isArray()) {
            // 有的模型会直接返回裸数组
            if (root.isArray()) {
                return root;
            }
            throw new IllegalStateException("JSON 里没有 answers 数组");
        }
        return answers;
    }

    private void markAll(List<QuizQuestion> list, String note) {
        for (QuizQuestion q : list) {
            q.setManual(true);
            q.setNote(note);
        }
    }

    private static String brief(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static String brief(String s) {
        return brief(s, 120);
    }

    /**
     * AI 配置。放 {@code ai-config.json}（后端工作目录，也就是安装目录的 app/ 下），
     * 每次调用前重读，改完不用重启后端。
     */
    public static class Config {
        public String baseUrl = "https://api.deepseek.com";
        public String apiKey = "";
        public String model = "deepseek-chat";
        public int timeoutSec = 60;

        /**
         * 答题策略，取值见 {@link com.tihai.constant.QuizMode}。
         *
         * <p>默认 {@code confirm}：抓题 + AI 出答案 + 落库，全部挂「待确认」等人点。
         * 想无人值守就把模式调成 {@code auto_*}，只有**每道题都过了校验、且置信度都不低于
         * 门槛**时才自动交；只要有一道没把握，整份照旧挂回待确认。
         * 提交不可逆，所以宁可漏交也不要交错。
         */
        public String answerMode = com.tihai.constant.QuizMode.CONFIRM;

        public boolean usable() {
            return apiKey != null && !apiKey.trim().isEmpty();
        }

        public String endpoint() {
            String base = baseUrl == null ? "" : baseUrl.trim();
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            // 允许用户直接填到 /v1 或填完整 URL
            if (base.endsWith("/chat/completions")) {
                return base;
            }
            if (!base.endsWith("/v1")) {
                base = base + "/v1";
            }
            return base + "/chat/completions";
        }

        public static Config load() {
            Config cfg = new Config();
            File f = new File(CONFIG_FILE);
            if (!f.isFile()) {
                return cfg;
            }
            try {
                JsonNode n = MAPPER.readTree(f);
                cfg.baseUrl = n.path("base_url").asText(cfg.baseUrl);
                cfg.apiKey = n.path("api_key").asText("");
                cfg.model = n.path("model").asText(cfg.model);
                cfg.timeoutSec = n.path("timeout_sec").asInt(cfg.timeoutSec);
                String mode = n.path("answer_mode").asText("").trim().toLowerCase();
                if (com.tihai.constant.QuizMode.valid(mode)) {
                    cfg.answerMode = mode;
                }
            } catch (Exception e) {
                log.warn("读 {} 失败: {}", CONFIG_FILE, e.toString());
            }
            return cfg;
        }
    }
}
