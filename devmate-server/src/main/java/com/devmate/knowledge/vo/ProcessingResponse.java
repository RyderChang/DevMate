package com.devmate.knowledge.vo;

/** Metadata only; the latest attempt can fail while an earlier complete generation remains active. */
public record ProcessingResponse(ProcessingSummary latest, ProcessingSummary active,
                                 String positionBasis, boolean indexed) {
    public ProcessingResponse(ProcessingSummary latest, ProcessingSummary active) {
        this(latest, active, "NORMALIZED_UNICODE_CODE_POINT", false);
    }
}
