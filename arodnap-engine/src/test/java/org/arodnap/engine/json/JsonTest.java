package org.arodnap.engine.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Output files keep the format they have always had: Python's {@code json.dumps(value, indent=2)}. */
class JsonTest {
    private static Map<String, Object> value() {
        Map<String, Object> value = new LinkedHashMap<>();
        List<Object> b = new ArrayList<>(Arrays.asList(1, 2.5, 0.000123, 12.0, true, null,
                "café   \"q\" \\ \t\n\u0001\u007f", List.of(), Map.of()));
        value.put("b", b);
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("z", "x");
        a.put("y", List.of(Map.of("k", 1)));
        value.put("a", a);
        value.put("c", 1234567);
        return value;
    }

    private static JsonNode recorded() throws IOException {
        try (InputStream in = JsonTest.class.getResourceAsStream("/json/python-dumps.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    @Test
    void writesSortedKeysLikePython() throws IOException {
        assertThat(Json.write(value(), true)).isEqualTo(recorded().get("sorted").asText());
    }

    @Test
    void keepsInsertionOrderWhenNotSorting() throws IOException {
        assertThat(Json.write(value(), false)).isEqualTo(recorded().get("unsorted").asText());
    }

    @Test
    void writesFloatsLikePythonRepr() {
        assertThat(Json.decimal(0.25)).isEqualTo("0.25");
        assertThat(Json.decimal(3.0)).isEqualTo("3.0");
        assertThat(Json.decimal(123.456789)).isEqualTo("123.456789");
    }
}
