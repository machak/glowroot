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

import com.google.common.base.Objects;

/**
 * Structure used as part of the response to "/trace/details".
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class StoredTrace implements Comparable<StoredTrace> {

    private String id;
    private long startAt;
    private boolean stuck;
    private long duration; // nanoseconds
    private boolean completed;
    private String threadNames;
    private String username;

    private String metricData;
    private String rootSpan;
    // using CharSequence so these potentially very large strings can be built using
    // LargeStringBuilder
    private CharSequence spans;
    private CharSequence mergedStackTree;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public long getStartAt() {
        return startAt;
    }

    public void setStartAt(long startAt) {
        this.startAt = startAt;
    }

    public boolean isStuck() {
        return stuck;
    }

    public void setStuck(boolean stuck) {
        this.stuck = stuck;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void setCompleted(boolean completed) {
        this.completed = completed;
    }

    public String getThreadNames() {
        return threadNames;
    }

    public void setThreadNames(String threadNames) {
        this.threadNames = threadNames;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getMetricData() {
        return metricData;
    }

    public void setMetricData(String metricData) {
        this.metricData = metricData;
    }

    public String getRootSpan() {
        return rootSpan;
    }

    public void setRootSpan(String rootSpan) {
        this.rootSpan = rootSpan;
    }

    public CharSequence getSpans() {
        return spans;
    }

    public void setSpans(CharSequence spans) {
        this.spans = spans;
    }

    public CharSequence getMergedStackTree() {
        return mergedStackTree;
    }

    public void setMergedStackTree(CharSequence mergedStackTree) {
        this.mergedStackTree = mergedStackTree;
    }

    public int compareTo(StoredTrace o) {
        return (int) (startAt - o.startAt);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("id", id)
                .add("startAt", startAt)
                .add("stuck", stuck)
                .add("duration", duration)
                .add("completed", completed)
                .add("threadNames", threadNames)
                .add("username", username)
                .add("metricData", metricData)
                .add("spans", spans)
                .add("mergedStackTree", mergedStackTree)
                .toString();
    }
}