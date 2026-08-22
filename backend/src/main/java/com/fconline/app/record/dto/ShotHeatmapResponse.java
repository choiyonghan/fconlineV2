package com.fconline.app.record.dto;

import java.util.List;

public record ShotHeatmapResponse(String ouid, List<ShotPointResponse> points) {
}
