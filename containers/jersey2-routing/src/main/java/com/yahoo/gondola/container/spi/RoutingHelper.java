/*
 * Copyright 2015, Yahoo Inc.
 * Copyrights licensed under the New BSD License.
 * See the accompanying LICENSE file for terms.
 */

package com.yahoo.gondola.container.spi;

import javax.ws.rs.container.ContainerRequestContext;

/**
 * RoutingHelper is a helper interface that implemented in app that helps to gather some application specific
 * information, or control some routing specific functions.
 *
 * <p> When initialization <ul> <li>RoutingFilter initialize the callback.</li> <li>Application initialize the Gondola
 * and set to RoutingFilter's static variable.</li> </ul> </p>
 *
 * <p> When processing the request <ul> <li>RoutingFilter pass gondola instance and servlet request to the
 * callback.</li> </ul> </p>
 */

public interface RoutingHelper {

    /**
     * The callback method to get gondola shard ID based on request.
     *
     * @param request the request
     * @return Gondola bucket Id, -1 means try to find colo affinity in routing layer
     */
    int getBucketHash(ContainerRequestContext request);
}
