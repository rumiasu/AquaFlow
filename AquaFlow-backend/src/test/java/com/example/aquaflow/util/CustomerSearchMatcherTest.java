package com.example.aquaflow.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CustomerSearchMatcher} 的纯函数用例（不起 Spring，毫秒级）。
 *
 * <p>集成口径（HTTP 端点、站隔离、真实 SQL）见
 * {@code integration/CustomerAddressSearchIntegrationTest}；本类只钉<b>归一化规则与阈值</b>，
 * 因为它们最容易被"顺手调一下"改坏，而且改坏了接口照样返回 200 —— 只是搜索悄悄变宽/变窄。</p>
 */
@DisplayName("客户搜索 · 归一化与相关性打分（纯函数）")
class CustomerSearchMatcherTest {

    /** 候选（姓名/电话/地址三元组），只为把 rank 的入参形状写得可读 */
    private record Cand(long id, String name, String phone, String address) {
    }

    private static List<Cand> rank(String keyword, Cand... candidates) {
        return CustomerSearchMatcher.rank(keyword, List.of(candidates),
                Cand::name, Cand::phone, Cand::address, 0);
    }

    // ==================== 归一化 ====================

    @Test
    @DisplayName("归一化：结构词被删掉，『阳光小区8栋1单元301』压成『阳光81301』")
    void normalizeSqueezesStructuralWords() {
        assertEquals("阳光81301", CustomerSearchMatcher.normalize("阳光小区8栋1单元301"));
        // 站长还会写这些等价形式，必须都归一到同一个串
        assertEquals("阳光81301", CustomerSearchMatcher.normalize("阳光 小区 8栋 1单元 301室"));
        assertEquals("阳光81301", CustomerSearchMatcher.normalize("阳光小区8栋-1单元-301"));
        assertEquals("阳光81301", CustomerSearchMatcher.normalize("阳光小区8幢1单元301房"));
    }

    @Test
    @DisplayName("归一化：中文数字与阿拉伯数字混用等价（八栋 = 8栋 = 第八栋）")
    void normalizeConvertsChineseNumerals() {
        assertEquals("阳光8", CustomerSearchMatcher.normalize("阳光八栋"));
        assertEquals("阳光8", CustomerSearchMatcher.normalize("阳光8栋"));
        assertEquals("阳光8", CustomerSearchMatcher.normalize("阳光第八栋"));
        assertEquals("12", CustomerSearchMatcher.normalize("十二栋"));
        assertEquals("23", CustomerSearchMatcher.normalize("二十三号楼"));
        assertEquals("301", CustomerSearchMatcher.normalize("三百零一室"));
        assertEquals("301", CustomerSearchMatcher.normalize("三零一室"));
        assertEquals("2", CustomerSearchMatcher.normalize("两栋"));
        // 全角数字 + 大写数字
        assertEquals("301", CustomerSearchMatcher.normalize("３０１室"));
        assertEquals("12", CustomerSearchMatcher.normalize("壹拾贰栋"));
    }

    @Test
    @DisplayName("归一化：全角半角、大小写、标点空格统一")
    void normalizeUnifiesWidthCaseAndPunctuation() {
        assertEquals("abc123", CustomerSearchMatcher.normalize("ＡＢＣ－１２３"));
        assertEquals("81301", CustomerSearchMatcher.normalize("8-1-301"));
        assertEquals("81301", CustomerSearchMatcher.normalize("8 1 301"));
        assertEquals("", CustomerSearchMatcher.normalize(null));
        assertTrue(CustomerSearchMatcher.isBlank("   "));
        assertFalse(CustomerSearchMatcher.isBlank("王"));
    }

    @Test
    @DisplayName("归一化：有区分度的词必须留着 —— 新村≠小区、花园≠苑，『万客隆』不被吃成数字")
    void normalizeKeepsDistinguishingWords() {
        // 反例警戒：谁要把「村/园/苑/花园/城/广场/公寓/新村」加进结构词名单，
        // 这几条会先红 —— 那等于把"阳光新村"和"阳光小区"混成同一个人
        assertEquals("阳光新村", CustomerSearchMatcher.normalize("阳光新村"));
        assertEquals("阳光花园", CustomerSearchMatcher.normalize("阳光花园"));
        assertEquals("阳光苑", CustomerSearchMatcher.normalize("阳光苑"));
        assertEquals("阳光公寓", CustomerSearchMatcher.normalize("阳光公寓"));
        assertFalse(CustomerSearchMatcher.normalize("阳光新村")
                .equals(CustomerSearchMatcher.normalize("阳光小区")), "新村与小区不得归一成同一个串");
        // 万/亿刻意不当单位：当单位会把名字里的「万」换算成 10000（「万客隆」→「10000客隆」），
        // 而它们在名字里比在地址编号里常见得多（"丢了万/亿最多是一万不换算"，代价可接受）。
        assertEquals("万客隆", CustomerSearchMatcher.normalize("万客隆"));
        // 注意「一」仍按数字逐位处理，所以「万一」→「万1」。这不影响命中：
        // 查询与候选走的是同一套归一化，写法一致就必定互相命中（下面这条断言锁住这一点）
        assertEquals("万1", CustomerSearchMatcher.normalize("万一"));
        assertEquals(100, CustomerSearchMatcher.score("万一", "万一", null, null),
                "两侧同一套归一化：客户叫「万一」时，搜「万一」必须命中");
    }

    // ==================== 打分 ====================

