/*
 * [테스트 공통] 조회 Tool 6개의 기대값 검증. MCP 클라이언트로 tools/call 호출하고 정답 파일(src/test/resources/expected/)과
 * 필드명·값을 비교한다. mock 백엔드(BusinessToolsIntegrationTest)와 실제 백엔드(LiveBackendToolsTest)가 같은 검증을 쓴다.
 */
package com.example.rfq.businessmcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.example.rfq.businessmcp.McpTestClient.byPartId;
import static com.example.rfq.businessmcp.McpTestClient.expected;
import static org.assertj.core.api.Assertions.assertThat;

/** 기대값은 v2.2 시드 기준 (CLAUDE.md "테스트 기대값"). */
abstract class ReadToolsContract {
    static final List<String> B_OPTIONS = List.of("OPT-HT", "OPT-IP9K");

    McpTestClient mcp;

    /** 테스트 서버에 접속한 MCP 클라이언트. */
    abstract McpTestClient connect();

    @BeforeEach
    void connectMcp() {
        mcp = connect();
    }

    @AfterEach
    void closeMcp() {
        mcp.close();
    }

    /** 정답 객체의 모든 필드가 같은 이름·값으로 결과에 있어야 한다. */
    static void assertHasFields(JsonNode actual, JsonNode expected) {
        expected.fieldNames().forEachRemaining(field -> {
            assertThat(actual.has(field)).as("%s 필드 없음: %s", field, actual).isTrue();
            assertThat(actual.get(field)).as("%s.%s", expected.path("part_id").asText(), field)
                    .isEqualTo(expected.get(field));
        });
    }

    JsonNode bomB2000() throws IOException {
        return mcp.callOk("get_bom", Map.of("model_id", "EWP-12-B", "options", B_OPTIONS, "qty", 2000));
    }

    /** get_bom 결과를 그대로 check_parts_supply 입력으로 (납기 2026-12-15, 검토일 2026-10-19). */
    JsonNode supplyB2000() throws IOException {
        List<Map<String, Object>> parts = StreamSupport.stream(bomB2000().get("parts").spliterator(), false)
                .map(p -> Map.<String, Object>of("part_id", p.get("part_id").asText(),
                        "required_qty", p.get("required_qty").asInt()))
                .toList();
        return mcp.callOk("check_parts_supply",
                Map.of("due_date", "2026-12-15", "base_date", "2026-10-19", "parts", parts));
    }

    @Test
    void inputSchemasUseSnakeCase() {
        var supply = mcp.tool("check_parts_supply");
        assertThat(supply.inputSchema().properties()).containsOnlyKeys("due_date", "base_date", "parts");
        assertThat(supply.inputSchema().required()).containsExactlyInAnyOrder("due_date", "parts");
        assertThat(McpTestClient.JSON.valueToTree(supply.inputSchema().properties().get("parts")).toString())
                .contains("\"part_id\"", "\"required_qty\"");

        var substitutes = mcp.tool("get_substitutes");
        assertThat(substitutes.inputSchema().properties()).containsOnlyKeys("part_id", "short_qty", "base_date");
        assertThat(substitutes.inputSchema().required()).containsExactly("part_id");
    }

    @Test
    void getModels() throws IOException {
        JsonNode models = mcp.callOk("get_models", Map.of());
        assertThat(models).hasSize(3);
        assertThat(models.get(0).get("model_id").asText()).isEqualTo("EWP-12-A");
        JsonNode b = models.get(1);
        assertThat(b.get("model_id").asText()).isEqualTo("EWP-12-B");
        assertThat(b.get("flow_lpm").decimalValue()).isEqualByComparingTo("25.0");
        assertThat(b.get("comm").asText()).isEqualTo("LIN,PWM");
        // 명세대로 정수 표기 (9.0이 아니라 9)
        assertThat(b.get("voltage_min").isIntegralNumber()).isTrue();
        assertThat(b.get("voltage_min").asInt()).isEqualTo(9);
        assertThat(b.get("voltage_max").asInt()).isEqualTo(16);
        assertThat(b.get("size_l_mm").asInt()).isEqualTo(110);
        assertThat(b.get("size_w_mm").asInt()).isEqualTo(90);
        assertThat(b.get("size_h_mm").asInt()).isEqualTo(90);
        assertThat(b.get("weight_kg").decimalValue()).isEqualByComparingTo("0.70");
        assertThat(b.get("base_price_krw").asInt()).isEqualTo(34000);
        assertThat(b.has("options")).as("get_models는 옵션을 내보내지 않는다").isFalse();
    }

