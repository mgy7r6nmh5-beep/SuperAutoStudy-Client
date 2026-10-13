package com.tihai.utils;

import com.alibaba.nacos.api.exception.NacosException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tihai.common.*;
import com.tihai.common.GlobalConst;
import com.tihai.domain.chaoxing.SuperStarLog;
import com.tihai.domain.chaoxing.WkUser;
import com.tihai.dubbo.pojo.course.Course;
import com.tihai.properties.StudyProperties;
import com.tihai.service.superstar.SuperStarCookieService;
import com.tihai.service.superstar.SuperStarLogService;
import okhttp3.*;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @Copyright : DuanInnovator
 * @Description : 课程工具
 * @Author : DuanInnovator
 * @CreateTime : 2025/3/1
 * @Link : <a href="https://github.com/DuanInnovator/SuperAutotudy">...</a>
 **/
@Component
public class CourseUtil {


    public Map<String, String> cookies;
    public List<Cookie> cookieList = new ArrayList<>();

    private String account;

    @Autowired
    private SuperStarLogService superStarLogService;

    @Autowired
    private SuperStarCookieService cookieService;

    @Autowired
    private ServerInfoUtil serverInfoUtil;

    @Autowired
    private StudyProperties studyProperties;

    @Autowired
    private Query query;

    public void setAccount(String account) {
        this.account = account;
    }


    public void setCookies(String cookies) {
        this.cookies = convertCookieStringToMap(cookies);
        cookieList = convertCookieStringToList(cookies);
    }

    /**
     * 复制出一个<b>会话独立</b>的 CourseUtil，供并发只读接口（ApiController）使用。
     *
     * <p>背景：{@link #cookies} / {@link #account} 是本 bean 的<b>可变实例字段</b>，
     * 而刷课主流程（{@code SuperStarTaskServiceImpl}）会在同一个单例上
     * {@code setCookies()} 后连续调用若干方法。若只读接口直接复用这个单例，
     * 两边会互相覆盖 cookie —— 轻则拉错账号的数据，重则刷课打到别人账号上。
     *
     * <p>分叉出的实例：cookie / account 是副本，Spring 依赖（cookieService 等）
     * 仍共享引用（它们本身无会话状态）。只读路径只用得到 cookieService，
     * 复制全部依赖只是为了避免以后改动踩坑。
     */
    public CourseUtil forkSession() {
        CourseUtil c = new CourseUtil();
        c.superStarLogService = this.superStarLogService;
        c.cookieService = this.cookieService;
        c.serverInfoUtil = this.serverInfoUtil;
        c.studyProperties = this.studyProperties;
        c.query = this.query;
        c.cookies = (this.cookies == null) ? null : new LinkedHashMap<>(this.cookies);
        c.cookieList = new ArrayList<>(this.cookieList);
        c.account = this.account;
        return c;
    }

    /**
     * 将 Cookie 字符串转换为 Cookie 列表
     *
     * @param cookieStr Cookie 字符串
     * @return Cookie 列表
     */
    public static List<Cookie> convertCookieStringToList(String cookieStr) {
        List<Cookie> cookies = new ArrayList<>();

        if (cookieStr == null || cookieStr.isEmpty()) {
            return cookies;
        }
        String[] cookieArray = cookieStr.split(", ");
        for (String cookieEntry : cookieArray) {
            String[] parts = cookieEntry.split(";");
            if (parts.length > 0) {
                String[] keyValue = parts[0].split("=", 2);
                if (keyValue.length == 2) {
                    Cookie cookie = new Cookie.Builder()
                            .name(keyValue[0].trim())
                            .value(keyValue[1].trim())
                            .domain("chaoxing.com")
                            .path("/")
                            .build();
                    cookies.add(cookie);
                }
            }
        }
        return cookies;
    }

    public static Map<String, String> convertCookieStringToMap(String cookieStr) {
        // 去掉前后的方括号
        if (cookieStr.startsWith("[") && cookieStr.endsWith("]")) {
            cookieStr = cookieStr.substring(1, cookieStr.length() - 1);
        }
        Map<String, String> cookieMap = new LinkedHashMap<>();
        String[] cookies = cookieStr.split(", ");


        for (String cookie : cookies) {
            String[] attributes = cookie.split(";");
            String[] keyValue = attributes[0].split("=", 2); // 只取第一个键值对，防止 value 中带 "=" 导致错误

            if (keyValue.length == 2) {
                String key = keyValue[0].trim();
                String value = keyValue[1].trim();

                // 如果 key 已存在，合并值，确保所有相同 key 的值都能保留
                if (cookieMap.containsKey(key)) {
                    cookieMap.put(key, cookieMap.get(key) + "," + value);
                } else {
                    cookieMap.put(key, value);
                }
            }
        }
        return cookieMap;
    }

    public String getValue(String key) {
        return cookies.get(key);
    }


    /**
     * 获取随机秒数
     *
     * @return 随机秒数
     */
    public static int getRandomSeconds() {
        return ThreadLocalRandom.current().nextInt(100, 120);
    }

    /**
     * 获取当前时间戳
     *
     * @return 时间戳字符串
     */
    public static String getTimestamp() {
        return String.valueOf(System.currentTimeMillis());
    }


    /**
     * 初始化 OkHttpClient，并根据 isVideo / isAudio 设置请求头
     */

    public OkHttpClient initSession(boolean isVideo, boolean isAudio) {
        // 创建信任所有证书的 TrustManager
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
        };

