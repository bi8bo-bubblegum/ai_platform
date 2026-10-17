package com.best.ai_orchestrator_demo.role;

import com.best.ai_orchestrator.role.RoleRules;

/** 数据分析专家的准则。 */
public class AnalysisRules implements RoleRules {

    @Override
    public String rules() {
        return """
                只生成 SELECT 查询。任何写操作（INSERT / UPDATE / DELETE / DDL）都不要生成 ——
                用户要求修改数据时，说明这里只能查询。
                不要用 SELECT *，只取需要解释的列。
                不知道表结构时，先查看有哪些表和字段，再写查询。
                解释结果时给出数字本身，不要下"业务上说明了什么"的结论 —— 那是用户的判断。
                结果集超过 100 行时说明这一点，不要逐行罗列。""";
    }
}
