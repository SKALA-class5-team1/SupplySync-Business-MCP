/*
 * [DB 조회] models, options, model_options, bom 테이블 SELECT (NamedParameterJdbcTemplate, 파라미터 바인딩).
 * SQL은 PostgreSQL·H2 공용 표준 SQL만 쓴다.
 */
package com.example.rfq.businessmcp.repository;

import com.example.rfq.businessmcp.dto.ModelSpec;
import com.example.rfq.businessmcp.dto.OptionInfo;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** models, options, model_options, bom 조회. */
@Repository
public class ProductRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public ProductRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record BomRow(String partId, int qtyPerUnit) {
    }

    public List<ModelSpec> findAllModels() {
        String sql = """
                SELECT model_id, model_name, flow_lpm, head_kpa, voltage_min, voltage_max,
                       temp_min, temp_max, ip_rating, noise_db, comm,
                       size_l_mm, size_w_mm, size_h_mm, weight_kg, base_price_krw
                  FROM models
                 ORDER BY model_id
                """;
        return jdbc.query(sql, (rs, rowNum) -> mapModel(rs));
    }

    public Optional<Integer> findBasePrice(String modelId) {
        String sql = "SELECT base_price_krw FROM models WHERE model_id = :modelId";
        return jdbc.queryForList(sql, Map.of("modelId", modelId), Integer.class).stream().findFirst();
    }

    /** 모델에 허용된 옵션 (model_options 기준). */
    public List<OptionInfo> findOptions(String modelId) {
        String sql = """
                SELECT o.option_code, o.option_name, o.changes_field, o.new_value,
                       o.add_weight_kg, o.add_noise_db, o.add_price_krw,
                       o.replaces_part_id, o.uses_part_id
                  FROM model_options mo
                  JOIN options o ON o.option_code = mo.option_code
                 WHERE mo.model_id = :modelId
                 ORDER BY o.option_code
                """;
        return jdbc.query(sql, Map.of("modelId", modelId), (rs, rowNum) -> mapOption(rs));
    }

    /** 옵션 적용 전 기본 BOM. bom에 순서 컬럼이 없으므로 part_id 순으로 고정한다. */
    public List<BomRow> findBom(String modelId) {
        String sql = """
                SELECT part_id, qty_per_unit
                  FROM bom
                 WHERE model_id = :modelId
                 ORDER BY part_id
                """;
        return jdbc.query(sql, Map.of("modelId", modelId),
                (rs, rowNum) -> new BomRow(rs.getString("part_id"), rs.getInt("qty_per_unit")));
    }

    private static ModelSpec mapModel(ResultSet rs) throws SQLException {
        return new ModelSpec(
                rs.getString("model_id"),
                rs.getString("model_name"),
                rs.getBigDecimal("flow_lpm"),
                rs.getInt("head_kpa"),
                stripZeros(rs.getBigDecimal("voltage_min")),
                stripZeros(rs.getBigDecimal("voltage_max")),
                rs.getInt("temp_min"),
                rs.getInt("temp_max"),
                rs.getString("ip_rating"),
                rs.getInt("noise_db"),
                rs.getString("comm"),
                rs.getInt("size_l_mm"),
                rs.getInt("size_w_mm"),
                rs.getInt("size_h_mm"),
                rs.getBigDecimal("weight_kg"),
                rs.getInt("base_price_krw"));
    }

    private static OptionInfo mapOption(ResultSet rs) throws SQLException {
        return new OptionInfo(
                rs.getString("option_code"),
                rs.getString("option_name"),
                rs.getString("changes_field"),
                rs.getString("new_value"),
                rs.getBigDecimal("add_weight_kg"),
                rs.getInt("add_noise_db"),
                rs.getInt("add_price_krw"),
                rs.getString("replaces_part_id"),
                rs.getString("uses_part_id"));
    }

    /** NUMERIC(4,1) 전압을 명세대로 9.0 → 9로 내보낸다. 13.5처럼 소수가 있으면 유지. */
    private static BigDecimal stripZeros(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        // 10.0은 stripTrailingZeros()가 1E+1이 되므로 scale을 0으로 되돌린다
        return s.scale() < 0 ? s.setScale(0) : s;
    }
}
