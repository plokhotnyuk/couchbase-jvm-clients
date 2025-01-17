/*
 * Copyright (c) 2018 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.client.core.endpoint;

import com.couchbase.client.core.CoreContext;
import com.couchbase.client.core.service.ServiceType;

import java.net.SocketAddress;
import java.util.Map;
import java.util.Optional;

import static com.couchbase.client.core.logging.RedactableArgument.redactMeta;
import static com.couchbase.client.core.logging.RedactableArgument.redactSystem;

public class EndpointContext extends CoreContext {

  /**
   * The hostname of this endpoint.
   */
  private final String remoteHostname;

  private final Optional<SocketAddress> localSocket;

  /**
   * The port of this endpoint.
   */
  private final int remotePort;

  /**
   * The circuit breaker used for this endpoint.
   */
  private final CircuitBreaker circuitBreaker;

  /**
   * The service type of this endpoint.
   */
  private final ServiceType serviceType;

  private final Optional<String> bucket;

  private final Optional<String> channelId;

  /**
   * Creates a new {@link EndpointContext}.
   *
   * @param ctx the parent context to use.
   * @param remoteHostname the remote hostname.
   * @param remotePort the remote port.
   */
  public EndpointContext(CoreContext ctx, String remoteHostname, int remotePort,
                         CircuitBreaker circuitBreaker, ServiceType serviceType,
                         Optional<SocketAddress> localSocket, Optional<String> bucket, Optional<String> channelId) {
    super(ctx.core(), ctx.id(), ctx.environment());
    this.remoteHostname = remoteHostname;
    this.remotePort = remotePort;
    this.circuitBreaker = circuitBreaker;
    this.serviceType = serviceType;
    this.bucket = bucket;
    this.localSocket = localSocket;
    this.channelId = channelId;
  }

  @Override
  protected void injectExportableParams(final Map<String, Object> input) {
    super.injectExportableParams(input);
    input.put("remote", redactSystem(remoteHostname() + ":" + remotePort()));
    localSocket.ifPresent(s -> input.put("local", redactSystem(s)));
    input.put("circuitBreaker", circuitBreaker.state().toString());
    input.put("type", serviceType);
    bucket.ifPresent(b -> input.put("bucket", redactMeta(b)));
    channelId.ifPresent(i -> input.put("channelId", i));
  }

  public String remoteHostname() {
    return remoteHostname;
  }

  public int remotePort() {
    return remotePort;
  }

  public CircuitBreaker circuitBreaker() {
    return circuitBreaker;
  }

  public Optional<SocketAddress> localSocket() {
    return localSocket;
  }

  public ServiceType serviceType() {
    return serviceType;
  }

  public Optional<String> bucket() {
    return bucket;
  }
}
