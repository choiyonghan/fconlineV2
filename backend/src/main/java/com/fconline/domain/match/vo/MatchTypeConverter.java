package com.fconline.domain.match.vo;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class MatchTypeConverter implements AttributeConverter<MatchType, Integer> {

    @Override
    public Integer convertToDatabaseColumn(MatchType attribute) {
        return attribute == null ? null : attribute.code();
    }

    @Override
    public MatchType convertToEntityAttribute(Integer dbData) {
        return dbData == null ? null : MatchType.fromCode(dbData);
    }
}
