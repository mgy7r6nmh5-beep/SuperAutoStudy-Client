package com.tihai.utils;


import com.tihai.common.*;
import com.tihai.dubbo.pojo.course.Course;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Copyright : DuanInnovator
 * @Description : 课程解码
 * @Author : DuanInnovator
 * @CreateTime : 2025/2/26
 * @Link : <a href="https://github.com/DuanInnovator/SuperAutotudy">...</a>
 **/
public class Decode {
    /**
     * 解码课程列表
     */
    public static List<Course> decodeCourseList(String html) {
//        LoggerUtil.logger.trace("开始解码课程列表...");
        List<Course> courseList = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        Elements rawCourses = doc.select("div.course");
        Pattern cpiPattern = Pattern.compile("cpi=(.*?)&");

        for (Element course : rawCourses) {
            // 排除未开放的课程：含有 a.not-open-tip 或 div.not-open-tip 的忽略
            if (!course.select("a.not-open-tip").isEmpty() || !course.select("div.not-open-tip").isEmpty()) {
                continue;
            }
            Course courseDetail = new Course();
            courseDetail.setId(course.attr("id"));
            courseDetail.setInfo(course.attr("info"));
            courseDetail.setRoleId(course.attr("roleid"));
            courseDetail.setClazzId(course.select("input.clazzId").attr("value"));
            courseDetail.setCourseId(course.select("input.courseId").attr("value"));

            // 从 a 标签的 href 中提取 cpi
            String href = course.select("a").attr("href");
            Matcher m = cpiPattern.matcher(href);
            if (m.find()) {
                courseDetail.setCpi(m.group(1));
            } else {
                courseDetail.setCpi("");
            }
            courseDetail.setTitle(course.select("span.course-name").attr("title"));
            Element descEl = course.select("p.margint10").first();
            courseDetail.setDesc(descEl != null ? descEl.attr("title") : "");
            courseDetail.setTeacher(course.select("p.color3").attr("title"));
            courseList.add(courseDetail);
        }
        return courseList;
    }

    /**
     * 解码二级课程列表（文件夹）
     */
    public static List<CourseFolder> decodeCourseFolder(String html) {
//        LoggerUtil.logger.trace("开始解码二级课程列表...");
        List<CourseFolder> folderList = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        Elements rawFolders = doc.select("ul.file-list > li");
        for (Element folder : rawFolders) {
            String fileId = folder.attr("fileid");
            if (fileId != null && !fileId.isEmpty()) {
                CourseFolder courseFolder = new CourseFolder();
                courseFolder.setId(fileId);
                courseFolder.setRename(folder.select("input.rename-input").attr("value"));
                folderList.add(courseFolder);
            }
        }
        return folderList;
    }

    /**
     * 解码章节列表（课程点）
     */
    public static CoursePoint decodeCoursePoint(String html) {
//        LoggerUtil.logger.trace("开始解码章节列表...");
        CoursePoint coursePoint = new CoursePoint();
        coursePoint.setHasLocked(false);
        List<ChapterPoint> points = new ArrayList<>();

        Document doc = Jsoup.parse(html);
        Elements chapterUnits = doc.select("div.chapter_unit");
        Pattern idPattern = Pattern.compile("^cur(\\d{1,20})$");

        for (Element chapter : chapterUnits) {
            Elements rawPoints = chapter.select("li");
            for (Element li : rawPoints) {
                Element pointDiv = li.select("div").first();
                if (pointDiv == null || !pointDiv.hasAttr("id")) {
                    continue;
                }
                Matcher m = idPattern.matcher(pointDiv.attr("id"));
                if (!m.find()) {
                    continue;
                }
                ChapterPoint pointDetail = new ChapterPoint();
                pointDetail.setId(m.group(1));
                String title = pointDiv.select("a.clicktitle").text().replace("\n", "").trim();
                pointDetail.setTitle(title);
                // 默认为1
                pointDetail.setJobCount(1);
                Element knowledgeJobCount = pointDiv.select("input.knowledgeJobCount").first();
                if (knowledgeJobCount != null) {
                    pointDetail.setJobCount(Integer.parseInt(knowledgeJobCount.attr("value")));
                } else {
                    Element tipEl = pointDiv.select("span.bntHoverTips").first();
                    if (tipEl != null && tipEl.text().contains("解锁")) {
                        coursePoint.setHasLocked(true);
                    }
                }
                points.add(pointDetail);
            }
        }
        coursePoint.setPoints(points);
        return coursePoint;
    }

