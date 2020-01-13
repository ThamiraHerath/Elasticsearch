/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.type;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * SQL-related information about an index field that cannot be supported by SQL.
 * All the subfields (properties) of an unsupported type should also be unsupported.
 */
public class UnsupportedEsField extends EsField {

    private String originalType;

    public UnsupportedEsField(String name, String originalType) {
        this(name, originalType, new HashMap<>());
    }
    
    public UnsupportedEsField(String name, String originalType, Map<String, EsField> properties) {
        super(name, DataType.UNSUPPORTED, properties, false);
        this.originalType = originalType;
    }

    public String getOriginalType() {
        return originalType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        UnsupportedEsField that = (UnsupportedEsField) o;
        return Objects.equals(originalType, that.originalType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), originalType);
    }
}
