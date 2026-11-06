package io.github.lakernote.meerkat.example;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Service
public class DemoDatabaseService {
    private static final String INSERT =
            "insert into demo_orders(order_id, customer_name, status, created_at) values (?, ?, ?, ?)";
    private final JdbcTemplate jdbc;

    public DemoDatabaseService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> createAndReadOrder(String orderId, String customer) {
        insertOrder(orderId, customer);
        return jdbc.queryForMap("select order_id, customer_name, status, created_at from demo_orders where order_id = ?",
                orderId);
    }

    @Transactional
    public void insertThenRollback(String orderId) {
        jdbc.update(INSERT, orderId, "rollback-demo", "ROLLBACK", OffsetDateTime.now());
        throw new IllegalStateException("Intentional demo failure; transaction should roll back");
    }

    @Transactional
    public void insertDuplicateOrder(String orderId) {
        insertOrder(orderId, "first insert");
        insertOrder(orderId, "duplicate insert");
    }

    public void insertOrder(String orderId, String customer) {
        jdbc.update(INSERT, orderId, customer, "CREATED", OffsetDateTime.now());
    }

    public List<Map<String, Object>> findOrder(String orderId) {
        return jdbc.queryForList("select order_id, status from demo_orders where order_id = ?", orderId);
    }

    /** Same lookup with simulated per-query latency, like a database across the network. */
    public List<Map<String, Object>> findOrder(String orderId, long latencyMillis) {
        return jdbc.queryForList(
                "select order_id, status from demo_orders where order_id = ? and demo_sleep(?) >= 0", orderId, latencyMillis);
    }

    public long countOrders() {
        Long count = jdbc.queryForObject("select count(*) from demo_orders", Long.class);
        return count == null ? 0 : count;
    }

    /** A real SQL statement that takes {@code millis}, via the H2 function declared in schema.sql. */
    public void slowQuery(long millis) {
        jdbc.queryForObject("select demo_sleep(?)", Long.class, millis);
    }
}
