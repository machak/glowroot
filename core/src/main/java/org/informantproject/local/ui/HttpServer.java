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
package org.informantproject.local.ui;

import static org.jboss.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.informantproject.core.util.HttpServerBase;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders.Names;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closeables;
import com.google.gson.Gson;
import com.google.inject.BindingAnnotation;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Handles all http requests for the embedded UI (by default http://localhost:4000).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class HttpServer extends HttpServerBase {

    private static final Logger logger = LoggerFactory.getLogger(HttpServer.class);

    private static final long TEN_YEARS = 10 * 365 * 24 * 60 * 60 * 1000L;

    private final Map<Pattern, Object> uriMappings = Collections
            .synchronizedMap(new LinkedHashMap<Pattern, Object>());

    {
        // pages
        uriMappings.put(Pattern.compile("^/$"), "org/informantproject/local/ui/index.html");
        uriMappings.put(Pattern.compile("^/traces.html$"),
                "org/informantproject/local/ui/traces.html");
        uriMappings.put(Pattern.compile("^/configuration.html$"),
                "org/informantproject/local/ui/configuration.html");
        uriMappings.put(Pattern.compile("^/plugins.html$"),
                "org/informantproject/local/ui/plugins.html");
        // internal resources
        uriMappings.put(Pattern.compile("^/img/(.*)$"), "org/informantproject/local/ui/img/$1");
        uriMappings.put(Pattern.compile("^/css/(.*)$"), "org/informantproject/local/ui/css/$1");
        uriMappings.put(Pattern.compile("^/js/(.*)$"), "org/informantproject/local/ui/js/$1");
        // 3rd party resources
        uriMappings.put(Pattern.compile("^/bootstrap/(.*)$"),
                "org/informantproject/webresources/bootstrap/$1");
        uriMappings.put(Pattern.compile("^/specialelite/(.*)$"),
                "org/informantproject/webresources/specialelite/$1");
        uriMappings.put(Pattern.compile("^/jquery/(.*)$"),
                "org/informantproject/webresources/jquery/$1");
        uriMappings.put(Pattern.compile("^/jqueryui/(.*)$"),
                "org/informantproject/webresources/jqueryui/$1");
        uriMappings.put(Pattern.compile("^/flot/(.*)$"),
                "org/informantproject/webresources/flot/$1");
        uriMappings.put(Pattern.compile("^/dynatree/(.*)$"),
                "org/informantproject/webresources/dynatree/$1");
        uriMappings.put(Pattern.compile("^/dateformat/(.*)$"),
                "org/informantproject/webresources/dateformat/$1");
        uriMappings.put(Pattern.compile("^/handlebars/(.*)$"),
                "org/informantproject/webresources/handlebars/$1");
        uriMappings.put(Pattern.compile("^/spin/(.*)$"),
                "org/informantproject/webresources/spin/$1");
    }

    @Inject
    public HttpServer(@LocalHttpServerPort int port,
            ConfigurationJsonService configurationJsonService,
            TraceDetailJsonService traceDetailJsonService,
            TraceSummaryJsonService traceSummaryJsonService,
            StackTraceJsonService stackTraceJsonService, MetricJsonService metricJsonService,
            AdminJsonService adminJsonService, StatJsonService statJsonService,
            PluginJsonService pluginJsonService) {

        super(port);
        // the parentheses define the part of the match that is used to dynamically construct the
        // "handleX" method to call in the json service, e.g. /trace/details calls the method
        // handleDetails in TraceDetailJsonService
        uriMappings.put(Pattern.compile("^/configuration/(.*)$"), configurationJsonService);
        uriMappings.put(Pattern.compile("^/trace/(details)$"), traceDetailJsonService);
        uriMappings.put(Pattern.compile("^/trace/(summaries)$"), traceSummaryJsonService);
        uriMappings.put(Pattern.compile("^/stacktrace/(read)$"), stackTraceJsonService);
        uriMappings.put(Pattern.compile("^/metrics/(.*)$"), metricJsonService);
        uriMappings.put(Pattern.compile("^/admin/(.*)"), adminJsonService);
        uriMappings.put(Pattern.compile("^/stat/(.*)"), statJsonService);
        uriMappings.put(Pattern.compile("^/plugin/(.*)"), pluginJsonService);
    }

    @Override
    protected HttpResponse handleRequest(HttpRequest request) throws IOException {
        logger.debug("handleRequest(): request.uri={}", request.getUri());
        QueryStringDecoder decoder = new QueryStringDecoder(request.getUri());
        String path = decoder.getPath();
        logger.debug("handleRequest(): path={}", path);
        for (Entry<Pattern, Object> uriMappingEntry : uriMappings.entrySet()) {
            Matcher matcher = uriMappingEntry.getKey().matcher(path);
            if (matcher.matches()) {
                if (uriMappingEntry.getValue() instanceof JsonService) {
                    String serviceMethodName = "handle"
                            + matcher.group(1).substring(0, 1).toUpperCase()
                            + matcher.group(1).substring(1);
                    String requestText = getRequestText(request, decoder);
                    return handleJsonRequest((JsonService) uriMappingEntry.getValue(),
                            serviceMethodName, requestText);
                } else {
                    // only other value type is String
                    String resourcePath = matcher.replaceFirst((String) uriMappingEntry.getValue());
                    return handleStaticRequest(resourcePath);
                }
            }
        }
        logger.warn("Unexpected uri '{}'", request.getUri());
        return new DefaultHttpResponse(HTTP_1_1, NOT_FOUND);
    }

    private static HttpResponse handleStaticRequest(String path) throws IOException {
        int extensionStartIndex = path.lastIndexOf(".");
        if (extensionStartIndex == -1) {
            logger.warn("Missing extension '{}'", path);
            return new DefaultHttpResponse(HTTP_1_1, NOT_FOUND);
        }
        String extension = path.substring(extensionStartIndex + 1);
        String mimeType = getMimeType(extension);
        if (mimeType == null) {
            logger.warn("Unexpected extension '{}' for path '{}'", extension, path);
            return new DefaultHttpResponse(HTTP_1_1, NOT_FOUND);
        }
        InputStream staticContentStream = HttpServer.class.getClassLoader().getResourceAsStream(
                path);
        if (staticContentStream == null) {
            logger.warn("Unexpected path '{}'", path);
            return new DefaultHttpResponse(HTTP_1_1, NOT_FOUND);
        }
        byte[] staticContent;
        try {
            staticContent = ByteStreams.toByteArray(staticContentStream);
        } finally {
            Closeables.closeQuietly(staticContentStream);
        }
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        response.setContent(ChannelBuffers.copiedBuffer(staticContent));
        response.setHeader(Names.CONTENT_TYPE, mimeType + "; charset=UTF-8");
        response.setHeader(Names.CONTENT_LENGTH, staticContent.length);
        if (path.startsWith("org/informantproject/webresources/")) {
            // these are all third-party versioned resources and can be safely cached forever
            response.setHeader(Names.EXPIRES, new Date(System.currentTimeMillis() + TEN_YEARS));
        }
        return response;
    }

    private static String getMimeType(String extension) {
        if (extension.equals("html")) {
            return "text/html";
        } else if (extension.equals("js")) {
            return "application/javascript";
        } else if (extension.equals("css")) {
            return "text/css";
        } else if (extension.equals("png")) {
            return "image/png";
        } else if (extension.equals("ico")) {
            return "image/x-icon";
        } else if (extension.equals("woff")) {
            return "application/x-font-woff";
        } else if (extension.equals("eot")) {
            return "application/vnd.ms-fontobject";
        } else if (extension.equals("ttf")) {
            return "application/x-font-ttf";
        } else {
            return null;
        }
    }

    private static HttpResponse handleJsonRequest(JsonService jsonService,
            String serviceMethodName, String requestText) {

        logger.debug("handleJsonRequest(): serviceMethodName={}, requestText={}",
                serviceMethodName, requestText);
        Object responseText;
        try {
            responseText = callMethod(jsonService, serviceMethodName, requestText);
        } catch (SecurityException e) {
            logger.error(e.getMessage(), e);
            return new DefaultHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        } catch (IllegalArgumentException e) {
            logger.error(e.getMessage(), e);
            return new DefaultHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        } catch (NoSuchMethodException e) {
            logger.error(e.getMessage(), e);
            return new DefaultHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        } catch (IllegalAccessException e) {
            logger.error(e.getMessage(), e);
            return new DefaultHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        } catch (InvocationTargetException e) {
            logger.error(e.getMessage(), e);
            return new DefaultHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        }
        HttpResponse response;
        if (responseText == null) {
            response = new DefaultHttpResponse(HTTP_1_1, OK);
            response.setContent(ChannelBuffers.EMPTY_BUFFER);
        } else if (responseText instanceof String) {
            response = new DefaultHttpResponse(HTTP_1_1, OK);
            response.setContent(ChannelBuffers.copiedBuffer(responseText.toString(),
                    Charsets.ISO_8859_1));
            response.setHeader(Names.CONTENT_TYPE, "application/json; charset=UTF-8");
        } else {
            logger.error("Unexpected type of json service response '{}'",
                    responseText.getClass().getName());
            return new DefaultHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR);
        }
        // prevent caching of dynamic json data, using 'definitive' minimum set of headers from
        // http://stackoverflow.com/questions/49547/
        // making-sure-a-web-page-is-not-cached-across-all-browsers/2068407#2068407
        response.setHeader(Names.CACHE_CONTROL, "no-cache, no-store, must-revalidate");
        response.setHeader(Names.PRAGMA, "no-cache");
        response.setHeader(Names.EXPIRES, new Date(0));
        return response;
    }

    private static Object callMethod(Object object, String methodName, String optionalArg)
            throws SecurityException, NoSuchMethodException, IllegalArgumentException,
            IllegalAccessException, InvocationTargetException {

        boolean withArg = true;
        Method method;
        try {
            method = object.getClass().getMethod(methodName, String.class);
        } catch (NoSuchMethodException e) {
            method = object.getClass().getMethod(methodName);
            withArg = false;
        }
        if (withArg) {
            return method.invoke(object, optionalArg);
        } else {
            return method.invoke(object);
        }
    }

    private static String getRequestText(HttpRequest request, QueryStringDecoder decoder) {
        if (decoder.getParameters().isEmpty()) {
            return request.getContent().toString(Charsets.ISO_8859_1);
        } else {
            // create json message out of the query string
            // flatten map values from list to single element where possible
            Map<String, Object> parameters = new HashMap<String, Object>();
            for (Entry<String, List<String>> entry : decoder.getParameters().entrySet()) {
                String key = entry.getKey();
                key = convertUnderscoreToCamel(key);
                if (entry.getValue().size() == 1) {
                    parameters.put(key, entry.getValue().get(0));
                } else {
                    parameters.put(key, entry.getValue());
                }
            }
            return new Gson().toJson(parameters);
        }
    }

    private static String convertUnderscoreToCamel(String s) {
        int underscoreIndex;
        while ((underscoreIndex = s.indexOf('_')) != -1) {
            s = s.substring(0, underscoreIndex) + s.substring(underscoreIndex + 1,
                    underscoreIndex + 2).toUpperCase() + s.substring(underscoreIndex + 2);
        }
        return s;
    }

    // marker interface
    public interface JsonService {}

    @SuppressWarnings("serial")
    public static class PathNotFoundException extends Exception {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @BindingAnnotation
    public @interface LocalHttpServerPort {}
}