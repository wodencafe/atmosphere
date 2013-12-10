/*
 * Copyright 2013 Jeanfrancois Arcand
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.config.managed;

import org.atmosphere.config.service.MeteorService;
import org.atmosphere.config.service.Singleton;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework.AtmosphereHandlerWrapper;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.handler.ReflectorServletProcessor;
import org.atmosphere.interceptor.InvokationOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;

/**
 * Handle {@link org.atmosphere.config.service.Singleton},{@link org.atmosphere.config.service.MeteorService}
 * processing.
 *
 * @author Jeanfrancois Arcand
 */
public class MeteorServiceInterceptor extends AtmosphereInterceptorAdapter {

    private final static Logger logger = LoggerFactory.getLogger(MeteorServiceInterceptor.class);
    private AtmosphereConfig config;
    private boolean wildcardMapping = false;

    @Override
    public void configure(AtmosphereConfig config) {
        this.config = config;
        optimizeMapping();
    }

    @Override
    public Action inspect(AtmosphereResource r) {
        if (!wildcardMapping) return Action.CONTINUE;

        mapAnnotatedService(r.getRequest(), (AtmosphereHandlerWrapper)
                r.getRequest().getAttribute(FrameworkConfig.ATMOSPHERE_HANDLER_WRAPPER));

        return Action.CONTINUE;
    }

    protected void optimizeMapping() {
        for (String w : config.handlers().keySet()) {
            if (w.contains("{") && w.contains("}")) {
                wildcardMapping = true;
                break;
            }
        }
    }

    /**
     * Inspect the request and its mapped {@link org.atmosphere.cpr.AtmosphereHandler} to determine if the '{}' was used when defined the
     * annotation's path value. It will create a new {@link org.atmosphere.cpr.AtmosphereHandler} in case {} is detected .
     *
     * @param request
     * @param w
     * @return
     */
    protected void mapAnnotatedService(AtmosphereRequest request, AtmosphereHandlerWrapper w) {
        Broadcaster b = w.broadcaster;

        String path;
        String pathInfo = null;
        try {
            pathInfo = request.getPathInfo();
        } catch (IllegalStateException ex) {
            // http://java.net/jira/browse/GRIZZLY-1301
        }

        if (pathInfo != null) {
            path = request.getServletPath() + pathInfo;
        } else {
            path = request.getServletPath();
        }

        if (path == null || path.isEmpty()) {
            path = "/";
        }

        // Remove the Broadcaster with curly braces
        if (b.getID().contains("{")) {
            config.getBroadcasterFactory().remove(b.getID());
        }

        synchronized (config.handlers()) {
            if (config.handlers().get(path) == null) {
                // MeteorService
                if (ReflectorServletProcessor.class.isAssignableFrom(w.atmosphereHandler.getClass())) {
                    Servlet s = ReflectorServletProcessor.class.cast(w.atmosphereHandler).getServlet();
                    MeteorService m = s.getClass().getAnnotation(MeteorService.class);
                    if (m!= null) {
                        String targetPath = m.path();
                        if (targetPath.indexOf("{") != -1 && targetPath.indexOf("}") != -1) {
                            try {
                                boolean singleton = s.getClass().getAnnotation(Singleton.class) != null;
                                if (!singleton) {
                                    ReflectorServletProcessor r = config.framework().newClassInstance(ReflectorServletProcessor.class);
                                    r.setServlet(config.framework().newClassInstance(s.getClass()));
                                    r.init(config);
                                    config.framework().addAtmosphereHandler(path, r,
                                            config.getBroadcasterFactory().lookup(m.broadcaster(), path, true), w.interceptors);
                                } else {
                                    config.framework().addAtmosphereHandler(path, w.atmosphereHandler,
                                            config.getBroadcasterFactory().lookup(m.broadcaster(), path, true), w.interceptors);
                                }
                                request.setAttribute(FrameworkConfig.NEW_MAPPING, "true");
                            } catch (Throwable e) {
                                logger.warn("Unable to create AtmosphereHandler", e);
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public PRIORITY priority() {
        return InvokationOrder.BEFORE_DEFAULT;
    }
}
