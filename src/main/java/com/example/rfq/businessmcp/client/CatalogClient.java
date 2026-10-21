/*
 * [외부 호출] 백엔드 조회 API(/internal/models·/internal/parts) 호출. 경로와 상태 코드별 Tool 오류 메시지를 정한다.
 * 조회 Tool(get_models ~ get_parts_cost, get_substitutes)이 BackendClient로 직접 부르지 않고 이 클래스를 쓴다.
 */
package com.example.rfq.businessmcp.client;

import com.example.rfq.businessmcp.dto.BackendCatalog.Bom;
import com.example.rfq.businessmcp.dto.BackendCatalog.Inventory;
import com.example.rfq.businessmcp.dto.BackendCatalog.Model;
import com.example.rfq.businessmcp.dto.BackendCatalog.Part;
import com.example.rfq.businessmcp.dto.BackendCatalog.Substitute;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 백엔드 오류 메시지는 BackendClient가 "(백엔드: ...)"로 뒤에 붙인다. */
@Component
public class CatalogClient {
    private final BackendClient backendClient;

    public CatalogClient(BackendClient backendClient) {
        this.backendClient = backendClient;
    }

    public List<Model> models() {
        return List.of(backendClient.get("/internal/models", null, Model[].class, Map.of()));
    }

    public Model model(String modelId) {
        return backendClient.get("/internal/models/{modelId}", modelId, Model.class, modelNotFound(modelId));
    }

    /** 옵션 적용 BOM. 옵션이 없으면 options 쿼리를 붙이지 않는다 (빈 값은 백엔드가 오류로 본다). */
    public Bom bom(String modelId, List<String> optionCodes) {
        // 400의 백엔드 메시지에 문제 옵션 코드가 들어 있다 (예: 모델에 적용할 수 없는 옵션입니다: OPT-IP9K)
        Map<Integer, String> messages = Map.of(
                404, "존재하지 않는 모델입니다: " + modelId,
                400, "모델 " + modelId + "에 허용되지 않은 옵션이 있습니다 (요청: " + optionCodes + ")");
        if (optionCodes.isEmpty()) {
            return backendClient.get("/internal/models/{modelId}/bom", modelId, Bom.class, messages);
        }
        return backendClient.get("/internal/models/{modelId}/bom", modelId,
                "options", String.join(",", optionCodes),
                Bom.class, messages);
    }

    public Part part(String partId) {
        return backendClient.get("/internal/parts/{partId}", partId, Part.class, partNotFound(partId));
    }

    public Inventory inventory(String partId) {
        return backendClient.get("/internal/parts/{partId}/inventory", partId, Inventory.class,
                Map.of(404, "재고(inventory) 정보가 없는 부품입니다: " + partId));
    }

    /** 대체 후보가 없으면 빈 목록. */
    public List<Substitute> substitutes(String partId) {
        return List.of(backendClient.get("/internal/parts/{partId}/substitutes", partId, Substitute[].class,
                partNotFound(partId)));
    }

    private static Map<Integer, String> modelNotFound(String modelId) {
        return Map.of(404, "존재하지 않는 모델입니다: " + modelId);
    }

    private static Map<Integer, String> partNotFound(String partId) {
        return Map.of(404, "존재하지 않는 부품입니다: " + partId);
    }
}
