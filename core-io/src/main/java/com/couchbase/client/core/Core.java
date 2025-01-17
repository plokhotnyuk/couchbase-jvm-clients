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

package com.couchbase.client.core;

import com.couchbase.client.core.annotation.Stability;
import com.couchbase.client.core.cnc.events.core.BucketClosedEvent;
import com.couchbase.client.core.cnc.events.core.BucketOpenedEvent;
import com.couchbase.client.core.cnc.events.core.ReconfigurationCompletedEvent;
import com.couchbase.client.core.cnc.events.core.ReconfigurationErrorDetectedEvent;
import com.couchbase.client.core.cnc.events.core.ReconfigurationIgnoredEvent;
import com.couchbase.client.core.cnc.events.core.ServiceReconfigurationFailedEvent;
import com.couchbase.client.core.cnc.events.core.ShutdownCompletedEvent;
import com.couchbase.client.core.config.BucketConfig;
import com.couchbase.client.core.config.ClusterConfig;
import com.couchbase.client.core.config.ConfigurationProvider;
import com.couchbase.client.core.config.DefaultConfigurationProvider;
import com.couchbase.client.core.env.CoreEnvironment;
import com.couchbase.client.core.msg.CancellationReason;
import com.couchbase.client.core.msg.Request;
import com.couchbase.client.core.msg.Response;
import com.couchbase.client.core.node.KeyValueLocator;
import com.couchbase.client.core.node.Locator;
import com.couchbase.client.core.node.ManagerLocator;
import com.couchbase.client.core.node.Node;
import com.couchbase.client.core.node.NodeIdentifier;
import com.couchbase.client.core.node.RoundRobinLocator;
import com.couchbase.client.core.service.ServiceScope;
import com.couchbase.client.core.service.ServiceType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The main entry point into the core layer.
 *
 * <p>This class has been around behind a facade in the 1.x days, but here it is just a plain
 * simple class that can be instantiated and is used across the upper language bindings.</p>
 *
 * @since 2.0.0
 */
public class Core {

  /**
   * Counts up core ids for each new instance.
   */
  private static final AtomicLong CORE_IDS = new AtomicLong();

  private static final KeyValueLocator KEY_VALUE_LOCATOR = new KeyValueLocator();

  private static final ManagerLocator MANAGER_LOCATOR = new ManagerLocator();

  private static final RoundRobinLocator QUERY_LOCATOR =
    new RoundRobinLocator(ServiceType.QUERY);

  private static final RoundRobinLocator ANALYTICS_LOCATOR =
    new RoundRobinLocator(ServiceType.ANALYTICS);

  private static final RoundRobinLocator SEARCH_LOCATOR =
    new RoundRobinLocator(ServiceType.SEARCH);

  private static final RoundRobinLocator VIEWS_LOCATOR =
    new RoundRobinLocator(ServiceType.VIEWS);

  /**
   * Holds the current core context.
   */
  private final CoreContext coreContext;

  /**
   * Holds the current configuration provider.
   */
  private final ConfigurationProvider configurationProvider;

  /**
   * Holds the current configuration for all buckets.
   */
  private volatile ClusterConfig currentConfig;

  /**
   * The list of currently managed nodes against the cluster.
   */
  private final CopyOnWriteArrayList<Node> nodes;

  private final AtomicBoolean reconfigureInProgress = new AtomicBoolean(false);

  /**
   * We use a barrier to only run one reconfiguration at the same time, so when a new one comes in
   * and is ignored we need to set a flag to come back to it once the others finished.
   */
  private final AtomicBoolean moreConfigsPending = new AtomicBoolean(false);

  private final AtomicBoolean shutdown = new AtomicBoolean(false);

  public static Core create(final CoreEnvironment environment) {
    return new Core(environment);
  }

  protected Core(final CoreEnvironment environment) {
    this.coreContext = new CoreContext(this, CORE_IDS.incrementAndGet(), environment);
    this.configurationProvider = createConfigurationProvider();
    this.nodes = new CopyOnWriteArrayList<>();
    currentConfig = configurationProvider.config();
    configurationProvider
      .configs()
      .subscribe(c -> {
        currentConfig = c;
        reconfigure();
      });
  }

  /**
   * During testing this can be overridden so that a custom configuration provider is used
   * in the system.
   *
   * @return by default returns the default config provider.
   */
  ConfigurationProvider createConfigurationProvider() {
    return new DefaultConfigurationProvider(this);
  }

  @Stability.Internal
  public ConfigurationProvider configurationProvider() {
    return configurationProvider;
  }

  public <R extends Response> void send(final Request<R> request) {
    send(request, true);
  }

  @Stability.Internal
  @SuppressWarnings({"unchecked"})
  public <R extends Response> void send(final Request<R> request, final boolean registerForTimeout) {
    if (shutdown.get()) {
      request.cancel(CancellationReason.SHUTDOWN);
      return;
    }

    if (registerForTimeout) {
      context().environment().timer().register((Request<Response>) request);
    }

    locator(request.serviceType()).dispatch(request, nodes, currentConfig, context());
  }

