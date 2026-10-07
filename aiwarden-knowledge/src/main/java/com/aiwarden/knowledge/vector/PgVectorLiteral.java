package com.aiwarden.knowledge.vector;

/**
 * pgvector 字面量工具（{@code '[v1,v2,...]'}）：写入与检索查询共用，避免两处格式漂移。
 */
public final class PgVectorLiteral {

    private PgVectorLiteral() {
    }

    public static String of(float[] embedding) {
        StringBuilder builder = new StringBuilder(embedding.length * 12).append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(embedding[i]);
        }
        return builder.append(']').toString();
    }
}
