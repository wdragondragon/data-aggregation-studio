package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.DataIngestionPayloadMode;
import com.jdragon.studio.dto.enums.DataServiceValueType;
import com.jdragon.studio.dto.enums.FieldValueType;
import com.jdragon.studio.dto.enums.WebServiceSoapVersion;
import com.jdragon.studio.dto.model.DataIngestionFieldMapping;
import com.jdragon.studio.dto.model.DataIngestionInvokeResult;
import com.jdragon.studio.dto.model.DataIngestionServiceView;
import com.jdragon.studio.dto.model.DataServiceDefinitionView;
import com.jdragon.studio.dto.model.DataServicePublishParamView;
import com.jdragon.studio.dto.model.DataServiceResponseParamView;
import com.jdragon.studio.dto.model.WebServiceConfig;
import com.jdragon.studio.dto.model.WebServicePreviewView;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class WebServiceSupport {

    private static final String SOAP_11_NS = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String SOAP_12_NS = "http://www.w3.org/2003/05/soap-envelope";
    private static final String WSDL_NS = "http://schemas.xmlsoap.org/wsdl/";
    private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema";
    private static final String SOAP_WSDL_NS = "http://schemas.xmlsoap.org/wsdl/soap/";
    private static final String DATA_SERVICE_DOMAIN = "data-service";
    private static final String DEFAULT_RESPONSE_DATA_NODE_PATH = "table.row";

    WebServiceConfig normalizeConfig(WebServiceConfig input, String domain, String serviceCode) {
        WebServiceConfig result = new WebServiceConfig();
        String safeCode = hasText(serviceCode) ? serviceCode.trim() : "service";
        result.setEnabled(input == null ? Boolean.FALSE : Boolean.TRUE.equals(input.getEnabled()));
        result.setSoapVersion(input == null || input.getSoapVersion() == null ? WebServiceSoapVersion.SOAP_11 : input.getSoapVersion());
        result.setNamespaceUri(normalizeText(input == null ? null : input.getNamespaceUri(),
                "http://studio.jdragon.com/" + domain + "/" + safeCode));
        result.setOperationName(normalizeName(input == null ? null : input.getOperationName(), safeCode));
        result.setSoapAction(normalizeText(input == null ? null : input.getSoapAction(), null));
        result.setRequestRootName(normalizeName(input == null ? null : input.getRequestRootName(), result.getOperationName()));
        result.setResponseRootName(normalizeName(input == null ? null : input.getResponseRootName(),
                result.getOperationName() + "Response"));
        result.setResponseDataNodePath(DATA_SERVICE_DOMAIN.equals(domain)
                ? normalizeDataNodePath(input == null ? null : input.getResponseDataNodePath(), DEFAULT_RESPONSE_DATA_NODE_PATH)
                : null);
        return result;
    }

    ParsedSoapRequest parse(String envelope) {
        if (!hasText(envelope)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "SOAP envelope is required");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new InputSource(new StringReader(envelope)));
            Element envelopeElement = document.getDocumentElement();
            if (envelopeElement == null || !"Envelope".equals(localName(envelopeElement))) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "SOAP Envelope is required");
            }
            WebServiceSoapVersion version = SOAP_12_NS.equals(envelopeElement.getNamespaceURI())
                    ? WebServiceSoapVersion.SOAP_12 : WebServiceSoapVersion.SOAP_11;
            Element header = directChild(envelopeElement, "Header");
            Element body = directChild(envelopeElement, "Body");
            if (body == null) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "SOAP Body is required");
            }
            Element operation = firstElementChild(body);
            if (operation == null) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "SOAP operation body is required");
            }
            return new ParsedSoapRequest(version, localName(operation), childrenToMap(header), childrenToMap(operation));
        } catch (StudioException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Invalid SOAP envelope: " + rootMessage(ex));
        }
    }

    String tokenFromSoapHeader(ParsedSoapRequest request, String... names) {
        if (request == null || request.headers == null || names == null) {
            return null;
        }
        for (String name : names) {
            Object value = lookupIgnoreCase(request.headers, name);
            if (value != null && hasText(String.valueOf(value))) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    WebServicePreviewView previewForDataService(DataServiceDefinitionView service, String endpointPath) {
        WebServiceConfig config = normalizeConfig(service.getWebserviceConfig(), "data-service", service.getServiceCode());
        WebServicePreviewView view = basePreview(config, endpointPath);
        view.setWsdl(wsdlForDataService(service, config, endpointPath));
        view.setSampleRequest(sampleRequest(config, dataServiceRequestFields(service)));
        view.setSampleResponse(successEnvelope(config, sampleDataServiceResponse(service), config.getSoapVersion()));
        return view;
    }

    WebServicePreviewView previewForDataIngestion(DataIngestionServiceView service, String endpointPath) {
        WebServiceConfig config = normalizeConfig(service.getWebserviceConfig(), "data-ingestion-service", service.getServiceCode());
        WebServicePreviewView view = basePreview(config, endpointPath);
        view.setWsdl(wsdlForDataIngestion(service, config, endpointPath));
        view.setSampleRequest(sampleRequest(config, dataIngestionRequestFields(service)));
        view.setSampleResponse(successEnvelope(config, sampleIngestionResult(service), config.getSoapVersion()));
        return view;
    }

    String wsdlForDataService(DataServiceDefinitionView service, WebServiceConfig config, String endpointPath) {
        List<FieldSpec> inputFields = dataServiceRequestFields(service);
        List<FieldSpec> outputFields = new ArrayList<FieldSpec>();
        outputFields.add(new FieldSpec("pageNum", "int", "1"));
        outputFields.add(new FieldSpec("pageSize", "int", "10"));
        outputFields.add(new FieldSpec("pages", "long", "1"));
        outputFields.add(new FieldSpec(firstSegment(config.getResponseDataNodePath()), "string", null));
        return wsdl(service.getServiceName(), config, endpointPath, inputFields, outputFields);
    }

    String wsdlForDataIngestion(DataIngestionServiceView service, WebServiceConfig config, String endpointPath) {
        List<FieldSpec> outputFields = new ArrayList<FieldSpec>();
        outputFields.add(new FieldSpec("requestId", "string", "1"));
        outputFields.add(new FieldSpec("serviceCode", "string", service.getServiceCode()));
        outputFields.add(new FieldSpec("receivedCount", "long", "1"));
        outputFields.add(new FieldSpec("successCount", "long", "1"));
        outputFields.add(new FieldSpec("failedCount", "long", "0"));
        outputFields.add(new FieldSpec("status", "string", "SUCCESS"));
        return wsdl(service.getServiceName(), config, endpointPath, dataIngestionRequestFields(service), outputFields);
    }

    String successEnvelope(WebServiceConfig config, Object payload, WebServiceSoapVersion version) {
        WebServiceSoapVersion soapVersion = version == null ? config.getSoapVersion() : version;
        String soapNs = soapVersion == WebServiceSoapVersion.SOAP_12 ? SOAP_12_NS : SOAP_11_NS;
        StringBuilder builder = new StringBuilder(2048);
        builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        builder.append("<soap:Envelope xmlns:soap=\"").append(soapNs).append("\" xmlns:tns=\"")
                .append(escapeXml(config.getNamespaceUri()))
                .append("\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">");
        builder.append("<soap:Body>");
        builder.append("<tns:").append(config.getResponseRootName()).append(">");
        appendValue(builder, responsePayload(config, payload), "result");
        builder.append("</tns:").append(config.getResponseRootName()).append(">");
        builder.append("</soap:Body></soap:Envelope>");
        return builder.toString();
    }

    String faultEnvelope(WebServiceSoapVersion version, String code, String message) {
        WebServiceSoapVersion soapVersion = version == null ? WebServiceSoapVersion.SOAP_11 : version;
        String soapNs = soapVersion == WebServiceSoapVersion.SOAP_12 ? SOAP_12_NS : SOAP_11_NS;
        String safeCode = hasText(code) ? code.trim() : StudioErrorCode.INTERNAL_SERVER_ERROR;
        String safeMessage = hasText(message) ? message.trim() : "WebService invocation failed";
        StringBuilder builder = new StringBuilder(1024);
        builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        builder.append("<soap:Envelope xmlns:soap=\"").append(soapNs).append("\"><soap:Body>");
        if (soapVersion == WebServiceSoapVersion.SOAP_12) {
            builder.append("<soap:Fault><soap:Code><soap:Value>soap:Receiver</soap:Value></soap:Code>");
            builder.append("<soap:Reason><soap:Text xml:lang=\"zh-CN\">").append(escapeXml(safeMessage)).append("</soap:Text></soap:Reason>");
            builder.append("<soap:Detail><code>").append(escapeXml(safeCode)).append("</code></soap:Detail></soap:Fault>");
        } else {
            builder.append("<soap:Fault><faultcode>").append(escapeXml(safeCode)).append("</faultcode>");
            builder.append("<faultstring>").append(escapeXml(safeMessage)).append("</faultstring></soap:Fault>");
        }
        builder.append("</soap:Body></soap:Envelope>");
        return builder.toString();
    }

    Map<String, Object> mergeHeaders(Map<String, Object> httpHeaders, Map<String, Object> soapHeaders) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (soapHeaders != null) {
            result.putAll(soapHeaders);
        }
        if (httpHeaders != null) {
            result.putAll(httpHeaders);
        }
        return result;
    }

    Object ingestionResultToPayload(DataIngestionInvokeResult result) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("requestId", result == null ? null : result.getRequestId());
        payload.put("serviceCode", result == null ? null : result.getServiceCode());
        payload.put("receivedCount", result == null ? null : result.getReceivedCount());
        payload.put("successCount", result == null ? null : result.getSuccessCount());
        payload.put("failedCount", result == null ? null : result.getFailedCount());
        payload.put("status", result == null ? null : result.getStatus());
        return payload;
    }

    private WebServicePreviewView basePreview(WebServiceConfig config, String endpointPath) {
        WebServicePreviewView view = new WebServicePreviewView();
        view.setEndpointPath(endpointPath);
        view.setWsdlPath(endpointPath + "?wsdl");
        view.setSoapAction(config.getSoapAction());
        view.setNamespaceUri(config.getNamespaceUri());
        view.setOperationName(config.getOperationName());
        return view;
    }

    private List<FieldSpec> dataServiceRequestFields(DataServiceDefinitionView service) {
        List<FieldSpec> fields = new ArrayList<FieldSpec>();
        if (service == null || service.getPublishParams() == null) {
            return fields;
        }
        for (DataServicePublishParamView param : service.getPublishParams()) {
            if (param == null || !hasText(param.getFrontendParamName())) {
                continue;
            }
            fields.add(new FieldSpec(param.getFrontendParamName(), typeName(param.getValueType()), valueOrExample(param.getExampleValue(), param.getDefaultValue())));
        }
        return fields;
    }

    private List<FieldSpec> dataIngestionRequestFields(DataIngestionServiceView service) {
        List<FieldSpec> fields = new ArrayList<FieldSpec>();
        if (service == null || service.getFieldMappings() == null) {
            return fields;
        }
        boolean arrayMode = service.getPayloadMode() == DataIngestionPayloadMode.ARRAY;
        if (arrayMode) {
            fields.add(new FieldSpec("records", "string", null));
            return fields;
        }
        for (DataIngestionFieldMapping mapping : service.getFieldMappings()) {
            if (mapping == null || !hasText(mapping.getSourceField())) {
                continue;
            }
            fields.add(new FieldSpec(firstSegment(mapping.getSourceField()), typeName(mapping.getValueType()), mapping.getDefaultValue()));
        }
        return fields;
    }

    private Map<String, Object> sampleDataServiceResponse(DataServiceDefinitionView service) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("pageNum", Integer.valueOf(1));
        root.put("pageSize", Integer.valueOf(10));
        root.put("pages", Integer.valueOf(1));
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        if (service != null && service.getResponseParams() != null) {
            for (DataServiceResponseParamView param : service.getResponseParams()) {
                if (param != null && Boolean.TRUE.equals(param.getEnabled()) && hasText(param.getParamName())) {
                    row.put(param.getParamName(), valueOrExample(param.getExampleValue(), null));
                }
            }
        }
        Map<String, Object> table = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(row);
        table.put("bodies", rows);
        root.put("table", table);
        return root;
    }

    private Object responsePayload(WebServiceConfig config, Object payload) {
        if (config == null || !hasText(config.getResponseDataNodePath()) || !(payload instanceof Map<?, ?>)) {
            return payload;
        }
        Map<String, Object> source = castMap((Map<?, ?>) payload);
        Object rowsValue = readPath(source, "table.bodies");
        if (!(rowsValue instanceof List<?>)) {
            Object configuredValue = readPath(source, config.getResponseDataNodePath());
            if (configuredValue instanceof List<?>) {
                rowsValue = configuredValue;
            } else if (configuredValue != null) {
                List<Object> singleton = new ArrayList<Object>();
                singleton.add(configuredValue);
                rowsValue = singleton;
            }
        }
        List<?> rows = rowsValue instanceof List<?> ? (List<?>) rowsValue : new ArrayList<Object>();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (!"table".equals(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        putRepeatedPath(result, config.getResponseDataNodePath(), rows);
        return result;
    }

    private Map<String, Object> sampleIngestionResult(DataIngestionServiceView service) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("requestId", "1");
        root.put("serviceCode", service == null ? "service" : service.getServiceCode());
        root.put("receivedCount", Integer.valueOf(1));
        root.put("successCount", Integer.valueOf(1));
        root.put("failedCount", Integer.valueOf(0));
        root.put("status", "SUCCESS");
        return root;
    }

    private String sampleRequest(WebServiceConfig config, List<FieldSpec> fields) {
        String soapNs = config.getSoapVersion() == WebServiceSoapVersion.SOAP_12 ? SOAP_12_NS : SOAP_11_NS;
        StringBuilder builder = new StringBuilder(2048);
        builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        builder.append("<soap:Envelope xmlns:soap=\"").append(soapNs).append("\" xmlns:tns=\"")
                .append(escapeXml(config.getNamespaceUri())).append("\">");
        builder.append("<soap:Header><tns:token>your-token</tns:token></soap:Header>");
        builder.append("<soap:Body><tns:").append(config.getRequestRootName()).append(">");
        for (FieldSpec field : fields) {
            builder.append('<').append(field.name).append('>')
                    .append(escapeXml(valueOrExample(field.example, null)))
                    .append("</").append(field.name).append('>');
        }
        builder.append("</tns:").append(config.getRequestRootName()).append("></soap:Body></soap:Envelope>");
        return builder.toString();
    }

    private String wsdl(String serviceName,
                        WebServiceConfig config,
                        String endpointPath,
                        List<FieldSpec> inputFields,
                        List<FieldSpec> outputFields) {
        String name = normalizeName(serviceName, config.getOperationName());
        StringBuilder builder = new StringBuilder(8192);
        builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        builder.append("<wsdl:definitions xmlns:wsdl=\"").append(WSDL_NS)
                .append("\" xmlns:soap=\"").append(SOAP_WSDL_NS)
                .append("\" xmlns:xsd=\"").append(XSD_NS)
                .append("\" xmlns:tns=\"").append(escapeXml(config.getNamespaceUri()))
                .append("\" targetNamespace=\"").append(escapeXml(config.getNamespaceUri())).append("\">");
        builder.append("<wsdl:types><xsd:schema targetNamespace=\"").append(escapeXml(config.getNamespaceUri()))
                .append("\" elementFormDefault=\"qualified\">");
        appendElement(builder, config.getRequestRootName(), inputFields);
        appendElement(builder, config.getResponseRootName(), outputFields);
        builder.append("</xsd:schema></wsdl:types>");
        appendMessage(builder, config.getRequestRootName());
        appendMessage(builder, config.getResponseRootName());
        builder.append("<wsdl:portType name=\"").append(name).append("PortType\"><wsdl:operation name=\"")
                .append(config.getOperationName()).append("\"><wsdl:input message=\"tns:")
                .append(config.getRequestRootName()).append("Message\"/><wsdl:output message=\"tns:")
                .append(config.getResponseRootName()).append("Message\"/></wsdl:operation></wsdl:portType>");
        builder.append("<wsdl:binding name=\"").append(name).append("Binding\" type=\"tns:").append(name).append("PortType\">");
        builder.append("<soap:binding style=\"document\" transport=\"http://schemas.xmlsoap.org/soap/http\"/>");
        builder.append("<wsdl:operation name=\"").append(config.getOperationName()).append("\"><soap:operation soapAction=\"")
                .append(escapeXml(config.getSoapAction())).append("\"/><wsdl:input><soap:body use=\"literal\"/></wsdl:input>")
                .append("<wsdl:output><soap:body use=\"literal\"/></wsdl:output></wsdl:operation></wsdl:binding>");
        builder.append("<wsdl:service name=\"").append(name).append("\"><wsdl:port name=\"").append(name)
                .append("Port\" binding=\"tns:").append(name).append("Binding\"><soap:address location=\"")
                .append(escapeXml(endpointPath)).append("\"/></wsdl:port></wsdl:service>");
        builder.append("</wsdl:definitions>");
        return builder.toString();
    }

    private void appendElement(StringBuilder builder, String rootName, List<FieldSpec> fields) {
        builder.append("<xsd:element name=\"").append(rootName).append("\"><xsd:complexType><xsd:sequence>");
        for (FieldSpec field : fields) {
            builder.append("<xsd:element name=\"").append(field.name).append("\" type=\"xsd:")
                    .append(field.xsdType).append("\" minOccurs=\"0\" maxOccurs=\"unbounded\"/>");
        }
        builder.append("</xsd:sequence></xsd:complexType></xsd:element>");
    }

    private void appendMessage(StringBuilder builder, String rootName) {
        builder.append("<wsdl:message name=\"").append(rootName).append("Message\"><wsdl:part name=\"parameters\" element=\"tns:")
                .append(rootName).append("\"/></wsdl:message>");
    }

    @SuppressWarnings("unchecked")
    private void appendValue(StringBuilder builder, Object value, String fallbackName) {
        if (value instanceof RepeatedElements) {
            appendRepeatedElements(builder, (RepeatedElements) value);
        } else if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = normalizeName(String.valueOf(entry.getKey()), fallbackName);
                Object child = entry.getValue();
                if (child instanceof RepeatedElements) {
                    appendRepeatedElements(builder, (RepeatedElements) child);
                } else if (child instanceof List<?>) {
                    appendList(builder, key, (List<?>) child);
                } else if (child == null) {
                    appendNilElement(builder, key);
                } else {
                    builder.append('<').append(key).append('>');
                    appendValue(builder, child, key);
                    builder.append("</").append(key).append('>');
                }
            }
        } else if (value instanceof List<?>) {
            appendList(builder, fallbackName, (List<?>) value);
        } else if (value != null) {
            builder.append(escapeXml(String.valueOf(value)));
        }
    }

    private void appendRepeatedElements(StringBuilder builder, RepeatedElements repeated) {
        if (repeated == null || repeated.values == null || repeated.values.isEmpty()) {
            appendNilElement(builder, repeated == null ? "item" : repeated.elementName);
            return;
        }
        for (Object item : repeated.values) {
            if (item == null) {
                appendNilElement(builder, repeated.elementName);
            } else {
                builder.append('<').append(repeated.elementName).append('>');
                appendValue(builder, item, repeated.elementName);
                builder.append("</").append(repeated.elementName).append('>');
            }
        }
    }

    private void appendList(StringBuilder builder, String key, List<?> values) {
        String itemName = "bodies".equals(key) ? "row" : "item";
        for (Object item : values) {
            if (item == null) {
                appendNilElement(builder, itemName);
            } else {
                builder.append('<').append(itemName).append('>');
                appendValue(builder, item, itemName);
                builder.append("</").append(itemName).append('>');
            }
        }
    }

    private void appendNilElement(StringBuilder builder, String key) {
        builder.append('<').append(key).append(" xsi:nil=\"true\"/>");
    }

    @SuppressWarnings("unchecked")
    private void putRepeatedPath(Map<String, Object> root, String path, List<?> values) {
        List<String> segments = pathSegments(path);
        if (segments.isEmpty()) {
            return;
        }
        Map<String, Object> current = root;
        for (int index = 0; index < segments.size() - 1; index++) {
            String segment = segments.get(index);
            Object next = current.get(segment);
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<String, Object>();
                current.put(segment, next);
            }
            current = (Map<String, Object>) next;
        }
        String elementName = segments.get(segments.size() - 1);
        current.put(elementName, new RepeatedElements(elementName, values));
    }

    private Object readPath(Object source, String path) {
        Object current = source;
        for (String segment : pathSegments(path)) {
            if (!(current instanceof Map<?, ?>)) {
                return null;
            }
            current = castMap((Map<?, ?>) current).get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private Map<String, Object> castMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (source == null) {
            return result;
        }
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private Map<String, Object> childrenToMap(Element parent) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (parent == null) {
            return result;
        }
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node instanceof Element) {
                putRepeated(result, localName(node), elementValue((Element) node));
            }
        }
        return result;
    }

    private Object elementValue(Element element) {
        if (isNilElement(element)) {
            return null;
        }
        Map<String, Object> children = childrenToMap(element);
        if (!children.isEmpty()) {
            return children;
        }
        return element.getTextContent() == null ? null : element.getTextContent().trim();
    }

    private boolean isNilElement(Element element) {
        if (element == null) {
            return false;
        }
        String nil = element.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "nil");
        if (!hasText(nil)) {
            nil = element.getAttribute("nil");
        }
        return "true".equalsIgnoreCase(nil) || "1".equals(nil);
    }

    private void putRepeated(Map<String, Object> target, String key, Object value) {
        if (!target.containsKey(key)) {
            target.put(key, value);
            return;
        }
        Object existing = target.get(key);
        List<Object> values;
        if (existing instanceof List<?>) {
            values = new ArrayList<Object>((List<?>) existing);
        } else {
            values = new ArrayList<Object>();
            values.add(existing);
        }
        values.add(value);
        target.put(key, values);
    }

    private Element directChild(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node instanceof Element && localName.equals(localName(node))) {
                return (Element) node;
            }
        }
        return null;
    }

    private Element firstElementChild(Element parent) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node instanceof Element) {
                return (Element) node;
            }
        }
        return null;
    }

    private Object lookupIgnoreCase(Map<String, Object> source, String key) {
        if (source == null || key == null) {
            return null;
        }
        if (source.containsKey(key)) {
            return source.get(key);
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String localName(Node node) {
        String localName = node.getLocalName();
        if (hasText(localName)) {
            return localName;
        }
        String name = node.getNodeName();
        int colon = name == null ? -1 : name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private String typeName(DataServiceValueType type) {
        if (type == DataServiceValueType.INT) {
            return "int";
        }
        if (type == DataServiceValueType.FLOAT) {
            return "double";
        }
        return "string";
    }

    private String typeName(FieldValueType type) {
        if (type == FieldValueType.INTEGER || type == FieldValueType.LONG) {
            return "long";
        }
        if (type == FieldValueType.DECIMAL) {
            return "double";
        }
        if (type == FieldValueType.BOOLEAN) {
            return "boolean";
        }
        return "string";
    }

    private String firstSegment(String path) {
        if (!hasText(path)) {
            return "value";
        }
        String trimmed = path.trim();
        int dot = trimmed.indexOf('.');
        return dot >= 0 ? trimmed.substring(0, dot) : trimmed;
    }

    private String valueOrExample(String value, String fallback) {
        if (hasText(value)) {
            return value.trim();
        }
        if (hasText(fallback)) {
            return fallback.trim();
        }
        return "sample";
    }

    private String normalizeText(String value, String defaultValue) {
        return hasText(value) ? value.trim() : defaultValue;
    }

    private String normalizeDataNodePath(String value, String defaultValue) {
        String raw = hasText(value) ? value.trim() : defaultValue;
        List<String> segments = pathSegments(raw);
        if (segments.isEmpty()) {
            return defaultValue;
        }
        List<String> normalized = new ArrayList<String>();
        for (String segment : segments) {
            normalized.add(normalizeName(segment, "node"));
        }
        return String.join(".", normalized);
    }

    private List<String> pathSegments(String path) {
        List<String> segments = new ArrayList<String>();
        if (!hasText(path)) {
            return segments;
        }
        for (String segment : path.trim().split("\\.")) {
            if (hasText(segment)) {
                segments.add(segment.trim());
            }
        }
        return segments;
    }

    private String normalizeName(String value, String defaultValue) {
        String raw = hasText(value) ? value.trim() : defaultValue;
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < raw.length(); index++) {
            char current = raw.charAt(index);
            boolean allowed = current == '_' || current == '-' || current == '.' || Character.isLetterOrDigit(current);
            if (allowed) {
                builder.append(current);
            }
        }
        String normalized = builder.length() == 0 ? "service" : builder.toString();
        char first = normalized.charAt(0);
        return Character.isLetter(first) || first == '_' ? normalized : "n" + normalized;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null || current.getMessage() == null ? "unknown error" : current.getMessage();
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static final class RepeatedElements {
        private final String elementName;
        private final List<?> values;

        private RepeatedElements(String elementName, List<?> values) {
            this.elementName = elementName;
            this.values = values;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    static final class ParsedSoapRequest {
        private final WebServiceSoapVersion soapVersion;
        private final String operationName;
        private final Map<String, Object> headers;
        private final Map<String, Object> body;

        ParsedSoapRequest(WebServiceSoapVersion soapVersion,
                          String operationName,
                          Map<String, Object> headers,
                          Map<String, Object> body) {
            this.soapVersion = soapVersion;
            this.operationName = operationName;
            this.headers = headers;
            this.body = body;
        }

        WebServiceSoapVersion getSoapVersion() {
            return soapVersion;
        }

        String getOperationName() {
            return operationName;
        }

        Map<String, Object> getHeaders() {
            return headers;
        }

        Map<String, Object> getBody() {
            return body;
        }
    }

    private static final class FieldSpec {
        private final String name;
        private final String xsdType;
        private final String example;

        private FieldSpec(String name, String xsdType, String example) {
            this.name = name;
            this.xsdType = xsdType == null ? "string" : xsdType.toLowerCase(Locale.ENGLISH);
            this.example = example;
        }
    }
}
