/*
 * Copyright 2013-2016 the original author or authors.
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
package org.glowroot.agent.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import org.immutables.value.Value;

import org.glowroot.storage.config.ConfigDefaults;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.Proto.OptionalInt32;

@Value.Immutable
public abstract class AdvancedConfig {

    public static final int OVERALL_AGGREGATE_QUERIES_HARD_LIMIT_MULTIPLIER = 10;
    public static final int TRANSACTION_AGGREGATE_QUERIES_HARD_LIMIT_MULTIPLIER = 2;

    @Value.Default
    @JsonInclude(value = Include.NON_EMPTY)
    public boolean weavingTimer() {
        return false;
    }

    @Value.Default
    public int immediatePartialStoreThresholdSeconds() {
        return 60;
    }

    // used to limit memory requirement
    @Value.Default
    public int maxAggregateTransactionsPerTransactionType() {
        return ConfigDefaults.MAX_AGGREGATE_TRANSACTIONS_PER_TRANSACTION_TYPE;
    }

    // used to limit memory requirement
    // applied to individual traces, transaction aggregates and overall aggregates
    @Value.Default
    public int maxAggregateQueriesPerQueryType() {
        return ConfigDefaults.MAX_AGGREGATE_QUERIES_PER_QUERY_TYPE;
    }

    // used to limit memory requirement, also used to help limit trace capture size
    @Value.Default
    public int maxTraceEntriesPerTransaction() {
        return 2000;
    }

    // used to limit memory requirement, also used to help limit trace capture size
    @Value.Default
    public int maxStackTraceSamplesPerTransaction() {
        return 10000;
    }

    @Value.Default
    public int mbeanGaugeNotFoundDelaySeconds() {
        return 60;
    }

    public AgentConfig.AdvancedConfig toProto() {
        return AgentConfig.AdvancedConfig.newBuilder()
                .setWeavingTimer(weavingTimer())
                .setImmediatePartialStoreThresholdSeconds(
                        of(immediatePartialStoreThresholdSeconds()))
                .setMaxAggregateTransactionsPerTransactionType(
                        of(maxAggregateTransactionsPerTransactionType()))
                .setMaxAggregateQueriesPerQueryType(of(maxAggregateQueriesPerQueryType()))
                .setMaxTraceEntriesPerTransaction(of(maxTraceEntriesPerTransaction()))
                .setMaxStackTraceSamplesPerTransaction(of(maxStackTraceSamplesPerTransaction()))
                .setMbeanGaugeNotFoundDelaySeconds(of(mbeanGaugeNotFoundDelaySeconds()))
                .build();
    }

    public static AdvancedConfig create(AgentConfig.AdvancedConfig config) {
        ImmutableAdvancedConfig.Builder builder = ImmutableAdvancedConfig.builder()
                .weavingTimer(config.getWeavingTimer());
        if (config.hasImmediatePartialStoreThresholdSeconds()) {
            builder.immediatePartialStoreThresholdSeconds(
                    config.getImmediatePartialStoreThresholdSeconds().getValue());
        }
        if (config.hasMaxAggregateTransactionsPerTransactionType()) {
            builder.maxAggregateTransactionsPerTransactionType(
                    config.getMaxAggregateTransactionsPerTransactionType().getValue());
        }
        if (config.hasMaxAggregateQueriesPerQueryType()) {
            builder.maxAggregateQueriesPerQueryType(
                    config.getMaxAggregateQueriesPerQueryType().getValue());
        }
        if (config.hasMaxTraceEntriesPerTransaction()) {
            builder.maxTraceEntriesPerTransaction(
                    config.getMaxTraceEntriesPerTransaction().getValue());
        }
        if (config.hasMaxStackTraceSamplesPerTransaction()) {
            builder.maxStackTraceSamplesPerTransaction(
                    config.getMaxStackTraceSamplesPerTransaction().getValue());
        }
        if (config.hasMbeanGaugeNotFoundDelaySeconds()) {
            builder.mbeanGaugeNotFoundDelaySeconds(
                    config.getMbeanGaugeNotFoundDelaySeconds().getValue());
        }
        return builder.build();
    }

    private static OptionalInt32 of(int value) {
        return OptionalInt32.newBuilder().setValue(value).build();
    }
}
