package com.gcll.ticketagent.api.dto;

import java.util.List;

public record LogFileUploadResponse(
        int uploaded,
        List<String> files
) {
}
