package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DataServiceResolveFieldsView {
    private List<DataServiceFieldView> fields = new ArrayList<DataServiceFieldView>();
    private List<DataServiceRequestParamView> requestParams = new ArrayList<DataServiceRequestParamView>();
    private List<DataServiceResponseParamView> responseParams = new ArrayList<DataServiceResponseParamView>();
}
