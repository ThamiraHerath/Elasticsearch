/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.filestructurefinder;

import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.xpack.core.ml.filestructurefinder.FileStructure;
import org.supercsv.prefs.CsvPreference;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.elasticsearch.xpack.ml.filestructurefinder.DelimitedFileStructureFinder.levenshteinFieldwiseCompareRows;
import static org.elasticsearch.xpack.ml.filestructurefinder.DelimitedFileStructureFinder.levenshteinDistance;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

public class DelimitedFileStructureFinderTests extends FileStructureTestCase {

    private FileStructureFinderFactory csvFactory = new DelimitedFileStructureFinderFactory(',', '"', 2, false);
    private FileStructureFinderFactory tsvFactory = new DelimitedFileStructureFinderFactory('\t', '"', 3, false);

    public void testCreateConfigsGivenCompleteCsv() throws Exception {
        String sample = "time,message\n" +
            "2018-05-17T13:41:23,hello\n" +
            "2018-05-17T13:41:32,hello again\n";
        assertTrue(csvFactory.canCreateFromSample(explanation, sample, 0.0));

        String charset = randomFrom(POSSIBLE_CHARSETS);
        Boolean hasByteOrderMarker = randomHasByteOrderMarker(charset);
        FileStructureFinder structureFinder = csvFactory.createFromSample(explanation, sample, charset, hasByteOrderMarker,
            FileStructureFinderManager.DEFAULT_LINE_MERGE_SIZE_LIMIT, FileStructureOverrides.EMPTY_OVERRIDES, NOOP_TIMEOUT_CHECKER);

        FileStructure structure = structureFinder.getStructure();

        assertEquals(FileStructure.Format.DELIMITED, structure.getFormat());
        assertEquals(charset, structure.getCharset());
        if (hasByteOrderMarker == null) {
            assertNull(structure.getHasByteOrderMarker());
        } else {
            assertEquals(hasByteOrderMarker, structure.getHasByteOrderMarker());
        }
        assertEquals("^\"?time\"?,\"?message\"?", structure.getExcludeLinesPattern());
        assertNull(structure.getMultilineStartPattern());
        assertEquals(Character.valueOf(','), structure.getDelimiter());
        assertEquals(Character.valueOf('"'), structure.getQuote());
        assertTrue(structure.getHasHeaderRow());
        assertNull(structure.getShouldTrimFields());
        assertEquals(Arrays.asList("time", "message"), structure.getColumnNames());
        assertNull(structure.getGrokPattern());
        assertEquals("time", structure.getTimestampField());
        assertEquals(Collections.singletonList("ISO8601"), structure.getJodaTimestampFormats());
    }

    public void testCreateConfigsGivenIncompleteCsv() throws Exception {
        String sample = "time,message\n" +
            "2018-05-17T13:41:23,hello\n" +
            "2018-05-17T13:41:25,hello\n" +
            "2018-05-17T13:41:26,hello\n" +
            "2018-05-17T13:41:27,hello\n" +
            "2018-05-17T13:41:28,hello\n" +
            "2018-05-17T13:41:29,hello\n" +
            "2018-05-17T13:41:30,hello\n" +
            "2018-05-17T13:41:31,hello\n" +
            "2018-05-17T13:41:32,hello\n" +
            "badrow\n" +
            "2018-05-17T13:41:33,hello again\n";
        assertTrue("assertion failed. Explanation " + explanation,
            csvFactory.canCreateFromSample(explanation, sample, 0.10));

        String charset = randomFrom(POSSIBLE_CHARSETS);
        Boolean hasByteOrderMarker = randomHasByteOrderMarker(charset);
        FileStructureFinder structureFinder = csvFactory.createFromSample(explanation, sample, charset, hasByteOrderMarker,
            FileStructureFinderManager.DEFAULT_LINE_MERGE_SIZE_LIMIT, FileStructureOverrides.EMPTY_OVERRIDES, NOOP_TIMEOUT_CHECKER);


        FileStructure structure = structureFinder.getStructure();

        assertEquals(FileStructure.Format.DELIMITED, structure.getFormat());
        assertEquals(charset, structure.getCharset());
        if (hasByteOrderMarker == null) {
            assertNull(structure.getHasByteOrderMarker());
        } else {
            assertEquals(hasByteOrderMarker, structure.getHasByteOrderMarker());
        }
        assertEquals("^\"?time\"?,\"?message\"?", structure.getExcludeLinesPattern());
        assertEquals("time", structure.getTimestampField());
        assertEquals(Collections.singletonList("ISO8601"), structure.getJodaTimestampFormats());
        assertEquals(Arrays.asList("time", "message"), structure.getColumnNames());
        assertEquals(Character.valueOf(','), structure.getDelimiter());
        assertEquals(Character.valueOf('"'), structure.getQuote());
        assertTrue(structure.getHasHeaderRow());
        assertNull(structure.getMultilineStartPattern());
        assertNull(structure.getShouldTrimFields());
        assertNull(structure.getGrokPattern());
    }

    public void testCreateConfigsGivenCompleteCsvAndColumnNamesOverride() throws Exception {

        FileStructureOverrides overrides = FileStructureOverrides.builder().setColumnNames(Arrays.asList("my_time", "my_message")).build();

        String sample = "time,message\n" +
            "2018-05-17T13:41:23,hello\n" +
            "2018-05-17T13:41:32,hello again\n";
        assertTrue(csvFactory.canCreateFromSample(explanation, sample, 0.0));

        String charset = randomFrom(POSSIBLE_CHARSETS);
        Boolean hasByteOrderMarker = randomHasByteOrderMarker(charset);
        FileStructureFinder structureFinder = csvFactory.createFromSample(explanation, sample, charset, hasByteOrderMarker,
            FileStructureFinderManager.DEFAULT_LINE_MERGE_SIZE_LIMIT, overrides, NOOP_TIMEOUT_CHECKER);

        FileStructure structure = structureFinder.getStructure();

        assertEquals(FileStructure.Format.DELIMITED, structure.getFormat());
        assertEquals(charset, structure.getCharset());
        if (hasByteOrderMarker == null) {
            assertNull(structure.getHasByteOrderMarker());
        } else {
            assertEquals(hasByteOrderMarker, structure.getHasByteOrderMarker());
        }
        assertEquals("^\"?time\"?,\"?message\"?", structure.getExcludeLinesPattern());
        assertNull(structure.getMultilineStartPattern());
        assertEquals(Character.valueOf(','), structure.getDelimiter());
        assertEquals(Character.valueOf('"'), structure.getQuote());
        assertTrue(structure.getHasHeaderRow());
        assertNull(structure.getShouldTrimFields());
        assertEquals(Arrays.asList("my_time", "my_message"), structure.getColumnNames());
        assertNull(structure.getGrokPattern());
        assertEquals("my_time", structure.getTimestampField());
        assertEquals(Collections.singletonList("ISO8601"), structure.getJodaTimestampFormats());
    }

