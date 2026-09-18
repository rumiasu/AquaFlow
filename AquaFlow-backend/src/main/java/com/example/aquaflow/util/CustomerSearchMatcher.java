package com.example.aquaflow.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 站长端客户搜索的<b>归一化 + 相关性打分</b>（<b>全仓唯一实现</b>）。
 *
 * <p><b>为什么要它</b>（2026-09-18 站长原话转述）：站长搜客户"更注重地址，最多记个姓氏，
 * 不关注客户名字 —— 地址其实更能指代人"。他们的输入习惯是：</p>
 * <ul>
 *   <li><b>缩写</b>：「阳光小区8栋1单元301」打成「阳光81301」；</li>
 *   <li><b>不爱打空格/标点</b>：「8-1-301」与「81301」等价；</li>
 *   <li><b>中文数字与阿拉伯数字混用</b>：「八栋」与「8栋」是同一个地址。</li>
 * </ul>
 * <p>所以这里<b>不做强子串匹配</b>：两侧先归一化，再按"关联性"打分，取阈值以上者按分降序。</p>
 *
 * <p>⚠️ <b>唯一的调用方是 {@code CustomerServiceImpl}</b>（客户列表 / 代客下单选择器的候选集都经它打分）。
 * 任何"再写一份 LIKE 或再写一套打分"的做法都会重演本仓的<b>计价双轨</b>事故
 * （两条路径各算一套，界面与落库长期不一致），新增搜索入口请复用本类。</p>
 *
 * <h3>归一化规则（顺序即实现顺序）</h3>
 * <ol>
 *   <li>全角 → 半角（含全角空格）；</li>
 *   <li>英文大小写统一（{@code Locale.ROOT}，避免土耳其语 i 问题）；</li>
 *   <li>中文数字 → 阿拉伯数字（{@code 八栋} → {@code 8栋}、{@code 十二栋} → {@code 12栋}、
 *       {@code 三百零一室} → {@code 301室}）；</li>
 *   <li>去结构词/通名（名单与理由见 {@link #STRUCTURAL_CHARS}）；</li>
 *   <li>只保留字母、数字、汉字（标点、空格、{@code - / #} 一律丢弃）。</li>
 * </ol>
 *
 * <h3>打分规则（{@code scoreField}，逐条可解释）</h3>
 * <ul>
 *   <li><b>整体包含</b>（归一化后关键字是字段的子串）→ {@value #CONTAIN_SCORE} 分；
 *       这是最强证据（"阳光81301" ⊂ "阳光81301"）。</li>
 *   <li>否则 → {@code round(}{@value #PARTIAL_MAX}{@code × (}{@value #COVERAGE_WEIGHT}{@code × 字符覆盖率
 *       + }{@value #ORDER_WEIGHT}{@code × 最长公共子序列比))}：
 *       覆盖率回答"关键字里的字到了几个"（缩写省略的是结构词，字应全在），
 *       顺序比回答"到的字是否保持原顺序"（防止同几个字乱序堆砌也算命中）。
 *       部分命中封顶 {@value #PARTIAL_MAX}，保证"整体包含"永远排在"拼出来"的前面。</li>
 *   <li>候选的最终分 = 姓名 / 电话 / 地址三个字段各自得分的<b>最大值</b>；
 *       同分时按 {@code 姓名 > 电话 > 地址} 排（地址是"间接指代人"，姓名电话才是身份标识）。</li>
 *   <li>低于阈值 {@value #MIN_SCORE} 视为不相关、直接丢弃（<b>不是</b>"返回全部"）。</li>
 * </ul>
 *
 * <h3>阈值为什么是 {@value #MIN_SCORE}（而不是拍脑袋）</h3>
 * <p>非包含命中要拿到 {@value #MIN_SCORE} 分，需要
 * {@code 0.6×覆盖率 + 0.4×顺序比 ≥ 2/3}，即<b>关键字里约 2/3 的证据必须同时"出现过"且"顺序对"</b>。
 * 于是：全字命中但乱序（覆盖率 1.0、顺序比 0.6）得 76 分（保留）；
 * 四个字里只对上一个（覆盖率 0.25、顺序比 0.25）得 23 分（丢弃）。
 * 参数化查询注入串（如 {@code ' OR 1=1 --} 归一化后是 {@code or11}）正是被这一档挡掉的，
 * 回归用例见 {@code OrderEntryAndInjectionIntegrationTest}。</p>
 *
 * <h3>性能边界（实测）</h3>
 * <p>纯函数、无 IO，成本 = O(候选数 × 字段数 × 字段长度)。本机实测（JDK 17，单次 {@link #rank}，
 * 合成候选：姓名 + 11 位手机号 + 一条「阳光小区N栋N单元N室」地址）：</p>
 * <ul>
 *   <li>纯打分：<b>500 条 ≈ 1.2–2.4ms / 1000 条 ≈ 2.3–3.8ms / 3000 条 ≈ 12–19ms</b>；</li>
 *   <li>端到端（真实 MySQL + HTTP，测试库 3000 个本站客户各一条地址）：
 *       {@code GET /api/manager/order-assist/customers} ≈ <b>50–105ms</b>，
 *       客户列表（多一次画像聚合查询）≈ <b>56–71ms</b>。</li>
 * </ul>
 * <p>两处优化是"为什么不慢"的原因，改代码时别顺手删掉：
 * ① LCS 只在"覆盖率已够"时才算（{@code scoreOne} 的上界剪枝）；
 * ② 归一化每一趟在没有需要修改的字符时直接返回原串，避免每次打分都重建 5 个中间串。
 * 再往上（单站客户破万）就该换检索引擎，而不是加大 {@link #MAX_CANDIDATES}。</p>
 */
public final class CustomerSearchMatcher {

    /**
     * 候选上限：SQL 粗筛一次最多取回多少条本站客户。
     * <p>产品口径给定"站长端客户量级是几百到几千"，3000 覆盖该上界；
     * 超过上限时按建档倒序截断（新客户优先保住）并打 WARN，接口仍正常返回。</p>
     */
    public static final int MAX_CANDIDATES = 3000;

    /** 单次返回上限：命中很多时只给最相关的前 N 条（站长端列表不需要一次看几百条） */
    public static final int MAX_RESULTS = 50;

    /** 命中阈值：低于它视为不相关（推导见类注释） */
    public static final int MIN_SCORE = 60;

    /** 整体包含的得分 */
    private static final int CONTAIN_SCORE = 100;

    /** 非整体包含的封顶分，必须小于 {@link #CONTAIN_SCORE} */
    private static final int PARTIAL_MAX = 90;

    /** 部分命中里"字符覆盖率"的权重，其余给"顺序保留率" */
    private static final double COVERAGE_WEIGHT = 0.6;

    /** 顺序保留率的权重（= 1 − {@link #COVERAGE_WEIGHT}） */
    private static final double ORDER_WEIGHT = 1 - COVERAGE_WEIGHT;

    /** 同分时的字段优先级：数字越小越靠前 */
    private static final int RANK_NAME = 1;
    private static final int RANK_PHONE = 2;
    private static final int RANK_ADDRESS = 3;

    /** 中文数字解析的上界：超过它说明这串根本不是数字（如「千万」），原样保留不猜 */
    private static final long MAX_NUMERAL = 999_999_999L;

    /**
     * 多字结构词：整体删除（先于单字执行）。
     *
     * <p>{@code 小区}、{@code 单元} 是"任何住宅地址都有"的通名，站长缩写时必省；
     * 删掉它们才能让「阳光81301」直接落进整串包含（100 分）。</p>
     */
    private static final String[] STRUCTURAL_WORDS = {"小区", "单元"};

    /**
     * 单字结构词/通名：楼栋门牌与行政区划的"格式字"。
     *
     * <p>判据是<b>"对所有候选一视同仁、零区分度"</b>：{@code 栋/幢/座/楼/层/室/房/号/梯/第}
     * 是编号的包装，{@code 省/市/区/县/镇/乡/街/路/道/巷/弄} 是行政区划通名。
     * 它们人人都有，删掉不会把两个不同的地址混成一个，反而让缩写更容易被"整体包含"判定接住。</p>
     *
     * <p><b>刻意不删的</b>（有区分度，删了就等于把不同小区混为一谈）：
     * {@code 村}（新村 ≠ 小区）、{@code 园/苑/花园/家园}（阳光花园 ≠ 阳光苑）、
     * {@code 城/广场/大厦/公寓/新村}。产品口径原话："别把有区分度的词也删掉"。</p>
     */
    private static final String STRUCTURAL_CHARS = "栋幢座楼层室房号梯第省市区县镇乡街路道巷弄";

    private CustomerSearchMatcher() {
    }

    // ==================== 对外入口 ====================

    /**
     * 关键字是否为空（空 = 不筛，按"最近建档"返回若干条）。
     *
     * <p>站长端首屏（代客下单选客户）不带关键字，这条路径必须保持"返回最近若干条"，
     * 不能因为"没输入"就返回空列表。</p>
     */
    public static boolean isBlank(String keyword) {
        return keyword == null || keyword.trim().isEmpty();
    }

    /**
     * 归一化（完整规则，含去结构词）。供打分内部使用，也便于排查"为什么这个关键字没命中"。
     */
    public static String normalize(String raw) {
        return compact(stripStructuralWords(numeralsToArabic(foldCase(toHalfWidth(raw)))));
    }

    /**
     * 归一化（<b>不去结构词</b>）：仅当完整归一化把关键字吃得一字不剩时的兜底。
     *
     * <p>站长真的会搜「小区」「单元」这种纯结构词，也会搜姓「楼」的客户；
     * 若一律返回空，表现就是"搜了没反应"，比"搜得宽一点"更难排查。</p>
     */
    public static String normalizeLoose(String raw) {
        return compact(numeralsToArabic(foldCase(toHalfWidth(raw))));
    }

    /**
     * 单个候选的相关性得分（0 = 不相关）。
     *
     * <p>公开出来是为了<b>可测、可解释</b>：{@link #rank} 用的是同一套判据，
     * 用例可以直接断言"这个关键字对这个地址得几分"。</p>
     */
    public static int score(String keyword, String name, String phone, String address) {
        String strict = normalize(keyword);
        boolean loose = strict.isEmpty();
        String k = loose ? normalizeLoose(keyword) : strict;
        if (k.isEmpty()) {
            return 0;
        }
        return bestOf(k, loose, name, phone, address)[0];
    }

    /**
     * 候选排序：过滤掉低于阈值的，按"得分降序 → 字段优先级（姓名 &gt; 电话 &gt; 地址）"返回前 {@code limit} 条。
     *
     * <p>排序是<b>稳定</b>的：完全同分同优先级的候选保留入参顺序（SQL 给的是建档倒序），
     * 所以同一个关键字两次请求的结果可复现。入参 {@code candidates} 不会被修改。</p>
     *
     * @param nameOf    取姓名的函数；{@code null} 安全
     * @param phoneOf   取电话的函数
     * @param addressOf 取地址文本的函数（可含多条地址，用换行拼接）
     * @param limit     &le;0 时取 {@link #MAX_RESULTS}
     */
    public static <T> List<T> rank(String keyword, List<T> candidates,
                                   Function<T, String> nameOf,
                                   Function<T, String> phoneOf,
                                   Function<T, String> addressOf,
                                   int limit) {
        List<T> out = new ArrayList<>();
        if (candidates == null || candidates.isEmpty()) {
            return out;
        }
        String strict = normalize(keyword);
        boolean loose = strict.isEmpty();
        String k = loose ? normalizeLoose(keyword) : strict;
        if (k.isEmpty()) {
            // 关键字全是标点（如 "--"）：没有任何证据可用，返回空而不是返回全部
            return out;
        }
        int cap = limit <= 0 ? MAX_RESULTS : Math.min(limit, MAX_RESULTS);

        List<Hit<T>> hits = new ArrayList<>();
        for (T candidate : candidates) {
            // prune = true：这里只需要"够不够阈值"，算不准的那一档会被直接丢掉（见 scoreOne）
            int[] best = bestOf(k, loose, nameOf.apply(candidate), phoneOf.apply(candidate),
                    addressOf.apply(candidate), true);
            if (best[0] >= MIN_SCORE) {
                hits.add(new Hit<>(candidate, best[0], best[1]));
            }
        }
        hits.sort(Comparator.comparingInt((Hit<T> h) -> h.score).reversed()
                .thenComparingInt(h -> h.fieldRank));

        for (int i = 0; i < hits.size() && out.size() < cap; i++) {
            out.add(hits.get(i).item);
        }
        return out;
    }

    // ==================== 打分 ====================

    /** @return {@code [得分, 字段优先级]}；得分取三个字段的最大值，优先级取拿到最大分的那个字段 */
    private static int[] bestOf(String k, boolean loose, String name, String phone, String address) {
        return bestOf(k, loose, name, phone, address, false);
    }

    /**
     * @param prune 是否允许"证明到不了阈值就跳过 LCS"这一步优化。
     *              {@link #rank} 传 true（结果只用于筛人），公开的 {@link #score} 传 false（要给准确分数）。
     */
    private static int[] bestOf(String k, boolean loose, String name, String phone, String address, boolean prune) {
        int scoreName = scoreOne(k, normalizeField(name, loose), prune);
        int scorePhone = scoreOne(k, normalizeField(phone, loose), prune);
        int scoreAddress = scoreOne(k, normalizeField(address, loose), prune);
        int best = Math.max(scoreName, Math.max(scorePhone, scoreAddress));
        // 同分时姓名优先于电话、电话优先于地址（地址是间接指代人）
        int rank = scoreName == best ? RANK_NAME : (scorePhone == best ? RANK_PHONE : RANK_ADDRESS);
        return new int[]{best, rank};
    }

    private static String normalizeField(String raw, boolean loose) {
        return loose ? normalizeLoose(raw) : normalize(raw);
    }

    private static int scoreOne(String k, String field, boolean prune) {
        if (k.isEmpty() || field.isEmpty()) {
            return 0;
        }
        if (field.contains(k)) {
            return CONTAIN_SCORE;
        }
        double coverage = charCoverage(k, field);
        if (prune) {
            // LCS 是打分里唯一"贵"的一步（O(|关键字|×|字段|)），而顺序比最大就是 1：
            // 把顺序比按 1 代进去仍到不了阈值时，这个字段无论顺序多好都救不回来 → 直接跳过 LCS。
            // ⚠️ 此时返回的是**上界**（≤ 真实分）。它只用于"是否丢弃"的判断，不会进排序：
            // 走这个分支返回值必然 < MIN_SCORE，永远进不了 rank 的结果集。
            int upperBound = (int) Math.round(PARTIAL_MAX * (COVERAGE_WEIGHT * coverage + ORDER_WEIGHT));
            if (upperBound < MIN_SCORE) {
                return upperBound;
            }
        }
        double order = longestCommonSubsequence(k, field) / (double) k.length();
        return (int) Math.round(PARTIAL_MAX * (COVERAGE_WEIGHT * coverage + ORDER_WEIGHT * order));
    }

    /** 字符覆盖率（按多重集合算：关键字里两个相同的字，候选里只有一个就只算一个） */
    private static double charCoverage(String k, String field) {
        int matched = 0;
        StringBuilder seen = new StringBuilder();
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            if (seen.indexOf(String.valueOf(c)) >= 0) {
                continue;
            }
            seen.append(c);
            matched += Math.min(countChar(k, c), countChar(field, c));
        }
        return matched / (double) k.length();
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /** 最长公共子序列长度（滚动数组，空间 O(|b|)） */
    private static int longestCommonSubsequence(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = a.charAt(i - 1) == b.charAt(j - 1)
                        ? prev[j - 1] + 1
                        : Math.max(prev[j], cur[j - 1]);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
            java.util.Arrays.fill(cur, 0);
        }
        return prev[b.length()];
    }

    // ==================== 归一化 ====================

    /** 全角 → 半角（U+FF01~U+FF5E 平移 0xFEE0；全角空格单独处理）。无变化时原样返回，省一次分配 */
    private static String toHalfWidth(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder sb = null;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            char out = c;
            if (c == '\u3000') {
                out = ' ';
            } else if (c >= '\uFF01' && c <= '\uFF5E') {
                out = (char) (c - 0xFEE0);
            }
            if (out != c && sb == null) {
                sb = new StringBuilder(raw.length());
                sb.append(raw, 0, i);
            }
            if (sb != null) {
                sb.append(out);
            }
        }
        return sb == null ? raw : sb.toString();
    }

    /** 英文大小写统一。无大写字母时原样返回（地址里绝大多数串都没有），省一次分配 */
    private static String foldCase(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isUpperCase(s.charAt(i))) {
                // 用 Locale.ROOT：默认 Locale 在土耳其语环境下会把 I 变成 ı
                return s.toLowerCase(Locale.ROOT);
            }
        }
        return s;
    }

    /** 去结构词：先删多字词，再删单字（「单元」必须先于单字，否则「1单元301」会被切碎） */
    private static String stripStructuralWords(String s) {
        String out = s;
        for (String word : STRUCTURAL_WORDS) {
            if (out.indexOf(word) >= 0) {
                out = out.replace(word, "");
            }
        }
        if (out.isEmpty()) {
            return out;
        }
        int hit = -1;
        for (int i = 0; i < out.length() && hit < 0; i++) {
            if (STRUCTURAL_CHARS.indexOf(out.charAt(i)) >= 0) {
                hit = i;
            }
        }
        if (hit < 0) {
            return out;
        }
        StringBuilder sb = new StringBuilder(out.length());
        sb.append(out, 0, hit);
        for (int i = hit; i < out.length(); i++) {
            char c = out.charAt(i);
            if (STRUCTURAL_CHARS.indexOf(c) < 0) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 只保留字母/数字/汉字：标点、空格、{@code - / #} 一律丢弃（「8-1-301」→「81301」）。无变化时原样返回 */
    private static String compact(String s) {
        int hit = -1;
        for (int i = 0; i < s.length() && hit < 0; i++) {
            if (!Character.isLetterOrDigit(s.charAt(i))) {
                hit = i;
            }
        }
        if (hit < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        sb.append(s, 0, hit);
        for (int i = hit; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 中文数字 → 阿拉伯数字（逐"数字串"处理，遇到非数字字符就断开）。
     *
     * <p>两种写法都要对：<b>逐位写法</b>（{@code 三零一} → {@code 301}、{@code 一单元} → {@code 1}）与
     * <b>带单位写法</b>（{@code 十二} → {@code 12}、{@code 二十三} → {@code 23}、{@code 三百零一} → {@code 301}）。</p>
     *
     * <p>{@code 万/亿} <b>刻意不当作单位</b>：它们更常出现在名字里（「万客隆」「万一」），
     * 当单位会把名字改成数字；丢了这两个单位最多是"「一万」不换算"，代价可接受。
     * 换算只影响<b>两侧同时归一化</b>的比较，所以"两侧都写成汉字"或"都写成数字"都能命中。</p>
     */
    private static String numeralsToArabic(String s) {
        int first = -1;
        for (int i = 0; i < s.length() && first < 0; i++) {
            if (isNumeralChar(s.charAt(i))) {
                first = i;
            }
        }
        if (first < 0) {
            // 一个数字字符都没有（"月光花园" 这类）—— 原样返回，省掉一次整体重建
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        out.append(s, 0, first);
        int i = first;
        while (i < s.length()) {
            if (!isNumeralChar(s.charAt(i))) {
                out.append(s.charAt(i++));
                continue;
            }
            int j = i;
            while (j < s.length() && isNumeralChar(s.charAt(j))) {
                j++;
            }
            out.append(convertNumeralRun(s, i, j));
            i = j;
        }
        return out.toString();
    }

    /** 转换 {@code s[from, to)} 这一段连续的数字字符（不切子串，避免每次打分都多一次分配） */
    private static String convertNumeralRun(String s, int from, int to) {
        boolean hasUnit = false;
        for (int i = from; i < to; i++) {
            if (cnUnit(s.charAt(i)) > 0) {
                hasUnit = true;
                break;
            }
        }
        StringBuilder sb = new StringBuilder(to - from);
        if (!hasUnit) {
            // 逐位写法：一 → 1、八 → 8、三零一 → 301（阿拉伯数字原样保留）
            for (int i = from; i < to; i++) {
                char c = s.charAt(i);
                int d = cnDigit(c);
                sb.append(d >= 0 ? (char) ('0' + d) : c);
            }
            return sb.toString();
        }
        long total = 0;
        long current = 0;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            int d = cnDigit(c);
            if (d >= 0) {
                current = d;
                continue;
            }
            if (c >= '0' && c <= '9') {
                current = current * 10 + (c - '0');
                continue;
            }
            int unit = cnUnit(c);
            if (unit > 0) {
                // 「十二」= 12、「十」= 10：单位前没写数字时按 1 算
                if (current == 0) {
                    current = 1;
                }
                total += current * unit;
                current = 0;
            }
        }
        total += current;
        if (total <= 0 || total > MAX_NUMERAL) {
            // 解析不出合理数值就原样保留 —— 宁可匹配不上，也不要猜出一个错的数字
            return s.substring(from, to);
        }
        return Long.toString(total);
    }

    private static boolean isNumeralChar(char c) {
        return cnDigit(c) >= 0 || cnUnit(c) > 0 || (c >= '0' && c <= '9');
    }

    private static int cnDigit(char c) {
        switch (c) {
            case '零': case '〇': case '○': return 0;
            case '一': case '壹': return 1;
            case '二': case '贰': case '两': return 2;
            case '三': case '叁': return 3;
            case '四': case '肆': return 4;
            case '五': case '伍': return 5;
            case '六': case '陆': return 6;
            case '七': case '柒': return 7;
            case '八': case '捌': return 8;
            case '九': case '玖': return 9;
            default: return -1;
        }
    }

    private static int cnUnit(char c) {
        switch (c) {
            case '十': case '拾': return 10;
            case '百': case '佰': return 100;
            case '千': case '仟': return 1000;
            default: return -1;
        }
    }

    /** 带分数与字段优先级的中间结果（{@link #rank} 内部用） */
    private static final class Hit<T> {
        private final T item;
        private final int score;
        private final int fieldRank;

        private Hit(T item, int score, int fieldRank) {
            this.item = item;
            this.score = score;
            this.fieldRank = fieldRank;
        }
    }
}
