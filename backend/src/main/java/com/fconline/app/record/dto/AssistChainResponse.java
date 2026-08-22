package com.fconline.app.record.dto;

public record AssistChainResponse(String assisterSpId, String assisterName,
                                   String scorerSpId, String scorerName, long goals) {
}