    public void testCreateConfigsGivenCompleteCsvAndHasHeaderRowOverride() throws Exception {

        // It's obvious the first row really should be a header row, so by overriding
        // detection with the wrong choice the results will be completely changed
        FileStructureOverrides overrides = FileStructureOverrides.builder().setHasHeaderRow(false).build();

        String sample = "time,message\n" +
            "2018-05-17T13:41:23,hello\n" +
            "2018-05-17T13:41:32,hello again\n";
        assertTrue(csvFactory.canCreateFromSample(explanation, sample, 0.0));

        String charset = randomFrom(POSSIBLE_CHARSETS);
        Boolean hasByteOrderMarker = randomHasByteOrderMarker(charset);
        FileStructureFinder structureFinder = csvFactory.createFromSample(explanation, sample, charset, hasByteOrderMarker,
            FileStructureFinderManager.DEFAULT_LINE_MERGE_SIZE_LIMIT, overrides, NOOP_TIMEOUT_CHECKER);

        FileStructure structure = structureFinder.getStructure();

        assertEquals(FileStructure.Format.DELIMITED, structure.getFormat());
        assertEquals(charset, structure.getCharset());
        if (hasByteOrderMarker == null) {
            assertNull(structure.getHasByteOrderMarker());
        } else {
            assertEquals(hasByteOrderMarker, structure.getHasByteOrderMarker());
        }
        assertNull(structure.getExcludeLinesPattern());
        assertNull(structure.getMultilineStartPattern());
        assertEquals(Character.valueOf(','), structure.getDelimiter());
        assertEquals(Character.valueOf('"'), structure.getQuote());
        assertFalse(structure.getHasHeaderRow());
        assertNull(structure.getShouldTrimFields());
        assertEquals(Arrays.asList("column1", "column2"), structure.getColumnNames());
        assertNull(structure.getGrokPattern());
        assertNull(structure.getTimestampField());
        assertNull(structure.getJodaTimestampFormats());
    }

    public void testCreateConfigsGivenCsvWithIncompleteLastRecord() throws Exception {
        String sample = "time,message,count\n" +
            "2018-05-17T13:41:23,\"hello\n" +
            "world\",1\n" +
            "2019-01-18T14:46:57,\"hello again\n"; // note that this last record is truncated
        assertTrue(csvFactory.canCreateFromSample(explanation, sample, 0.0));

        String charset = randomFrom(POSSIBLE_CHARSETS);
        Boolean hasByteOrderMarker = randomHasByteOrderMarker(charset);
        FileStructureFinder structureFinder = csvFactory.createFromSample(explanation, sample, charset, hasByteOrderMarker,
            FileStructureFinderManager.DEFAULT_LINE_MERGE_SIZE_LIMIT, FileStructureOverrides.EMPTY_OVERRIDES, NOOP_TIMEOUT_CHECKER);

        FileStructure structure = structureFinder.getStructure();

        assertEquals(FileStructure.Format.DELIMITED, structure.getFormat());
        assertEquals(charset, structure.getCharset());
        if (hasByteOrderMarker == null) {
            assertNull(structure.getHasByteOrderMarker());
        } else {
            assertEquals(hasByteOrderMarker, structure.getHasByteOrderMarker());
        }
        assertEquals("^\"?time\"?,\"?message\"?,\"?count\"?", structure.getExcludeLinesPattern());
        assertEquals("^\"?\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}", structure.getMultilineStartPattern());
        assertEquals(Character.valueOf(','), structure.getDelimiter());
        assertEquals(Character.valueOf('"'), structure.getQuote());
        assertTrue(structure.getHasHeaderRow());
        assertNull(structure.getShouldTrimFields());
        assertEquals(Arrays.asList("time", "message", "count"), structure.getColumnNames());
        assertNull(structure.getGrokPattern());
        assertEquals("time", structure.getTimestampField());
        assertEquals(Collections.singletonList("ISO8601"), structure.getJodaTimestampFormats());
    }

    public void testCreateConfigsGivenCsvWithTrailingNulls() throws Exception {
        String sample = "VendorID,tpep_pickup_datetime,tpep_dropoff_datetime,passenger_count,trip_distance,RatecodeID," +
            "store_and_fwd_flag,PULocationID,DOLocationID,payment_type,fare_amount,extra,mta_tax,tip_amount,tolls_amount," +
            "improvement_surcharge,total_amount,,\n" +
            "2,2016-12-31 15:15:01,2016-12-31 15:15:09,1,.00,1,N,264,264,2,1,0,0.5,0,0,0.3,1.8,,\n" +
            "1,2016-12-01 00:00:01,2016-12-01 00:10:22,1,1.60,1,N,163,143,2,9,0.5,0.5,0,0,0.3,10.3,,\n" +
            "1,2016-12-01 00:00:01,2016-12-01 00:11:01,1,1.40,1,N,164,229,1,9,0.5,0.5,2.05,0,0.3,12.35,,\n";
        assertTrue(csvFactory.canCreateFromSample(explanation, sample, 0.0));

        String charset = randomFrom(POSSIBLE_CHARSETS);
        Boolean hasByteOrderMarker = randomHasByteOrderMarker(charset);
        FileStructureFinder structureFinder = csvFactory.createFromSample(explanation, sample, charset, hasByteOrderMarker,
            FileStructureFinderManager.DEFAULT_LINE_MERGE_SIZE_LIMIT, FileStructureOverrides.EMPTY_OVERRIDES, NOOP_TIMEOUT_CHECKER);

        FileStructure structure = structureFinder.getStructure();

        assertEquals(FileStructure.Format.DELIMITED, structure.getFormat());
        assertEquals(charset, structure.getCharset());
        if (hasByteOrderMarker == null) {
            assertNull(structure.getHasByteOrderMarker());
        } else {
            assertEquals(hasByteOrderMarker, structure.getHasByteOrderMarker());
        }
        assertEquals("^\"?VendorID\"?,\"?tpep_pickup_datetime\"?,\"?tpep_dropoff_datetime\"?,\"?passenger_count\"?,\"?trip_distance\"?," +
            "\"?RatecodeID\"?,\"?store_and_fwd_flag\"?,\"?PULocationID\"?,\"?DOLocationID\"?,\"?payment_type\"?,\"?fare_amount\"?," +
            "\"?extra\"?,\"?mta_tax\"?,\"?tip_amount\"?,\"?tolls_amount\"?,\"?improvement_surcharge\"?,\"?total_amount\"?,\"?\"?,\"?\"?",
            structure.getExcludeLinesPattern());
        assertNull(structure.getMultilineStartPattern());
        assertEquals(Character.valueOf(','), structure.getDelimiter());
        assertEquals(Character.valueOf('"'), structure.getQuote());
        assertTrue(structure.getHasHeaderRow());
        assertNull(structure.getShouldTrimFields());
        assertEquals(Arrays.asList("VendorID", "tpep_pickup_datetime", "tpep_dropoff_datetime", "passenger_count", "trip_distance",
            "RatecodeID", "store_and_fwd_flag", "PULocationID", "DOLocationID", "payment_type", "fare_amount", "extra", "mta_tax",
            "tip_amount", "tolls_amount", "improvement_surcharge", "total_amount", "column18", "column19"), structure.getColumnNames());
        assertNull(structure.getGrokPattern());
        assertEquals("tpep_pickup_datetime", structure.getTimestampField());
        assertEquals(Collections.singletonList("YYYY-MM-dd HH:mm:ss"), structure.getJodaTimestampFormats());
    }

