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
package org.glowroot.ui;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Ints;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.live.LiveWeavingService;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.Versions;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.CaptureKind;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.MethodModifier;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.GlobalMeta;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MethodSignature;
import org.glowroot.wire.api.model.Proto.OptionalInt32;

import static com.google.common.base.Preconditions.checkNotNull;

@JsonService
class InstrumentationConfigJsonService {

    private static final Logger logger =
            LoggerFactory.getLogger(InstrumentationConfigJsonService.class);

    private static final ObjectMapper mapper = ObjectMappers.create();

    private static final Ordering<InstrumentationConfig> ordering =
            new InstrumentationConfigOrdering();

    private final ConfigRepository configRepository;
    private final LiveWeavingService liveWeavingService;

    InstrumentationConfigJsonService(ConfigRepository configRepository,
            LiveWeavingService liveWeavingService) {
        this.configRepository = configRepository;
        this.liveWeavingService = liveWeavingService;
    }

    @GET("/backend/config/instrumentation")
    String getInstrumentationConfig(String queryString) throws Exception {
        InstrumentationConfigRequest request =
                QueryStrings.decode(queryString, InstrumentationConfigRequest.class);
        String serverId = request.serverId();
        Optional<String> version = request.version();
        if (version.isPresent()) {
            return getInstrumentationConfigInternal(serverId, version.get());
        } else {
            List<InstrumentationConfig> configs =
                    configRepository.getInstrumentationConfigs(serverId);
            configs = ordering.immutableSortedCopy(configs);
            List<InstrumentationConfigDto> dtos = Lists.newArrayList();
            for (InstrumentationConfig config : configs) {
                dtos.add(InstrumentationConfigDto.create(config));
            }
            GlobalMeta globalMeta = liveWeavingService.getGlobalMeta(serverId);
            return mapper.writeValueAsString(ImmutableInstrumentationListResponse.builder()
                    .addAllConfigs(dtos)
                    .jvmOutOfSync(globalMeta.getJvmOutOfSync())
                    .jvmRetransformClassesSupported(globalMeta.getJvmRetransformClassesSupported())
                    .build());
        }
    }

