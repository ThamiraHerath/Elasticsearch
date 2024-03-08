/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.index.fieldvisitor;

import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.StoredFieldVisitor;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.index.IndexMode;
import org.elasticsearch.index.mapper.IdFieldMapper;
import org.elasticsearch.index.mapper.IgnoredFieldMapper;
import org.elasticsearch.index.mapper.LegacyTypeFieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.RoutingFieldMapper;
import org.elasticsearch.index.mapper.SourceFieldMapper;
import org.elasticsearch.index.mapper.Uid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static java.util.Collections.emptyMap;

/**
 * Base {@link StoredFieldVisitor} that retrieves all non-redundant metadata.
 */
public class FieldsVisitor extends FieldNamesProvidingStoredFieldsVisitor {
    private static final Set<String> BASE_REQUIRED_FIELDS = Set.of(IdFieldMapper.NAME, RoutingFieldMapper.NAME);

    private final boolean loadSource;
    private final String sourceFieldName;
    private final IndexMode indexMode;
    private final Set<String> requiredFields;
    protected BytesReference source;
    protected String id;
    protected Map<String, List<Object>> fieldsValues;

    public FieldsVisitor(boolean loadSource) {
        this(loadSource, SourceFieldMapper.NAME, IndexMode.STANDARD);
    }

    @SuppressWarnings("this-escape")
    public FieldsVisitor(boolean loadSource, String sourceFieldName, IndexMode indexMode) {
        this.loadSource = loadSource;
        this.sourceFieldName = sourceFieldName;
        this.indexMode = indexMode;
        requiredFields = new HashSet<>();
        reset();
    }

    @Override
    public Status needsField(FieldInfo fieldInfo) {
        if (requiredFields.remove(fieldInfo.name)) {
            return Status.YES;
        }
        // Always load _ignored to be explicit about ignored fields
        // This works because _ignored is added as the first metadata mapper,
        // so its stored fields always appear first in the list.
        if (IgnoredFieldMapper.NAME.equals(fieldInfo.name)) {
            return Status.YES;
        }
        // support _uid for loading older indices
        if ("_uid".equals(fieldInfo.name)) {
            if (requiredFields.remove(IdFieldMapper.NAME) || requiredFields.remove(LegacyTypeFieldMapper.NAME)) {
                return Status.YES;
            }
        }
        // All these fields are single-valued so we can stop when the set is
        // empty
        return requiredFields.isEmpty() ? Status.STOP : Status.NO;
    }

    @Override
    public Set<String> getFieldNames() {
        return requiredFields;
    }

    public final void postProcess(Function<String, MappedFieldType> fieldTypeLookup) {
        for (Map.Entry<String, List<Object>> entry : fields().entrySet()) {
            MappedFieldType fieldType = fieldTypeLookup.apply(entry.getKey());
            if (fieldType == null) {
                continue; // TODO this is lame
            }
            List<Object> fieldValues = entry.getValue();
            for (int i = 0; i < fieldValues.size(); i++) {
                fieldValues.set(i, fieldType.valueForDisplay(fieldValues.get(i)));
            }
        }
    }

    @Override
    public void binaryField(FieldInfo fieldInfo, byte[] value) {
        binaryField(fieldInfo, new BytesRef(value));
    }

    public void binaryField(FieldInfo fieldInfo, BytesRef value) {
        if (sourceFieldName.equals(fieldInfo.name)) {
            source = new BytesArray(value);
        } else if (IdFieldMapper.NAME.equals(fieldInfo.name)) {
            id = Uid.decodeId(value.bytes, value.offset, value.length);
        } else {
            addValue(fieldInfo.name, value);
        }
    }

    @Override
    public void stringField(FieldInfo fieldInfo, String value) {
        assert sourceFieldName.equals(fieldInfo.name) == false : "source field must go through binaryField";
        if ("_uid".equals(fieldInfo.name)) {
            // 5.x-only
            int delimiterIndex = value.indexOf('#'); // type is not allowed to have # in it..., ids can
            String type = value.substring(0, delimiterIndex);
            id = value.substring(delimiterIndex + 1);
            addValue(LegacyTypeFieldMapper.NAME, type);
        } else if (IdFieldMapper.NAME.equals(fieldInfo.name)) {
            // only applies to 5.x indices that have single_type = true
            id = value;
        } else {
            addValue(fieldInfo.name, value);
        }
    }

    @Override
    public void intField(FieldInfo fieldInfo, int value) {
        addValue(fieldInfo.name, value);
    }

    @Override
    public void longField(FieldInfo fieldInfo, long value) {
        addValue(fieldInfo.name, value);
    }

    @Override
    public void floatField(FieldInfo fieldInfo, float value) {
        addValue(fieldInfo.name, value);
    }

    @Override
    public void doubleField(FieldInfo fieldInfo, double value) {
        addValue(fieldInfo.name, value);
    }

    public void objectField(FieldInfo fieldInfo, Object object) {
        assert IdFieldMapper.NAME.equals(fieldInfo.name) == false : "_id field must go through binaryField";
        assert sourceFieldName.equals(fieldInfo.name) == false : "source field must go through binaryField";
        addValue(fieldInfo.name, object);
    }

    public BytesReference source() {
        return source;
    }

    public String id() {
        return id;
    }

    public String routing() {
        if (indexMode == IndexMode.TIME_SERIES && id != null) {
            // In time-series indexes, the routing hash is stored in the first 4 bytes of the id.
            // To retrieve it as routing param, we first need to decode the id, then encode these first 4 bytes.
            byte[] idBytes = Base64.getUrlDecoder().decode(id);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(idBytes, 4));
        }
        if (fieldsValues == null) {
            return null;
        }
        List<Object> values = fieldsValues.get(RoutingFieldMapper.NAME);
        if (values == null || values.isEmpty()) {
            return null;
        }
        assert values.size() == 1;
        return values.get(0).toString();
    }

    public Map<String, List<Object>> fields() {
        return fieldsValues != null ? fieldsValues : emptyMap();
    }

    public void reset() {
        if (fieldsValues != null) fieldsValues.clear();
        source = null;
        id = null;

        requiredFields.addAll(BASE_REQUIRED_FIELDS);
        if (loadSource) {
            requiredFields.add(sourceFieldName);
        }
    }

    void addValue(String name, Object value) {
        if (fieldsValues == null) {
            fieldsValues = new HashMap<>();
        }

        List<Object> values = fieldsValues.get(name);
        if (values == null) {
            values = new ArrayList<>(2);
            fieldsValues.put(name, values);
        }
        values.add(value);
    }
}