    public void testCreateConfigsGivenCsvWithTrailingNullsAndOverriddenTimeField() throws Exception {

        // Default timestamp field is the first field from the start of each row that contains a
        // consistent timestamp format, so if we want the second we need an override
        FileStructureOverrides overrides = FileStructureOverrides.builder().setTimestampField("tpep_dropoff_datetime").build();

        String sample = "VendorID,tpep_pickup_datetime,tpep_dropoff_datetime,passenger_count,trip_distance,RatecodeID," +
            "store_and_fwd_flag,PULocationID,DOLocationID,payment_type,fare_amount,extra,mta_tax,tip_amount,tolls_amount," +
            "improvement_surcharge,total_amount,,\n" +
            "2,2016-12-31 15:15:01,2016-12-31 15:15:09,1,.00,1,N,264,264,2,1,0,0.5,0,0,0.3,1.8,,\n" +
            "1,2016-12-01 00:00:01,2016-12-01 00:10:22,1,1.60,1,N,163,143,2,9,0.5,0.5,0,0,0.3,10.3,,\n" +
            "1,2016-12-01 00:00:01,2016-12-01 00:11:01,1,1.40,1,N,164,229,1,9,0.5,0.5,2.05,0,0.3,12.35,,\n";
        assertTrue(csvFactory.canCreateFromSample(explanation, sample, 0.0));

        String charset = randomFrom(POSSIBLE_CHARSETS);
        Boolean hasByteOrderMarker = randomHasByteOrderMarker(charset);
        FileStructureFinder structureFinder = csvFactory.createFromSample(explanation, sample, charset, hasByteOrderMarker,
            FileStructureFinderManager.DEFAULT_LINE_MERGE_SIZE_LIMIT, overrides, NOOP_TIMEOUT_CHECKER);

        FileStructure structure = structureFinder.getStructure();

        assertEquals(FileStructure.Format.DELIMITED, structure.getFormat());
        assertEquals(charset, structure.getCharset());
        if (hasByteOrderMarker == null) {
            assertNull(structure.getHasByteOrderMarker());
        } else {
            assertEquals(hasByteOrderMarker, structure.getHasByteOrderMarker());
        }
        assertEquals("^\"?VendorID\"?,\"?tpep_pickup_datetime\"?,\"?tpep_dropoff_datetime\"?,\"?passenger_count\"?,\"?trip_distance\"?," +
            "\"?RatecodeID\"?,\"?store_and_fwd_flag\"?,\"?PULocationID\"?,\"?DOLocationID\"?,\"?payment_type\"?,\"?fare_amount\"?," +
            "\"?extra\"?,\"?mta_tax\"?,\"?tip_amount\"?,\"?tolls_amount\"?,\"?improvement_surcharge\"?,\"?total_amount\"?,\"?\"?,\"?\"?",
            structure.getExcludeLinesPattern());
        assertNull(structure.getMultilineStartPattern());
        assertEquals(Character.valueOf(','), structure.getDelimiter());
        assertEquals(Character.valueOf('"'), structure.getQuote());
        assertTrue(structure.getHasHeaderRow());
        assertNull(structure.getShouldTrimFields());
        assertEquals(Arrays.asList("VendorID", "tpep_pickup_datetime", "tpep_dropoff_datetime", "passenger_count", "trip_distance",
            "RatecodeID", "store_and_fwd_flag", "PULocationID", "DOLocationID", "payment_type", "fare_amount", "extra", "mta_tax",
            "tip_amount", "tolls_amount", "improvement_surcharge", "total_amount", "column18", "column19"), structure.getColumnNames());
        assertNull(structure.getGrokPattern());
        assertEquals("tpep_dropoff_datetime", structure.getTimestampField());
        assertEquals(Collections.singletonList("YYYY-MM-dd HH:mm:ss"), structure.getJodaTimestampFormats());
    }

