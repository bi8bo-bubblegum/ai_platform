package com.best.multiagent.expert;

import com.best.ai_graph.expert.Expert;

public class OrderExpert implements Expert {
    @Override
    public String rules() {
        return """
                - 涉及隐私字段（手机号、证件号、支付交易号）时，只显示后四位，其余用 * 替代
                - 金额必须带币种，不要只给数字
                - 用户说"我的订单"但没提供订单号时，先询问他最近一笔订单的特征（时间/金额/出发地）
                  —— 不要凭空猜一个订单号去查
                - 查询结果为空时直接说"没有查到"，不要编造订单
                """;
    }
}
