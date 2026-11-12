package io.github.luisfm44.caseware.summary;

import io.github.luisfm44.caseware.summary.SummaryService.Generation;
import io.github.luisfm44.caseware.summary.SummaryService.View;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class SummaryController {

    private final SummaryService service;

    SummaryController(SummaryService service) {
        this.service = service;
    }

    @PostMapping("/summaries")
    ResponseEntity<Generation> generate(@RequestBody ComparisonRequest request) {
        var result = service.generate(request.baselineRevision(), request.targetRevision(), request.locale());
        var status = switch (result.status()) {
            case HASH_MISMATCH, INVALID_TEMPLATE -> HttpStatus.UNPROCESSABLE_CONTENT;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).body(result);
    }

    @PostMapping("/summaries/{version}/views")
    @ResponseStatus(HttpStatus.CREATED)
    View recordView(@PathVariable String version) {
        return service.recordView(version);
    }

    @GetMapping("/views/{receiptId}")
    View view(@PathVariable String receiptId) {
        return service.view(receiptId);
    }

    record ComparisonRequest(String baselineRevision, String targetRevision, String locale) {}
}