    public void testCreateConfigsGivenCsvWithTrailingNullsExceptHeader() throws Exception {
        String sample = "VendorID,tpep_pickup_datetime,tpep_dropoff_datetime,passenger_count,trip_distance,RatecodeID," +
            "store_and_fwd_flag,PULocationID,DOLocationID,payment_type,fare_amount,extra,mta_tax,tip_amount,tolls_amount," +
            "improvement_surcharge,total_amount\n" +
            "2,2016-12-31 15:15:01,2016-12-31 15:15:09,1,.00,1,N,264,264,2,1,0,0.5,0,0,0.3,1.8,,\n" +
            "1,2016-12-01 00:00:01,2016-12-01 00:10:22,1,1.60,1,N,163,143,2,9,0.5,0.5,0,0,0.3,10.3,,\n" +
            "1,2016-12-01 00:00:01,2016-12-01 00:11:01,1,1.40,1,N,164,229,1,9,0.5,0.5,2.05,0,0.3,12.35,,\n";
        assertTrue(csvFactory.canCreateFromSample(explanation, sample, 0.0));

        String charset = randomFrom(POSSIBLE_CHARSETS);
        Boolean hasByteOrderMarker = randomHasByteOrderMarker(charset);
        FileStructureFinder structureFinder = csvFactory.createFromSample(explanation, sample, charset, hasByteOrderMarker,
            FileStructureFinderManager.DEFAULT_LINE_MERGE_SIZE_LIMIT, FileStructureOverrides.EMPTY_OVERRIDES, NOOP_TIMEOUT_CHECKER);

        FileStructure structure = structureFinder.getStructure();

        assertEquals(FileStructure.Format.DELIMITED, structure.getFormat());
        assertEquals(charset, structure.getCharset());
        if (hasByteOrderMarker == null) {
            assertNull(structure.getHasByteOrderMarker());
        } else {
            assertEquals(hasByteOrderMarker, structure.getHasByteOrderMarker());
        }
        assertEquals("^\"?VendorID\"?,\"?tpep_pickup_datetime\"?,\"?tpep_dropoff_datetime\"?,\"?passenger_count\"?,\"?trip_distance\"?," +
                "\"?RatecodeID\"?,\"?store_and_fwd_flag\"?,\"?PULocationID\"?,\"?DOLocationID\"?,\"?payment_type\"?,\"?fare_amount\"?," +
                "\"?extra\"?,\"?mta_tax\"?,\"?tip_amount\"?,\"?tolls_amount\"?,\"?improvement_surcharge\"?,\"?total_amount\"?",
            structure.getExcludeLinesPattern());
        assertNull(structure.getMultilineStartPattern());
        assertEquals(Character.valueOf(','), structure.getDelimiter());
        assertEquals(Character.valueOf('"'), structure.getQuote());
        assertTrue(structure.getHasHeaderRow());
        assertNull(structure.getShouldTrimFields());
        assertEquals(Arrays.asList("VendorID", "tpep_pickup_datetime", "tpep_dropoff_datetime", "passenger_count", "trip_distance",
            "RatecodeID", "store_and_fwd_flag", "PULocationID", "DOLocationID", "payment_type", "fare_amount", "extra", "mta_tax",
            "tip_amount", "tolls_amount", "improvement_surcharge", "total_amount"), structure.getColumnNames());
        assertNull(structure.getGrokPattern());
        assertEquals("tpep_pickup_datetime", structure.getTimestampField());
        assertEquals(Collections.singletonList("YYYY-MM-dd HH:mm:ss"), structure.getJodaTimestampFormats());
    }

    public void testCreateConfigsGivenCsvWithTrailingNullsExceptHeaderAndColumnNamesOverride() throws Exception {

        FileStructureOverrides overrides = FileStructureOverrides.builder()
            .setColumnNames(Arrays.asList("my_VendorID", "my_tpep_pickup_datetime", "my_tpep_dropoff_datetime", "my_passenger_count",
                "my_trip_distance", "my_RatecodeID", "my_store_and_fwd_flag", "my_PULocationID", "my_DOLocationID", "my_payment_type",
                "my_fare_amount", "my_extra", "my_mta_tax", "my_tip_amount", "my_tolls_amount", "my_improvement_surcharge",
                "my_total_amount")).build();

        String sample = "VendorID,tpep_pickup_datetime,tpep_dropoff_datetime,passenger_count,trip_distance,RatecodeID," +
            "store_and_fwd_flag,PULocationID,DOLocationID,payment_type,fare_amount,extra,mta_tax,tip_amount,tolls_amount," +
            "improvement_surcharge,total_amount\n" +
            "2,2016-12-31 15:15:01,2016-12-31 15:15:09,1,.00,1,N,264,264,2,1,0,0.5,0,0,0.3,1.8,,\n" +
            "1,2016-12-01 00:00:01,2016-12-01 00:10:22,1,1.60,1,N,163,143,2,9,0.5,0.5,0,0,0.3,10.3,,\n" +
            "1,2016-12-01 00:00:01,2016-12-01 00:11:01,1,1.40,1,N,164,229,1,9,0.5,0.5,2.05,0,0.3,12.35,,\n";
        assertTrue(csvFactory.canCreateFromSample(explanation, sample, 0.0));

        String charset = randomFrom(POSSIBLE_CHARSETS);
        Boolean hasByteOrderMarker = randomHasByteOrderMarker(charset);
        FileStructureFinder structureFinder = csvFactory.createFromSample(explanation, sample, charset, hasByteOrderMarker,
            FileStructureFinderManager.DEFAULT_LINE_MERGE_SIZE_LIMIT, overrides, NOOP_TIMEOUT_CHECKER);

        FileStructure structure = structureFinder.getStructure();

        assertEquals(FileStructure.Format.DELIMITED, structure.getFormat());
        assertEquals(charset, structure.getCharset());
        if (hasByteOrderMarker == null) {
            assertNull(structure.getHasByteOrderMarker());
        } else {
            assertEquals(hasByteOrderMarker, structure.getHasByteOrderMarker());
        }
        assertEquals("^\"?VendorID\"?,\"?tpep_pickup_datetime\"?,\"?tpep_dropoff_datetime\"?,\"?passenger_count\"?,\"?trip_distance\"?," +
                "\"?RatecodeID\"?,\"?store_and_fwd_flag\"?,\"?PULocationID\"?,\"?DOLocationID\"?,\"?payment_type\"?,\"?fare_amount\"?," +
                "\"?extra\"?,\"?mta_tax\"?,\"?tip_amount\"?,\"?tolls_amount\"?,\"?improvement_surcharge\"?,\"?total_amount\"?",
            structure.getExcludeLinesPattern());
        assertNull(structure.getMultilineStartPattern());
        assertEquals(Character.valueOf(','), structure.getDelimiter());
        assertEquals(Character.valueOf('"'), structure.getQuote());
        assertTrue(structure.getHasHeaderRow());
        assertNull(structure.getShouldTrimFields());
        assertEquals(Arrays.asList("my_VendorID", "my_tpep_pickup_datetime", "my_tpep_dropoff_datetime", "my_passenger_count",
            "my_trip_distance", "my_RatecodeID", "my_store_and_fwd_flag", "my_PULocationID", "my_DOLocationID", "my_payment_type",
            "my_fare_amount", "my_extra", "my_mta_tax", "my_tip_amount", "my_tolls_amount", "my_improvement_surcharge", "my_total_amount"),
            structure.getColumnNames());
        assertNull(structure.getGrokPattern());
        assertEquals("my_tpep_pickup_datetime", structure.getTimestampField());
        assertEquals(Collections.singletonList("YYYY-MM-dd HH:mm:ss"), structure.getJodaTimestampFormats());
    }