    @Test
    void getOptions() throws IOException {
        JsonNode b = mcp.callOk("get_options", Map.of("model_id", "EWP-12-B"));
        assertThat(b.get("base_price_krw").asInt()).isEqualTo(34000);
        assertThat(b.get("options")).hasSize(2);
        JsonNode ht = b.get("options").get(0);
        assertThat(ht.get("option_code").asText()).isEqualTo("OPT-HT");
        assertThat(ht.get("add_price_krw").asInt()).isEqualTo(2400);
        assertThat(ht.get("replaces_part_id").asText()).isEqualTo("P-MCU-STD");
        assertThat(ht.get("uses_part_id").asText()).isEqualTo("P-MCU-G1");
        assertThat(b.get("options").get(1).get("add_price_krw").asInt()).isEqualTo(900);

        assertThat(mcp.callOk("get_options", Map.of("model_id", "EWP-12-C")).get("options")).isEmpty();
        assertThat(mcp.callError("get_options", Map.of("model_id", "EWP-99"))).contains("존재하지 않는 모델", "EWP-99");
    }

    @Test
    void getBomMatchesBomEffective() throws IOException {
        JsonNode bom = bomB2000();
        assertThat(bom.get("qty").asInt()).isEqualTo(2000);
        assertThat(bom.get("parts").findValuesAsText("part_id")).isSorted();

        JsonNode expectedBom = expected("5_sg2_supply.json").get("bom_effective");
        assertThat(bom.get("parts")).hasSize(expectedBom.size());
        for (JsonNode e : expectedBom) {
            JsonNode actual = byPartId(bom.get("parts"), e.get("part_id").asText());
            assertHasFields(actual, e);
            assertThat(actual.get("required_qty").asInt()).isEqualTo(2000);
            assertThat(actual.has("replaced_part_id")).as(e.toString()).isEqualTo(e.has("replaced_part_id"));
        }
    }

    @Test
    void getBomErrors() {
        assertThat(mcp.callError("get_bom", Map.of("model_id", "EWP-12-A", "options", List.of("OPT-IP9K"), "qty", 10)))
                .contains("허용되지 않은 옵션", "OPT-IP9K");
        assertThat(mcp.callError("get_bom", Map.of("model_id", "EWP-12-C", "options", List.of(), "qty", 10)))
                .contains("BOM이 없습니다");
        assertThat(mcp.callError("get_bom", Map.of("model_id", "EWP-99", "qty", 10))).contains("존재하지 않는 모델");
        assertThat(mcp.callError("get_bom", Map.of("model_id", "EWP-12-B", "qty", 0))).contains("qty");
        assertThat(mcp.callError("get_bom", Map.of("model_id", "EWP-12-B", "options", List.of(" "), "qty", 10)))
                .contains("옵션 코드");
    }

    @Test
    void getPartsCostMatchesQuoteDraft() throws IOException {
        JsonNode cost = mcp.callOk("get_parts_cost", Map.of("model_id", "EWP-12-B", "options", B_OPTIONS));
        JsonNode draft = expected("6_sg3_quote.json").get("quote_draft");
        assertThat(cost.get("parts_cost_krw")).isEqualTo(draft.get("parts_cost_krw"));
        assertThat(cost.get("parts_cost_breakdown")).hasSize(draft.get("parts_cost_breakdown").size());
        for (JsonNode e : draft.get("parts_cost_breakdown")) {
            JsonNode actual = byPartId(cost.get("parts_cost_breakdown"), e.get("part_id").asText());
            assertHasFields(actual, e);
            assertThat(actual.get("unit_cost_krw").asInt() * actual.get("qty_per_unit").asInt())
                    .isEqualTo(actual.get("cost_krw").asInt());
        }

        assertThat(mcp.callOk("get_parts_cost", Map.of("model_id", "EWP-12-B", "options", List.of()))
                .get("parts_cost_krw").asInt()).isEqualTo(19000);
        assertThat(mcp.callError("get_parts_cost", Map.of("model_id", "EWP-12-C"))).contains("BOM이 없습니다");
    }

