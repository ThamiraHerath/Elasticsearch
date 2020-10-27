/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.search.lookup;

import org.apache.lucene.index.LeafReader;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.index.fieldvisitor.SingleFieldsVisitor;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.TypeFieldMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static java.util.Collections.singletonMap;

public class LeafFieldsLookup implements Map {

    private final Function<String, MappedFieldType> fieldTypeLookup;

    @Nullable
    private final String[] types;

    private final LeafReader reader;

    private int docId = -1;

    private final Map<String, FieldLookup> cachedFieldData = new HashMap<>();

    LeafFieldsLookup(Function<String, MappedFieldType> fieldTypeLookup, @Nullable String[] types, LeafReader reader) {
        this.fieldTypeLookup = fieldTypeLookup;
        this.types = types;
        this.reader = reader;
    }

    public void setDocument(int docId) {
        if (this.docId == docId) { // if we are called with the same docId, don't invalidate source
            return;
        }
        this.docId = docId;
        clearCache();
    }

    @Override
    public Object get(Object key) {
        return loadFieldData(key.toString());
    }

    @Override
    public boolean containsKey(Object key) {
        try {
            loadFieldData(key.toString());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public int size() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isEmpty() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set keySet() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Collection values() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set entrySet() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object put(Object key, Object value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object remove(Object key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void putAll(Map m) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean containsValue(Object value) {
        throw new UnsupportedOperationException();
    }

    private FieldLookup loadFieldData(String name) {
        FieldLookup data = cachedFieldData.get(name);
        if (data == null) {
            MappedFieldType fieldType = fieldTypeLookup.apply(name);
            if (fieldType == null) {
                throw new IllegalArgumentException("No field found for [" + name + "] in mapping with types " + Arrays.toString(types));
            }
            data = new FieldLookup(fieldType);
            cachedFieldData.put(name, data);
        }
        if (data.fields() == null) {
            MappedFieldType fieldType = data.fieldType();
            List<Object> values;
            if (TypeFieldMapper.NAME.equals(fieldType.name())) {
                TypeFieldMapper.emitTypesDeprecationWarning();
                values = Collections.singletonList(((TypeFieldMapper.TypeFieldType)fieldType).getType());
            } else {
                values = new ArrayList<>(2);
                SingleFieldsVisitor visitor = new SingleFieldsVisitor(fieldType, values);
                try {
                    reader.document(docId, visitor);
                } catch (IOException e) {
                    throw new ElasticsearchParseException("failed to load field [{}]", e, name);
                }
            }
            data.fields(singletonMap(fieldType.name(), values));
        }
        return data;
    }

    private void clearCache() {
        for (Entry<String, FieldLookup> entry : cachedFieldData.entrySet()) {
            entry.getValue().clear();
        }
    }

}
