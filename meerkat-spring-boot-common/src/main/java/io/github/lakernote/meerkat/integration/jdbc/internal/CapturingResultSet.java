package io.github.lakernote.meerkat.integration.jdbc.internal;

import io.github.lakernote.meerkat.capture.internal.CaptureText;
import io.github.lakernote.meerkat.trace.internal.CaptureTarget;
import io.github.lakernote.meerkat.trace.internal.CaptureValue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Wraps a captured query's result set and records the values the application reads from its first rows. Values
 * that were never read are not fetched, so the application's access pattern and the driver's behavior stay the same.
 */
final class CapturingResultSet implements InvocationHandler, CaptureValue {
    private static final int MAX_CELL_CHARS = 500;
    /** Streams and LOBs would be consumed by recording them; they are shown as not captured. */
    private static final Set<String> SKIPPED_GETTERS = Set.of("getAsciiStream", "getBinaryStream",
            "getCharacterStream", "getNCharacterStream", "getUnicodeStream", "getBlob", "getClob", "getNClob",
            "getArray", "getRef", "getSQLXML", "getRowId", "getStatement", "getMetaData", "getWarnings",
            "getCursorName", "getFetchSize", "getFetchDirection", "getType", "getConcurrency", "getHoldability",
            "getRow");
    private final ResultSet delegate;
    private final int maxRows;
    private final List<String[]> rows = new ArrayList<>();
    private String[] labels;
    private Map<String, Integer> labelIndexes;
    private String[] currentRow;
    private long rowCount;
    private boolean metadataFailed;

    private CapturingResultSet(ResultSet delegate, int maxRows) {
        this.delegate = delegate;
        this.maxRows = maxRows;
    }

    static ResultSet wrap(ResultSet resultSet, CaptureTarget target) {
        try {
            CapturingResultSet handler = new CapturingResultSet(resultSet, target.limits().maxResultRows());
            target.capture("rows", handler);
            return (ResultSet) Proxy.newProxyInstance(CapturingResultSet.class.getClassLoader(),
                    new Class<?>[] {ResultSet.class}, handler);
        } catch (RuntimeException | LinkageError failure) {
            return resultSet;
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (method.getDeclaringClass() == Object.class) {
            if ("equals".equals(name)) return proxy == args[0];
            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
            if ("toString".equals(name)) return "Captured" + delegate;
        }
        Object result;
        try {
            result = method.invoke(delegate, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
        try {
            if ("next".equals(name) && args == null) {
                onNext(Boolean.TRUE.equals(result));
            } else if (currentRow != null && args != null && args.length >= 1 && name.startsWith("get")
                    && !SKIPPED_GETTERS.contains(name)) {
                record(args[0], result);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 抓取失败不能影响结果集读取。
        }
        return result;
    }

    private synchronized void onNext(boolean hasRow) {
        currentRow = null;
        if (!hasRow) return;
        rowCount++;
        if (rows.size() >= maxRows) return;
        if (labels == null && !metadataFailed) readLabels();
        currentRow = new String[labels == null ? 0 : labels.length];
        rows.add(currentRow);
    }

    private void readLabels() {
        try {
            ResultSetMetaData metadata = delegate.getMetaData();
            int count = metadata.getColumnCount();
            labels = new String[count];
            labelIndexes = new HashMap<>();
            for (int index = 1; index <= count; index++) {
                String label = metadata.getColumnLabel(index);
                labels[index - 1] = label;
                if (label != null) labelIndexes.putIfAbsent(label.toLowerCase(Locale.ROOT), index);
            }
        } catch (SQLException | RuntimeException unavailable) {
            metadataFailed = true;
        }
    }

    private synchronized void record(Object column, Object value) {
        if (currentRow == null) return;
        int index = column instanceof Integer position ? position
                : column instanceof String label && labelIndexes != null
                ? labelIndexes.getOrDefault(label.toLowerCase(Locale.ROOT), -1) : -1;
        if (index < 1 || index > currentRow.length) return;
        currentRow[index - 1] = CaptureText.of(value, MAX_CELL_CHARS);
    }

    @Override
    public synchronized String render(int maxChars) {
        StringBuilder text = new StringBuilder();
        if (labels != null) text.append(String.join(" | ", labels));
        for (String[] row : rows) {
            if (text.length() > maxChars) break;
            if (!text.isEmpty()) text.append('\n');
            for (int index = 0; index < row.length; index++) {
                if (index > 0) text.append(" | ");
                text.append(row[index] == null ? "·" : row[index]);
            }
        }
        if (!text.isEmpty()) text.append('\n');
        text.append("(rows read: ").append(rowCount);
        if (rowCount > rows.size()) text.append(", first ").append(rows.size()).append(" shown");
        text.append("; · = not read by the application)");
        return text.toString();
    }
}
