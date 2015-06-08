/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.http.impl;

import java.util.Iterator;

import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.Header;
import org.apache.http.HeaderElements;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.HttpVersion;
import org.apache.http.ProtocolVersion;
import org.apache.http.annotation.Immutable;
import org.apache.http.message.BasicHeaderIterator;
import org.apache.http.message.BasicTokenIterator;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.Args;

/**
 * Default implementation of a strategy deciding about connection re-use. The strategy
 * determines whether a connection is persistent or not based on the message’s protocol
 * version and {@code Connection} header field if present. Connections will not be
 * re-used and will close if any of these conditions is met
 * <ul>
 *     <li>the {@code close} connection option is present in the request message</li>
 *     <li>the response message content body is incorrectly or ambiguously delineated</li>
 *     <li>the {@code close} connection option is present in the response message</li>
 *     <li>If the received protocol is {@code HTTP/1.0} (or earlier) and {@code keep-alive}
 *     connection option is not present</li>
 * </ul>
 * In the absence of a {@code Connection} header field, the non-standard but commonly used
 * {@code Proxy-Connection} header field will be used instead. If no connection options are
 * explicitly given the default policy for the HTTP version is applied. {@code HTTP/1.1}
 * (or later) connections are re-used by default. {@code HTTP/1.0} (or earlier) connections
 * are not re-used by default.
 *
 * @since 4.0
 */
@Immutable
public class DefaultConnectionReuseStrategy implements ConnectionReuseStrategy {

    public static final DefaultConnectionReuseStrategy INSTANCE = new DefaultConnectionReuseStrategy();

    public DefaultConnectionReuseStrategy() {
        super();
    }

    // see interface ConnectionReuseStrategy
    @Override
    public boolean keepAlive(final HttpRequest request, final HttpResponse response,
                             final HttpContext context) {
        Args.notNull(response, "HTTP response");
        Args.notNull(context, "HTTP context");

        if (request != null) {
            final Header[] connHeaders = request.getHeaders(HttpHeaders.CONNECTION);
            if (connHeaders.length != 0) {
                final Iterator<String> ti = new BasicTokenIterator(new BasicHeaderIterator(connHeaders, null));
                while (ti.hasNext()) {
                    final String token = ti.next();
                    if (HeaderElements.CLOSE.equalsIgnoreCase(token)) {
                        return false;
                    }
                }
            }
        }

        // Check for a self-terminating entity. If the end of the entity will
        // be indicated by closing the connection, there is no keep-alive.
        final ProtocolVersion ver = response.getStatusLine().getProtocolVersion();
        final Header teh = response.getFirstHeader(HttpHeaders.TRANSFER_ENCODING);
        if (teh != null) {
            if (!HeaderElements.CHUNKED_ENCODING.equalsIgnoreCase(teh.getValue())) {
                return false;
            }
        } else {
            if (canResponseHaveBody(response)) {
                if (response.containsHeaders(HttpHeaders.CONTENT_LENGTH) != 1) {
                    return false;
                }
            }
        }

        // Check for the "Connection" header. If that is absent, check for
        // the "Proxy-Connection" header. The latter is an unspecified and
        // broken but unfortunately common extension of HTTP.
        Header[] connHeaders = response.getHeaders(HttpHeaders.CONNECTION);
        if (connHeaders.length == 0) {
            connHeaders = response.getHeaders("Proxy-Connection");
        }

        if (connHeaders.length != 0) {
            if (ver.greaterEquals(HttpVersion.HTTP_1_1)) {
                final Iterator<String> it = new BasicTokenIterator(new BasicHeaderIterator(connHeaders, null));
                while (it.hasNext()) {
                    final String token = it.next();
                    if (HeaderElements.CLOSE.equalsIgnoreCase(token)) {
                        return false;
                    }
                }
                return true;
            } else {
                final Iterator<String> it = new BasicTokenIterator(new BasicHeaderIterator(connHeaders, null));
                while (it.hasNext()) {
                    final String token = it.next();
                    if (HeaderElements.KEEP_ALIVE.equalsIgnoreCase(token)) {
                        return true;
                    }
                }
                return false;
            }
        }
        return ver.greaterEquals(HttpVersion.HTTP_1_1);
    }

    private boolean canResponseHaveBody(final HttpResponse response) {
        final int status = response.getStatusLine().getStatusCode();
        return status >= HttpStatus.SC_OK
            && status != HttpStatus.SC_NO_CONTENT
            && status != HttpStatus.SC_NOT_MODIFIED
            && status != HttpStatus.SC_RESET_CONTENT;
    }

}
