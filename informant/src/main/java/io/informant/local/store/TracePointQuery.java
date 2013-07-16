/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.informant.local.store;

import java.util.List;
import java.util.Locale;

import com.google.common.base.Objects;
import com.google.common.collect.Lists;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class TracePointQuery {

    private final long captureTimeFrom;
    private final long captureTimeTo;
    private final long durationLow;
    private final long durationHigh;
    private final Boolean background;
    private final boolean errorOnly;
    private final boolean fineOnly;
    private final StringComparator groupingComparator;
    private final String grouping;
    private final StringComparator userIdComparator;
    private final String userId;
    private final int limit;

    public TracePointQuery(long captureTimeFrom, long captureTimeTo, long durationLow,
            long durationHigh, Boolean background, boolean errorOnly, boolean fineOnly,
            StringComparator groupingComparator, String grouping,
            StringComparator userIdComparator, String userId, int limit) {
        this.captureTimeFrom = captureTimeFrom;
        this.captureTimeTo = captureTimeTo;
        this.durationLow = durationLow;
        this.durationHigh = durationHigh;
        this.background = background;
        this.errorOnly = errorOnly;
        this.fineOnly = fineOnly;
        this.groupingComparator = groupingComparator;
        this.grouping = grouping;
        this.userIdComparator = userIdComparator;
        this.userId = userId;
        this.limit = limit;
    }

    ParameterizedSql getParameterizedSql() {
        // all of these columns should be in the same index so h2 can return result set directly
        // from the index without having to reference the table for each row
        String sql = "select id, capture_time, duration, error from snapshot where stuck = ?"
                + " and capture_time >= ? and capture_time <= ?";
        List<Object> args = Lists.newArrayList();
        args.add(false);
        args.add(captureTimeFrom);
        args.add(captureTimeTo);
        if (durationLow != 0) {
            sql += " and duration >= ?";
            args.add(durationLow);
        }
        if (durationHigh != Long.MAX_VALUE) {
            sql += " and duration <= ?";
            args.add(durationHigh);
        }
        if (background != null) {
            sql += " and background = ?";
            args.add(background);
        }
        if (errorOnly) {
            sql += " and error = ?";
            args.add(true);
        }
        if (fineOnly) {
            sql += " and fine = ?";
            args.add(true);
        }
        if (groupingComparator != null && grouping != null) {
            sql += " and upper(grouping) " + groupingComparator.getComparator() + " ?";
            args.add(groupingComparator.formatParameter(grouping.toUpperCase(Locale.ENGLISH)));
        }
        if (userIdComparator != null && userId != null) {
            sql += " and upper(user_id) " + userIdComparator.getComparator() + " ?";
            args.add(userIdComparator.formatParameter(userId.toUpperCase(Locale.ENGLISH)));
        }
        sql += " order by duration desc limit ?";
        args.add(limit);
        return new ParameterizedSql(sql, args);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("captureTimeFrom", captureTimeFrom)
                .add("captureTimeTo", captureTimeTo)
                .add("durationLow", durationLow)
                .add("durationHigh", durationHigh)
                .add("background", background)
                .add("errorOnly", errorOnly)
                .add("fineOnly", fineOnly)
                .add("groupingComparator", groupingComparator)
                .add("grouping", grouping)
                .add("userIdComparator", userIdComparator)
                .add("userId", userId)
                .add("limit", limit)
                .toString();
    }

    public static enum StringComparator {

        BEGINS("like", "%s%%"),
        EQUALS("=", "%s"),
        ENDS("like", "%%%s"),
        CONTAINS("like", "%%%s%%");

        private final String comparator;
        private final String parameterFormat;

        private StringComparator(String comparator, String parameterTemplate) {
            this.comparator = comparator;
            this.parameterFormat = parameterTemplate;
        }

        public String formatParameter(String parameter) {
            return String.format(parameterFormat, parameter);
        }

        public String getComparator() {
            return comparator;
        }
    }

    static class ParameterizedSql {

        private final String sql;
        private final List<Object> args;

        private ParameterizedSql(String sql, List<Object> args) {
            this.sql = sql;
            this.args = args;
        }

        public String getSql() {
            return sql;
        }

        public List<Object> getArgs() {
            return args;
        }
    }
}