    /**
     * 解码任务点列表
     * 返回 Pair(任务点列表, 任务附加信息)
     */
    public static Pair<List<Job>, JobInfo> decodeCourseCard(String html) {
        return decodeCourseCard(html, false);
    }

    /**
     * 同 {@link #decodeCourseCard(String)}，但可选「是否保留 isPassed=true 的任务点」。
     *
     * <p>刷课主流程用 {@code includePassed=false}：已通过的任务点没必要再刷，
     * 返回的列表里只有「还没完成的」。
     * 客户端只读接口用 {@code includePassed=true}：界面上要能看到每一条任务点
     * （含已完成的）以及它的 isPassed / playTime。
     */
    public static Pair<List<Job>, JobInfo> decodeCourseCard(String html, boolean includePassed) {
//        LoggerUtil.logger.trace("开始解码任务点列表...");
        JobInfo jobInfo = new JobInfo();
        List<Job> jobList = new ArrayList<>();

        if (html.contains("章节未开放")) {
            jobInfo.setNotOpen(true);
            return new Pair<>(jobList, jobInfo);
        }

        // 去除空格后匹配 mArg={...};
        String cleaned = html.replace(" ", "");
        Pattern pattern = Pattern.compile("mArg=\\{(.*?)\\};");
        Matcher matcher = pattern.matcher(cleaned);
        String temp = "";
        if (matcher.find()) {
            temp = matcher.group(1);
        } else {
            return new Pair<>(jobList, new JobInfo());
        }
        // 构造 JSON 字符串，并解析
        JSONObject jsonObj = new JSONObject("{" + temp + "}");
        if (jsonObj != null) {
            JSONObject defaults = jsonObj.getJSONObject("defaults");
            jobInfo.setKtoken(defaults.optString("ktoken"));
            jobInfo.setMtEnc(defaults.optString("mtEnc"));
            jobInfo.setReportTimeInterval(defaults.optInt("reportTimeInterval"));
            jobInfo.setDefenc(defaults.optString("defenc"));
            jobInfo.setCardid(defaults.optString("cardid"));
            jobInfo.setCpi(defaults.optString("cpi"));
            jobInfo.setQnenc(defaults.optString("qnenc"));
            jobInfo.setKnowledgeid(defaults.optString("knowledgeid"));

            JSONArray attachments = jsonObj.getJSONArray("attachments");
            for (int i = 0; i < attachments.length(); i++) {
                JSONObject card = attachments.getJSONObject(i);
                boolean cardPassed = card.optBoolean("isPassed", false);
                long cardPlayTime = card.optLong("playTime", 0L);
                // 已通过的任务：刷课主流程直接跳过（includePassed=false，没必要重刷）；
                // 只读接口（includePassed=true）要保留，界面上才能显示「这条已经刷完了」。
                if (cardPassed && !includePassed) {
                    continue;
                }
                String cardType = card.optString("type");
                JSONObject property = card.optJSONObject("property");

                // 阅读任务：没有 job 字段，用 property.read 表示「已读」
                if ("read".equals(cardType)) {
                    if (property != null && property.optBoolean("read", false)) {
                        continue;   // 已阅读，跳过
                    }
                    Job job = new Job();
                    if (property != null) {
                        job.setTitle(property.optString("title"));
                        job.setId(property.optString("id"));
                    }
                    job.setType("read");
                    job.setJobId(card.optString("jobid"));
                    job.setJToken(card.optString("jtoken"));
                    job.setMid(card.optString("mid"));
                    job.setOtherInfo(card.optString("otherInfo"));
                    job.setEnc(card.optString("enc"));
                    job.setAid(card.optString("aid"));
                    job.setPassed(cardPassed);
                    job.setPlayTime(cardPlayTime);
                    jobList.add(job);
                    continue;
                }

                // ★★ FIX(2026-10-11)「文档任务点上报一律『无效的请求参数』」★★
                //
                // 可上报的 jobid 只有两处：attachment 顶层的 jobid，或 property.jobid。
                // **property._jobid 不是可上报的 jobid**（下划线前缀是超星内部字段）。
                // 实测对照（同一账号、同一分钟）：
                //   · 线性代数「1.1二阶与三阶行列式.pdf」
                //       顶层 jobid = property.jobid = 1702118097593622
                //       → /ananas/job/document 返回 {"msg":"考核点已经完成","status":true}
                //   · 高等数学先修课「专题二极限（3）.pdf」
                //       只有 property._jobid = 1786088442341716，顶层无 jobid
                //       → 顶层 / 加 enc / 加 mid / 去掉 knowledgeid 四种拼法全部返回
                //         {"msg":"无效的请求参数","status":false}
                //
                // 结论：拿不到 jobid 的附件只是章节里的参考资料，**不是任务点**，直接跳过。
                // 上一版曾用 property._jobid 兜底，结果把这类附件也当任务点上报，
                // 上报必然失败 → chapterOk=false → 整门课永远「未刷完」。这才是死循环来源。
                String topJobId = card.optString("jobid", "");
                String propJobId = (property == null) ? "" : property.optString("jobid", "");
                String jobId = !topJobId.isEmpty() ? topJobId : propJobId;
                if (jobId.isEmpty()) {
                    continue;
                }

                // 视频任务
                if ("video".equals(cardType)) {
                    Job job = new Job();
                    job.setType("video");
                    job.setJobId(jobId);
                    if (property != null) {
                        job.setName(property.optString("name"));
                        // 顺手补上 title：上层异常信息里用的是 getTitle()，
                        // 原来视频任务不设它，日志里一直是「任务ID=null」。
                        job.setTitle(property.optString("name"));
                    }
                    job.setOtherInfo(card.optString("otherInfo"));
                    if (!card.has("mid")) {
//                        LoggerUtil.logger.error("出现转码失败视频，已跳过...");
                        continue;
                    }
                    job.setMid(card.optString("mid"));
                    job.setObjectId(card.optString("objectId"));
                    job.setAid(card.optString("aid"));
                    // 新版播放器上报 URL 的尾部参数，缺了服务端不计分（见 CourseUtil.videoProgressLog）
                    job.setAttDuration(card.optInt("attDuration", 0));
                    job.setAttDurationEnc(card.optString("attDurationEnc"));
                    job.setVideoFaceCaptureEnc(card.optString("videoFaceCaptureEnc"));
                    job.setPassed(cardPassed);
                    job.setPlayTime(cardPlayTime);
                    jobList.add(job);
                    continue;
                }
                // 文档任务
                if ("document".equals(cardType)) {
                    Job job = new Job();
                    job.setType("document");
                    job.setJobId(jobId);
                    if (property != null) {
                        job.setObjectId(property.optString("objectid"));
                        // 补 title，否则异常信息里永远是「任务ID=null」
                        job.setTitle(property.optString("name", property.optString("title")));
                        job.setName(property.optString("name", property.optString("title")));
                    }
                    job.setOtherInfo(card.optString("otherInfo"));
                    job.setJToken(card.optString("jtoken"));
                    job.setMid(card.optString("mid"));
                    job.setEnc(card.optString("enc"));
                    job.setAid(card.optString("aid"));
                    job.setPassed(cardPassed);
                    job.setPlayTime(cardPlayTime);
                    jobList.add(job);
                    continue;
                }
                // ★★ 超链接任务点（type=hyperlink）★★
                // 实测（高等数学先修课 6.1 微积分前导 / 6.2 高等数学先修课）：
                //   {"type":"hyperlink","job":true,"jobid":"1787536685282304",
                //    "otherInfo":"nodeId_1210500040-cpi_562459847",
                //    "property":{"module":"hyperlink","textValue":"微积分前导","jobid":"1787536685282304",...}}
                //   → POST /ananas/job/hyperlink 返回 {"msg":"添加考核点成功","status":true}
                // 老实现没有这个分支 → 落到 switch 的 default → 被当成「已完成」，
                // 于是超星课程页一直挂着这几个待完成任务点，课永远刷不完。
                // 规律：上报端点是 /ananas/job/{type}，与 attachment 的 type 字段同名。
                if ("hyperlink".equals(cardType)) {
                    Job job = new Job();
                    job.setType("hyperlink");
                    job.setJobId(jobId);
                    if (property != null) {
                        job.setTitle(property.optString("textValue",
                                property.optString("title", property.optString("name"))));
                        job.setName(job.getTitle());
                    }
                    job.setOtherInfo(card.optString("otherInfo"));
                    job.setJToken(card.optString("jtoken"));
                    job.setMid(card.optString("mid"));
                    job.setEnc(card.optString("enc"));
                    job.setAid(card.optString("aid"));
                    job.setPassed(cardPassed);
                    job.setPlayTime(cardPlayTime);
                    jobList.add(job);
                    continue;
                }
                // 作业任务（workid 类型）
                if ("workid".equals(cardType)) {
                    Job job = new Job();
                    job.setType("workid");
                    job.setJobId(jobId);
                    job.setOtherInfo(card.optString("otherInfo"));
                    job.setMid(card.optString("mid"));
                    job.setEnc(card.optString("enc"));
                    job.setAid(card.optString("aid"));
                    if (property != null) {
                        job.setTitle(property.optString("name", property.optString("title")));
                        job.setName(property.optString("name", property.optString("title")));
                    }

                    job.setPassed(cardPassed);
                    job.setPlayTime(cardPlayTime);
                    jobList.add(job);
                    continue;
                }
                // 调查问卷（vote）等任务忽略
                if ("vote".equals(cardType)) {
                    continue;
                }
            }
        }
        return new Pair<>(jobList, jobInfo);
    }

