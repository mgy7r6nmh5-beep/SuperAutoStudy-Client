package com.tihai.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.fontbox.ttf.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 超星题目字体反爬解码器。
 *
 * <p>超星会把题干和选项里的字换成「显示成 A 但码点是 B」的字体。页面里
 * {@code <style id="cxSecretStyle">} 塞了一个 base64 的 TTF，每个码点对应的
 * 字形轮廓是**真字**的轮廓。所以只要把页面字体的每个字形算一遍轮廓哈希，
 * 再去 {@code font_map_table.json}（真字 → 轮廓哈希）里反查，就知道每个
 * 码点实际该显示成什么字。
 *
 * <p>2026-10 在「南京信息工程大学2026-2027学年劳动理论教育 / 1.1」实测：
 * 26 个字形算出 25 个有效哈希，全部命中映射表，
 * 「普通高等掣校要明确劳动稤育…」→「普通高等学校要明确劳动教育…」。
 *
 * <p>原始实现来自上游 DuanInnovator/SuperAutotudy（Apache-2.0），v1.0.8
 * 移除答题功能时被一起删掉，这里按原算法恢复。
 */
public class FontDecoder {

    private static final String FONT_MAP_TABLE_FILE_PATH = "font_map_table.json";

    /** 康熙部首 → 正常汉字。超星有时用部首区码点当混淆字，解码后还要再换一次。 */
    private static final Map<Integer, Character> KX_RADICALS_TAB = new HashMap<>();

    static {
        String keys = "⼀⼁⼂⼃⼄⼅⼆⼇⼈⼉⼊⼋⼌⼍⼎⼏⼐⼑⼒⼓⼔⼕⼖⼗⼘⼙⼚⼛⼜⼝⼞⼟⼠⼡⼢⼣⼤⼥⼦⼧⼨⼩⼪⼫⼬⼭⼮⼯⼰⼱⼲⼳⼴⼵⼶⼷⼸⼹⼺⼻⼼⼽⼾⼿⽀⽁⽂⽃⽄⽅⽆⽇⽈⽉⽊⽋⽌⽍⽎⽏⽐⽑⽒⽓⽔⽕⽖⽗⽘⽙⽚⽛⽜⽝⽞⽟⽠⽡⽢⽣⽤⽥⽦⽧⽨⽩⽪⽫⽬⽭⽮⽯⽰⽱⽲⽳⽴⽵⽶⽷⽸⽹⽺⽻⽼⽽⽾⽿⾀⾁⾂⾃⾄⾅⾆⾇⾈⾉⾊⾋⾌⾍⾎⾏⾐⾑⾒⾓⾔⾕⾖⾗⾘⾙⾚⾛⾜⾝⾞⾟⾠⾡⾢⾣⾤⾥⾦⾧⾨⾩⾪⾫⾬⾭⾮⾯⾰⾱⾲⾳⾴⾵⾶⾷⾸⾹⾺⾻⾼髙⾽⾾⾿⿀⿁⿂⿃⿄⿅⿆⿇⿈⿉⿊⿋⿌⿍⿎⿏⿐⿑⿒⿓⿔⿕⺠⻬⻩⻢⻜⻅⺟⻓";
        String values = "一丨丶丿乙亅二亠人儿入八冂冖冫几凵刀力勹匕匚匸十卜卩厂厶又口囗土士夂夊夕大女子宀寸小尢尸屮山巛工己巾干幺广廴廾弋弓彐彡彳心戈戶手支攴文斗斤方无日曰月木欠止歹殳毋比毛氏气水火爪父爻爿片牙牛犬玄玉瓜瓦甘生用田疋疒癶白皮皿目矛矢石示禸禾穴立竹米糸缶网羊羽老而耒耳聿肉臣自至臼舌舛舟艮色艸虍虫血行衣襾見角言谷豆豕豸貝赤走足身車辛辰辵邑酉采里金長門阜隶隹雨青非面革韋韭音頁風飛食首香馬骨高高髟鬥鬯鬲鬼魚鳥鹵鹿麥麻黃黍黑黹黽鼎鼓鼠鼻齊齒龍龜龠民齐黄马飞见母长";
        for (int i = 0; i < keys.length(); i++) {
            KX_RADICALS_TAB.put((int) keys.charAt(i), values.charAt(i));
        }
    }

    /**
     * 映射表 DAO。表本身是「字形名 → 轮廓哈希」，这里建一份反向索引。
     * 表在 classpath 里（1.6 MB / 30884 条），静态加载一次就够。
     */
    public static class FontHashDAO {
        private final Map<String, String> charMap;
        private final Map<String, String> hashMap;

        public FontHashDAO(String filePath) throws IOException {
            ObjectMapper mapper = new ObjectMapper();
            InputStream in = getClass().getClassLoader().getResourceAsStream(filePath);
            if (in == null) {
                throw new FileNotFoundException("File not found in classpath: " + filePath);
            }
            charMap = mapper.readValue(in, new TypeReference<Map<String, String>>() {
            });
            hashMap = new HashMap<>();
            for (Map.Entry<String, String> e : charMap.entrySet()) {
                hashMap.put(e.getValue(), e.getKey());
            }
        }

