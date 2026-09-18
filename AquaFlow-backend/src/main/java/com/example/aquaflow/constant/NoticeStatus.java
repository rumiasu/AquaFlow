package com.example.aquaflow.constant;

/**
 * 公告发布状态（`notice.status`）。
 *
 * <p>[2026-09-18 立] 站长端「营业状态与公告」页此前在**前端**写了一句
 * {@code n.status === 1 ? '已发布' : '草稿'} —— 正是本仓明令禁止的「前端自带映射表」
 * （{@code PayMethod} 的文件头记着它导致的真实事故：两端各写一套，后端改口径前端不跟随）。
 * 文案的唯一真相源在这里，由 {@link com.example.aquaflow.entity.Notice#getStatusText()} 下发。</p>
 */
public class NoticeStatus {

    /** 0 下架（草稿：只有本站站长可见，顾客端读不到） */
    public static final Integer OFFLINE = 0;

    /** 1 发布（顾客端与员工端都能读到） */
    public static final Integer PUBLISHED = 1;

    /** 状态中文文案（全系统唯一来源，前端禁止自行维护 status → 文案 映射表） */
    public static String textOf(Integer status) {
        if (status == null) return "未知";
        return PUBLISHED.equals(status) ? "已发布" : "草稿";
    }

    /** 是否已发布。判据只此一处，别在业务代码里散写 {@code status == 1} */
    public static boolean isPublished(Integer status) {
        return PUBLISHED.equals(status);
    }

    private NoticeStatus() {}
}
