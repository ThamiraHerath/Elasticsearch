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

package org.elasticsearch.common.joda;

import org.elasticsearch.common.Strings;
import org.joda.time.*;
import org.joda.time.field.DividedDateTimeField;
import org.joda.time.field.OffsetDateTimeField;
import org.joda.time.field.ScaledDurationField;
import org.joda.time.format.*;

import java.io.IOException;
import java.io.Writer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 *
 */
public class Joda {

    public static FormatDateTimeFormatter forPattern(String input) {
        return forPattern(input, Locale.ROOT);
    }

    /**
     * Parses a joda based pattern, including some named ones (similar to the built in Joda ISO ones).
     */
    public static FormatDateTimeFormatter forPattern(String input, Locale locale) {
        if (Strings.hasLength(input)) {
            input = input.trim();
        }
        if (input == null || input.length() == 0) {
            throw new IllegalArgumentException("No date pattern provided");
        }

        DateTimeFormatter formatter;
        if ("basicDate".equals(input) || "basic_date".equals(input)) {
            formatter = ISODateTimeFormat.basicDate();
        } else if ("basicDateTime".equals(input) || "basic_date_time".equals(input)) {
            formatter = ISODateTimeFormat.basicDateTime();
        } else if ("basicDateTimeNoMillis".equals(input) || "basic_date_time_no_millis".equals(input)) {
            formatter = ISODateTimeFormat.basicDateTimeNoMillis();
        } else if ("basicOrdinalDate".equals(input) || "basic_ordinal_date".equals(input)) {
            formatter = ISODateTimeFormat.basicOrdinalDate();
        } else if ("basicOrdinalDateTime".equals(input) || "basic_ordinal_date_time".equals(input)) {
            formatter = ISODateTimeFormat.basicOrdinalDateTime();
        } else if ("basicOrdinalDateTimeNoMillis".equals(input) || "basic_ordinal_date_time_no_millis".equals(input)) {
            formatter = ISODateTimeFormat.basicOrdinalDateTimeNoMillis();
        } else if ("basicTime".equals(input) || "basic_time".equals(input)) {
            formatter = ISODateTimeFormat.basicTime();
        } else if ("basicTimeNoMillis".equals(input) || "basic_time_no_millis".equals(input)) {
            formatter = ISODateTimeFormat.basicTimeNoMillis();
        } else if ("basicTTime".equals(input) || "basic_t_Time".equals(input)) {
            formatter = ISODateTimeFormat.basicTTime();
        } else if ("basicTTimeNoMillis".equals(input) || "basic_t_time_no_millis".equals(input)) {
            formatter = ISODateTimeFormat.basicTTimeNoMillis();
        } else if ("basicWeekDate".equals(input) || "basic_week_date".equals(input)) {
            formatter = ISODateTimeFormat.basicWeekDate();
        } else if ("basicWeekDateTime".equals(input) || "basic_week_date_time".equals(input)) {
            formatter = ISODateTimeFormat.basicWeekDateTime();
        } else if ("basicWeekDateTimeNoMillis".equals(input) || "basic_week_date_time_no_millis".equals(input)) {
            formatter = ISODateTimeFormat.basicWeekDateTimeNoMillis();
        } else if ("date".equals(input)) {
            formatter = ISODateTimeFormat.date();
        } else if ("dateHour".equals(input) || "date_hour".equals(input)) {
            formatter = ISODateTimeFormat.dateHour();
        } else if ("dateHourMinute".equals(input) || "date_hour_minute".equals(input)) {
            formatter = ISODateTimeFormat.dateHourMinute();
        } else if ("dateHourMinuteSecond".equals(input) || "date_hour_minute_second".equals(input)) {
            formatter = ISODateTimeFormat.dateHourMinuteSecond();
        } else if ("dateHourMinuteSecondFraction".equals(input) || "date_hour_minute_second_fraction".equals(input)) {
            formatter = ISODateTimeFormat.dateHourMinuteSecondFraction();
        } else if ("dateHourMinuteSecondMillis".equals(input) || "date_hour_minute_second_millis".equals(input)) {
            formatter = ISODateTimeFormat.dateHourMinuteSecondMillis();
        } else if ("dateOptionalTime".equals(input) || "date_optional_time".equals(input)) {
            // in this case, we have a separate parser and printer since the dataOptionalTimeParser can't print
            // this sucks we should use the root local by default and not be dependent on the node
            return new FormatDateTimeFormatter(input,
                    ISODateTimeFormat.dateOptionalTimeParser().withZone(DateTimeZone.UTC),
                    ISODateTimeFormat.dateTime().withZone(DateTimeZone.UTC), locale);
        } else if ("dateTime".equals(input) || "date_time".equals(input)) {
            formatter = ISODateTimeFormat.dateTime();
        } else if ("dateTimeNoMillis".equals(input) || "date_time_no_millis".equals(input)) {
            formatter = ISODateTimeFormat.dateTimeNoMillis();
        } else if ("hour".equals(input)) {
            formatter = ISODateTimeFormat.hour();
        } else if ("hourMinute".equals(input) || "hour_minute".equals(input)) {
            formatter = ISODateTimeFormat.hourMinute();
        } else if ("hourMinuteSecond".equals(input) || "hour_minute_second".equals(input)) {
            formatter = ISODateTimeFormat.hourMinuteSecond();
        } else if ("hourMinuteSecondFraction".equals(input) || "hour_minute_second_fraction".equals(input)) {
            formatter = ISODateTimeFormat.hourMinuteSecondFraction();
        } else if ("hourMinuteSecondMillis".equals(input) || "hour_minute_second_millis".equals(input)) {
            formatter = ISODateTimeFormat.hourMinuteSecondMillis();
        } else if ("ordinalDate".equals(input) || "ordinal_date".equals(input)) {
            formatter = ISODateTimeFormat.ordinalDate();
        } else if ("ordinalDateTime".equals(input) || "ordinal_date_time".equals(input)) {
            formatter = ISODateTimeFormat.ordinalDateTime();
        } else if ("ordinalDateTimeNoMillis".equals(input) || "ordinal_date_time_no_millis".equals(input)) {
            formatter = ISODateTimeFormat.ordinalDateTimeNoMillis();
        } else if ("time".equals(input)) {
            formatter = ISODateTimeFormat.time();
        } else if ("tTime".equals(input) || "t_time".equals(input)) {
            formatter = ISODateTimeFormat.tTime();
        } else if ("tTimeNoMillis".equals(input) || "t_time_no_millis".equals(input)) {
            formatter = ISODateTimeFormat.tTimeNoMillis();
        } else if ("weekDate".equals(input) || "week_date".equals(input)) {
            formatter = ISODateTimeFormat.weekDate();
        } else if ("weekDateTime".equals(input) || "week_date_time".equals(input)) {
            formatter = ISODateTimeFormat.weekDateTime();
        } else if ("weekyear".equals(input) || "week_year".equals(input)) {
            formatter = ISODateTimeFormat.weekyear();
        } else if ("weekyearWeek".equals(input)) {
            formatter = ISODateTimeFormat.weekyearWeek();
        } else if ("year".equals(input)) {
            formatter = ISODateTimeFormat.year();
        } else if ("yearMonth".equals(input) || "year_month".equals(input)) {
            formatter = ISODateTimeFormat.yearMonth();
        } else if ("yearMonthDay".equals(input) || "year_month_day".equals(input)) {
            formatter = ISODateTimeFormat.yearMonthDay();
        } else if ("epoch_second".equals(input)) {
            formatter = new DateTimeFormatterBuilder().append(new EpochTimePrinter(false), new EpochTimeParser(false)).toFormatter();
        } else if ("epoch_millis".equals(input)) {
            formatter = new DateTimeFormatterBuilder().append(new EpochTimePrinter(true), new EpochTimeParser(true)).toFormatter();
        } else if (Strings.hasLength(input) && input.contains("||")) {
                String[] formats = Strings.delimitedListToStringArray(input, "||");
                DateTimeParser[] parsers = new DateTimeParser[formats.length];

                if (formats.length == 1) {
                    formatter = forPattern(input, locale).parser();
                } else {
                    DateTimeFormatter dateTimeFormatter = null;
                    for (int i = 0; i < formats.length; i++) {
                        FormatDateTimeFormatter currentFormatter = forPattern(formats[i], locale);
                        DateTimeFormatter currentParser = currentFormatter.parser();
                        if (dateTimeFormatter == null) {
                            dateTimeFormatter = currentFormatter.printer();
                        }
                        parsers[i] = currentParser.getParser();
                    }

                    DateTimeFormatterBuilder builder = new DateTimeFormatterBuilder().append(dateTimeFormatter.withZone(DateTimeZone.UTC).getPrinter(), parsers);
                    formatter = builder.toFormatter();
                }
        } else {
            try {
                formatter = DateTimeFormat.forPattern(input);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid format: [" + input + "]: " + e.getMessage(), e);
            }
        }

        return new FormatDateTimeFormatter(input, formatter.withZone(DateTimeZone.UTC), locale);
    }