    public void testCreateConfigsGivenCsvWithTimeLastColumn() throws Exception {
        String sample = "\"pos_id\",\"trip_id\",\"latitude\",\"longitude\",\"altitude\",\"timestamp\"\n" +
            "\"1\",\"3\",\"4703.7815\",\"1527.4713\",\"359.9\",\"2017-01-19 16:19:04.742113\"\n" +
            "\"2\",\"3\",\"4703.7815\",\"1527.4714\",\"359.9\",\"2017-01-19 16:19:05.741890\"\n";
        assertTrue(csvFactory.canCreateFromSample(explanation, sample, 0.0));

        String charset = randomFrom(POSSIBLE_CHARSETS);
        Boolean hasByteOrderMarker = randomHasByteOrderMarker(charset);
        FileStructureFinder structureFinder = csvFactory.createFromSample(explanation, sample, charset, hasByteOrderMarker,
            FileStructureFinderManager.DEFAULT_LINE_MERGE_SIZE_LIMIT, FileStructureOverrides.EMPTY_OVERRIDES, NOOP_TIMEOUT_CHECKER);

        FileStructure structure = structureFinder.getStructure();

        assertEquals(FileStructure.Format.DELIMITED, structure.getFormat());
        assertEquals(charset, structure.getCharset());
        if (hasByteOrderMarker == null) {
            assertNull(structure.getHasByteOrderMarker());
        } else {
            assertEquals(hasByteOrderMarker, structure.getHasByteOrderMarker());
        }
        assertEquals("^\"?pos_id\"?,\"?trip_id\"?,\"?latitude\"?,\"?longitude\"?,\"?altitude\"?,\"?timestamp\"?",
            structure.getExcludeLinesPattern());
        assertNull(structure.getMultilineStartPattern());
        assertEquals(Character.valueOf(','), structure.getDelimiter());
        assertEquals(Character.valueOf('"'), structure.getQuote());
        assertTrue(structure.getHasHeaderRow());
        assertNull(structure.getShouldTrimFields());
        assertEquals(Arrays.asList("pos_id", "trip_id", "latitude", "longitude", "altitude", "timestamp"), structure.getColumnNames());
        assertNull(structure.getGrokPattern());
        assertEquals("timestamp", structure.getTimestampField());
        assertEquals(Collections.singletonList("YYYY-MM-dd HH:mm:ss.SSSSSS"), structure.getJodaTimestampFormats());
    }

    public void testCreateConfigsGivenTsvWithSyslogLikeTimestamp() throws Exception {
        String sample = "Latitude\tLongitude\tloc\tTimestamp\n" +
            "25.78042\t18.441196\t\"25.7804200000,18.4411960000\"\tJun 30 2019 13:21:24\n" +
            "25.743484\t18.443047\t\"25.7434840000,18.4430470000\"\tJun 30 2019 06:02:35\n" +
            "25.744583\t18.442783\t\"25.7445830000,18.4427830000\"\tJun 30 2019 06:02:35\n" +
            "25.754593\t18.431637\t\"25.7545930000,18.4316370000\"\tJul 1 2019 06:02:43\n" +
            "25.768574\t18.433483\t\"25.7685740000,18.4334830000\"\tJul 1 2019 06:21:28\n" +
            "25.757736\t18.438683\t\"25.7577360000,18.4386830000\"\tJul 1 2019 12:06:08\n" +
            "25.76615\t18.436565\t\"25.7661500000,18.4365650000\"\tJul 1 2019 12:06:08\n" +
            "25.76896\t18.43586\t\"25.7689600000,18.4358600000\"\tJul 1 2019 12:13:50\n" +
            "25.76423\t18.43705\t\"25.7642300000,18.4370500000\"\tJul 1 2019 12:39:10\n";
        assertTrue(tsvFactory.canCreateFromSample(explanation, sample, 0.0));

        String charset = randomFrom(POSSIBLE_CHARSETS);
        Boolean hasByteOrderMarker = randomHasByteOrderMarker(charset);
        FileStructureFinder structureFinder = tsvFactory.createFromSample(explanation, sample, charset, hasByteOrderMarker,
            FileStructureFinderManager.DEFAULT_LINE_MERGE_SIZE_LIMIT, FileStructureOverrides.EMPTY_OVERRIDES, NOOP_TIMEOUT_CHECKER);

        FileStructure structure = structureFinder.getStructure();

        assertEquals(FileStructure.Format.DELIMITED, structure.getFormat());
        assertEquals(charset, structure.getCharset());
        if (hasByteOrderMarker == null) {
            assertNull(structure.getHasByteOrderMarker());
        } else {
            assertEquals(hasByteOrderMarker, structure.getHasByteOrderMarker());
        }
        assertEquals("^\"?Latitude\"?\\t\"?Longitude\"?\\t\"?loc\"?\\t\"?Timestamp\"?",
            structure.getExcludeLinesPattern());
        assertNull(structure.getMultilineStartPattern());
        assertEquals(Character.valueOf('\t'), structure.getDelimiter());
        assertEquals(Character.valueOf('"'), structure.getQuote());
        assertTrue(structure.getHasHeaderRow());
        assertNull(structure.getShouldTrimFields());
        assertEquals(Arrays.asList("Latitude", "Longitude", "loc", "Timestamp"), structure.getColumnNames());
        assertNull(structure.getGrokPattern());
        assertEquals("Timestamp", structure.getTimestampField());
        assertEquals(Arrays.asList("MMM dd YYYY HH:mm:ss", "MMM  d YYYY HH:mm:ss", "MMM d YYYY HH:mm:ss"),
            structure.getJodaTimestampFormats());
    }