    /**
     * 解码课程页的「真实完成度」——以超星自己写在页面上的数字为准。
     *
     * <p>返回 key：total(章节总数) / done(已完成章节) / pending(待完成任务点合计) / noTask(该层无任务点)
     *
     * <p>课程页每个章节 li 有三种形态（2026-10 在 291 章的课程上实测）：
     * <pre>
     *   1) &lt;div class="catalog_state icon_yiwanc ..."&gt;&lt;span class="bntHoverTips"&gt;已完成&lt;/span&gt;   已完成
     *   2) &lt;input type="hidden" value="N" class="knowledgeJobCount"/&gt; ... N个待完成任务点   有 N 个未完成
     *   3) &lt;div class="catalog_jindu ..."&gt;&lt;span class="catalog_points_yi"&gt;0&lt;/span&gt;&lt;/div&gt;     该层无任务点
     * </pre>
     * ⚠️ {@code knowledgeJobCount} 的 value 是「<b>未完成</b>任务点数」，不是任务点总数。
     * 老代码在 decodeCoursePoint 里把它当 jobCount 的默认值，既没用上又误导人；
     * 这里才是它的正确用途 —— 判断课程到底刷完没有。
     */
    public static Map<String, Integer> decodeRealProgress(String html) {
        Map<String, Integer> result = new HashMap<>();
        int total = 0, done = 0, pending = 0, noTask = 0;
        Document doc = Jsoup.parse(html);
        Pattern idPattern = Pattern.compile("^cur(\\d{1,20})$");
        for (Element chapter : doc.select("div.chapter_unit")) {
            for (Element li : chapter.select("li")) {
                Element pointDiv = li.select("div").first();
                if (pointDiv == null || !pointDiv.hasAttr("id")) {
                    continue;
                }
                if (!idPattern.matcher(pointDiv.attr("id")).find()) {
                    continue;
                }
                total++;
                int p = 0;
                Element kjc = li.select("input.knowledgeJobCount").first();
                if (kjc != null) {
                    try {
                        p = Integer.parseInt(kjc.attr("value").trim());
                    } catch (NumberFormatException ignore) {
                        p = 0;
                    }
                }
                boolean yiWanCheng = li.outerHtml().contains("icon_yiwanc");
                if (p > 0) {
                    pending += p;
                } else if (yiWanCheng) {
                    done++;
                } else {
                    noTask++;
                }
            }
        }
        result.put("total", total);
        result.put("done", done);
        result.put("pending", pending);
        result.put("noTask", noTask);
        return result;
    }

