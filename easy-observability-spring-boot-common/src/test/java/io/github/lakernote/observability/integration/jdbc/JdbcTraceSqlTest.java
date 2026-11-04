package io.github.lakernote.observability.integration.jdbc;

import net.ttddyy.dsproxy.QueryInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcTraceSqlTest {

    @Test
    void redactsStandardLiteralsWithoutChangingIdentifiers() {
        String sql = "SELECT * FROM tenant_2026.orders WHERE email = 'alice@example.com' AND age = -12.50";

        assertThat(describe(sql))
                .isEqualTo("SELECT * FROM tenant_2026.orders WHERE email = ? AND age = -?")
                .doesNotContain("alice@example.com", "12.50");
    }

    @Test
    void treatsCommentMarkersInsideStringsAsLiteralContent() {
        assertThat(describe("SELECT 'literal-secret--tail', 'block/*secret*/tail'"))
                .isEqualTo("SELECT ?, ?")
                .doesNotContain("literal-secret", "block", "tail");
    }

    @Test
    void removesLineNestedAndUnterminatedComments() {
        String sql = "SELECT 1 /* outer-secret /* nested-secret */ tail-secret */ + 2 # line-secret\n"
                + "FROM tenant_2026.orders -- final-secret";

        assertThat(describe(sql))
                .isEqualTo("SELECT ? + ? FROM tenant_2026.orders")
                .doesNotContain("secret");
        assertThat(describe("SELECT 1 /* unterminated-secret"))
                .isEqualTo("SELECT ?")
                .doesNotContain("unterminated-secret");
    }

    @Test
    void redactsDialectSpecificAndUnterminatedQuotedLiterals() {
        String sql = "SELECT $$postgres-secret$$, $tag$tagged-secret$tag$, q'[oracle-secret]', "
                + "\"double-secret\", `customer_id`, [order_id]";

        assertThat(describe(sql))
                .isEqualTo("SELECT ?, ?, ?, ?, `customer_id`, [order_id]")
                .doesNotContain("secret");
        assertThat(describe("SELECT E'pa\\'ssword', 'O''Reilly'"))
                .isEqualTo("SELECT E?, ?")
                .doesNotContain("pa", "ssword", "Reilly");
        assertThat(describe("SELECT 'unterminated-secret"))
                .isEqualTo("SELECT ?")
                .doesNotContain("unterminated-secret");
        assertThat(describe("SELECT $tag$unterminated-secret"))
                .isEqualTo("SELECT ?")
                .doesNotContain("unterminated-secret");
    }

    @Test
    void redactsLongAndIncompleteDollarQuoteDelimiters() {
        String tag = "tag".repeat(80);

        assertThat(describe("SELECT $" + tag + "$long-tag-secret$" + tag + "$"))
                .isEqualTo("SELECT ?")
                .doesNotContain("long-tag-secret");
        assertThat(describe("SELECT $unfinished_tag"))
                .isEqualTo("SELECT ?")
                .doesNotContain("unfinished_tag");
        assertThat(describe("SELECT foo$bar FROM tenant_2026.orders"))
                .isEqualTo("SELECT foo$bar FROM tenant_2026.orders");
    }

    @Test
    void backslashCannotExtendQuotedIdentifiersAcrossStringLiterals() {
        assertThat(describe("SELECT [col\\], 'bracket-secret' AS [x]"))
                .isEqualTo("SELECT [col\\], ? AS [x]")
                .doesNotContain("bracket-secret");
        assertThat(describe("SELECT `col\\`, 'backtick-secret' AS `x`"))
                .isEqualTo("SELECT `col\\`, ? AS `x`")
                .doesNotContain("backtick-secret");
    }

    @Test
    void redactsDecimalScientificHexBinaryAndUuidValues() {
        String sql = "SELECT 6.022e+23, 0xDEADBEEF, 0b1010, 550e8400-e29b-41d4-a716-446655440000, "
                + "$123.45, foo$2026";

        assertThat(describe(sql))
                .isEqualTo("SELECT ?, ?, ?, ?, $?, foo$2026")
                .doesNotContain("DEADBEEF", "1010", "550e8400", "123.45");
    }

    @Test
    void neverReadsBoundParameterValues() {
        QueryInfo query = new QueryInfo("SELECT CAST(? AS VARCHAR)") {
            @Override
            public List<List<net.ttddyy.dsproxy.proxy.ParameterSetOperation>> getParametersList() {
                throw new AssertionError("bound parameter values must not be read");
            }

            @Override
            public List<Map<String, Object>> getQueryArgsList() {
                throw new AssertionError("bound query arguments must not be read");
            }
        };

        assertThat(JdbcTraceSql.describe(List.of(query)))
                .isEqualTo("SELECT CAST(? AS VARCHAR)")
                .doesNotContain("bind-secret");
    }

    @Test
    void boundsInputOutputAndBatchDescription() {
        String oversized = "SELECT " + "column_name,".repeat(JdbcTraceSql.MAX_SCAN_LENGTH);
        String description = describe(oversized);

        assertThat(description).hasSize(JdbcTraceSql.MAX_SQL_LENGTH);
        assertThat(describe(" ".repeat(JdbcTraceSql.MAX_SCAN_LENGTH) + "'beyond-scan-secret'"))
                .isEqualTo("statement")
                .doesNotContain("beyond-scan-secret");
        assertThat(JdbcTraceSql.describe(List.of(
                new QueryInfo("SELECT 'first-secret'"),
                new QueryInfo("SELECT 'second-secret'"))))
                .isEqualTo("SELECT ? (1 more statements)")
                .doesNotContain("first-secret", "second-secret");
    }

    @Test
    void onlyUsesAllowlistedAsciiCommandsForSpanNames() {
        assertThat(JdbcTraceSql.command(List.of(new QueryInfo("/* hidden */ select 1")))).isEqualTo("SELECT");
        assertThat(JdbcTraceSql.command(List.of(new QueryInfo("secret-token 'value'")))).isEqualTo("STATEMENT");
        assertThat(JdbcTraceSql.command(List.of(new QueryInfo("UNKNOWN value")))).isEqualTo("STATEMENT");
        assertThat(JdbcTraceSql.command(List.of())).isEqualTo("STATEMENT");
    }

    private static String describe(String sql) {
        return JdbcTraceSql.describe(List.of(new QueryInfo(sql)));
    }
}