    public void testCreateConfigsGivenDotInFieldName() throws Exception {
        String sample = "time.iso8601,message\n" +
            "2018-05-17T13:41:23,hello\n" +
            "2018-05-17T13:41:32,hello again\n";
        assertTrue(csvFactory.canCreateFromSample(explanation, sample, 0.0));

        String charset = randomFrom(POSSIBLE_CHARSETS);
        Boolean hasByteOrderMarker = randomHasByteOrderMarker(charset);
        FileStructureFinder structureFinder = csvFactory.createFromSample(explanation, sample, charset, hasByteOrderMarker,
            FileStructureFinderManager.DEFAULT_LINE_MERGE_SIZE_LIMIT, FileStructureOverrides.EMPTY_OVERRIDES, NOOP_TIMEOUT_CHECKER);

        FileStructure structure = structureFinder.getStructure();

        assertEquals(FileStructure.Format.DELIMITED, structure.getFormat());
        assertEquals(charset, structure.getCharset());
        if (hasByteOrderMarker == null) {
            assertNull(structure.getHasByteOrderMarker());
        } else {
            assertEquals(hasByteOrderMarker, structure.getHasByteOrderMarker());
        }
        // The exclude pattern needs to work on the raw text, so reflects the unmodified field names
        assertEquals("^\"?time\\.iso8601\"?,\"?message\"?", structure.getExcludeLinesPattern());
        assertNull(structure.getMultilineStartPattern());
        assertEquals(Character.valueOf(','), structure.getDelimiter());
        assertEquals(Character.valueOf('"'), structure.getQuote());
        assertTrue(structure.getHasHeaderRow());
        assertNull(structure.getShouldTrimFields());
        assertEquals(Arrays.asList("time_iso8601", "message"), structure.getColumnNames());
        assertNull(structure.getGrokPattern());
        assertEquals("time_iso8601", structure.getTimestampField());
        assertEquals(Collections.singletonList("ISO8601"), structure.getJodaTimestampFormats());
    }

    public void testFindHeaderFromSampleGivenHeaderInSample() throws IOException {
        String withHeader = "time,airline,responsetime,sourcetype\n" +
            "2014-06-23 00:00:00Z,AAL,132.2046,farequote\n" +
            "2014-06-23 00:00:00Z,JZA,990.4628,farequote\n" +
            "2014-06-23 00:00:01Z,JBU,877.5927,farequote\n" +
            "2014-06-23 00:00:01Z,KLM,1355.4812,farequote\n";

        Tuple<Boolean, String[]> header = DelimitedFileStructureFinder.findHeaderFromSample(explanation,
            DelimitedFileStructureFinder.readRows(withHeader, CsvPreference.EXCEL_PREFERENCE, NOOP_TIMEOUT_CHECKER).v1(),
            FileStructureOverrides.EMPTY_OVERRIDES);

        assertTrue(header.v1());
        assertThat(header.v2(), arrayContaining("time", "airline", "responsetime", "sourcetype"));
    }

    public void testFindHeaderFromSampleGivenHeaderNotInSample() throws IOException {
        String noHeader = "2014-06-23 00:00:00Z,AAL,132.2046,farequote\n" +
            "2014-06-23 00:00:00Z,JZA,990.4628,farequote\n" +
            "2014-06-23 00:00:01Z,JBU,877.5927,farequote\n" +
            "2014-06-23 00:00:01Z,KLM,1355.4812,farequote\n";

        Tuple<Boolean, String[]> header = DelimitedFileStructureFinder.findHeaderFromSample(explanation,
            DelimitedFileStructureFinder.readRows(noHeader, CsvPreference.EXCEL_PREFERENCE, NOOP_TIMEOUT_CHECKER).v1(),
            FileStructureOverrides.EMPTY_OVERRIDES);

        assertFalse(header.v1());
        assertThat(header.v2(), arrayContaining("", "", "", ""));
    }

    public void testLevenshteinDistance() {

        assertEquals(0, levenshteinDistance("cat", "cat"));
        assertEquals(3, levenshteinDistance("cat", "dog"));
        assertEquals(5, levenshteinDistance("cat", "mouse"));
        assertEquals(3, levenshteinDistance("cat", ""));

        assertEquals(3, levenshteinDistance("dog", "cat"));
        assertEquals(0, levenshteinDistance("dog", "dog"));
        assertEquals(4, levenshteinDistance("dog", "mouse"));
        assertEquals(3, levenshteinDistance("dog", ""));

        assertEquals(5, levenshteinDistance("mouse", "cat"));
        assertEquals(4, levenshteinDistance("mouse", "dog"));
        assertEquals(0, levenshteinDistance("mouse", "mouse"));
        assertEquals(5, levenshteinDistance("mouse", ""));

        assertEquals(3, levenshteinDistance("", "cat"));
        assertEquals(3, levenshteinDistance("", "dog"));
        assertEquals(5, levenshteinDistance("", "mouse"));
        assertEquals(0, levenshteinDistance("", ""));
    }

    public void testMakeShortFieldMask() {

        List<List<String>> rows = new ArrayList<>();
        rows.add(Arrays.asList(randomAlphaOfLength(5), randomAlphaOfLength(20), randomAlphaOfLength(5)));
        rows.add(Arrays.asList(randomAlphaOfLength(50), randomAlphaOfLength(5), randomAlphaOfLength(5)));
        rows.add(Arrays.asList(randomAlphaOfLength(5), randomAlphaOfLength(5), randomAlphaOfLength(5)));
        rows.add(Arrays.asList(randomAlphaOfLength(5), randomAlphaOfLength(5), randomAlphaOfLength(80)));

        BitSet shortFieldMask = DelimitedFileStructureFinder.makeShortFieldMask(rows, 110);
        assertThat(shortFieldMask, equalTo(TimestampFormatFinder.stringToNumberPosBitSet("111")));
        shortFieldMask = DelimitedFileStructureFinder.makeShortFieldMask(rows, 80);
        assertThat(shortFieldMask, equalTo(TimestampFormatFinder.stringToNumberPosBitSet("11 ")));
        shortFieldMask = DelimitedFileStructureFinder.makeShortFieldMask(rows, 50);
        assertThat(shortFieldMask, equalTo(TimestampFormatFinder.stringToNumberPosBitSet(" 1 ")));
        shortFieldMask = DelimitedFileStructureFinder.makeShortFieldMask(rows, 20);
        assertThat(shortFieldMask, equalTo(TimestampFormatFinder.stringToNumberPosBitSet("   ")));
    }