    /**
     * 解析课程页，返回「章节 knowledgeId → 该章未完成任务点数」。
     *
     * <p>只读接口（ApiController）用它来<b>跳过已完成的章节</b>：
     * 一门课动辄上百章，每章都要发 1~2 次超星请求，全量扫描要几分钟；
     * 而用户真正关心的是「还有哪些没刷完」，所以只对未完成的章节去拉任务点明细。
     *
     * <p>value &gt; 0 = 该章有 N 个待完成任务点；
     * value == 0 = 该章已完成（icon_yiwanc）或本来就没有任务点，两种情况都不必再拉。
     */
    public static Map<String, Integer> decodeChapterPending(String html) {
        Map<String, Integer> out = new HashMap<>();
        Document doc = Jsoup.parse(html);
        Pattern idPattern = Pattern.compile("^cur(\\d{1,20})$");
        for (Element chapter : doc.select("div.chapter_unit")) {
            for (Element li : chapter.select("li")) {
                Element pointDiv = li.select("div").first();
                if (pointDiv == null || !pointDiv.hasAttr("id")) {
                    continue;
                }
                Matcher m = idPattern.matcher(pointDiv.attr("id"));
                if (!m.find()) {
                    continue;
                }
                int p = 0;
                Element kjc = li.select("input.knowledgeJobCount").first();
                if (kjc != null) {
                    try {
                        p = Integer.parseInt(kjc.attr("value").trim());
                    } catch (NumberFormatException ignore) {
                        p = 0;
                    }
                }
                out.put(m.group(1), p);
            }
        }
        return out;
    }
}
