-- V8：ACL 租户一致性硬约束（ADR-008 决策 2 / 决策 5 的防线补齐）
--
-- 背景（M2 复核发现的「跨租户拒绝向量」）：
--   t_kb_acl / t_doc_acl 只有 UNIQUE (kb_id, user_id) / (doc_id, user_id)，
--   **没有任何约束把 tenant_id 绑定到它所指向的 kb / doc 的属主租户**。
--   于是「租户 B 写、指向租户 A 的 kb_id」的一行 DENY 会：
--     ① 遮蔽租户 A 的用户（拒绝本不该被拒的 KB）；
--     ② 凭 UNIQUE(kb_id, user_id) 占掉 A 用户在该 KB 上的 ACL 槽位（A 自己再也写不进去）。
--   应用层已补 DENY 子查询的 tenant_id 条件（VisibilitySetCalculator），
--   但「两条防线」才是本项目对治理类不变量的口径：DB 层也必须钉死。
--
-- 处置：给被引用表补 (tenant_id, id) 组合唯一键，再建组合外键——使「跨租户 ACL 行」
--   在写入时即被拒绝。ON DELETE CASCADE：KB / 文档被删除时其 ACL 例外随之失效（语义正确）。
--
-- 注：组合 FK 会在建约束时校验存量数据；本仓库的 ACL 行仅由测试 seed 产生，
--   均为同租户，故校验通过。真实部署若已有脏数据，需先清理再上本迁移。

ALTER TABLE t_knowledge_base ADD CONSTRAINT uk_t_kb_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE t_document       ADD CONSTRAINT uk_t_document_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE t_kb_acl ADD CONSTRAINT fk_t_kb_acl_tenant_kb
    FOREIGN KEY (tenant_id, kb_id) REFERENCES t_knowledge_base (tenant_id, id) ON DELETE CASCADE;

ALTER TABLE t_doc_acl ADD CONSTRAINT fk_t_doc_acl_tenant_doc
    FOREIGN KEY (tenant_id, doc_id) REFERENCES t_document (tenant_id, id) ON DELETE CASCADE;
