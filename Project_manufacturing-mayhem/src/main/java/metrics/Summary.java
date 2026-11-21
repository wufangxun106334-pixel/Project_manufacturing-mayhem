package metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The metrics for one simulation run, as rows of (section, entity, metric, value), e.g.
 * ("Idle while work available", "r2", "idle (% of run)", 100.0). Values are numbers, except in
 * the Scenario section, which describes the run.
 */
public class Summary {
    public record Row(String section, String entity, String metric, Object value) {}

    private final String run;
    private final List<Row> rows = new ArrayList<>();

    Summary(String run) {
        this.run = run;
    }

    void add(String section, String entity, String metric, Object value) {
        rows.add(new Row(section, entity, metric, value));
    }

    public String run() {
        return run;
    }

    public List<Row> rows() {
        return Collections.unmodifiableList(rows);
    }

    /** One line per row: section,entity,metric,value. */
    public String toCsv() {
        StringBuilder csv = new StringBuilder("section,entity,metric,value\n");
        for (Row row : rows) {
            csv.append(quote(row.section())).append(',').append(quote(row.entity())).append(',')
                .append(quote(row.metric())).append(',').append(quote(String.valueOf(row.value()))).append('\n');
        }
        return csv.toString();
    }

    /** {"run": ..., "metrics": {section: {entity: {metric: value}}}} */
    public String toJson() {
        Map<String, Map<String, Map<String, Object>>> sections = new LinkedHashMap<>();
        for (Row row : rows) {
            sections.computeIfAbsent(row.section(), k -> new LinkedHashMap<>())
                .computeIfAbsent(row.entity(), k -> new LinkedHashMap<>())
                .put(row.metric(), row.value());
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("run", run);
        json.put("metrics", sections);
        try {
            return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String quote(String value) {
        if (!value.contains(",") && !value.contains("\"")) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
