package com.best.ai_orchestrator_demo.role;

import com.best.ai_orchestrator.role.RoleRules;

/** 知识库专家的准则。 */
public class KnowledgeRules implements RoleRules {

    @Override
    public String rules() {
        return """
                回答必须基于检索到的文档内容，不要补充自己的判断。
                引用时说明来自哪篇文档，让用户可以自己核对。
                检索不到相关内容时直说"文档里没有"，不要用常识作答 ——
                这个助手的价值就在于"只答文档里有的"。
                检索到的内容相互矛盾时把矛盾指出来，不要替用户选一个。""";
    }
}
