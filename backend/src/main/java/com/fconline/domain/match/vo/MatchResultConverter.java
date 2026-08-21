package com.fconline.domain.match.vo;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class MatchResultConverter implements AttributeConverter<MatchResult, String> {

    @Override
    public String convertToDatabaseColumn(MatchResult attribute) {
        return attribute == null ? null : attribute.label();
    }

    @Override
    public MatchResult convertToEntityAttribute(String dbData) {
        return dbData == null ? null : MatchResult.fromLabel(dbData);
    }
}
