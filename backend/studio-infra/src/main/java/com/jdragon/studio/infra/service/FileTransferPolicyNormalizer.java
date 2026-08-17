package com.jdragon.studio.infra.service;

import com.jdragon.aggregation.transfer.TransferContractException;
import com.jdragon.aggregation.transfer.TransferSpecNormalizer;
import com.jdragon.aggregation.transfer.model.TransferPolicy;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;

import java.util.Map;

public final class FileTransferPolicyNormalizer {

    public static final int DEFAULT_FRAME_COUNT = TransferPolicy.DEFAULT_VERIFICATION_FRAME_COUNT;
    public static final long DEFAULT_FRAME_SIZE_BYTES =
            TransferPolicy.DEFAULT_VERIFICATION_FRAME_SIZE_BYTES;

    private FileTransferPolicyNormalizer() {
    }

    public static Map<String, Object> normalize(Map<String, Object> values) {
        try {
            return TransferSpecNormalizer.normalizePolicyMap(values);
        } catch (TransferContractException exception) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, exception.getMessage());
        }
    }
}