  /**
   * Returns the {@link CoreContext} of this core instance.
   *
   * @return the core context.
   */
  public CoreContext context() {
    return coreContext;
  }

  /**
   * Attempts to open a bucket and fails the {@link Mono} if there is a persistent error
   * as the reason.
   */
  @Stability.Internal
  public Mono<Void> openBucket(final String name) {
    return Mono.defer(() -> {
      long start = System.nanoTime();
      return configurationProvider
        .openBucket(name)
        .doOnSuccess(ignored -> {
          BucketOpenedEvent event = new BucketOpenedEvent(
            Duration.ofNanos(System.nanoTime() - start),
            coreContext,
            name
          );
          coreContext.environment().eventBus().publish(event);
        });
    });
  }

  /**
   * This API provides access to the current config that is published throughout the core.
   *
   * <p>Note that this is internal API and might change at any time.</p>
   */
  @Stability.Internal
  public ClusterConfig clusterConfig() {
    return configurationProvider.config();
  }

  /**
   * Attempts to close a bucket and fails the {@link Mono} if there is a persistent error
   * as the reason.
   */
  @Stability.Internal
  public Mono<Void> closeBucket(final String name) {
    return Mono.defer(() -> {
      long start = System.nanoTime();
      return configurationProvider
        .closeBucket(name)
        .doOnSuccess(ignored -> {
          BucketClosedEvent event = new BucketClosedEvent(
            Duration.ofNanos(System.nanoTime() - start),
            coreContext,
            name
          );
          coreContext.environment().eventBus().publish(event);
        });
    });
  }

  /**
   * This method can be used by a caller to make sure a certain service is enabled at the given
   * target node.
   *
   * <p>This is advanced, internal functionality and should only be used if the caller knows
   * what they are doing.</p>
   *
   * @param identifier the node to check.
   * @param serviceType the service type to enable if not enabled already.
   * @return a {@link Mono} which completes once initiated.
   */
  @Stability.Internal
  public Mono<Void> ensureServiceAt(final NodeIdentifier identifier, final ServiceType serviceType,
                                    final int port, final Optional<String> bucket) {
    return Flux
      .fromIterable(nodes)
      .filter(n -> n.identifier().equals(identifier))
      .switchIfEmpty(Mono.defer(() -> {
        Node node = createNode(identifier);
        nodes.add(node);
        return Mono.just(node);
      }))
      .flatMap(node -> node.addService(serviceType, port, bucket))
      .then();
  }

  protected Node createNode(final NodeIdentifier identifier) {
    return Node.create(coreContext, identifier);
  }

  /**
   * Check if the given {@link Node} needs to be removed from the cluster topology.
   *
   * @param node the node in question
   * @param config the current config.
   * @return a mono once disconnected (or completes immediately if there is no need to do so).
   */
  private Mono<Void> maybeRemoveNode(final Node node, final ClusterConfig config) {
    boolean stillPresent = config
      .bucketConfigs()
      .values()
      .stream()
      .flatMap(bc -> bc.nodes().stream())
      .anyMatch(ni -> ni.identifier().equals(node.identifier()));

    if (!stillPresent || !node.hasServicesEnabled()) {
      nodes.remove(node);
      return node.disconnect();
    }

    return Mono.empty();
  }

  /**
   * This method is used to remove a service from a node.
   *
   * @param identifier the node to check.
   * @param serviceType the service type to remove if present.
   * @return a {@link Mono} which completes once initiated.
   */
  private Mono<Void> removeServiceFrom(final NodeIdentifier identifier, final ServiceType serviceType,
                                       final Optional<String> bucket) {
    return Flux
      .fromIterable(new ArrayList<>(nodes))
      .filter(n -> n.identifier().equals(identifier))
      .filter(node -> node.serviceEnabled(serviceType))
      .flatMap(node -> node.removeService(serviceType, bucket))
      .then();
  }

  /**
   * Shuts down this core and all associated, owned resources.
   */
  @Stability.Internal
  public Mono<Void> shutdown() {
    return Mono.defer(() -> {
      long start = System.nanoTime();
      if (shutdown.compareAndSet(false, true)) {
        return Flux
          .fromIterable(currentConfig.bucketConfigs().keySet())
          .flatMap(this::closeBucket)
          .then(configurationProvider.shutdown())
          .doOnTerminate(() -> coreContext.environment().eventBus().publish(
            new ShutdownCompletedEvent(Duration.ofNanos(System.nanoTime() - start), coreContext)
          ))
          .then();
      }
      return Mono.empty();
    });
  }