    // this is marked as @GET so it can be used without update rights (e.g. demo instance)
    @GET("/backend/config/preload-classpath-cache")
    void preloadClasspathCache(String queryString) throws Exception {
        final String serverId = getServerId(queryString);
        // HttpServer is configured with a very small thread pool to keep number of threads down
        // (currently only a single thread), so spawn a background thread to perform the preloading
        // so it doesn't block other http requests
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    liveWeavingService.preloadClasspathCache(serverId);
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
            }
        });
        thread.setDaemon(true);
        thread.setName("Glowroot-Temporary-Thread");
        thread.start();
    }

    @GET("/backend/config/matching-class-names")
    String getMatchingClassNames(String queryString) throws Exception {
        ClassNamesRequest request = QueryStrings.decode(queryString, ClassNamesRequest.class);
        return mapper.writeValueAsString(liveWeavingService.getMatchingClassNames(
                request.serverId(), request.partialClassName(), request.limit()));
    }

    @GET("/backend/config/matching-method-names")
    String getMatchingMethodNames(String queryString) throws Exception {
        MethodNamesRequest request = QueryStrings.decode(queryString, MethodNamesRequest.class);
        List<String> matchingMethodNames =
                liveWeavingService.getMatchingMethodNames(request.serverId(), request.className(),
                        request.partialMethodName(), request.limit());
        return mapper.writeValueAsString(matchingMethodNames);
    }

    @GET("/backend/config/method-signatures")
    String getMethodSignatures(String queryString) throws Exception {
        MethodSignaturesRequest request =
                QueryStrings.decode(queryString, MethodSignaturesRequest.class);
        List<MethodSignature> signatures = liveWeavingService
                .getMethodSignatures(request.serverId(), request.className(), request.methodName());
        List<MethodSignatureDto> methodSignatures = Lists.newArrayList();
        for (MethodSignature signature : signatures) {
            methodSignatures.add(MethodSignatureDto.create(signature));
        }
        return mapper.writeValueAsString(methodSignatures);
    }

    @POST("/backend/config/instrumentation/add")
    String addInstrumentationConfig(String content) throws Exception {
        InstrumentationConfigDto configDto =
                mapper.readValue(content, ImmutableInstrumentationConfigDto.class);
        String serverId = checkNotNull(configDto.serverId());
        InstrumentationConfig config = configDto.convert();
        configRepository.insertInstrumentationConfig(serverId, config);
        return getInstrumentationConfigInternal(serverId, Versions.getVersion(config));
    }

    @POST("/backend/config/instrumentation/update")
    String updateInstrumentationConfig(String content) throws Exception {
        InstrumentationConfigDto configDto =
                mapper.readValue(content, ImmutableInstrumentationConfigDto.class);
        String serverId = checkNotNull(configDto.serverId());
        InstrumentationConfig config = configDto.convert();
        String version = configDto.version();
        checkNotNull(version, "Missing required request property: version");
        configRepository.updateInstrumentationConfig(serverId, config, version);
        return getInstrumentationConfigInternal(serverId, Versions.getVersion(config));
    }

    @POST("/backend/config/instrumentation/remove")
    void removeInstrumentationConfig(String content) throws IOException {
        InstrumentationConfigRequest request =
                mapper.readValue(content, ImmutableInstrumentationConfigRequest.class);
        configRepository.deleteInstrumentationConfig(request.serverId(), request.version().get());
    }

    private String getInstrumentationConfigInternal(String serverId, String version)
            throws Exception {
        InstrumentationConfig config =
                configRepository.getInstrumentationConfig(serverId, version);
        if (config == null) {
            throw new JsonServiceException(HttpResponseStatus.NOT_FOUND);
        }
        List<MethodSignature> methodSignatures = liveWeavingService.getMethodSignatures(serverId,
                config.getClassName(), config.getMethodName());
        ImmutableInstrumentationConfigResponse.Builder builder =
                ImmutableInstrumentationConfigResponse.builder()
                        .config(InstrumentationConfigDto.create(config));
        for (MethodSignature methodSignature : methodSignatures) {
            builder.addMethodSignatures(MethodSignatureDto.create(methodSignature));
        }
        return mapper.writeValueAsString(builder.build());
    }

    private static String getServerId(String queryString) {
        return QueryStringDecoder.decodeComponent(queryString.substring("server-id".length() + 1));
    }

    @Value.Immutable
    interface InstrumentationConfigRequest {
        String serverId();
        Optional<String> version();
    }

    @Value.Immutable
    interface ClassNamesRequest {
        String serverId();
        String partialClassName();
        int limit();
    }

    @Value.Immutable
    interface MethodNamesRequest {
        String serverId();
        String className();
        String partialMethodName();
        int limit();
    }

    @Value.Immutable
    interface MethodSignaturesRequest {
        String serverId();
        String className();
        String methodName();
    }

    @Value.Immutable
    interface InstrumentationListResponse {
        ImmutableList<InstrumentationConfigDto> configs();
        boolean jvmOutOfSync();
        boolean jvmRetransformClassesSupported();
    }

    @Value.Immutable
    interface InstrumentationConfigResponse {
        InstrumentationConfigDto config();
        ImmutableList<MethodSignatureDto> methodSignatures();
    }

    @Value.Immutable
    interface InstrumentationErrorResponse {
        abstract ImmutableList<String> errors();
    }

    @Value.Immutable
    @JsonInclude(value = Include.ALWAYS)
    abstract static class InstrumentationConfigDto {

        @JsonInclude(value = Include.NON_EMPTY)
        abstract @Nullable String serverId(); // only used in request
        abstract String className();
        abstract String classAnnotation();
        abstract String methodDeclaringClassName();
        abstract String methodName();
        abstract String methodAnnotation();
        abstract ImmutableList<String> methodParameterTypes();
        abstract String methodReturnType();
        abstract ImmutableList<MethodModifier> methodModifiers();
        abstract String nestingGroup();
        abstract int priority();
        abstract CaptureKind captureKind();
        abstract String timerName();
        abstract String traceEntryMessageTemplate();
        abstract @Nullable Integer traceEntryStackThresholdMillis();
        abstract boolean traceEntryCaptureSelfNested();
        abstract String transactionType();
        abstract String transactionNameTemplate();
        abstract String transactionUserTemplate();
        abstract Map<String, String> transactionAttributeTemplates();
        abstract @Nullable Integer transactionSlowThresholdMillis();
        abstract String enabledProperty();
        abstract String traceEntryEnabledProperty();
        abstract @Nullable String version(); // absent for insert operations

        private InstrumentationConfig convert() {
            InstrumentationConfig.Builder builder = InstrumentationConfig.newBuilder()
                    .setClassName(className())
                    .setMethodDeclaringClassName(methodDeclaringClassName())
                    .setMethodName(methodName())
                    .addAllMethodParameterType(methodParameterTypes())
                    .setMethodReturnType(methodReturnType())
                    .addAllMethodModifier(methodModifiers())
                    .setNestingGroup(nestingGroup())
                    .setPriority(priority())
                    .setCaptureKind(captureKind())
                    .setTimerName(timerName())
                    .setTraceEntryMessageTemplate(traceEntryMessageTemplate());
            Integer traceEntryStackThresholdMillis = traceEntryStackThresholdMillis();
            if (traceEntryStackThresholdMillis != null) {
                builder.setTraceEntryStackThresholdMillis(
                        OptionalInt32.newBuilder().setValue(traceEntryStackThresholdMillis));
            }
            builder.setTraceEntryCaptureSelfNested(traceEntryCaptureSelfNested())
                    .setTransactionType(transactionType())
                    .setTransactionNameTemplate(transactionNameTemplate())
                    .setTransactionUserTemplate(transactionUserTemplate())
                    .putAllTransactionAttributeTemplates(transactionAttributeTemplates());
            Integer transactionSlowThresholdMillis = transactionSlowThresholdMillis();
            if (transactionSlowThresholdMillis != null) {
                builder.setTransactionSlowThresholdMillis(
                        OptionalInt32.newBuilder().setValue(transactionSlowThresholdMillis));
            }
            return builder.setEnabledProperty(enabledProperty())
                    .setTraceEntryEnabledProperty(traceEntryEnabledProperty())
                    .build();
        }

        private static InstrumentationConfigDto create(InstrumentationConfig config) {
            ImmutableInstrumentationConfigDto.Builder builder =
                    ImmutableInstrumentationConfigDto.builder()
                            .className(config.getClassName())
                            .classAnnotation(config.getClassAnnotation())
                            .methodDeclaringClassName(config.getMethodDeclaringClassName())
                            .methodName(config.getMethodName())
                            .methodAnnotation(config.getMethodAnnotation())
                            .addAllMethodParameterTypes(config.getMethodParameterTypeList())
                            .methodReturnType(config.getMethodReturnType())
                            .addAllMethodModifiers(config.getMethodModifierList())
                            .nestingGroup(config.getNestingGroup())
                            .priority(config.getPriority())
                            .captureKind(config.getCaptureKind())
                            .timerName(config.getTimerName())
                            .traceEntryMessageTemplate(config.getTraceEntryMessageTemplate());
            if (config.hasTraceEntryStackThresholdMillis()) {
                builder.traceEntryStackThresholdMillis(
                        config.getTraceEntryStackThresholdMillis().getValue());
            }
            builder.traceEntryCaptureSelfNested(config.getTraceEntryCaptureSelfNested())
                    .transactionType(config.getTransactionType())
                    .transactionNameTemplate(config.getTransactionNameTemplate())
                    .transactionUserTemplate(config.getTransactionUserTemplate())
                    .putAllTransactionAttributeTemplates(config.getTransactionAttributeTemplates());
            if (config.hasTransactionSlowThresholdMillis()) {
                builder.transactionSlowThresholdMillis(
                        config.getTransactionSlowThresholdMillis().getValue());
            }
            return builder.enabledProperty(config.getEnabledProperty())
                    .traceEntryEnabledProperty(config.getTraceEntryEnabledProperty())
                    .version(Versions.getVersion(config))
                    .build();
        }
    }

    @Value.Immutable
    @JsonInclude(value = Include.ALWAYS)
    abstract static class MethodSignatureDto {

        abstract String name();
        abstract ImmutableList<String> parameterTypes();
        abstract String returnType();
        abstract ImmutableList<String> modifiers();

        private static MethodSignatureDto create(MethodSignature methodSignature) {
            return ImmutableMethodSignatureDto.builder()
                    .name(methodSignature.getName())
                    .addAllParameterTypes(methodSignature.getParameterTypeList())
                    .returnType(methodSignature.getReturnType())
                    .modifiers(methodSignature.getModifierList())
                    .build();
        }
    }

    @VisibleForTesting
    static class InstrumentationConfigOrdering extends Ordering<InstrumentationConfig> {
        @Override
        public int compare(InstrumentationConfig left, InstrumentationConfig right) {
            int compare = left.getClassName().compareToIgnoreCase(right.getClassName());
            if (compare != 0) {
                return compare;
            }
            compare = left.getMethodName().compareToIgnoreCase(right.getMethodName());
            if (compare != 0) {
                return compare;
            }
            List<String> leftParameterTypes = left.getMethodParameterTypeList();
            List<String> rightParameterTypes = right.getMethodParameterTypeList();
            compare = Ints.compare(leftParameterTypes.size(), rightParameterTypes.size());
            if (compare != 0) {
                return compare;
            }
            for (int i = 0; i < leftParameterTypes.size(); i++) {
                compare = leftParameterTypes.get(i).compareToIgnoreCase(rightParameterTypes.get(i));
                if (compare != 0) {
                    return compare;
                }
            }
            return 0;
        }
    }
}
