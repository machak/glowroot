/*
 * Copyright 2011-2016 the original author or authors.
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
package org.glowroot.agent.plugin.servlet;

import java.security.Principal;
import java.util.Enumeration;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.OptionalThreadContext;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.util.FastThreadLocal;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindReturn;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.api.weaving.Shim;

// only the calls to the top-most Filter and to the top-most Servlet are captured
//
// this plugin is careful not to rely on request or session objects being thread-safe
public class ServletAspect {

    private static final FastThreadLocal</*@Nullable*/ ServletMessageSupplier> currServletMessageSupplier =
            new FastThreadLocal</*@Nullable*/ ServletMessageSupplier>();

    // the life of this thread local is tied to the life of the topLevel thread local
    // it is only created if the topLevel thread local exists, and it is cleared when topLevel
    // thread local is cleared
    private static final FastThreadLocal</*@Nullable*/ String> sendError =
            new FastThreadLocal</*@Nullable*/ String>();

    @Shim("javax.servlet.http.HttpServletRequest")
    public interface HttpServletRequest {

        @Shim("javax.servlet.http.HttpSession getSession(boolean)")
        @Nullable
        HttpSession glowrootShimGetSession(boolean create);

        @Nullable
        String getRequestURI();

        @Nullable
        String getQueryString();

        @Nullable
        String getMethod();

        @Nullable
        Enumeration<String> getHeaderNames();

        @Nullable
        Enumeration<String> getHeaders(String name);

        @Nullable
        String getHeader(String name);

        @Nullable
        Map<String, String[]> getParameterMap();
    }

    @Shim("javax.servlet.http.HttpSession")
    public interface HttpSession {

        @Nullable
        Object getAttribute(String attributePath);

        @Nullable
        Enumeration<?> getAttributeNames();
    }

    @Pointcut(className = "javax.servlet.Servlet", methodName = "service",
            methodParameterTypes = {"javax.servlet.ServletRequest",
                    "javax.servlet.ServletResponse"},
            nestingGroup = "outer-servlet-or-filter", timerName = "http request")
    public static class ServiceAdvice {
        private static final TimerName timerName = Agent.getTimerName(ServiceAdvice.class);
        @OnBefore
        public static @Nullable TraceEntry onBefore(OptionalThreadContext context,
                @BindParameter @Nullable Object req) {
            if (req == null || !(req instanceof HttpServletRequest)) {
                // seems nothing sensible to do here other than ignore
                return null;
            }
            HttpServletRequest request = (HttpServletRequest) req;
            // request parameter map is collected in GetParameterAdvice
            // session info is collected here if the request already has a session
            ServletMessageSupplier messageSupplier;
            HttpSession session = request.glowrootShimGetSession(false);
            String requestUri = Strings.nullToEmpty(request.getRequestURI());
            // don't convert null to empty, since null means no query string, while empty means
            // url ended with ? but nothing after that
            String requestQueryString = request.getQueryString();
            String requestMethod = Strings.nullToEmpty(request.getMethod());
            ImmutableMap<String, Object> requestHeaders =
                    DetailCapture.captureRequestHeaders(request);
            if (session == null) {
                messageSupplier = new ServletMessageSupplier(requestMethod, requestUri,
                        requestQueryString, requestHeaders, ImmutableMap.<String, String>of());
            } else {
                ImmutableMap<String, String> sessionAttributes =
                        HttpSessions.getSessionAttributes(session);
                messageSupplier = new ServletMessageSupplier(requestMethod, requestUri,
                        requestQueryString, requestHeaders, sessionAttributes);
            }
            currServletMessageSupplier.set(messageSupplier);
            String user = null;
            if (session != null) {
                String sessionUserAttributePath =
                        ServletPluginProperties.sessionUserAttributePath();
                if (!sessionUserAttributePath.isEmpty()) {
                    // capture user now, don't use a lazy supplier
                    user = HttpSessions.getSessionAttributeTextValue(session,
                            sessionUserAttributePath);
                }
            }
            TraceEntry traceEntry =
                    context.startTransaction("Servlet", requestUri, messageSupplier, timerName);
            // Glowroot-Transaction-Name header is useful for automated tests which want to send a
            // more specific name for the transaction
            String transactionNameOverride = request.getHeader("Glowroot-Transaction-Name");
            if (transactionNameOverride != null) {
                // using setTransactionName() instead of passing this into startTransaction() so
                // that it will be the first override and other overrides won't replace it
                context.setTransactionName(transactionNameOverride);
            }
            if (user != null) {
                context.setTransactionUser(user);
            }
            return traceEntry;
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry == null) {
                return;
            }
            FastThreadLocal.Holder</*@Nullable*/ String> errorMessageHolder = sendError.getHolder();
            String errorMessage = errorMessageHolder.get();
            if (errorMessage != null) {
                traceEntry.endWithError(errorMessage);
                errorMessageHolder.set(null);
            } else {
                traceEntry.end();
            }
            currServletMessageSupplier.set(null);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry == null) {
                return;
            }
            // ignoring potential sendError since this seems worse
            sendError.set(null);
            traceEntry.endWithError(t);
            currServletMessageSupplier.set(null);
        }
    }

    @Pointcut(className = "javax.servlet.Filter",
            methodName = "doFilter", methodParameterTypes = {"javax.servlet.ServletRequest",
                    "javax.servlet.ServletResponse", "javax.servlet.FilterChain"},
            nestingGroup = "outer-servlet-or-filter", timerName = "http request")
    public static class DoFilterAdvice {
        @OnBefore
        public static @Nullable TraceEntry onBefore(OptionalThreadContext context,
                @BindParameter @Nullable Object request) {
            return ServiceAdvice.onBefore(context, request);
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable TraceEntry traceEntry) {
            ServiceAdvice.onReturn(traceEntry);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable TraceEntry traceEntry) {
            ServiceAdvice.onThrow(t, traceEntry);
        }
    }

    @Pointcut(className = "org.eclipse.jetty.server.Handler", methodName = "handle",
            methodParameterTypes = {"java.lang.String", "org.eclipse.jetty.server.Request",
                    "javax.servlet.http.HttpServletRequest",
                    "javax.servlet.http.HttpServletResponse"},
            nestingGroup = "outer-servlet-or-filter", timerName = "http request")
    public static class JettyHandlerAdvice {
        @OnBefore
        public static @Nullable TraceEntry onBefore(OptionalThreadContext context,
                @SuppressWarnings("unused") @BindParameter @Nullable String target,
                @SuppressWarnings("unused") @BindParameter @Nullable Object baseRequest,
                @BindParameter @Nullable Object request) {
            return ServiceAdvice.onBefore(context, request);
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable TraceEntry traceEntry) {
            ServiceAdvice.onReturn(traceEntry);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable TraceEntry traceEntry) {
            ServiceAdvice.onThrow(t, traceEntry);
        }
    }

    @Pointcut(className = "javax.servlet.http.HttpServletResponse", methodName = "sendError",
            methodParameterTypes = {"int", ".."}, nestingGroup = "servlet-inner-call")
    public static class SendErrorAdvice {
        @OnAfter
        public static void onAfter(ThreadContext context, @BindParameter Integer statusCode) {
            FastThreadLocal.Holder</*@Nullable*/ String> errorMessageHolder = sendError.getHolder();
            // only capture 5xx server errors
            if (statusCode >= 500 && errorMessageHolder.get() == null) {
                context.addErrorEntry("sendError, HTTP status code " + statusCode);
                errorMessageHolder.set("sendError, HTTP status code " + statusCode);
            }
        }
    }

    @Pointcut(className = "javax.servlet.http.HttpServletResponse", methodName = "setStatus",
            methodParameterTypes = {"int", ".."}, nestingGroup = "servlet-inner-call")
    public static class SetStatusAdvice {
        @OnAfter
        public static void onAfter(ThreadContext context, @BindParameter Integer statusCode) {
            FastThreadLocal.Holder</*@Nullable*/ String> errorMessageHolder = sendError.getHolder();
            // only capture 5xx server errors
            if (statusCode >= 500 && errorMessageHolder.get() == null) {
                context.addErrorEntry("setStatus, HTTP status code " + statusCode);
                errorMessageHolder.set("setStatus, HTTP status code " + statusCode);
            }
        }
    }

    @Pointcut(className = "javax.servlet.http.HttpServletRequest", methodName = "getUserPrincipal",
            methodParameterTypes = {}, methodReturnType = "java.security.Principal",
            nestingGroup = "servlet-inner-call")
    public static class GetUserPrincipalAdvice {
        @OnReturn
        public static void onReturn(@BindReturn @Nullable Principal principal,
                ThreadContext context) {
            if (principal != null) {
                context.setTransactionUser(principal.getName());
            }
        }
    }

    static @Nullable ServletMessageSupplier getServletMessageSupplier() {
        return currServletMessageSupplier.get();
    }
}
