package io.github.lakernote.observability.integration.jdbc;

import net.ttddyy.dsproxy.QueryInfo;

import java.util.List;
import java.util.regex.Pattern;

/** 生成有长度上限的 SQL 标签，不保留字面量或绑定参数。 */
final class JdbcTraceSql {
    private static final int MAX_SQL_LENGTH = 512;
    private static final Pattern COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/|--[^\\r\\n]*");
    private static final Pattern LITERALS = Pattern.compile("'(?:''|[^'])*'|\\b[0-9]+(?:\\.[0-9]+)?\\b");

    private JdbcTraceSql() { }

    static String describe(List<QueryInfo> queries) {
        if (queries == null || queries.isEmpty()) return "statement";
        String sql = normalize(queries.get(0).getQuery());
        if (sql.isBlank()) sql = "statement";
        if (queries.size() > 1) sql += " (" + (queries.size() - 1) + " more statements)";
        return sql.length() > MAX_SQL_LENGTH ? sql.substring(0, MAX_SQL_LENGTH) : sql;
    }

    static String command(List<QueryInfo> queries) {
        String sql = describe(queries);
        int separator = sql.indexOf(' ');
        String command = separator < 0 ? sql : sql.substring(0, separator);
        if (command.isBlank() || "statement".equals(command)) return "STATEMENT";
        return command.toUpperCase(java.util.Locale.ROOT);
    }

    private static String normalize(String sql) {
        if (sql == null || sql.isBlank()) return "";
        String withoutComments = COMMENTS.matcher(sql).replaceAll(" ");
        String withoutLiterals = LITERALS.matcher(withoutComments).replaceAll("?");
        return withoutLiterals.replaceAll("\\s+", " ").trim();
    }
}