        public String findChar(String fontHash) {
            return hashMap.get(fontHash);
        }
    }

    private static final FontHashDAO FONT_HASH_DAO;

    static {
        try {
            FONT_HASH_DAO = new FontHashDAO(FONT_MAP_TABLE_FILE_PATH);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * 字形轮廓 → MD5。
     *
     * <p>逐条轮廓、逐点拼「x + y + 是否在线」，再整体 MD5。和上游实现保持
     * 一字不差，因为映射表就是按这个算法预先算好的 —— 改一个字符就对不上。
     */
    public static String hashGlyph(GlyphDescription desc) {
        StringBuilder sb = new StringBuilder();
        int last = 0;
        int contourCount = desc.getContourCount();
        for (int i = 0; i < contourCount; i++) {
            int endPt = desc.getEndPtOfContours(i);
            for (int j = last; j <= endPt; j++) {
                sb.append(desc.getXCoordinate(j))
                        .append(desc.getYCoordinate(j))
                        .append(desc.getFlags(j) & 0x01);
            }
            last = endPt + 1;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(sb.toString().getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /** 解析 base64 / 文件里的字体，生成「uni码点 → 轮廓哈希」。 */
    public static Map<String, String> font2map(String fontData) throws IOException {
        byte[] bytes;
        if (fontData.startsWith("data:")) {
            String b64 = fontData.substring(fontData.indexOf(',') + 1);
            bytes = Base64.getDecoder().decode(b64);
        } else {
            bytes = java.nio.file.Files.readAllBytes(new java.io.File(fontData).toPath());
        }
        try (InputStream is = new ByteArrayInputStream(bytes)) {
            TrueTypeFont font = new TTFParser(false).parse(is);
            CmapTable cmapTable = font.getCmap();
            CmapSubtable cmapSubtable = cmapTable.getSubtable(
                    CmapTable.PLATFORM_WINDOWS, CmapTable.ENCODING_WIN_UNICODE_BMP);

            Map<String, String> map = new HashMap<>();
            GlyphTable glyphTable = font.getGlyph();
            GlyphData[] glyphs = glyphTable.getGlyphs();

            if (cmapSubtable != null) {
                for (char c = 0; c < 0xFFFF; c++) {
                    int glyphId = cmapSubtable.getGlyphId(c);
                    if (glyphId == 0 || glyphId >= glyphs.length) {
                        continue;
                    }
                    GlyphData gd = glyphs[glyphId];
                    if (gd == null || gd.getDescription() == null) {
                        continue;
                    }
                    map.put("uni" + Integer.toHexString(c).toUpperCase(),
                            hashGlyph(gd.getDescription()));
                }
            }
            font.close();
            return map;
        }
    }

    /** 把一段混淆文本还原成真字。 */
    public static String decrypt(Map<String, String> dstMap, String dstStr) {
        if (dstStr == null || dstStr.isEmpty()) {
            return "";
        }
        StringBuilder ori = new StringBuilder();
        for (char c : dstStr.toCharArray()) {
            String hash = dstMap.get(String.format("uni%04X", (int) c));
            String name = hash == null ? null : FONT_HASH_DAO.findChar(hash);
            // 映射表里大部分是 uni4E00 这种码点名，但也混着 A / Aacute / .notdef
            // 这类拉丁字形名。上游实现直接 substring(3) 再 parseInt，碰到拉丁名会
            // 抛 NumberFormatException 把整页解码炸掉 —— 这里只认 uniXXXX。
            if (name != null && name.length() > 3 && name.regionMatches(true, 0, "uni", 0, 3)
                    && name.substring(3).matches("[0-9A-Fa-f]{4,5}")) {
                ori.append((char) Integer.parseInt(name.substring(3), 16));
            } else {
                // 没命中就原样保留：未混淆的字符（数字、标点、英文）本来就该保留
                ori.append(c);
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < ori.length(); i++) {
            char ch = ori.charAt(i);
            out.append(KX_RADICALS_TAB.getOrDefault((int) ch, ch));
        }
        return out.toString();
    }

    private final Map<String, String> fontMap;

    public FontDecoder(String html) throws IOException {
        Document doc = Jsoup.parse(html);
        Element style = doc.getElementById("cxSecretStyle");
        if (style == null) {
            throw new IllegalArgumentException("页面里没有 #cxSecretStyle，这题可能没有字体混淆");
        }
        Matcher m = Pattern.compile("base64,([\\w\\W]+?)'").matcher(style.html());
        if (!m.find()) {
            throw new IllegalArgumentException("cxSecretStyle 里没有 base64 字体数据");
        }
        this.fontMap = font2map("data:application/font-ttf;charset=utf-8;base64," + m.group(1));
    }

    public String decode(String target) {
        return decrypt(this.fontMap, target);
    }
}
