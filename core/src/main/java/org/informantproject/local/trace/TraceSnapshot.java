/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.local.trace;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import org.informantproject.core.util.ByteStream;

import com.google.common.base.Objects;
import com.google.common.base.Objects.ToStringHelper;

/**
 * Structure used as part of the response to "/trace/details".
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
// TODO make this Immutable by storing Supplier<ByteStream> for spans and mergedStackTrace
@ThreadSafe
public class TraceSnapshot {

    private final String id;
    private final long startAt;
    private final boolean stuck;
    private final long duration; // nanoseconds
    private final boolean completed;
    private final String description;
    @Nullable
    private final String attributes; // json data
    @Nullable
    private final String username;
    @Nullable
    private final String errorText;
    @Nullable
    private final String errorDetail; // json data
    @Nullable
    private final String errorStackTrace; // json data
    @Nullable
    private final String metrics; // json data
    // using ByteStream so these potentially very large strings can be streamed without consuming
    // large amounts of memory
    @Nullable
    private final ByteStream spans; // json data
    @Nullable
    private final ByteStream coarseMergedStackTree; // json data
    @Nullable
    private final ByteStream fineMergedStackTree; // json data

    private TraceSnapshot(String id, long startAt, boolean stuck, long duration, boolean completed,
            String description, @Nullable String attributes, @Nullable String username,
            @Nullable String errorText, @Nullable String errorDetail,
            @Nullable String errorStackTrace, @Nullable String metrics, @Nullable ByteStream spans,
            @Nullable ByteStream coarseMergedStackTree, @Nullable ByteStream fineMergedStackTree) {

        this.id = id;
        this.startAt = startAt;
        this.stuck = stuck;
        this.duration = duration;
        this.completed = completed;
        this.description = description;
        this.attributes = attributes;
        this.username = username;
        this.errorText = errorText;
        this.errorDetail = errorDetail;
        this.errorStackTrace = errorStackTrace;
        this.metrics = metrics;
        this.spans = spans;
        this.coarseMergedStackTree = coarseMergedStackTree;
        this.fineMergedStackTree = fineMergedStackTree;
    }

    String getId() {
        return id;
    }

    long getStartAt() {
        return startAt;
    }

    boolean isStuck() {
        return stuck;
    }

    long getDuration() {
        return duration;
    }

    boolean isCompleted() {
        return completed;
    }

    String getDescription() {
        return description;
    }

    @Nullable
    String getAttributes() {
        return attributes;
    }

    @Nullable
    String getUsername() {
        return username;
    }

    @Nullable
    String getErrorText() {
        return errorText;
    }

    @Nullable
    String getErrorDetail() {
        return errorDetail;
    }

    @Nullable
    String getErrorStackTrace() {
        return errorStackTrace;
    }

    @Nullable
    String getMetrics() {
        return metrics;
    }

    @Nullable
    ByteStream getSpans() {
        return spans;
    }

    @Nullable
    ByteStream getCoarseMergedStackTree() {
        return coarseMergedStackTree;
    }

    @Nullable
    ByteStream getFineMergedStackTree() {
        return fineMergedStackTree;
    }

    @Override
    public String toString() {
        ToStringHelper toStringHelper = Objects.toStringHelper(this)
                .add("id", id)
                .add("startAt", startAt)
                .add("stuck", stuck)
                .add("duration", duration)
                .add("completed", completed)
                .add("description", description)
                .add("attributes", attributes)
                .add("username", username)
                .add("errorText", errorText)
                .add("errorDetail", errorDetail)
                .add("errorStackTrace", errorStackTrace)
                .add("metrics", metrics);
        return toStringHelper.toString();
    }

    static Builder builder() {
        return new Builder();
    }

    static class Builder {

        @Nullable
        private String id;
        private long startAt;
        private boolean stuck;
        private long duration;
        private boolean completed;
        @Nullable
        private String description;
        @Nullable
        private String attributes;
        @Nullable
        private String username;
        @Nullable
        private String errorText;
        @Nullable
        private String errorDetail;
        @Nullable
        private String errorStackTrace;
        @Nullable
        private String metrics;
        @Nullable
        private ByteStream spans;
        @Nullable
        private ByteStream coarseMergedStackTree;
        @Nullable
        private ByteStream fineMergedStackTree;

        private Builder() {}

        Builder id(String id) {
            this.id = id;
            return this;
        }

        Builder startAt(long startAt) {
            this.startAt = startAt;
            return this;
        }

        Builder stuck(boolean stuck) {
            this.stuck = stuck;
            return this;
        }

        Builder duration(long duration) {
            this.duration = duration;
            return this;
        }

        Builder completed(boolean completed) {
            this.completed = completed;
            return this;
        }

        Builder description(String description) {
            this.description = description;
            return this;
        }

        Builder attributes(@Nullable String attributes) {
            this.attributes = attributes;
            return this;
        }

        Builder username(@Nullable String username) {
            this.username = username;
            return this;
        }

        Builder errorText(String errorText) {
            this.errorText = errorText;
            return this;
        }

        Builder errorDetail(@Nullable String errorDetail) {
            this.errorDetail = errorDetail;
            return this;
        }

        Builder errorStackTrace(String errorStackTrace) {
            this.errorStackTrace = errorStackTrace;
            return this;
        }

        Builder metrics(@Nullable String metrics) {
            this.metrics = metrics;
            return this;
        }

        Builder spans(@Nullable ByteStream spans) {
            this.spans = spans;
            return this;
        }

        Builder coarseMergedStackTree(@Nullable ByteStream coarseMergedStackTree) {
            this.coarseMergedStackTree = coarseMergedStackTree;
            return this;
        }

        Builder fineMergedStackTree(@Nullable ByteStream fineMergedStackTree) {
            this.fineMergedStackTree = fineMergedStackTree;
            return this;
        }

        TraceSnapshot build() {
            return new TraceSnapshot(id, startAt, stuck, duration, completed, description,
                    attributes, username, errorText, errorDetail, errorStackTrace, metrics, spans,
                    coarseMergedStackTree, fineMergedStackTree);
        }
    }
}