    @Test
    @DisplayName("打分：缩写整体包含得满分，中间省掉一个词仍能高分命中")
    void scoreRewardsContainmentAndToleratesOmission() {
        assertEquals(100, CustomerSearchMatcher.score("阳光81301", null, null, "阳光小区8栋1单元301"));
        // 关键字里少写一个「花园」：不再是子串，但字全在、顺序对 → 90 分，仍然命中
        assertEquals(90, CustomerSearchMatcher.score("阳光301", null, null, "阳光花园8栋1单元301"));
        assertEquals(100, CustomerSearchMatcher.score("13800", null, "13800138000", null));
        assertEquals(100, CustomerSearchMatcher.score("王", "王小明", null, null));
        assertEquals(100, CustomerSearchMatcher.score("王", "李四", null, "王府井3号"));
    }

    @Test
    @DisplayName("阈值边界（两侧都钉死）：60 分刚好命中，54 分刚好不命中")
    void thresholdIsPinnedOnBothSides() {
        // ⚠️ 改 COVERAGE_WEIGHT / PARTIAL_MAX / MIN_SCORE 必须同步改这三条断言：
        // 它们是"搜索悄悄变宽或变窄"的唯一警报（接口在两种情况下都返回 200）。
        assertEquals(60, CustomerSearchMatcher.MIN_SCORE, "阈值本体的值被改了");

        // 刚好命中：3 个字里到了 2 个且顺序对（覆盖率 2/3、顺序比 2/3）→ 60 分（含等号）
        int justHit = CustomerSearchMatcher.score("阳光8", null, null, "月光88");
        assertEquals(60, justHit, "边界命中分变了：搜索范围被悄悄放大或收窄");
        assertEquals(1, rank("阳光8", new Cand(1L, "张三", null, "月光88")).size(),
                "等于阈值的候选必须返回（判据是 score >= MIN_SCORE）");

        // 刚好不命中：5 个字里到了 3 个且顺序对（0.6 / 0.6）→ 54 分 < 60
        // 业务含义：搜「阳光花园8」不该命中「阳光小区8」的客户 —— 那是两个小区，正是要保住的判断力
        int justMiss = CustomerSearchMatcher.score("阳光花园8", null, null, "阳光小区8栋");
        assertEquals(54, justMiss, "边界不命中分变了");
        assertTrue(justMiss < CustomerSearchMatcher.MIN_SCORE);
        assertTrue(rank("阳光花园8", new Cand(1L, "张三", null, "阳光小区8栋")).isEmpty(),
                "低于阈值的候选必须被丢掉，而不是'宁滥勿缺'地返回");
    }

    @Test
    @DisplayName("打分：字只对上一两个的输入（含注入串）必须被阈值挡掉")
    void scoreRejectsScrambledAndInjectedInput() {
        // 注入串归一化后是 "or11"：候选「张三 / 注入路1号」只有那个数字 1 对得上
        // （覆盖率 0.25、顺序比 0.25）→ 23 分，远低于阈值。
        // 这就是参数化查询之外的第二道闸：即使将来有人把 LIKE 换成拼接，注入串也命中不了客户
        assertEquals(23, CustomerSearchMatcher.score("' OR 1=1 --", "张三", null, "注入路1号"));
        assertTrue(CustomerSearchMatcher.score("' OR 1=1 --", "张三", null, "注入路1号")
                < CustomerSearchMatcher.MIN_SCORE);
        assertTrue(rank("zzz-no-such-thing", new Cand(1L, "张三", null, "阳光小区8栋1单元301")).isEmpty());
        assertTrue(rank("--", new Cand(1L, "张三", null, "阳光小区8栋1单元301")).isEmpty(),
                "关键字全是标点时应返回空，而不是返回全部");
    }

    // ==================== 排序 ====================

    @Test
    @DisplayName("排序：同分时姓名命中排在'仅地址命中'之前，且结果可复现")
    void rankPrefersNameHitOnTie() {
        Cand onlyAddress = new Cand(1L, "李四", null, "王府井3号");
        Cand byName = new Cand(2L, "王小明", null, null);
        assertEquals(List.of(byName, onlyAddress), rank("王", onlyAddress, byName),
                "两者都是 100 分，字段优先级必须是 姓名 > 地址");
        // 再排一次结果不变（稳定排序 + 不依赖入参顺序以外的随机因素）
        assertEquals(List.of(byName, onlyAddress), rank("王", onlyAddress, byName));
    }

    @Test
    @DisplayName("排序：同分同优先级保留入参顺序（SQL 给的是建档倒序），并受返回上限约束")
    void rankIsStableAndLimited() {
        Cand newer = new Cand(9L, "王小明", null, null);
        Cand older = new Cand(3L, "王大锤", null, null);
        assertEquals(List.of(newer, older), rank("王", newer, older), "同分时必须保留入参顺序");
        assertEquals(1, CustomerSearchMatcher.rank("王", List.of(newer, older),
                Cand::name, Cand::phone, Cand::address, 1).size(), "limit 必须生效");
    }

    // ==================== 兜底 ====================

    @Test
    @DisplayName("兜底：关键字被结构词吃干净时退到宽松归一化（搜『小区』不能一无所获）")
    void structuralOnlyKeywordFallsBackToLooseNormalization() {
        assertTrue(CustomerSearchMatcher.normalize("小区").isEmpty(), "完整归一化会把纯结构词吃干净");
        assertEquals("小区", CustomerSearchMatcher.normalizeLoose("小区"), "宽松归一化不去结构词");
        assertEquals(1, rank("小区", new Cand(1L, "张三", null, "阳光小区8栋1单元301")).size(),
                "搜『小区』应命中该小区的客户，而不是返回空");
        assertEquals(1, rank("八栋", new Cand(1L, "张三", null, "阳光八栋2单元")).size(),
                "汉字写法也要走同一条兜底/归一化路径");
    }
}
