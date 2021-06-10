/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.store;

import org.elasticsearch.core.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public enum LuceneFilesExtensions {

    CFE("cfe", "Compound Files Entries", true, false),
    // Compound files are tricky because they store all the information for the segment. Benchmarks
    // suggested that not mapping them hurts performance.
    CFS("cfs", "Compound Files", false, true),
    CMP("cmp", "Completion Index", true, false),
    DII("dii", "Points Index", false, false),
    // dim files only apply up to lucene 8.x indices. It can be removed once we are in lucene 10
    DIM("dim", "Points", false, true),
    // MMapDirectory has special logic to read long[] arrays in little-endian order that helps speed
    // up the decoding of postings. The same logic applies to positions (.pos) of offsets (.pay) but we
    // are not mmaping them as queries that leverage positions are more costly and the decoding of postings
    // tends to be less a bottleneck.
    DOC("doc", "Frequencies", false, true),
    // Doc values are typically performance-sensitive and hot in the page
    // cache, so we use mmap, which provides better performance.
    DVD("dvd", "DocValues", false, true),
    DVM("dvm", "DocValues Metadata", true, false),
    FDM("fdm", "Field Metadata", true, false),
    FDT("fdt", "Field Data", false, false),
    FDX("fdx", "Field Index", false, false),
    FNM("fnm", "Fields", true, false),
    // old extension
    KDD("kdd", "Points", false, true),
    // old extension
    KDI("kdi", "Points Index", false, true),
    // Lucene 8.6 point format metadata file
    KDM("kdm", "Points Metadata", true, false),
    LIV("liv", "Live Documents", false, false),
    LKP("lkp", "Completion Dictionary", false, false),
    // Norms are typically performance-sensitive and hot in the page
    // cache, so we use mmap, which provides better performance.
    NVD("nvd", "Norms", false, true),
    NVM("nvm", "Norms Metadata", true, false),
    PAY("pay", "Payloads", false, false),
    POS("pos", "Positions", false, false),
    SI("si", "Segment Info", true, false),
    // Term dictionaries are typically performance-sensitive and hot in the page
    // cache, so we use mmap, which provides better performance.
    TIM("tim", "Term Dictionary", false, true),
    // We want to open the terms index and KD-tree index off-heap to save memory, but this only performs
    // well if using mmap.
    TIP("tip", "Term Index", false, true),
    // Lucene 8.6 terms metadata file
    TMD("tmd", "Term Dictionary Metadata", true, false),
    // Temporary Lucene file
    TMP("tmp", "Temporary File", false, false),
    TVD("tvd", "Term Vector Documents", false, false),
    TVF("tvf", "Term Vector Fields", false, false),
    TVM("tvm", "Term Vector Metadata", true, false),
    TVX("tvx", "Term Vector Index", false, false),
    VEC("vec", "Vector Data", false, false),
    // Lucene 9.0 indexed vectors metadata
    VEM("vem","Vector Metadata", true, false);

    /**
     * Lucene file's extension.
     */
    private final String extension;

    /**
     * Short description of the Lucene file
     */
    private final String description;

    /**
     * Some Lucene files should be memory-mapped when applicable.
     */
    private final boolean mmap;

    /**
     * Some Lucene files are considered as "metadata" files and should therefore be fully cached when applicable. Those files are usually
     * fully read by Lucene when a Directory is opened. For non-metadata files Lucene usually only reads the header and footer checksums.
     */
    private final boolean metadata;

    LuceneFilesExtensions(String extension, String description, boolean metadata, boolean mmap) {
        this.description = Objects.requireNonNull(description);
        this.extension = Objects.requireNonNull(extension);
        this.metadata = metadata;
        this.mmap = mmap;
    }

    public String getDescription() {
        return description;
    }

    public String getExtension() {
        return extension;
    }

    public boolean isMetadata() {
        return metadata;
    }

    public boolean shouldMmap() {
        return mmap;
    }

    private static final Map<String, LuceneFilesExtensions> extensions;
    static {
        final Map<String, LuceneFilesExtensions> map = new HashMap<>(values().length);
        for (LuceneFilesExtensions extension : values()) {
            map.put(extension.extension, extension);
        }
        extensions = Collections.unmodifiableMap(map);
    }

    @Nullable
    public static LuceneFilesExtensions fromExtension(String ext) {
        if (ext != null && ext.isEmpty() == false) {
            final LuceneFilesExtensions extension = extensions.get(ext);
            assert extension != null: "unknown Lucene file extension [" + ext + ']';
            return extension;
        }
        return null;
    }
}
