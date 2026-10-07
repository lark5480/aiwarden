package com.aiwarden.knowledge.document;

import com.aiwarden.common.exception.ConflictException;
import com.aiwarden.contract.knowledge.KnowledgeBaseResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 知识库服务（FR-KB-01）：租户内 CRUD；跨租户访问不可见（所有查询显式带 tenant_id）。
 */
@Service
public class KnowledgeBaseService {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeBaseService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public KnowledgeBaseResponse create(long tenantId, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("知识库名称不能为空");
        }
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap("""
                    INSERT INTO t_knowledge_base (tenant_id, name) VALUES (?, ?)
                    RETURNING id, name
                    """, tenantId, name);
            return new KnowledgeBaseResponse(((Number) row.get("id")).longValue(), (String) row.get("name"));
        } catch (DuplicateKeyException e) {
            throw new ConflictException("知识库名称已存在：" + name);
        }
    }

    public List<KnowledgeBaseResponse> list(long tenantId) {
        return jdbcTemplate.query("""
                        SELECT id, name FROM t_knowledge_base WHERE tenant_id = ? ORDER BY id
                        """,
                (rs, rowNum) -> new KnowledgeBaseResponse(rs.getLong("id"), rs.getString("name")),
                tenantId);
    }
}