    @Test
    void checkPartsSupplyMatchesSg2Expected() throws IOException {
        JsonNode supply = supplyB2000();
        assertThat(supply.get("due_date").asText()).isEqualTo("2026-12-15");
        assertThat(supply.get("base_date").asText()).isEqualTo("2026-10-19");
        assertThat(supply.get("has_risk").asBoolean()).isTrue();

        JsonNode sg2 = expected("5_sg2_supply.json");
        for (JsonNode e : sg2.get("supply_check")) {
            assertHasFields(byPartId(supply.get("parts"), e.get("part_id").asText()), e);
        }
        for (JsonNode e : sg2.get("shortages")) {
            assertHasFields(byPartId(supply.get("parts"), e.get("part_id").asText()), e);
        }

        // part_id, lead, new_order_arrival, incoming_delay_days (CLAUDE.md 기대값 표)
        Object[][] schedule = {
                {"P-MTR-BL12", 45, "2026-12-03", 0},
                {"P-MCU-G1", 120, "2027-02-16", 36},
                {"P-IMP-25", 14, "2026-11-02", null},
                {"P-HSG-AL", 30, "2026-11-18", 0},
                {"P-SEAL-9K", 21, "2026-11-09", null},
                {"P-CON-4P", 21, "2026-11-09", null},
        };
        for (Object[] e : schedule) {
            JsonNode p = byPartId(supply.get("parts"), (String) e[0]);
            assertThat(p.get("lead_time_days").asInt()).as("%s lead", e[0]).isEqualTo(e[1]);
            assertThat(p.get("new_order_arrival").asText()).as("%s arrival", e[0]).isEqualTo(e[2]);
            assertThat(p.get("incoming_delay_days").isNull() ? null : p.get("incoming_delay_days").asInt())
                    .as("%s delay", e[0]).isEqualTo(e[3]);
            if (!"P-MCU-G1".equals(e[0])) {
                assertThat(p.get("risks")).as("%s risks", e[0]).isEmpty();
                assertThat(p.get("substitutes")).as("%s substitutes", e[0]).isEmpty();
            }
        }

        JsonNode mcu = byPartId(supply.get("parts"), "P-MCU-G1");
        assertThat(mcu.get("incoming_before_due").asBoolean()).isFalse();
        assertThat(mcu.get("risks").toString())
                .isEqualTo("[\"SHORTAGE\",\"INCOMING_AFTER_DUE\",\"NEW_ORDER_AFTER_DUE\"]");
        assertThat(mcu.get("substitutes")).hasSize(1);
        JsonNode alt = mcu.get("substitutes").get(0);
        assertThat(alt.get("substitute_part_id").asText()).isEqualTo("P-MCU-G1-ALT");
        assertThat(alt.get("part_name").asText()).isEqualTo("제어칩 (고온용, 대체)");
        assertThat(alt.get("supplier").asText()).isEqualTo("누리칩스");
        assertThat(alt.get("available").asInt()).isEqualTo(3000);
        assertThat(alt.get("covers_shortage").asBoolean()).isTrue();
        assertThat(alt.get("unit_cost_krw").asInt()).isEqualTo(5600);
        assertThat(alt.get("unit_cost_diff_krw").asInt()).isEqualTo(300);
        assertThat(alt.get("validation_days").asInt()).isEqualTo(14);
        assertThat(alt.get("usable_date").asText()).isEqualTo("2026-11-02");
        assertThat(alt.get("lead_time_days").asInt()).isEqualTo(30);
        assertThat(alt.get("pin_compatible").asBoolean()).isTrue();
        assertThat(alt.get("ref_chunk_id").asText()).isEqualTo("alt-002");
    }