    public static final DurationFieldType Quarters = new DurationFieldType("quarters") {
        private static final long serialVersionUID = -8167713675442491871L;

        @Override
        public DurationField getField(Chronology chronology) {
            return new ScaledDurationField(chronology.months(), Quarters, 3);
        }
    };

    public static final DateTimeFieldType QuarterOfYear = new DateTimeFieldType("quarterOfYear") {
        private static final long serialVersionUID = -5677872459807379123L;

        @Override
        public DurationFieldType getDurationType() {
            return Quarters;
        }

        @Override
        public DurationFieldType getRangeDurationType() {
            return DurationFieldType.years();
        }

        @Override
        public DateTimeField getField(Chronology chronology) {
            return new OffsetDateTimeField(new DividedDateTimeField(new OffsetDateTimeField(chronology.monthOfYear(), -1), QuarterOfYear, 3), 1);
        }
    };

    public static class EpochTimeParser implements DateTimeParser {

        private static final Pattern MILLI_SECOND_PRECISION_PATTERN = Pattern.compile("^-?\\d{1,13}$");
        private static final Pattern SECOND_PRECISION_PATTERN = Pattern.compile("^-?\\d{1,10}$");

        private final boolean hasMilliSecondPrecision;
        private final Pattern pattern;

        public EpochTimeParser(boolean hasMilliSecondPrecision) {
            this.hasMilliSecondPrecision = hasMilliSecondPrecision;
            this.pattern = hasMilliSecondPrecision ? MILLI_SECOND_PRECISION_PATTERN : SECOND_PRECISION_PATTERN;
        }

