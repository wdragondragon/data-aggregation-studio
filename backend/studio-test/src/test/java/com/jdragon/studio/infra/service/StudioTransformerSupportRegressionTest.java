package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.DataServiceResponseParamView;
import com.jdragon.studio.dto.model.TransformerBinding;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StudioTransformerSupportRegressionTest {

    private final StudioTransformerSupport transformerSupport = new StudioTransformerSupport(new ObjectMapper());

    @Test
    void responseTransformerConfigShouldUseResponseParamOrderColumnIndex() {
        DataServiceResponseParamView phone = responseParam("phone", transformer("date_mask", Arrays.asList("hide", 2, 4)));
        DataServiceResponseParamView name = responseParam("name", null);

        List<Map<String, Object>> transformers = transformerSupport.buildAggregationTransformersForResponses(
                Arrays.asList(phone, name),
                Arrays.asList("id", "phone", "name"),
                true);

        assertEquals(1, transformers.size());
        assertEquals("date_mask", transformers.get(0).get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> parameter = (Map<String, Object>) transformers.get(0).get("parameter");
        assertEquals(1, parameter.get("columnIndex"));
        assertIterableEquals(Arrays.asList("hide", 2, 4), castList(parameter.get("paras")));
    }

    @Test
    void onlineResponseTransformersShouldRejectUnsafeRules() {
        DataServiceResponseParamView responseParam = responseParam("name", transformer("dx_filter", Collections.emptyList()));

        assertThrows(StudioException.class,
                () -> transformerSupport.validateOnlineResponseTransformers(Collections.singletonList(responseParam)));

        DataServiceResponseParamView builtinFilter = responseParam("name", transformer("range_number_filter", Collections.emptyList()));
        assertThrows(StudioException.class,
                () -> transformerSupport.validateOnlineResponseTransformers(Collections.singletonList(builtinFilter)));
    }

    @Test
    void emptyResponseTransformersShouldKeepRowsUnchanged() {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", 1L);
        row.put("name", "alice");
        List<Map<String, Object>> rows = Collections.singletonList(row);

        List<Map<String, Object>> transformed = transformerSupport.applyOnlineResponseTransformers(
                rows,
                Collections.singletonList(responseParam("name", null)));

        assertSame(rows, transformed);
        assertEquals("alice", transformed.get(0).get("name"));
    }

    private DataServiceResponseParamView responseParam(String paramName, TransformerBinding transformer) {
        DataServiceResponseParamView view = new DataServiceResponseParamView();
        view.setEnabled(true);
        view.setParamName(paramName);
        view.setFieldName(paramName);
        view.setTransformers(transformer == null
                ? Collections.<TransformerBinding>emptyList()
                : Collections.singletonList(transformer));
        return view;
    }

    private TransformerBinding transformer(String code, List<Object> paras) {
        TransformerBinding binding = new TransformerBinding();
        binding.setTransformerCode(code);
        binding.setMappingCode(code);
        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        parameters.put("paras", paras);
        binding.setParameters(parameters);
        return binding;
    }

    private List<?> castList(Object value) {
        return (List<?>) value;
    }
}