  /**
   * Reconfigures the SDK topology to align with the current server configuration.
   *
   * <p>When reconfigure is called, it will grab a current configuration and then add/remove
   * nodes/services to mirror the current topology and configuration settings.</p>
   *
   * <p>This is a eventually consistent process, so in-flight operations might still be rescheduled
   * and then picked up later (or cancelled, depending on the strategy). For those coming from 1.x,
   * it works very similar.</p>
   */
  private void reconfigure() {
    if (reconfigureInProgress.compareAndSet(false, true)) {
      final ClusterConfig configForThisAttempt = currentConfig;

      long start = System.nanoTime();

      if (configForThisAttempt.bucketConfigs().isEmpty()) {
        Flux
          .fromIterable(new ArrayList<>(nodes))
          .flatMap(Node::disconnect)
          .doOnComplete(nodes::clear)
          .subscribe(
            v -> {},
            e -> {
              clearReconfigureInProgress();
              coreContext
                .environment()
                .eventBus()
                .publish(new ReconfigurationErrorDetectedEvent(context(), e));
            },
            () -> {
              clearReconfigureInProgress();
              coreContext
                .environment()
                .eventBus()
                .publish(new ReconfigurationCompletedEvent(
                  Duration.ofNanos(System.nanoTime() - start),
                  coreContext
                ));
            }
          );

        return;
      }

      Flux<BucketConfig> bucketConfigFlux = Flux
        .just(configForThisAttempt)
        .flatMap(cc -> Flux.fromIterable(cc.bucketConfigs().values()));

      reconfigureBuckets(bucketConfigFlux)
        .then(Mono.defer(() ->
          Flux
            .fromIterable(new ArrayList<>(nodes))
            .flatMap(n -> maybeRemoveNode(n, configForThisAttempt))
            .then()
        ))
        .subscribe(
        v -> {},
        e -> {
          clearReconfigureInProgress();
          coreContext
            .environment()
            .eventBus()
            .publish(new ReconfigurationErrorDetectedEvent(context(), e));
        },
        () -> {
          clearReconfigureInProgress();
          coreContext
            .environment()
            .eventBus()
            .publish(new ReconfigurationCompletedEvent(
              Duration.ofNanos(System.nanoTime() - start),
              coreContext
            ));
        }
      );
    } else {
      moreConfigsPending.set(true);
      coreContext
        .environment()
        .eventBus()
        .publish(new ReconfigurationIgnoredEvent(coreContext));
    }
  }

  /**
   * Clean reconfiguration in progress and check if there is a new one we need to try.
   */
  private void clearReconfigureInProgress() {
    reconfigureInProgress.set(false);
    if (moreConfigsPending.compareAndSet(true, false)) {
      reconfigure();
    }
  }

  /**
   * Contains logic to perform reconfiguration for a bucket config.
   *
   * @param bucketConfigs the flux of bucket configs currently open.
   * @return a mono once reconfiguration for all buckets is complete
   */
  private Mono<Void> reconfigureBuckets(final Flux<BucketConfig> bucketConfigs) {
    return bucketConfigs.flatMap(bc ->
      Flux.fromIterable(bc.nodes())
        .flatMap(ni -> {
          boolean tls = coreContext.environment().securityConfig().tlsEnabled();
          Set<Map.Entry<ServiceType, Integer>> services = tls
            ? ni.sslServices().entrySet()
            : ni.services().entrySet();

          Flux<Void> serviceRemoveFlux = Flux
            .fromIterable(Arrays.asList(ServiceType.values()))
            .filter(s -> {
              for (Map.Entry<ServiceType, Integer> inConfig : services) {
                if (inConfig.getKey() == s) {
                  return false;
                }
              }
              return true;
            })
            .flatMap(s -> removeServiceFrom(
              ni.identifier(),
              s,
              s.scope() == ServiceScope.BUCKET ? Optional.of(bc.name()) : Optional.empty())
              .onErrorResume(throwable -> {
                throwable.printStackTrace();
                coreContext.environment().eventBus().publish(new ServiceReconfigurationFailedEvent(
                  coreContext,
                  ni.hostname(),
                  s,
                  throwable
                ));
                return Mono.empty();
              })
            );

          Flux<Void> serviceAddFlux = Flux
            .fromIterable(services)
            .flatMap(s -> ensureServiceAt(
              ni.identifier(),
              s.getKey(),
              s.getValue(),
              s.getKey().scope() == ServiceScope.BUCKET ? Optional.of(bc.name()) : Optional.empty())
              .onErrorResume(throwable -> {
                throwable.printStackTrace();
                coreContext.environment().eventBus().publish(new ServiceReconfigurationFailedEvent(
                  coreContext,
                  ni.hostname(),
                  s.getKey(),
                  throwable
                ));
                return Mono.empty();
              })
            );

          return Flux.merge(serviceAddFlux, serviceRemoveFlux);
        })
    ).then();
  }

  private static Locator locator(final ServiceType serviceType) {
    switch (serviceType) {
      case KV:
        return KEY_VALUE_LOCATOR;
      case MANAGER:
        return MANAGER_LOCATOR;
      case QUERY:
        return QUERY_LOCATOR;
      case ANALYTICS:
        return ANALYTICS_LOCATOR;
      case SEARCH:
        return SEARCH_LOCATOR;
      case VIEWS:
        return VIEWS_LOCATOR;
      default:
        throw new IllegalStateException("Unsupported ServiceType: " + serviceType);
    }
  }

}