        @Override
        public int estimateParsedLength() {
            return hasMilliSecondPrecision ? 13 : 10;
        }

        @Override
        public int parseInto(DateTimeParserBucket bucket, String text, int position) {
            boolean isPositive = text.startsWith("-") == false;
            boolean isTooLong = text.length() > estimateParsedLength();

            if ((isPositive && isTooLong) ||
                // timestamps have to have UTC timezone
                bucket.getZone() != DateTimeZone.UTC ||
                pattern.matcher(text).matches() == false) {
                return -1;
            }

            int factor = hasMilliSecondPrecision ? 1 : 1000;
            try {
                long millis = Long.valueOf(text) * factor;
                DateTime dt = new DateTime(millis, DateTimeZone.UTC);
                bucket.saveField(DateTimeFieldType.year(), dt.getYear());
                bucket.saveField(DateTimeFieldType.monthOfYear(), dt.getMonthOfYear());
                bucket.saveField(DateTimeFieldType.dayOfMonth(), dt.getDayOfMonth());
                bucket.saveField(DateTimeFieldType.hourOfDay(), dt.getHourOfDay());
                bucket.saveField(DateTimeFieldType.minuteOfHour(), dt.getMinuteOfHour());
                bucket.saveField(DateTimeFieldType.secondOfMinute(), dt.getSecondOfMinute());
                bucket.saveField(DateTimeFieldType.millisOfSecond(), dt.getMillisOfSecond());
                bucket.setZone(DateTimeZone.UTC);
            } catch (Exception e) {
                return -1;
            }
            return text.length();
        }
    }

    public static class EpochTimePrinter implements DateTimePrinter {

        private boolean hasMilliSecondPrecision;

        public EpochTimePrinter(boolean hasMilliSecondPrecision) {
            this.hasMilliSecondPrecision = hasMilliSecondPrecision;
        }

        @Override
        public int estimatePrintedLength() {
            return hasMilliSecondPrecision ? 13 : 10;
        }

        @Override
        public void printTo(StringBuffer buf, long instant, Chronology chrono, int displayOffset, DateTimeZone displayZone, Locale locale) {
            if (hasMilliSecondPrecision) {
                buf.append(instant);
            } else {
                buf.append(instant / 1000);
            }
        }

        @Override
        public void printTo(Writer out, long instant, Chronology chrono, int displayOffset, DateTimeZone displayZone, Locale locale) throws IOException {
            if (hasMilliSecondPrecision) {
                out.write(String.valueOf(instant));
            } else {
                out.append(String.valueOf(instant / 1000));
            }
        }

        @Override
        public void printTo(StringBuffer buf, ReadablePartial partial, Locale locale) {
            if (hasMilliSecondPrecision) {
                buf.append(String.valueOf(getDateTimeMillis(partial)));
            } else {
                buf.append(String.valueOf(getDateTimeMillis(partial) / 1000));
            }
        }

        @Override
        public void printTo(Writer out, ReadablePartial partial, Locale locale) throws IOException {
            if (hasMilliSecondPrecision) {
                out.append(String.valueOf(getDateTimeMillis(partial)));
            } else {
                out.append(String.valueOf(getDateTimeMillis(partial) / 1000));
            }
        }

        private long getDateTimeMillis(ReadablePartial partial) {
            int year = partial.get(DateTimeFieldType.year());
            int monthOfYear = partial.get(DateTimeFieldType.monthOfYear());
            int dayOfMonth = partial.get(DateTimeFieldType.dayOfMonth());
            int hourOfDay = partial.get(DateTimeFieldType.hourOfDay());
            int minuteOfHour = partial.get(DateTimeFieldType.minuteOfHour());
            int secondOfMinute = partial.get(DateTimeFieldType.secondOfMinute());
            int millisOfSecond = partial.get(DateTimeFieldType.millisOfSecond());
            return partial.getChronology().getDateTimeMillis(year, monthOfYear, dayOfMonth, hourOfDay, minuteOfHour, secondOfMinute, millisOfSecond);
        }
    }
}