    public void testLevenshteinCompareRows() {

        assertEquals(0, levenshteinFieldwiseCompareRows(Arrays.asList("cat", "dog"), Arrays.asList("cat", "dog")));
        assertEquals(3, levenshteinFieldwiseCompareRows(Arrays.asList("cat", "dog"), Arrays.asList("cat", "cat")));
        assertEquals(6, levenshteinFieldwiseCompareRows(Arrays.asList("cat", "dog"), Arrays.asList("dog", "cat")));
        assertEquals(8, levenshteinFieldwiseCompareRows(Arrays.asList("cat", "dog"), Arrays.asList("mouse", "cat")));
        assertEquals(10, levenshteinFieldwiseCompareRows(Arrays.asList("cat", "dog", "mouse"), Arrays.asList("mouse", "dog", "cat")));
        assertEquals(9, levenshteinFieldwiseCompareRows(Arrays.asList("cat", "dog", "mouse"), Arrays.asList("mouse", "mouse", "mouse")));
        assertEquals(12, levenshteinFieldwiseCompareRows(Arrays.asList("cat", "dog", "mouse"), Arrays.asList("mouse", "cat", "dog")));
    }

    public void testLevenshteinCompareRowsWithMask() {

        assertEquals(0, levenshteinFieldwiseCompareRows(Arrays.asList("cat", "dog"), Arrays.asList("cat", "dog"),
            TimestampFormatFinder.stringToNumberPosBitSet(randomFrom("  ", "1 ", " 1", "11"))));
        assertEquals(0, levenshteinFieldwiseCompareRows(Arrays.asList("cat", "dog"), Arrays.asList("cat", "cat"),
            TimestampFormatFinder.stringToNumberPosBitSet(randomFrom("  ", "1 "))));
        assertEquals(3, levenshteinFieldwiseCompareRows(Arrays.asList("cat", "dog"), Arrays.asList("dog", "cat"),
            TimestampFormatFinder.stringToNumberPosBitSet(randomFrom(" 1", "1 "))));
        assertEquals(3, levenshteinFieldwiseCompareRows(Arrays.asList("cat", "dog"), Arrays.asList("mouse", "cat"),
            TimestampFormatFinder.stringToNumberPosBitSet(" 1")));
        assertEquals(5, levenshteinFieldwiseCompareRows(Arrays.asList("cat", "dog", "mouse"), Arrays.asList("mouse", "dog", "cat"),
            TimestampFormatFinder.stringToNumberPosBitSet(" 11")));
        assertEquals(4, levenshteinFieldwiseCompareRows(Arrays.asList("cat", "dog", "mouse"), Arrays.asList("mouse", "mouse", "mouse"),
            TimestampFormatFinder.stringToNumberPosBitSet(" 11")));
        assertEquals(7, levenshteinFieldwiseCompareRows(Arrays.asList("cat", "dog", "mouse"), Arrays.asList("mouse", "cat", "dog"),
            TimestampFormatFinder.stringToNumberPosBitSet(" 11")));
    }