        // 创建 SSLContext 并使用 TrustManager 初始化
        SSLContext sslContext;
        try {
            sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // 创建 OkHttpClient.Builder
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .retryOnConnectionFailure(true); // 启用重试机制
//                .sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0]) // 禁用 SSL 验证
//                .hostnameVerifier((hostname, session) -> true); // 禁用主机名验证

        // 配置 CookieJar
        builder.cookieJar(new CookieJar() {
            private final Map<String, List<Cookie>> cookieCache = new ConcurrentHashMap<>();

            @Override
            public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                String domain = url.host();

                // 获取现有Cookie（从内存或数据库）
                List<Cookie> existingCookies = cookieCache.computeIfAbsent(domain, k -> {
                    String cookieStr = cookieService.getWkUserCookie(account);
                    return parseCookieString(cookieStr);
                });

                // 检测是否有变更
                boolean changed = false;
                for (Cookie newCookie : cookies) {
                    boolean exists = existingCookies.stream()
                            .anyMatch(c -> c.name().equals(newCookie.name()) &&
                                    c.value().equals(newCookie.value()));

                    if (!exists) {
                        existingCookies.removeIf(c -> c.name().equals(newCookie.name()));
                        existingCookies.add(newCookie);
                        changed = true;
                    }
                }

                // 如果有变更则更新数据库
                if (changed) {
                    String formattedCookies = formatCookies(existingCookies);
                    WkUser user = new WkUser();
                    user.setAccount(account);
                    user.setCookies(formattedCookies);
                    cookieService.updateWkUserCookies(user);
                }
            }

            // 格式化Cookie为[ ]包裹的字符串
            // 格式化Cookie为字符串（去除[ ]）
            private String formatCookies(List<Cookie> cookies) {
                if (cookies == null || cookies.isEmpty()) {
                    return "";  // 返回空字符串
                }

                return cookies.stream()
                        .map(c -> {
                            StringBuilder sb = new StringBuilder();
                            sb.append(c.name()).append("=").append(c.value());

                            if (c.expiresAt() != Long.MAX_VALUE) {
                                sb.append("; expires=").append(formatDate(c.expiresAt()));
                            }
                            if (c.domain() != null) {
                                sb.append("; domain=").append(c.domain());
                            }
                            if (c.path() != null) {
                                sb.append("; path=").append(c.path());
                            }
                            if (c.secure()) {
                                sb.append("; secure");
                            }
                            if (c.httpOnly()) {
                                sb.append("; httponly");
                            }
                            return sb.toString();
                        })
                        .collect(Collectors.joining(", "));  // 直接拼接字符串，不加方括号
            }

            // 日期格式化（RFC 1123格式）
            private String formatDate(long timestamp) {
                SimpleDateFormat sdf = new SimpleDateFormat(
                        "EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
                return sdf.format(new Date(timestamp));
            }

            // 从字符串解析Cookie列表
            // 从字符串解析Cookie列表
            private List<Cookie> parseCookieString(String cookieStr) {
                if (cookieStr == null || cookieStr.trim().isEmpty()) {
                    return new ArrayList<>();
                }

                // 处理空内容
                String content = cookieStr.trim();
                if (content.isEmpty()) {
                    return new ArrayList<>();
                }

                // 处理包含逗号的cookie值
                return Arrays.stream(content.split(",(?=\\s*[^=]+=)"))
                        .map(String::trim)
                        .map(this::parseSingleCookie)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
            }


            private Cookie parseSingleCookie(String cookieStr) {
                try {
                    String[] parts = cookieStr.split(";\\s*");
                    String[] nameValue = parts[0].split("=", 2);
                    if (nameValue.length != 2) return null;

                    Cookie.Builder builder = new Cookie.Builder()
                            .name(nameValue[0].trim())
                            .value(nameValue[1].trim());

                    for (int i = 1; i < parts.length; i++) {
                        String attr = parts[i].trim().toLowerCase();
                        if (attr.startsWith("domain=")) {
                            builder.domain(attr.substring(7));
                        } else if (attr.startsWith("path=")) {
                            builder.path(attr.substring(5));
                        } else if (attr.startsWith("expires=")) {
                            try {
                                SimpleDateFormat sdf = new SimpleDateFormat(
                                        "EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
                                Date date = sdf.parse(attr.substring(8));
                                builder.expiresAt(date.getTime());
                            } catch (ParseException e) {
                                // 忽略日期解析错误
                            }
                        } else if (attr.equals("secure")) {
                            builder.secure();
                        } else if (attr.equals("httponly")) {
                            builder.httpOnly();
                        }
                    }
                    return builder.build();
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            public List<Cookie> loadForRequest(HttpUrl url) {

                try {
                    // 1. 检查基础条件
                    if (url == null || account == null || cookieService == null) {
                        System.out.println("获取Cookie异常: 参数错误 -> " +
                                "url=" + url + ", " +
                                "account=" + account + ", " +
                                "cookieService=" + cookieService);
                        return Collections.emptyList();
                    }

                    // 2. 内存缓存检查
                    String domain = url.host();
                    if (cookieCache.containsKey(domain)) {
                        return new ArrayList<>(cookieCache.get(domain)); // 返回副本避免并发修改
                    }

                    // 3. 安全获取Cookie
                    String wkUserCookie = Optional.ofNullable(cookieService)
                            .map(service -> service.getWkUserCookie(account))
                            .orElse("");

                    if (!wkUserCookie.isEmpty()) {
                        List<Cookie> cookies = convertCookieStringToList(wkUserCookie);
                        cookieCache.put(domain, new CopyOnWriteArrayList<>(cookies));
                        return cookies;
                    }
                } catch (Exception e) {
                    System.out.println("获取Cookie异常: " + e.getMessage());
                }
                return Collections.emptyList();
            }

        });

        // 使用拦截器添加统一请求头
        builder.addInterceptor(chain -> {
            Request original = chain.request();
            Request.Builder reqBuilder = original.newBuilder();
            Map<String, String> headers;
            if (isVideo) {
                headers = GlobalConst.VIDEO_HEADERS;
            } else if (isAudio) {
                headers = GlobalConst.AUDIO_HEADERS;
            } else {
                headers = GlobalConst.HEADERS;
            }
            headers.forEach(reqBuilder::header);
            Request req = reqBuilder.build();
            return chain.proceed(req);
        });

        return builder.build();
    }


    /**
     * 获取课程列表请求头
     *
     * @return 请求头
     */
    private Map<String, String> getCourseListHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Host", "mooc2-ans.chaoxing.com");
        headers.put("sec-ch-ua-platform", "\"Windows\"");
        headers.put("X-Requested-With", "XMLHttpRequest");
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ...");
        headers.put("Accept", "text/html, */*; q=0.01");
        headers.put("sec-ch-ua", "\"Microsoft Edge\";v=\"129\", \"Not=A?Brand\";v=\"8\", \"Chromium\";v=\"129\"");
        headers.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("Origin", "https://mooc2-ans.chaoxing.com");
        headers.put("Sec-Fetch-Site", "same-origin");
        headers.put("Sec-Fetch-Mode", "cors");
        headers.put("Sec-Fetch-Dest", "empty");
        headers.put("Referer", "https://mooc2-ans.chaoxing.com/mooc2-ans/visit/interaction?moocDomain=https://mooc1-1.chaoxing.com/mooc-ans");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6,ja;q=0.5");
        return headers;
    }


    /**
     * 获取课程列表
     */
    public List<Course> getCourseList() throws IOException {
        OkHttpClient client = initSession(false, false);
        String url = "https://mooc2-ans.chaoxing.com/mooc2-ans/visit/courselistdata";
        FormBody formBody = new FormBody.Builder()
                .add("courseType", "1")
                .add("courseFolderId", "0")
                .add("Query", "")
                .add("superstarClass", "0")
                .build();
        // 此处定义专用的请求头
        Request request = new Request.Builder()
                .url(url)
                .post(formBody)
                .headers(Headers.of(getCourseListHeaders()))
                .build();


        Response response = client.newCall(request).execute();

        String respText = response.body().string();
        List<Course> courseList = Decode.decodeCourseList(respText);

        // 处理课程文件夹
        String interactionUrl = "https://mooc2-ans.chaoxing.com/mooc2-ans/visit/interaction";
        Request interRequest = new Request.Builder().url(interactionUrl).build();
        Response interResponse = client.newCall(interRequest).execute();
        String interText = interResponse.body().string();
        List<CourseFolder> courseFolder = Decode.decodeCourseFolder(interText);
        for (CourseFolder folder : courseFolder) {
            FormBody folderForm = new FormBody.Builder()
                    .add("courseType", "1")
                    .add("courseFolderId", folder.getId())
                    .add("Query", "")
                    .add("superstarClass", "0")
                    .build();
            Request folderRequest = new Request.Builder()
                    .url(url)
                    .post(folderForm)
                    .build();
            Response folderResponse = client.newCall(folderRequest).execute();
            String folderText = folderResponse.body().string();

            courseList.addAll(Decode.decodeCourseList(folderText));
        }
        return courseList;
    }


    /**
     * 获取课程章节（course point）
     */
    public List<CoursePoint> getCoursePoint(String courseId, String clazzId, String cpi) throws IOException {
        OkHttpClient client = initSession(false, false);
        String url = String.format("https://mooc2-ans.chaoxing.com/mooc2-ans/mycourse/studentcourse?courseid=%s&clazzid=%s&cpi=%s&ut=s",
                courseId, clazzId, cpi);
        Request request = new Request.Builder().url(url).addHeader("Cookie", cookieList.toString()).build();
        Response response = client.newCall(request).execute();
        String respText = response.body().string();
        CoursePoint stringObjectMap = Decode.decodeCoursePoint(respText);
        return Arrays.asList(Decode.decodeCoursePoint(respText));
    }

    /**
     * 拉课程页，解析超星自己写在页面上的真实完成度。
     *
     * @return key: total / done / pending / noTask；拉取或解析失败返回 null
     */
    public Map<String, Integer> getRealProgress(String courseId, String clazzId, String cpi) {        try {
            OkHttpClient client = initSession(false, false);
            String url = String.format("https://mooc2-ans.chaoxing.com/mooc2-ans/mycourse/studentcourse?courseid=%s&clazzid=%s&cpi=%s&ut=s",
                    courseId, clazzId, cpi);
            Request request = new Request.Builder().url(url)
                    .addHeader("Cookie", cookieList.toString()).build();
            Response response = client.newCall(request).execute();
            Map<String, Integer> real = Decode.decodeRealProgress(response.body().string());
            if (real.getOrDefault("total", 0) <= 0) {
                // 没解析出章节，多半是拿到了登录页/错误页，宁可返回 null 让上层走兜底
                return null;
            }
            return real;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 拉课程页，解析出「章节 knowledgeId → 该章未完成任务点数」。
     *
     * <p>只读接口用它跳过已完成的章节（见 {@link Decode#decodeChapterPending(String)}）。
     * 拉取失败返回 null，调用方应当退回「全量扫描」。
     */
    public Map<String, Integer> getChapterPending(String courseId, String clazzId, String cpi) {
        try {
            OkHttpClient client = initSession(false, false);
            String url = String.format("https://mooc2-ans.chaoxing.com/mooc2-ans/mycourse/studentcourse?courseid=%s&clazzid=%s&cpi=%s&ut=s",
                    courseId, clazzId, cpi);
            Request request = new Request.Builder().url(url)
                    .addHeader("Cookie", cookieList.toString()).build();
            Response response = client.newCall(request).execute();
            return Decode.decodeChapterPending(response.body().string());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取任务点列表
     *
     * <p>★★ FIX(2026-10-11)「课永远刷不完，却查不出卡在哪」★★
     *
     * <p>超星的 cards 页会**随机**返回占位页（HTTP 202 的「提示页面」或 404），
     * 占位页里没有 {@code mArg=}，{@link Decode#decodeCourseCard} 只能返回空 jobList。
     * 实测同一 URL 连发 4 次：{@code num=0} 是 200/200/202/200，{@code num=1} 是
     * 202/202/200/202 —— 大约一半概率拿到占位页。
     *
     * <p>老实现每个 num 只请求一次，撞上占位页就**永久漏掉那个任务点**：
     * 章节被当成「没有任务点」，{@code processCoursePoints} 里
     * {@code if (jobs.isEmpty()) { pointIndex++; continue; }} 直接跳过，
     * 于是超星课程页一直挂着「待完成任务点」，而工具认为这门课刷完了。
     *
     * <p>所以这里对每个 num 重试，直到拿到含 {@code mArg=} 的真实响应为止。
     */
    public Pair<List<Job>, List<JobInfo>> getJobList(String clazzId, String courseId, String cpi, String knowledgeId) throws IOException {
        return getJobList(clazzId, courseId, cpi, knowledgeId, false);
    }

    /**
     * 同 {@link #getJobList(String, String, String, String)}，但可选是否保留
     * {@code isPassed=true} 的任务点。
     *
     * <p>只读接口（ApiController）用 {@code includePassed=true}，这样返回的列表
     * 是「章节下的全部任务点」，客户端才能把每条视频都列出来并标出已完成/未完成。
     */
    public Pair<List<Job>, List<JobInfo>> getJobList(String clazzId, String courseId, String cpi, String knowledgeId, boolean includePassed) throws IOException {
        OkHttpClient client = initSession(false, false);
        List<Job> jobList = new ArrayList<>();
        List<JobInfo> jobInfo = new ArrayList<>();
        String[] possibleNums = {"0", "1", "2"};
        for (String num : possibleNums) {
            String url = String.format("https://mooc1.chaoxing.com/mooc-ans/knowledge/cards?clazzid=%s&courseid=%s&knowledgeid=%s&num=%s&ut=s&cpi=%s&v=20160407-3&mooc2=1",
                    clazzId, courseId, knowledgeId, num, cpi);
            String respText = null;
            for (int attempt = 1; attempt <= CARD_MAX_ATTEMPTS; attempt++) {
                awaitRiskClear("章节任务点: " + knowledgeId + " num=" + num);
                // 占位页是按副本的：重试时强制换连接，否则 keep-alive 会一直
                // 打在同一个坏副本上（同 videoProgressLog 里的说明）。
                // 仅加 Connection:close 头不够，必须 evictAll 丢弃空闲连接，
                // 下一次 newCall 才会真正建新连接、打到可能不同的副本。
                if (attempt >= 2) {
                    client.connectionPool().evictAll();
                }
                Request cardRequest = (attempt == 1)
                        ? new Request.Builder().url(url).build()
                        : new Request.Builder().url(url).header("Connection", "close").build();
                Response response = client.newCall(cardRequest).execute();
                respText = response.body() == null ? "" : response.body().string();
                // 拿到真实内容（或明确的「章节未开放」）就收工；占位页重试
                if (respText.contains("mArg=") || respText.contains("章节未开放")) {
                    break;
                }
                if (attempt < CARD_MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(CARD_RETRY_DELAY_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (respText == null) {
                continue;
            }
            Pair<List<Job>, JobInfo> result = Decode.decodeCourseCard(respText, includePassed);
            List<Job> _jobList = result.getFirst();

            List<JobInfo> _jobInfo = Arrays.asList(result.getSecond());
            if (Boolean.TRUE.equals(_jobInfo.get(0).getNotOpen())) {
                return new Pair<>(new ArrayList<>(), _jobInfo);
            }
            jobList.addAll(_jobList);
            jobInfo.addAll(_jobInfo);
            // 某个 num 拿到的是「真实但空」的页 → 后面不会再有任务点，提前收工，
            // 免得对不存在的 num 白白重试（num=2 常年 404/202，是最常见的浪费）
            if (_jobList.isEmpty() && respText.contains("mArg=")) {
                break;
            }
        }
        return new Pair<>(jobList, jobInfo);
    }

    /**
     * 生成 MD5 加密串
     */
    public String getEnc(String clazzId, String jobId, String objectId, long playingTime, long duration, String userId)
            throws Exception {
        String str = String.format("[%s][%s][%s][%s][%d][d_yHJ!$pdA~5][%d][0_%s]",
                clazzId, userId, jobId, objectId, playingTime * 1000, duration * 1000, duration);
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(str.getBytes());
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ========================================================================
    // 全局上报限速器
    //
    // 超星的风控是**按账号**的：多线程同时上报本身就是异常特征。
    // 之前 10 个线程各自 100 毫秒连发，等于给服务端每秒几十次上报，
    // 直接被判定为「操作异常」。所以所有线程必须共用一个最小间隔，排队上报。
    // ========================================================================
    private static final Object LOG_RATE_LOCK = new Object();
    private static volatile long lastLogAt = 0L;

    /** 所有线程共用的上报节流：保证任意两次 log 请求之间至少隔 minIntervalMs 毫秒。 */
    private void awaitLogSlot() throws InterruptedException {
        long minIntervalMs = studyProperties.getVideoReportPaceMs() == null
                ? 10000L : studyProperties.getVideoReportPaceMs().longValue();
        synchronized (LOG_RATE_LOCK) {
            long wait = lastLogAt + minIntervalMs - System.currentTimeMillis();
            if (wait > 0) {
                Thread.sleep(wait);
            }
            lastLogAt = System.currentTimeMillis();
        }
    }

    /**
     * 视频上报的结果。
     *
     * <p>老实现只返回一个 boolean，把「服务端拒收」和「服务端收了但没计分」
     * 混在一起，调用方无法区分，只能无脑重试——正是把账号打到风控的元凶。
     */
    public static class VideoLogResult {
        /** HTTP 状态码，-1 表示请求异常 */
        public int httpCode = -1;
        /** 服务端裁决：该任务点是否已计分 */
        public boolean isPassed = false;
        /**
         * true = 命中超星风控。表现为 HTTP 202/404 + 一个 HTML「提示页面」，
         * 内容是「【9010】操作异常，请输入图片中的验证码」。
         * 此时**必须停止**，继续发只会延长封禁。
         */
        public boolean blocked = false;
        /** 原始响应体（截断前 300 字），便于排查 */
        public String body = "";

        @Override
        public String toString() {
            return "http=" + httpCode + " isPassed=" + isPassed
                    + " blocked=" + blocked;
        }
    }

    /**
     * 视频播放进度日志
     *
     * @param isdrag 上报类型，取值与超星播放器源码（videojs-ext）里 sendLog 的 switch 一致：
     *               <ul>
     *                 <li>{@code "0"} = playing（播放中）</li>
     *                 <li>{@code "1"} = drag（拖动进度条）</li>
     *                 <li>{@code "2"} = seek 结束</li>
     *                 <li>{@code "3"} = 开始播放</li>
     *                 <li>{@code "4"} = ended（播放完毕）</li>
     *               </ul>
     *               一次完整播放的序列是 3 → 0 → 0 → … → 4。
     * @return {@link VideoLogResult}，包含 HTTP 码、isPassed 与是否命中风控
     *
     * <p>⚠️ 历史坑（2026-10 实测）：
     * <ol>
     *   <li>缺尾部参数（videoFaceCaptureEnc / attDuration / attDurationEnc /
     *       courseEngineInfo）时服务端「进度存下来了但不计分」。已按播放器源码补齐。</li>
     *   <li>URL 的 rt 参数必须和 otherInfo 里的 rt_X 一致，否则 403。
     *       otherInfo 里的 {@code rt_d} 是「默认」标记，不能当 rt 用，退回 0.9。</li>
     *   <li><b>风控</b>：高频连发会触发 9010 验证码，之后全部上报被拒。
     *       调用方收到 {@code blocked=true} 必须停手退避。</li>
     * </ol>
     */
    public VideoLogResult videoProgressLog(OkHttpClient session, Course course,
                                           Job job, JobInfo jobInfo, String dtoken, long duration,
                                           long playingTime, String isdrag, String type) throws Exception {
        VideoLogResult result = new VideoLogResult();
        String midText;
        if (job.getOtherInfo().contains("courseId")) {
            midText = "otherInfo=" + job.getOtherInfo() + "&";
        } else {
            midText = "otherInfo=" + job.getOtherInfo() + "&courseId=" + course.getCourseId() + "&";
        }
        // 附件时长：优先用 cards 里的 attDuration（服务端比对用的是它）
        long attDuration = (job.getAttDuration() != null && job.getAttDuration() > 0)
                ? job.getAttDuration() : duration;
        String vfce = job.getVideoFaceCaptureEnc() == null ? "" : job.getVideoFaceCaptureEnc();
        String ade = job.getAttDurationEnc() == null ? "" : job.getAttDurationEnc();

        // ⚠️ 坑：URL 的 rt 参数必须和 otherInfo 里的 rt_X 一致，否则服务端直接 403。
        //
        // otherInfo 形如：nodeId_xxx-cpi_562459847-rt_1-ds_1-ff_1-be_0_0-vt_1-v_6-enc_xxx&courseId=xxx
        // 实测（金课「锋面第5期：聊聊大学的To-Do List.mp4」，同一个 URL 只改 rt）：
        //   otherInfo 是 rt_1 的课程：rt=0.9 → 403，rt=1 → 200，rt=d → 404，rt=0.8 → 403
        // 注意 rt_d 是「默认」标记（不是数字），不能直接当 rt 用，这种就退回 0.9。
        List<String> rtCandidates = new ArrayList<>();
        int rtIdx = job.getOtherInfo().indexOf("rt_");
        if (rtIdx >= 0) {
            String tail = job.getOtherInfo().substring(rtIdx + 3);
            int dash = tail.indexOf('-');
            String rtValue = (dash > 0) ? tail.substring(0, dash) : tail;
            if (rtValue.matches("[0-9]+(\\.[0-9]+)?")) {
                rtCandidates.add(rtValue);
            }
        }
        if (!rtCandidates.contains("0.9")) {
            rtCandidates.add("0.9");
        }
        if (!rtCandidates.contains("1")) {
            rtCandidates.add("1");
        }

        for (String possibleRt : rtCandidates) {
            String url = String.format("https://mooc1.chaoxing.com/mooc-ans/multimedia/log/a/%s/%s?clazzId=%s&playingTime=%s" +
                            "&duration=%s&clipTime=0_%s&objectId=%s&%sjobid=%s&userid=%s&isdrag=%s&view=pc&enc=%s&rt=%s" +
                            "&videoFaceCaptureEnc=%s&dtype=%s&_t=%s&attDuration=%s&attDurationEnc=%s&courseEngineInfo=false",
                    course.getCpi(), dtoken, course.getClazzId(), playingTime, duration, duration,
                    job.getObjectId(),
                    midText, job.getJobId(), getValue("_uid"), isdrag,
                    getEnc(course.getClazzId(), job.getJobId(), job.getObjectId(),
                            playingTime, duration, getValue("_uid")),
                    possibleRt, vfce, type, getTimestamp(), attDuration, ade);
            Request request = new Request.Builder().url(url).build();
            awaitLogSlot();     // 全局限速：风控是按账号的，多线程必须排队
            if (isRiskBlocked()) {
                // 在限速队列里排队期间，别的线程撞上了风控。这一条也不能发 ——
                // 超星的风控窗口按「最后一次请求」滚动，漏一条就续期一次。
                result.blocked = true;
                result.body = "(退避期内主动放弃发送)";
                return result;
            }
            Response response = null;
            String respText = "";
            // ★★ FIX(2026-10-11)「视频永远计不上分」★★
            //
            // 超星的 log 接口会**逐请求随机**返回占位页。实测同一条 URL 连发 14 次：
            //   404 200 200 200 202 200 404 202 200 200 200 404 404 202
            // 其中 202 是「【9010】操作异常，请输入图片中的验证码」提示页（4527 字节），
            // 看起来像风控；但它和 404（738 字节的裸 404 页）一样，紧接着的下一条
            // 就可能 200 —— 说明这是**多副本未命中**，不是频率风控。
            //
            // 老实现把 404/202 一律当风控：立刻 blocked + 3 分钟退避 + 整轮播放作废。
            // 后果是视频几乎永远走不到最后那条 isdrag=4 的上报，而服务端**只在
            // isdrag=4 那条**才把 isPassed 判成 true —— 于是「超星一直挂着待完成任务点」，
            // 工具却反复重试、每轮都在中途被打断。
            //
            // 实测反证：4.3「专题三导数与微分(3).mp4」按 0/300/…/1814(isdrag=4) 走一遍，
            // 末尾那条返回 {"isPassed":true} —— 视频本身完全可计分。
            //
            // 现在改成：非 JSON 响应**就地重试**，并且**每次重试强制换一条连接**
            // （Connection: close）。原因：OkHttp 默认复用 keep-alive 连接，
            // 而占位页是**按副本**的 —— 复用连接会一直打在同一个坏副本上，
            // 实测 Java 侧连续 5 次重试全是 202，而 Python 每次新建连接时
            // 同一 URL 是 200/404/202 随机交错。换连接后重试才有意义。
            for (int attempt = 1; ; attempt++) {
                // ★★ FIX(2026-10-11)「占位页重试永远落到同一坏副本」★★
                // 只给请求加 Connection:close 头**不够**：OkHttp 仍会从连接池里
                // 复用 attempt=1 那条已经建好的 keep-alive 连接，于是 6 次重试
                // 全打到同一个坏副本上 —— 实测 Java 侧连续 5 次 202、Python 每次
                // 新建连接却 200/404/202 随机交错，就是这个差异。
                // 必须先把连接池里**所有空闲连接强制丢弃**，下一次 newCall 才会
                // 真正建一条新 TCP 连接、打到（可能不同的）副本上。
                if (attempt >= 2) {
                    session.connectionPool().evictAll();
                }
                Request attemptRequest = (attempt == 1)
                        ? request
                        : request.newBuilder().header("Connection", "close").build();
                response = session.newCall(attemptRequest).execute();
                respText = response.body() == null ? "" : response.body().string();
                String t = respText.trim();
                if (response.code() == 200 && t.startsWith("{")) {
                    break;                          // 真响应
                }
                if (response.code() == 403) {
                    break;                          // rt 不匹配，交给外层换候选
                }
                if (attempt >= VIDEO_REPORT_MAX_RETRIES) {
                    break;                          // 连续占位 → 才按风控处理
                }
                try {
                    Thread.sleep(VIDEO_REPORT_RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            result.httpCode = response.code();
            result.body = respText.substring(0, Math.min(300, respText.length()));

            String trimmed = respText.trim();
            if (result.httpCode == 200 && trimmed.startsWith("{")) {
                try {
                    JSONObject body = new JSONObject(trimmed);
                    result.isPassed = body.optBoolean("isPassed", false);
                } catch (Exception ignore) {
                    result.isPassed = false;
                }
                // 有一条成功就说明额度恢复了，把退避时长复位
                clearRiskBackoff();
                return result;
            }
            if (result.httpCode == 403) {
                // rt 不匹配，换下一个候选
                continue;
            }
            // 连续多次都是占位页：按风控处理，停手退避
            result.blocked = true;
            return result;
        }
        // 全部候选 rt 都是 403
        return result;
    }

    // ========================================================================
    // 超星风控退避（自适应）
    //
    // 2026-10-11 实测：log 接口有**按账号**的频率风控。超出额度时返回
    // HTTP 202 + 一个 HTML 提示页「【9010】操作异常，请输入图片中的验证码」，
    // 或者一个裸 404 页面。之后所有上报一律被拒 —— 视频 playTime 被写满
    // 但 isPassed 永远 false。
    //
    // ★ 关键性质：这个窗口是**滚动**的，按「最后一次请求」续期。
    //   15:07:50 停止一切请求 → 15:09:37 单发一条就恢复 200（约 110 秒）。
    //   反过来，只要退避期里每 60 秒还漏一条请求出去，窗口就被续期，
    //   永远等不到解除 —— 所以退避期必须「一条请求都不发」，
    //   包括 ananas/status、课程页、cards 页，见 awaitRiskClear()。
    //
    // 退避策略：首次歇 3 分钟（比实测的 110 秒窗口留足余量），
    // 连续命中才逐次翻倍（上限 15 分钟）。
    // 一旦有任意一条上报成功（200 + JSON），立刻把退避时长复位。
    //
    // ⚠️ 老实现是「一命中就全局停 5 分钟」——一条被拦，10 个线程全部瘫痪，
    //    实测跑 8 个视频 0 个计分。太钝了。
    // ========================================================================
    private static final long RISK_MIN_BACKOFF_MS = 3 * 60 * 1000L;    // 首次 3 分钟
    private static final long RISK_MAX_BACKOFF_MS = 15 * 60 * 1000L;   // 上限 15 分钟
    /** awaitRiskClear() 最多愿意等多久（兜底，避免线程被无限占住） */
    private static final long RISK_WAIT_MAX_MS = 10 * 60 * 1000L;
    private static volatile long riskBackoffMs = RISK_MIN_BACKOFF_MS;
    private static volatile long riskBlockedUntil = 0L;

    /**
     * cards 页占位响应的重试次数 / 间隔。
     *
     * <p>超星 cards 页会**随机**返回占位页（HTTP 202 的「提示页面」或 404，里面没有
     * {@code mArg=}），实测同一个 URL 连发 4 次的返回：
     * <pre>
     *   num=0 : 200[document] | 200[document] | 202[-] | 200[document]
     *   num=1 : 202[-]        | 202[-]        | 200[video] | 202[-]
     * </pre>
     * 老实现每个 num 只请求一次，撞上占位页就永久漏掉那个任务点。见 {@link #getJobList}。
     */
    private static final int CARD_MAX_ATTEMPTS = 4;
    private static final long CARD_RETRY_DELAY_MS = 700L;

    /**
     * 视频上报（multimedia/log）遇到占位页时的重试次数 / 间隔。
     *
     * <p>该接口同样会**逐请求随机**返回占位页（404 裸页 / 202 的「【9010】…验证码」提示页），
     * 实测连发 14 次的状态序列是 {@code 404 200 200 200 202 200 404 202 200 200 200 404 404 202}，
     * 说明 202/404 是**多副本未命中**而不是频率风控。只有**连续**失败到阈值才认定为真风控。
     * 见 {@link #videoProgressLog}。
     */
    private static final int VIDEO_REPORT_MAX_RETRIES = 6;
    private static final long VIDEO_REPORT_RETRY_DELAY_MS = 2000L;

    /** 当前是否处于风控退避期 */
    public static boolean isRiskBlocked() {
        return System.currentTimeMillis() < riskBlockedUntil;
    }

    /**
     * 风控退避期内**一条请求都不要发**，在这里把线程按住直到窗口解除。
     *
     * <p>为什么连 ananas/status、课程页、cards 页也要按住：超星的风控窗口是按
     * 「最后一次请求」滚动的。退避期里只要还漏请求出去，窗口就不断续期，
     * 表现为「明明退避了却永远是 202」。实测：完全静默约 110 秒即恢复。
     *
     * @param what 调用方说明，仅用于日志
     * @return true = 已解除；false = 等满 {@link #RISK_WAIT_MAX_MS} 仍未解除（调用方可自行决定是否硬闯）
     */
    public static boolean awaitRiskClear(String what) {
        if (!isRiskBlocked()) {
            return true;
        }
        long deadline = System.currentTimeMillis() + RISK_WAIT_MAX_MS;
        boolean announced = false;
        while (isRiskBlocked()) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            long wait = riskBlockedUntil - System.currentTimeMillis();
            if (!announced) {
                System.out.println(Thread.currentThread().getName()
                        + "风控退避期内暂停一切请求，等 " + Math.max(0, wait / 1000) + "s: " + what);
                announced = true;
            }
            try {
                // 分段睡，方便别的线程延长窗口时及时跟上
                Thread.sleep(Math.min(Math.max(500L, wait), 5000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private static synchronized void enterRiskBackoff() {
        long now = System.currentTimeMillis();
        if (now < riskBlockedUntil) {
            // 同一个惩罚窗口里别的线程也报了一次 —— 不要重复翻倍，
            // 否则 3 个线程各撞一次就把 3 分钟抬成 12 分钟。
            return;
        }
        riskBlockedUntil = now + riskBackoffMs;
        System.out.println(Thread.currentThread().getName()
                + "命中超星风控，退避 " + (riskBackoffMs / 1000) + "s（连续命中会翻倍）");
        riskBackoffMs = Math.min(RISK_MAX_BACKOFF_MS, riskBackoffMs * 2);
    }

    /** 任意一条上报成功就把退避时长复位回最小值 */
    private static void clearRiskBackoff() {
        riskBackoffMs = RISK_MIN_BACKOFF_MS;
        riskBlockedUntil = 0L;
    }

    /**
     * 学习视频（模拟观看）——按超星播放器的真实行为上报。
     *
     * <p>算法来源：超星自己的播放器源码（videojs-ext）里 {@code seekBarControl.sendLog}
     * 的 switch，以及 cards 页 {@code defaults} 下发的参数。权威事实：
     * <ol>
     *   <li>{@code reportTimeInterval = 60} —— 播放器<b>每 60 秒播放进度</b>上报一条。</li>
     *   <li>{@code isdrag} 取值：{@code playing→0, drag→1, seek结束→2, 开始播放→3, ended→4}。
     *       所以一次完整播放的上报序列是 {@code 3 → 0 → 0 → … → 4}。</li>
     *   <li>{@code playingTime} 就是当前播放位置，按 1 秒/秒推进（rate=1，<b>不倍速</b>）。</li>
     * </ol>
     *
     * <p>⚠️ 为什么不能再「连发抢分」：老实现按 100 毫秒连发最多 5000 条满时长上报，
     * 把账号直接打到超星风控，结果反而<b>一条都刷不完</b>（详见 RISK_BACKOFF_MS 的注释）。
     * 现在完全按播放器节奏：每 {@code reportTimeInterval} 秒播放进度一条，两条之间
     * 墙钟间隔由 {@code study.chaoxing.video-report-pace-ms} 控制（默认 10 秒，
     * 是在「不被风控」前提下能压到的最快值；设成 60000 就是字面意义的正常播放）。
     *
     * @return true = 超星已确认这个任务点通过（isPassed）；false = 未通过
     */
    public boolean studyVideo(Course course, Job job, JobInfo jobInfo,
                              double speed, String type, SuperStarLog log) throws Exception {

        // 风控退避期内连 ananas/status 都不要发：窗口是按「最后一次请求」滚动的，
        // 漏一条出去就把退避续期，于是永远等不到解除。
        awaitRiskClear("准备学习视频: " + job.getName());

        OkHttpClient session = ("Video".equals(type)) ?
                initSession(true, false) : initSession(false, true);
        // 获取视频信息
        String infoUrl = String.format("https://mooc1.chaoxing.com/ananas/status/%s?k=%s&flag=normal",
                job.getObjectId(), getValue("fid"));
        Request infoRequest = new Request.Builder().url(infoUrl).build();
        Response infoResponse = session.newCall(infoRequest).execute();
        Map<String, Object> videoInfo = JsonParser.parse(infoResponse.body().string());
        if (!"success".equals(videoInfo.get("status"))) {
            // 拿不到视频信息（转码中 / 已下线 / 需要音频通道），交给上层决定是否换 Audio 重试
            return false;
        }

        String dtoken = videoInfo.get("dtoken").toString();
        long duration = Long.parseLong(videoInfo.get("duration").toString());

        // 播放器自己的节奏：cards 页 defaults.reportTimeInterval（实测 = 60）
        int step = (jobInfo != null && jobInfo.getReportTimeInterval() > 0)
                ? jobInfo.getReportTimeInterval()
                : (studyProperties.getVideoReportStepSeconds() == null
                ? 60 : studyProperties.getVideoReportStepSeconds());
        long paceMs = studyProperties.getVideoReportPaceMs() == null
                ? 10000L : studyProperties.getVideoReportPaceMs().longValue();
        int maxRounds = studyProperties.getVideoMaxRounds() == null
                ? 3 : studyProperties.getVideoMaxRounds();

        System.out.println(Thread.currentThread().getName() + "开始学习视频: "
                + "课程名:" + course.getTitle() + " " + "任务名:" + job.getName()
                + " 时长:" + duration + "s" + " 上报步长:" + step + "s"
                + " 墙钟间隔:" + paceMs + "ms"
                // 给客户端算「已播多久」用的绝对起点。这些 println 没有日志框架的
                // 时间戳，客户端只能靠「自己第一次读到这行的时间」推算，
                // 一重启就把进度归零。带上 epoch 毫秒后，进度立刻准确。
                + " 起始:" + System.currentTimeMillis());

        if (log.getStartTime() == null) {
            log.setStartTime(LocalDateTime.now());
        }
        log.setCurrentJob(job.getName());

        long loopStart = System.currentTimeMillis();
        int reports = 0;
        int rounds = 0;              // 已完整播放的遍数
        int blockedWaits = 0;        // 因风控等待的次数
        boolean passed = false;
        boolean blocked = false;
        VideoLogResult lastBlocked = null;   // 最近一次被拒的响应，用于日志定位
        final int maxBlockedWaits = 3;   // 退避 3 分钟起步且逐次翻倍，等 3 次还没戏就放手

        // 注意：命中风控**不消耗** rounds —— 等退避结束会重试同一个视频，
        // 而不是把这个任务点丢掉。老实现一命中就 return false，
        // 结果 8 个视频全军覆没、0 个计分。
        while (rounds < maxRounds && !passed) {
            if (isRiskBlocked()) {
                if (blockedWaits >= maxBlockedWaits) {
                    blocked = true;
                    break;
                }
                long wait = riskBlockedUntil - System.currentTimeMillis();
                System.out.println(Thread.currentThread().getName()
                        + "处于超星风控退避期，等 " + Math.max(0, wait / 1000) + "s: " + job.getName());
                Thread.sleep(Math.max(1000L, wait));
                blockedWaits++;
                continue;
            }
            // 每一遍重新拉 dtoken（会过期）
            if (rounds > 0) {
                try {
                    Response refresh = session.newCall(
                            new Request.Builder().url(infoUrl).build()).execute();
                    Map<String, Object> refreshed = JsonParser.parse(refresh.body().string());
                    if ("success".equals(refreshed.get("status"))
                            && refreshed.get("dtoken") != null) {
                        dtoken = refreshed.get("dtoken").toString();
                    }
                } catch (Exception ignore) {
                    // 刷新失败就用旧的继续，不影响主流程
                }
            }
            rounds++;

            // ① 开始播放：isdrag=3, playingTime=0
            VideoLogResult r = videoProgressLog(session, course, job, jobInfo,
                    dtoken, duration, 0, "3", type);
            reports++;
            if (r.blocked) {
                if (lastBlocked == null) {
                    System.out.println(Thread.currentThread().getName()
                            + "上报被拒(" + r + "): " + job.getName());
                }
                lastBlocked = r;
                enterRiskBackoff();
                rounds--;        // 这一遍压根没发出去，不算数
                continue;
            }
            if (r.isPassed) {
                passed = true;
                break;
            }
            // 注意：两条上报之间的等待由 awaitLogSlot() 统一节流，
            // 这里不再单独 sleep，否则会叠加成两倍间隔。

            // ② 播放中：每 step 秒播放进度一条；走到满时长那条用 isdrag=4（播放完毕）
            long pt = 0;
            boolean roundBlocked = false;
            while (pt < duration) {
                pt = Math.min(pt + step, duration);
                String isdrag = (pt >= duration) ? "4" : "0";
                r = videoProgressLog(session, course, job, jobInfo,
                        dtoken, duration, pt, isdrag, type);
                reports++;
                if (r.blocked) {
                    if (lastBlocked == null) {
                        System.out.println(Thread.currentThread().getName()
                                + "上报被拒(" + r + "): " + job.getName());
                    }
                    lastBlocked = r;
                    roundBlocked = true;
                    break;
                }
                if (r.isPassed) {
                    passed = true;
                    break;
                }
                // 上报节流是**全局**的（任意两次上报间隔 >= paceMs，多线程排队），
                // 所以墙钟时间远大于真实播放位置：3 个线程时每条视频要 3 倍时间。
                // 客户端若按墙钟推算进度，会算出 >100%。这里把真实位置打出来。
                // 注意 rounds 在进入本轮循环时已 ++（见上方），这里就是当前遍数
                System.out.println(Thread.currentThread().getName() + "播放位置: "
                        + job.getName() + " " + pt + "s/" + duration + "s"
                        + " 第" + rounds + "遍 第" + reports + "报");
            }
            if (passed) {
                break;
            }
            if (roundBlocked) {
                enterRiskBackoff();
                rounds--;        // 这一遍不算数，退避后重来
            }
        }

        long cost = (System.currentTimeMillis() - loopStart) / 1000;
        if (passed) {
            log.setErrorMessage(null);
            log.setRemark("任务完成: " + job.getName());
            System.out.println(Thread.currentThread().getName()
                    + "视频已计分: " + job.getName() + "（共 " + reports + " 条上报，耗时 " + cost + "s）");
        } else if (blocked) {
            System.out.println(Thread.currentThread().getName()
                    + "视频被超星风控拦截: " + job.getName() + "（共 " + reports
                    + " 条上报，最后一条被拒: " + lastBlocked + "）");
            log.setRemark("被超星风控拦截: " + job.getName());
            log.setErrorMessage("超星拒绝上报（" + lastBlocked + "）；已退避，本任务点未计分");
        } else {
            // 不谎报：服务端没收就是没收
            System.out.println(Thread.currentThread().getName()
                    + "视频未计分: " + job.getName() + "（完整播放 " + maxRounds
                    + " 遍共 " + reports + " 条上报仍未翻牌，耗时 " + cost + "s）");
            log.setRemark("未通过服务端校验: " + job.getName());
            log.setErrorMessage("超星未计分（已按播放器节奏上报 " + reports + " 条，isPassed 仍为 false）");
        }
        log.setEndTime(LocalDateTime.now());
        log.setMachineNum(serverInfoUtil.getCurrentServerInstance());
        superStarLogService.saveLog(log);
        return passed;
    }

    /**
     * 学习文档（= 上报 {@code /ananas/job/document}）
     */
    public boolean studyDocument(Course course, Job job) throws IOException {
        return reportJobPoint(course, job, "document");
    }

    /**
     * 超链接任务点（= 上报 {@code /ananas/job/hyperlink}）
     */
    public boolean studyHyperlink(Course course, Job job) throws IOException {
        return reportJobPoint(course, job, "hyperlink");
    }

    /**
     * 通用「任务点完成」上报。
     *
     * <p>超星的上报端点是 {@code https://mooc1.chaoxing.com/ananas/job/{type}}，
     * 其中 {@code type} 就是 cards 页 attachment 的 {@code type} 字段
     * （video / document / read / hyperlink …；video 另有 multimedia/log 通道，
     * 见 {@link #videoProgressLog}）。
     *
     * <p>实测对照（同一账号、同一分钟）：
     * <ul>
     *   <li>{@code document} + 真实 jobid
     *       → {@code {"msg":"考核点已经完成","status":true}}</li>
     *   <li>{@code hyperlink}
     *       → {@code {"msg":"添加考核点成功","status":true}}</li>
     *   <li>jobid 缺失或非法（例如只有 {@code property._jobid}）
     *       → {@code {"msg":"无效的请求参数","status":false}}</li>
     * </ul>
     *
     * @return true 表示服务端确认这个任务点已完成
     */
    public boolean reportJobPoint(Course course, Job job, String type) throws IOException {
        // 上报吃账号额度，风控退避期内别发
        awaitRiskClear("任务点(" + type + "): " + job.getTitle());

        OkHttpClient client = initSession(false, false);
        String otherInfo = job.getOtherInfo() == null ? "" : job.getOtherInfo();
        Pattern pattern = Pattern.compile("nodeId_(.*?)-");
        Matcher matcher = pattern.matcher(otherInfo);
        String knowledgeId = matcher.find() ? matcher.group(1) : "";
        String url = String.format(
                "https://mooc1.chaoxing.com/ananas/job/%s?jobid=%s&knowledgeid=%s&courseid=%s&clazzid=%s&jtoken=%s&_dc=%s",
                type, job.getJobId(), knowledgeId, course.getCourseId(), course.getClazzId(),
                job.getJToken(), getTimestamp());
        Request request = new Request.Builder().url(url).build();
        // 与 videoProgressLog 同理：占位页（404 裸页 / 202 的「9010 验证码」提示页）是
        // **按副本**随机出现的，重试时必须换连接，否则 keep-alive 会一直打在坏副本上。
        Response response = null;
        String body = "";
        for (int attempt = 1; ; attempt++) {
            // 同 videoProgressLog：只加 Connection:close 头复用连接池无效，必须 evictAll
            // 强制丢弃空闲连接，下一次 newCall 才真正建新连接、打到可能不同的副本。
            if (attempt >= 2) {
                client.connectionPool().evictAll();
            }
            Request attemptRequest = (attempt == 1)
                    ? request
                    : request.newBuilder().header("Connection", "close").build();
            response = client.newCall(attemptRequest).execute();
            body = response.body() == null ? "" : response.body().string();
            String t = body.trim();
            if (t.startsWith("{")) {
                break;                              // 真响应（成功或 status:false 都是真响应）
            }
            if (attempt >= VIDEO_REPORT_MAX_RETRIES) {
                break;
            }
            try {
                Thread.sleep(VIDEO_REPORT_RETRY_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        String trimmed = body.trim();

        // 服务端用 status:true/false 表达成败。
        // FIX(2026-10-11): 老实现把响应**整个丢掉**，成功失败都不吭声；
        // 上报失败时上层 chapterOk 依然是 true，于是出现「超星还有 N 个待完成任务点」
        // 与「本次 0 个章节未通过服务端校验」同时成立，用户完全无从下手。
        boolean ok = response.code() == 200
                && !trimmed.contains("\"status\":false")
                && !trimmed.contains("\"success\":false");
        System.out.println(Thread.currentThread().getName()
                + (ok ? "任务点已完成[" + type + "]: " : "任务点上报失败[" + type + "/" + response.code() + "]: ")
                + course.getTitle() + " / " + job.getTitle() + " -> "
                + (trimmed.length() > 140 ? trimmed.substring(0, 140) : trimmed));
        if (!ok) {
            // 失败时把实际请求的 URL 打出来 —— 「无效的请求参数」只有对着 URL
            // 才看得出是 jobid 空、knowledgeid 没解析出来，还是 jtoken 不对。
            System.out.println(Thread.currentThread().getName() + "  请求URL: " + url);
        }
        return ok;
    }

    /**
     * 阅读任务，只完成任务点，不增加时长
     */
    public void studyRead(Course course, Job job, JobInfo jobInfo, SuperStarLog log)
            throws IOException {
        OkHttpClient client = initSession(false, false);
        HttpUrl url = HttpUrl.parse("https://mooc1.chaoxing.com/ananas/job/readv2").newBuilder()
                .addQueryParameter("jobid", job.getJobId())
                .addQueryParameter("knowledgeid", jobInfo.getKnowledgeid())
                .addQueryParameter("jtoken", job.getJToken())
                .addQueryParameter("courseid", course.getCourseId())
                .addQueryParameter("clazzid", course.getClazzId())
                .build();
        Request request = new Request.Builder().url(url).build();
        Response response = client.newCall(request).execute();
        if (response.code() != 200) {
            log.setRemark("阅读任务学习失败 -> [" + response.code() + "]" + response.body().string());
        } else {
            Map<String, Object> respJson = JsonParser.parse(response.body().string());
            log.setRemark("阅读任务学习 -> " + respJson.get("msg"));
        }
        superStarLogService.saveLog(log);
    }

    /**
     * 作业任务
     *
     * @param course  课程信息
     * @param job     任务点信息
     * @param jobInfo 作业信息
     * @throws Exception 异常
     */
    public void studyWork(Course course, Job job, JobInfo jobInfo, SuperStarLog log) throws Exception {

        OkHttpClient client = initSession(false, false);
        String originHtmlContent = "";

        // 获取作业页面
        String url = "https://mooc1.chaoxing.com/mooc-ans/api/work";
        HttpUrl.Builder urlBuilder = HttpUrl.parse(url).newBuilder()
                .addQueryParameter("api", "1")
                .addQueryParameter("workId", job.getJobId().replace("work-", ""))
                .addQueryParameter("jobid", job.getJobId())
                .addQueryParameter("originJobId", job.getJobId())
                .addQueryParameter("needRedirect", "true")
                .addQueryParameter("skipHeader", "true")
                .addQueryParameter("knowledgeid", jobInfo.getKnowledgeid())
                .addQueryParameter("ktoken", jobInfo.getKtoken())
                .addQueryParameter("cpi", jobInfo.getCpi())
                .addQueryParameter("ut", "s")
                .addQueryParameter("clazzId", course.getClazzId())
                .addQueryParameter("enc", job.getEnc())
                .addQueryParameter("mooc2", "1")
                .addQueryParameter("courseid", course.getCourseId());

        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36 Edg/129.0.0.0")
                .get()
                .build();

        Response response = client.newCall(request).execute();
        originHtmlContent = response.body().string();

        Map<String, Object> questions = Decode.decodeQuestionsInfo(originHtmlContent);

        processAnswers(questions, client, log, job.getTitle());
    }

    /**
     * 切割选项
     *
     * @param options 选项
     * @return 选项信息
     */
    private static List<String> multiCut(String options) {
        if (options == null) return Collections.emptyList();

        return Pattern.compile("\n+")
                .splitAsStream(options)
                .filter(s -> !s.trim().isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * 获取并处理答案
     *
     * @param questions 题目信息
     */
    public void processAnswers(Map<String, Object> questions, OkHttpClient client, SuperStarLog log, String jobTitle) throws IOException {
        // 获取题目列表
        List<Question> questionList = new ArrayList<>();
        List<Object> questions1 = (List<Object>) questions.get("questions");
        for (Object question : questions1) {
            if (question instanceof Map) {
                Map<String, Object> questionMap = (Map<String, Object>) question;
                Map<String, Object> answerField = (Map<String, Object>) questionMap.get("answerField");
                String title = (String) questionMap.get("title");
                String options = (String) questionMap.get("options");
                String id = (String) questionMap.get("id");
                String type = (String) questionMap.get("type");
                AnswerField<String> readAnswerField = new AnswerField<>();
                readAnswerField.updateFromMap(answerField);
                Question q = new Question(id, title, options, type, readAnswerField);
                questionList.add(q);
            }
        }

        for (Question q : questionList) {

            String questionType = q.getType();
            List<String> options = multiCut(q.getOptions());
            q.setTKOptions(options);
            String res = query.queryAnswerSync(q);
            StringBuilder answer = new StringBuilder(); //最终答案

            if (res == null || res.isEmpty()) {
                if (questionType.equals("judgement")) {
                    answer = new StringBuilder("true");  //对于判断题来说,如果题库无答案，则默认选对
                } else {
                    answer = new StringBuilder(randomAnswer(options)); //其他题型默认随机选
                }
            } else {
                switch (questionType) {

                    case "single":
                        for (String option : options) {
                            if (option.contains(res)) {
                                answer = new StringBuilder(option.substring(0, 1));
                                break;
                            }
                        }
                        break;
                    case "multiple":
                        String[] resArr = res.split("#");
                        for(String re : resArr){
                            for (String option : options) {
                                if (option.contains(re)) {
                                    answer.append(option.charAt(0));
                                    break;
                                }
                            }
                        }

                        answer = new StringBuilder(answer.chars()
                                .sorted()
                                .mapToObj(c -> String.valueOf((char) c))
                                .collect(Collectors.joining()));
                        break;

                    case "judgement":
                        if (res.equals("正确")) {
                            answer = new StringBuilder("true");
                        } else {
                            answer = new StringBuilder("false");
                        }
                    default:
                }

            }

            AnswerField<String> answerField = q.getAnswerField();
            answerField.setAnswerValue(answer.toString());

        }

        for (Question q : questionList) {
            questions.put("answer" + q.getId(), q.getAnswerField().getAnswerValue());
            String type = q.getType();
            String reaType = "0";
            switch (type) {
                case "single":
                    reaType = "0";
                    break;
                case "multiple":
                    reaType = "1";
                    break;
                case "fill":
                    reaType = "2";
                    break;
                case "judgement":
                    reaType = "3";
                    break;
                case "short":
                    reaType = "4";
                    break;

            }
            questions.put("answertype" + q.getId(), reaType);
        }
        try {
            questions.remove("questions");
            submitAnswers(questions, client, log, jobTitle);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (NacosException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * 随机选择一个选项
     *
     * @param options 选项
     * @return 答案
     */
    private String randomAnswer(List<String> options) {
        if (options == null || options.isEmpty()) {
            return "";
        }
        Random random = new Random();
        int index = random.nextInt(options.size());
        return options.get(index).substring(0, 1); // 返回选项首字母
    }


    /**
     * 提交章节测验答案
     *
     * @param questions 题目信息
     * @param client    OkHttpClient
     * @throws IOException 异常
     */
    private void submitAnswers(Map<String, Object> questions, OkHttpClient client, SuperStarLog log, String jobTitle) throws IOException, NacosException {
        FormBody.Builder formBodyBuilder = new FormBody.Builder(StandardCharsets.UTF_8);

        for (Map.Entry<String, Object> entry : questions.entrySet()) {
            formBodyBuilder.add(entry.getKey(), entry.getValue().toString());
        }
        Request request = new Request.Builder()
                .url("https://mooc1.chaoxing.com/mooc-ans/work/addStudentWorkNewWeb")  //2025/4/26发现学习通提交题目接口已改变
                .post(formBodyBuilder.build())
                .header("Host", "mooc1.chaoxing.com")
                .header("sec-ch-ua-platform", "\"Windows\"")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36 Edg/129.0.0.0")
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("sec-ch-ua", "\"Microsoft Edge\";v=\"129\", \"Not=A?Brand\";v=\"8\", \"Chromium\";v=\"129\"")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("sec-ch-ua-mobile", "?0")
                .header("Origin", "https://mooc1.chaoxing.com")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6,ja;q=0.5")
                .build();

        Response response = client.newCall(request).execute();
        ObjectMapper objectMapper = new ObjectMapper();
        assert response.body() != null;
        Map<String, Object> responseBody = objectMapper.readValue(response.body().string(), Map.class);
        if (responseBody.get("status").equals("true") && responseBody.get("msg").equals("success")) {
            log.setErrorMessage(null);
            log.setStatus(1);
            log.setRemark("章节测验完成: " + jobTitle);
            log.setEndTime(LocalDateTime.now());
            log.setMachineNum(serverInfoUtil.getCurrentServerInstance());
            superStarLogService.saveLog(log);
        } else {
            log.setErrorMessage("章节测验提交失败" + jobTitle);
            log.setStatus(5);
            log.setRemark("章节测验提交失败: " + jobTitle);
            log.setMachineNum(serverInfoUtil.getCurrentServerInstance());
            superStarLogService.saveLog(log);
        }
    }


}

