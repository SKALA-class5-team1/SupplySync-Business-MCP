/*
 * [DB 조회] parts, inventory, part_substitutes 테이블 SELECT (NamedParameterJdbcTemplate, IN 목록 바인딩).
 * SQL은 표준 SQL만 쓴다.
 */
package com.example.rfq.businessmcp.repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** parts, inventory, part_substitutes 조회. */
@Repository
public class PartRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public PartRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Part(
            String partId,
            String partName,
            String supplier,
            int unitCostKrw,
            int leadTimeDays,
            boolean critical) {
    }

    public record Inventory(
            int onHand,
            int allocated,
            int incomingQty,
            LocalDate incomingDate) {
        public int available() {
            return onHand - allocated;
        }
    }

    /** inventory는 대체 부품에 재고 행이 없으면 null. */
    public record Substitute(
            String originalPartId,
            Part part,
            Inventory inventory,
            int validationDays,
            boolean pinCompatible,
            String refChunkId) {
    }

    public Map<String, Part> findParts(Collection<String> partIds) {
        String sql = """
                SELECT part_id, part_name, supplier, unit_cost_krw, lead_time_days, is_critical
                  FROM parts
                 WHERE part_id IN (:ids)
                """;
        Map<String, Part> parts = new LinkedHashMap<>();
        jdbc.query(sql, Map.of("ids", partIds), rs -> {
            parts.put(rs.getString("part_id"), mapPart(rs));
        });
        return parts;
    }

    public Map<String, Inventory> findInventory(Collection<String> partIds) {
        String sql = """
                SELECT part_id, on_hand, allocated, incoming_qty, incoming_date
                  FROM inventory
                 WHERE part_id IN (:ids)
                """;
        Map<String, Inventory> inventory = new LinkedHashMap<>();
        jdbc.query(sql, Map.of("ids", partIds), rs -> {
            inventory.put(rs.getString("part_id"), mapInventory(rs));
        });
        return inventory;
    }

    /** 원래 부품 목록의 대체 부품. 대체 부품의 parts·inventory 정보를 함께 가져온다. */
    public List<Substitute> findSubstitutes(Collection<String> partIds) {
        String sql = """
                SELECT s.part_id AS original_part_id, s.validation_days, s.pin_compatible, s.ref_chunk_id,
                       p.part_id, p.part_name, p.supplier, p.unit_cost_krw, p.lead_time_days, p.is_critical,
                       i.part_id AS inventory_part_id, i.on_hand, i.allocated, i.incoming_qty, i.incoming_date
                  FROM part_substitutes s
                  JOIN parts p ON p.part_id = s.substitute_part_id
                  LEFT JOIN inventory i ON i.part_id = s.substitute_part_id
                 WHERE s.part_id IN (:ids)
                 ORDER BY s.part_id, s.substitute_part_id
                """;
        return jdbc.query(sql, Map.of("ids", partIds), (rs, rowNum) -> mapSubstitute(rs));
    }

    private static Part mapPart(ResultSet rs) throws SQLException {
        return new Part(
                rs.getString("part_id"),
                rs.getString("part_name"),
                rs.getString("supplier"),
                rs.getInt("unit_cost_krw"),
                rs.getInt("lead_time_days"),
                rs.getBoolean("is_critical"));
    }

    private static Inventory mapInventory(ResultSet rs) throws SQLException {
        Date incomingDate = rs.getDate("incoming_date");
        return new Inventory(
                rs.getInt("on_hand"),
                rs.getInt("allocated"),
                rs.getInt("incoming_qty"),
                incomingDate == null ? null : incomingDate.toLocalDate());
    }

    private static Substitute mapSubstitute(ResultSet rs) throws SQLException {
        // 대체 부품에 inventory 행이 없으면 LEFT JOIN 결과가 비어 있다
        boolean hasInventory = rs.getString("inventory_part_id") != null;
        return new Substitute(
                rs.getString("original_part_id"),
                mapPart(rs),
                hasInventory ? mapInventory(rs) : null,
                rs.getInt("validation_days"),
                rs.getBoolean("pin_compatible"),
                rs.getString("ref_chunk_id"));
    }
}