    public void testLineHasUnescapedQuote() {

        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("a,b,c", CsvPreference.EXCEL_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("\"a\",b,c", CsvPreference.EXCEL_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("\"a,b\",c", CsvPreference.EXCEL_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("\"a,b,c\"", CsvPreference.EXCEL_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("a,\"b\",c", CsvPreference.EXCEL_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("a,b,\"c\"", CsvPreference.EXCEL_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("a,\"b\"\"\",c", CsvPreference.EXCEL_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("a,b,\"c\"\"\"", CsvPreference.EXCEL_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("\"\"\"a\",b,c", CsvPreference.EXCEL_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("\"a\"\"\",b,c", CsvPreference.EXCEL_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("\"a,\"\"b\",c", CsvPreference.EXCEL_PREFERENCE));
        assertTrue(DelimitedFileStructureFinder.lineHasUnescapedQuote("between\"words,b,c", CsvPreference.EXCEL_PREFERENCE));
        assertTrue(DelimitedFileStructureFinder.lineHasUnescapedQuote("x and \"y\",b,c", CsvPreference.EXCEL_PREFERENCE));

        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("a\tb\tc", CsvPreference.TAB_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("\"a\"\tb\tc", CsvPreference.TAB_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("\"a\tb\"\tc", CsvPreference.TAB_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("\"a\tb\tc\"", CsvPreference.TAB_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("a\t\"b\"\tc", CsvPreference.TAB_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("a\tb\t\"c\"", CsvPreference.TAB_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("a\t\"b\"\"\"\tc", CsvPreference.TAB_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("a\tb\t\"c\"\"\"", CsvPreference.TAB_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("\"\"\"a\"\tb\tc", CsvPreference.TAB_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("\"a\"\"\"\tb\tc", CsvPreference.TAB_PREFERENCE));
        assertFalse(DelimitedFileStructureFinder.lineHasUnescapedQuote("\"a\t\"\"b\"\tc", CsvPreference.TAB_PREFERENCE));
        assertTrue(DelimitedFileStructureFinder.lineHasUnescapedQuote("between\"words\tb\tc", CsvPreference.TAB_PREFERENCE));
        assertTrue(DelimitedFileStructureFinder.lineHasUnescapedQuote("x and \"y\"\tb\tc", CsvPreference.TAB_PREFERENCE));
    }

    public void testRowContainsDuplicateNonEmptyValues() {

        assertNull(DelimitedFileStructureFinder.findDuplicateNonEmptyValues(Collections.singletonList("a")));
        assertNull(DelimitedFileStructureFinder.findDuplicateNonEmptyValues(Collections.singletonList("")));
        assertNull(DelimitedFileStructureFinder.findDuplicateNonEmptyValues(Arrays.asList("a", "b", "c")));
        assertEquals("a", DelimitedFileStructureFinder.findDuplicateNonEmptyValues(Arrays.asList("a", "b", "a")));
        assertEquals("b", DelimitedFileStructureFinder.findDuplicateNonEmptyValues(Arrays.asList("a", "b", "b")));
        assertNull(DelimitedFileStructureFinder.findDuplicateNonEmptyValues(Arrays.asList("a", "", "")));
        assertNull(DelimitedFileStructureFinder.findDuplicateNonEmptyValues(Arrays.asList("", "a", "")));
    }

    public void testMakeCsvProcessorSettings() {

        String field = randomAlphaOfLength(10);
        List<String> targetFields = Arrays.asList(generateRandomStringArray(10, field.length() - 1, false, false));
        char separator = randomFrom(',', ';', '\t', '|');
        char quote = randomFrom('"', '\'');
        boolean trim = randomBoolean();
        Map<String, Object> settings = DelimitedFileStructureFinder.makeCsvProcessorSettings(field, targetFields, separator, quote, trim);
        assertThat(settings.get("field"), equalTo(field));
        assertThat(settings.get("target_fields"), equalTo(targetFields));
        assertThat(settings.get("ignore_missing"), equalTo(false));
        if (separator == ',') {
            assertThat(settings, not(hasKey("separator")));
        } else {
            assertThat(settings.get("separator"), equalTo(String.valueOf(separator)));
        }
        if (quote == '"') {
            assertThat(settings, not(hasKey("quote")));
        } else {
            assertThat(settings.get("quote"), equalTo(String.valueOf(quote)));
        }
        if (trim) {
            assertThat(settings.get("trim"), equalTo(true));
        } else {
            assertThat(settings, not(hasKey("trim")));
        }
    }

    public void testMultilineStartPatternGivenNoMultiline() {

        List<String> columnNames = Stream.generate(() -> randomAlphaOfLengthBetween(5, 10)).limit(10).collect(Collectors.toList());
        String timeFieldName;
        TimestampFormatFinder timeFieldFormat;
        if (randomBoolean()) {
            timeFieldName = columnNames.get(randomIntBetween(0, columnNames.size() - 1));
            timeFieldFormat = new TimestampFormatFinder(explanation, true, true, true, NOOP_TIMEOUT_CHECKER);
            timeFieldFormat.addSample("2020-01-30T15:05:09");
        } else {
            timeFieldName = null;
            timeFieldFormat = null;
        }
        Map<String, Object> mappings = new TreeMap<>();
        for (String columnName : columnNames) {
            if (columnName.equals(timeFieldName)) {
                mappings.put(columnName, Collections.singletonMap(FileStructureUtils.MAPPING_TYPE_SETTING, "date"));
            } else {
                mappings.put(columnName,
                    Collections.singletonMap(FileStructureUtils.MAPPING_TYPE_SETTING,
                        randomFrom("boolean", "long", "double", "text", "keyword")));
            }
        }

        assertNull(DelimitedFileStructureFinder.makeMultilineStartPattern(explanation, columnNames, 1, ",", "\"", mappings, timeFieldName,
            timeFieldFormat));
        assertThat(explanation, contains("Not creating a multi-line start pattern as no sampled message spanned multiple lines"));
    }

    public void testMultilineStartPatternFromTimeField() {

        List<String> columnNames = Stream.generate(() -> randomAlphaOfLengthBetween(5, 10)).limit(10).collect(Collectors.toList());
        int timeFieldColumnIndex = randomIntBetween(0, columnNames.size() - 2);
        String timeFieldName = columnNames.get(timeFieldColumnIndex);
        TimestampFormatFinder timeFieldFormat = new TimestampFormatFinder(explanation, true, true, true, NOOP_TIMEOUT_CHECKER);
        timeFieldFormat.addSample("2020-01-30T15:05:09");
        Map<String, Object> mappings = new TreeMap<>();
        for (String columnName : columnNames) {
            if (columnName.equals(timeFieldName)) {
                mappings.put(columnName, Collections.singletonMap(FileStructureUtils.MAPPING_TYPE_SETTING, "date"));
            } else {
                mappings.put(columnName, Collections.singletonMap(FileStructureUtils.MAPPING_TYPE_SETTING, randomFrom("text", "keyword")));
            }
        }

        String expected = "^" + Stream.generate(() -> ".*?,").limit(timeFieldColumnIndex).collect(Collectors.joining()) +
            "\"?\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}";
        assertEquals(expected, DelimitedFileStructureFinder.makeMultilineStartPattern(explanation, columnNames, 2, ",", "\"", mappings,
            timeFieldName, timeFieldFormat));
        assertThat(explanation, contains("Created a multi-line start pattern based on timestamp column [" + timeFieldName + "]"));
    }

    public void testMultilineStartPatternFromMappings() {

        int randomIndex = randomIntBetween(0, 2);
        String type = new String[]{ "boolean", "long", "double" }[randomIndex];
        String expectedTypePattern =
            new String[]{ "(?:true|false)", "[+-]?\\d+", "[+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:[eE][+-]?\\d+)?" }[randomIndex];
        List<String> columnNames = Stream.generate(() -> randomAlphaOfLengthBetween(5, 10)).limit(10).collect(Collectors.toList());
        int chosenFieldColumnIndex = randomIntBetween(0, columnNames.size() - 2);
        String chosenField = columnNames.get(chosenFieldColumnIndex);
        Map<String, Object> mappings = new TreeMap<>();
        for (String columnName : columnNames) {
            if (columnName.equals(chosenField)) {
                mappings.put(columnName, Collections.singletonMap(FileStructureUtils.MAPPING_TYPE_SETTING, type));
            } else {
                mappings.put(columnName, Collections.singletonMap(FileStructureUtils.MAPPING_TYPE_SETTING, randomFrom("text", "keyword")));
            }
        }

        String expected = "^" + Stream.generate(() -> ".*?,").limit(chosenFieldColumnIndex).collect(Collectors.joining()) +
            "(?:" + expectedTypePattern + "|\"" + expectedTypePattern + "\"),";
        assertEquals(expected, DelimitedFileStructureFinder.makeMultilineStartPattern(explanation, columnNames, 2, ",", "\"", mappings,
            null, null));
        assertThat(explanation, contains("Created a multi-line start pattern based on [" + type + "] column [" + chosenField + "]"));
    }

    public void testMultilineStartPatternDeterminationTooHard() {

        List<String> columnNames = Stream.generate(() -> randomAlphaOfLengthBetween(5, 10)).limit(10).collect(Collectors.toList());
        Map<String, Object> mappings = new TreeMap<>();
        for (String columnName : columnNames) {
            mappings.put(columnName, Collections.singletonMap(FileStructureUtils.MAPPING_TYPE_SETTING, randomFrom("text", "keyword")));
        }

        assertNull(DelimitedFileStructureFinder.makeMultilineStartPattern(explanation, columnNames, 2, ",", "\"", mappings, null, null));
        assertThat(explanation, contains("Failed to create a suitable multi-line start pattern"));
    }

    static Map<String, Object> randomCsvProcessorSettings() {
        String field = randomAlphaOfLength(10);
        return DelimitedFileStructureFinder.makeCsvProcessorSettings(field,
            Arrays.asList(generateRandomStringArray(10, field.length() - 1, false, false)), randomFrom(',', ';', '\t', '|'),
            randomFrom('"', '\''), randomBoolean());
    }
}