    /** SG4 재확인: final_plan(P-MCU-G1 1,500 + P-MCU-G1-ALT 500)대로 다시 호출. 대체 부품도 일반 part_id로 조회된다. */
    @Test
    void checkPartsSupplyForFinalPlan() throws IOException {
        JsonNode finalPlan = expected("5_sg2_supply.json").get("final_plan");
        List<Map<String, Object>> parts = finalPlan.properties().stream()
                .map(e -> Map.<String, Object>of("part_id", e.getKey(), "required_qty", e.getValue().asInt()))
                .toList();

        JsonNode supply = mcp.callOk("check_parts_supply",
                Map.of("due_date", "2026-12-15", "base_date", "2026-10-19", "parts", parts));

        assertThat(supply.get("has_risk").asBoolean()).isFalse();
        assertThat(byPartId(supply.get("parts"), "P-MCU-G1").get("available").asInt()).isEqualTo(1500);
        assertThat(byPartId(supply.get("parts"), "P-MCU-G1-ALT").get("available").asInt()).isEqualTo(3000);
        assertThat(supply.get("parts").findValuesAsText("status")).containsOnly("OK");
    }

    @Test
    void checkPartsSupplyErrors() {
        var ok = List.of(Map.of("part_id", "P-MTR-BL12", "required_qty", 10));
        assertThat(mcp.callError("check_parts_supply", Map.of("due_date", "2026/12/15", "parts", ok))).contains("due_date");
        assertThat(mcp.callError("check_parts_supply", Map.of("due_date", "2026-12-15", "parts", List.of())))
                .contains("parts");
        assertThat(mcp.callError("check_parts_supply", Map.of("due_date", "2026-12-15",
                "parts", List.of(Map.of("part_id", "P-NONE", "required_qty", 10))))).contains("존재하지 않는 부품", "P-NONE");
        assertThat(mcp.callError("check_parts_supply", Map.of("due_date", "2026-12-15",
                "parts", List.of(Map.of("part_id", "P-MTR-BL12", "required_qty", 0))))).contains("required_qty");
    }

    /** get_substitutes의 대체 부품은 check_parts_supply의 substitutes와 필드·값이 완전히 같아야 한다. */
    @Test
    void getSubstitutesMatchesCheckPartsSupply() throws IOException {
        JsonNode result = mcp.callOk("get_substitutes",
                Map.of("part_id", "P-MCU-G1", "short_qty", 500, "base_date", "2026-10-19"));

        assertThat(result.get("part_id").asText()).isEqualTo("P-MCU-G1");
        assertThat(result.get("part_name").asText()).isEqualTo("제어칩 (고온용)");
        assertThat(result.get("unit_cost_krw").asInt()).isEqualTo(5300);
        JsonNode fromSupply = byPartId(supplyB2000().get("parts"), "P-MCU-G1").get("substitutes");
        assertThat(result.get("substitutes")).isEqualTo(fromSupply);
    }

    @Test
    void getSubstitutesOptionalInputsAndErrors() throws IOException {
        JsonNode noShortQty = mcp.callOk("get_substitutes", Map.of("part_id", "P-MCU-G1", "base_date", "2026-10-19"));
        JsonNode alt = noShortQty.get("substitutes").get(0);
        assertThat(alt.get("covers_shortage").isNull()).isTrue();
        assertThat(alt.get("usable_date").asText()).isEqualTo("2026-11-02");

        assertThat(mcp.callOk("get_substitutes", Map.of("part_id", "P-MTR-BL12")).get("substitutes")).isEmpty();

        assertThat(mcp.callError("get_substitutes", Map.of("part_id", "P-NONE"))).contains("존재하지 않는 부품", "P-NONE");
        assertThat(mcp.callError("get_substitutes", Map.of("part_id", "P-MCU-G1", "short_qty", 0))).contains("short_qty");
        assertThat(mcp.callError("get_substitutes", Map.of("part_id", "P-MCU-G1", "base_date", "2026/10/19")))
                .contains("base_date");
    }
}
