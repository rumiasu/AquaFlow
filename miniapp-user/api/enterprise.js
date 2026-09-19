const { get, post } = require('../utils/request')
const { API } = require('../config/api')

// 企业身份申请（v50）—— 顾客自助的两个端点。
//
// 产品口径是「不做独立入口」：客户不会在「我的」里找到"申请企业身份"，
// 只能是下单时金额达到阈值、报价下发 `enterpriseHint` 之后，由那个弹窗带进来
// （见 pages/order/create.js 的 maybePromptEnterprise）。**别顺手加一个入口按钮** ——
// 那正是当初被否掉的方案。
//
// stationId 必须显式传：客户身份是全局的，企业身份是**按站**审核的
// （申请行上有 station_id，站长只审本站那批）。
//
// 说明：本文件顶部刻意不用 /** 块注释 —— 它会紧挨着下面的函数 javadoc，
// 被 audit_comments.py 判为「悬空 javadoc」（无归属的注释块会误导读代码的人）。

/** 提交企业身份申请（幂等：同一站已有待审时，后端直接返回那一条，不会产生第二条）。 */
const submitEnterpriseApply = (data) => post(API.ENTERPRISE_APPLICATIONS, data)

/** 我在某站的申请（最近的几条）：下单页用它判断"已经申请过、还在等审核"，避免反复弹窗。 */
const getMyEnterpriseApplies = (stationId) => get(API.ENTERPRISE_APPLICATIONS_MY, { stationId })

module.exports = { submitEnterpriseApply, getMyEnterpriseApplies }
