/**
 * Copyright © 2016-2024 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.script.api.tbel.TbelInvokeService;
import org.thingsboard.server.common.data.cf.CalculatedField;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.CalculatedFieldId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.EntityIdFactory;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.config.annotations.ApiOperation;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.cf.ctx.state.CalculatedFieldScriptEngine;
import org.thingsboard.server.service.cf.ctx.state.CalculatedFieldTbelScriptEngine;
import org.thingsboard.server.service.entitiy.cf.TbCalculatedFieldService;
import org.thingsboard.server.service.security.permission.Operation;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.thingsboard.server.controller.ControllerConstants.CF_TEXT_SEARCH_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.ENTITY_ID_PARAM_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.ENTITY_TYPE_PARAM_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.MARKDOWN_CODE_BLOCK_END;
import static org.thingsboard.server.controller.ControllerConstants.MARKDOWN_CODE_BLOCK_START;
import static org.thingsboard.server.controller.ControllerConstants.PAGE_NUMBER_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.PAGE_SIZE_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.SORT_ORDER_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.SORT_PROPERTY_DESCRIPTION;
import static org.thingsboard.server.controller.ControllerConstants.TENANT_AUTHORITY_PARAGRAPH;
import static org.thingsboard.server.controller.ControllerConstants.TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH;
import static org.thingsboard.server.controller.ControllerConstants.UUID_WIKI_LINK;

@RestController
@TbCoreComponent
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class CalculatedFieldController extends BaseController {

    private final TbCalculatedFieldService tbCalculatedFieldService;
    private final TbelInvokeService tbelInvokeService;

    public static final String CALCULATED_FIELD_ID = "calculatedFieldId";

    public static final int TIMEOUT = 20;

    private static final String TEST_SCRIPT_EXPRESSION = "Execute the Script expression and return the result. The format of request: \n\n"
            + MARKDOWN_CODE_BLOCK_START
            + "{\n" +
            "  \"expression\": \"var temp = 0; foreach(element: temperature.entrySet()) { temp += element.getValue(); } var avgTemperature = temp / temperature.size(); var adjustedTemperature = avgTemperature + 0.1 * humidity; return { \\\"adjustedTemperature\\\": adjustedTemperature };\",\n" +
            "  \"argNames\": [\"temperature\", \"humidity\"],\n" +
            "  \"arguments\": {\n" +
            "    \"temperature\": {\n" +
            "      \"14327856345\": 22.4,\n" +
            "      \"14327857298\": 21.9,\n" +
            "      \"14327857510\": 22.0\n" +
            "    },\n" +
            "    \"humidity\": 42\n" +
            "  }\n" +
            "}"
            + MARKDOWN_CODE_BLOCK_END
            + "\n\n Expected result JSON contains \"output\" and \"error\".";

    @ApiOperation(value = "Create Or Update Calculated Field (saveCalculatedField)",
            notes = "Creates or Updates the Calculated Field. When creating calculated field, platform generates Calculated Field Id as " + UUID_WIKI_LINK +
                    "The newly created Calculated Field Id will be present in the response. " +
                    "Specify existing Calculated Field Id to update the calculated field. " +
                    "Referencing non-existing Calculated Field Id will cause 'Not Found' error. " +
                    "Remove 'id', 'tenantId' from the request body example (below) to create new Calculated Field entity. "
                    + TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/calculatedField", method = RequestMethod.POST)
    @ResponseBody
    public CalculatedField saveCalculatedField(@io.swagger.v3.oas.annotations.parameters.RequestBody(description = "A JSON value representing the calculated field.")
                                               @RequestBody CalculatedField calculatedField) throws Exception {
        calculatedField.setTenantId(getTenantId());
        checkEntityId(calculatedField.getEntityId(), Operation.WRITE_CALCULATED_FIELD);
        return tbCalculatedFieldService.save(calculatedField, getCurrentUser());
    }

    @ApiOperation(value = "Get Calculated Field (getCalculatedFieldById)",
            notes = "Fetch the Calculated Field object based on the provided Calculated Field Id."
    )
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/calculatedField/{calculatedFieldId}", method = RequestMethod.GET)
    @ResponseBody
    public CalculatedField getCalculatedFieldById(@Parameter @PathVariable(CALCULATED_FIELD_ID) String strCalculatedFieldId) throws ThingsboardException {
        checkParameter(CALCULATED_FIELD_ID, strCalculatedFieldId);
        CalculatedFieldId calculatedFieldId = new CalculatedFieldId(toUUID(strCalculatedFieldId));
        CalculatedField calculatedField = tbCalculatedFieldService.findById(calculatedFieldId, getCurrentUser());
        checkNotNull(calculatedField);
        checkEntityId(calculatedField.getEntityId(), Operation.READ_CALCULATED_FIELD);
        return calculatedField;
    }

    @ApiOperation(value = "Get Calculated Fields by Entity Id (getCalculatedFieldsByEntityId)",
            notes = "Fetch the Calculated Fields based on the provided Entity Id."
    )
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/{entityType}/{entityId}/calculatedFields", params = {"pageSize", "page"}, method = RequestMethod.GET)
    @ResponseBody
    public PageData<CalculatedField> getCalculatedFieldsByEntityId(
            @Parameter(description = ENTITY_TYPE_PARAM_DESCRIPTION, required = true, schema = @Schema(defaultValue = "DEVICE")) @PathVariable("entityType") String entityType,
            @Parameter(description = ENTITY_ID_PARAM_DESCRIPTION, required = true) @PathVariable("entityId") String entityIdStr,
            @Parameter(description = PAGE_SIZE_DESCRIPTION, required = true) @RequestParam int pageSize,
            @Parameter(description = PAGE_NUMBER_DESCRIPTION, required = true) @RequestParam int page,
            @Parameter(description = CF_TEXT_SEARCH_DESCRIPTION) @RequestParam(required = false) String textSearch,
            @Parameter(description = SORT_PROPERTY_DESCRIPTION, schema = @Schema(allowableValues = {"createdTime", "name"})) @RequestParam(required = false) String sortProperty,
            @Parameter(description = SORT_ORDER_DESCRIPTION, schema = @Schema(allowableValues = {"ASC", "DESC"})) @RequestParam(required = false) String sortOrder) throws ThingsboardException {
        PageLink pageLink = createPageLink(pageSize, page, textSearch, sortProperty, sortOrder);
        checkParameter("entityId", entityIdStr);
        EntityId entityId = EntityIdFactory.getByTypeAndUuid(entityType, entityIdStr);
        checkEntityId(entityId, Operation.READ_CALCULATED_FIELD);
        return checkNotNull(tbCalculatedFieldService.findAllByTenantIdAndEntityId(entityId, getCurrentUser(), pageLink));
    }

    @ApiOperation(value = "Delete Calculated Field (deleteCalculatedField)",
            notes = "Deletes the calculated field. Referencing non-existing Calculated Field Id will cause an error." + TENANT_OR_CUSTOMER_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/calculatedField/{calculatedFieldId}", method = RequestMethod.DELETE)
    @ResponseStatus(value = HttpStatus.OK)
    public void deleteCalculatedField(@PathVariable(CALCULATED_FIELD_ID) String strCalculatedField) throws Exception {
        checkParameter(CALCULATED_FIELD_ID, strCalculatedField);
        CalculatedFieldId calculatedFieldId = new CalculatedFieldId(toUUID(strCalculatedField));
        CalculatedField calculatedField = tbCalculatedFieldService.findById(calculatedFieldId, getCurrentUser());
        checkEntityId(calculatedField.getEntityId(), Operation.WRITE_CALCULATED_FIELD);
        tbCalculatedFieldService.delete(calculatedField, getCurrentUser());
    }

    @ApiOperation(value = "Test Script expression",
            notes = TEST_SCRIPT_EXPRESSION + TENANT_AUTHORITY_PARAGRAPH)
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    @RequestMapping(value = "/calculatedField/testScript", method = RequestMethod.POST)
    @ResponseBody
    public JsonNode testScript(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Test calculated field TBEL expression.")
            @RequestBody JsonNode inputParams) {
        String expression = inputParams.get("expression").asText();
        String[] argNames = JacksonUtil.treeToValue(inputParams.get("argNames"), String[].class);
        Map<String, Object> arguments = Objects.requireNonNullElse(
                JacksonUtil.convertValue(inputParams.get("arguments"), new TypeReference<Map<String, Object>>() {
                }),
                Collections.emptyMap()
        );

        String output = "";
        String errorText = "";

        try {
            if (tbelInvokeService == null) {
                throw new IllegalArgumentException("TBEL script engine is disabled!");
            }

            CalculatedFieldScriptEngine calculatedFieldScriptEngine = new CalculatedFieldTbelScriptEngine(
                    getTenantId(),
                    tbelInvokeService,
                    expression,
                    argNames
            );

            Object[] args = Arrays.stream(argNames)
                    .map(arguments::get)
                    .toArray();

            JsonNode json = calculatedFieldScriptEngine.executeJsonAsync(args).get(TIMEOUT, TimeUnit.SECONDS);
            output = JacksonUtil.toString(json);
        } catch (Exception e) {
            log.error("Error evaluating expression", e);
            errorText = e.getMessage();
        }

        ObjectNode result = JacksonUtil.newObjectNode();
        result.put("output", output);
        result.put("error", errorText);
        return result;
    }

}
