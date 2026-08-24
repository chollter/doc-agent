package com.gcll.ticketagent.api;

import com.gcll.ticketagent.api.dto.DocumentSummaryResponse;
import com.gcll.ticketagent.summary.DocumentSummaryService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/summary")
public class DocumentSummaryController {
    private final DocumentSummaryService service;

    public DocumentSummaryController(DocumentSummaryService service) { this.service = service; }

    @PostMapping(path = "/runs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentSummaryResponse summarize(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String instruction
    ) throws IOException {
        return service.summarize(file, instruction);
    }
}
