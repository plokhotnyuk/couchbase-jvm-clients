/*
 * Copyright (c) 2019 Couchbase, Inc.
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
package com.couchbase.client.scala


import com.couchbase.client.core.Core
import com.couchbase.client.core.env.{Credentials, OwnedSupplier}
import com.couchbase.client.core.error.{AnalyticsServiceException, QueryServiceException}
import com.couchbase.client.core.msg.query.QueryChunkRow
import com.couchbase.client.core.retry.RetryStrategy
import com.couchbase.client.scala.analytics._
import com.couchbase.client.scala.env.ClusterEnvironment
import com.couchbase.client.scala.query._
import com.couchbase.client.scala.query.handlers.{AnalyticsHandler, QueryHandler, SearchHandler}
import com.couchbase.client.scala.search.SearchQuery
import com.couchbase.client.scala.search.result.{SearchQueryRow, SearchResult}
import com.couchbase.client.scala.util.DurationConversions.javaDurationToScala
import com.couchbase.client.scala.util.{FunctionalUtil, FutureConversions, RowTraversalUtil}
import com.couchbase.client.scala.query.handlers.{AnalyticsHandler, QueryHandler, SearchHandler}
import com.couchbase.client.scala.search.SearchQuery
import com.couchbase.client.scala.search.result.{SearchQueryRow, SearchResult}
import com.couchbase.client.scala.util.DurationConversions.javaDurationToScala
import com.couchbase.client.scala.util.{FunctionalUtil, FutureConversions}
import io.opentracing.Span
import com.couchbase.client.scala.query.handlers.{AnalyticsHandler, QueryHandler}
import reactor.core.scala.publisher.{Flux, Mono}

import scala.compat.java8.OptionConverters._
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/** Represents a connection to a Couchbase cluster.
  *
  * This is the asynchronous version of the [[Cluster]] API.
  *
  * These can be created through the functions in the companion object, or through [[Cluster.async]].
  *
  * @param environment the environment used to create this
  * @param ec          an ExecutionContext to use for any Future.  Will be supplied automatically as long as
  *                    resources are
  *                    opened in the normal way, starting from functions in [[Cluster]]
  *
  * @author Graham Pople
  * @since 1.0.0
  */
class AsyncCluster(environment: => ClusterEnvironment) {
  private[scala] implicit val ec: ExecutionContext = environment.ec
  private[scala] val core = Core.create(environment.coreEnv)
  private[scala] val env = environment
  private[scala] val kvTimeout = javaDurationToScala(env.timeoutConfig.kvTimeout())
  private[scala] val searchTimeout = javaDurationToScala(env.timeoutConfig.searchTimeout())
  private[scala] val analyticsTimeout = javaDurationToScala(env.timeoutConfig.analyticsTimeout())
  private[scala] val retryStrategy = env.retryStrategy
  private[scala] val queryHandler = new QueryHandler()
  private[scala] val analyticsHandler = new AnalyticsHandler()
  private[scala] val searchHandler = new SearchHandler()

  /** Opens and returns a Couchbase bucket resource that exists on this cluster.
    *
    * @param name the name of the bucket to open
    */
  def bucket(name: String): Future[AsyncBucket] = {
    FutureConversions.javaMonoToScalaFuture(core.openBucket(name))
      .map(v => new AsyncBucket(name, core, environment))
  }

  /** Performs a N1QL query against the cluster.
    *
    * This is asynchronous.  See [[Cluster.reactive]] for a reactive streaming version of this API, and
    * [[Cluster]] for a blocking version.
    *
    * @param statement the N1QL statement to execute
    * @param options   any query options - see [[QueryOptions]] for documentation
    *
    * @return a `Future` containing a `Success(QueryResult)` (which includes any returned rows) if successful, else a
    *         `Failure`
    */
  def query(statement: String, options: QueryOptions = QueryOptions()): Future[QueryResult] = {

    queryHandler.request(statement, options, core, environment) match {
      case Success(request) =>
        core.send(request)

        import reactor.core.scala.publisher.{Mono => ScalaMono}

        val ret: Future[QueryResult] = FutureConversions.javaCFToScalaMono(request, request.response(),
          propagateCancellation = true)
          .flatMap(response => {
            FutureConversions.javaFluxToScalaFlux(response.rows())
              .collectSeq()
              .flatMap(rows => FutureConversions.javaMonoToScalaMono(response.trailer())
                .map(trailer => QueryResult(
                  rows,
                  QueryMeta(
                    response.header().requestId(),
                    response.header().clientContextId().asScala,
                    response.header().signature.asScala.map(bytes => QuerySignature(bytes)),
                    trailer.metrics.asScala.map(bytes => QueryMetrics.fromBytes(bytes)),
                    trailer.warnings.asScala.map(bytes => QueryWarnings(bytes)),
                    trailer.status,
                    trailer.profile.asScala.map(QueryProfile)))
                )
              )
          }
          )

          .onErrorResume(err => {
            err match {
              case e: QueryServiceException =>
                val x = QueryError(e.content)
                ScalaMono.error(x)
              case _ => ScalaMono.error(err)
            }
          })

          .toFuture

        ret


      case Failure(err) => Future.failed(err)
    }
  }

  /** Performs an Analytics query against the cluster.
    *
    * This is asynchronous.  See [[Cluster.reactive]] for a reactive streaming version of this API, and
    * [[Cluster]] for a blocking version.
    *
    * @param statement the Analytics query to execute
    * @param options   any query options - see [[AnalyticsOptions]] for documentation
    *
    * @return a `Future` containing a `Success(AnalyticsResult)` (which includes any returned rows) if successful,
    *         else a `Failure`
    */
  def analyticsQuery(statement: String, options: AnalyticsOptions = AnalyticsOptions()): Future[AnalyticsResult] = {

    analyticsHandler.request(statement, options, core, environment) match {
      case Success(request) =>
        core.send(request)

        import reactor.core.scala.publisher.{Mono => ScalaMono}

        val ret: Future[AnalyticsResult] = FutureConversions.javaCFToScalaMono(request, request.response(),
          propagateCancellation = true)
          .flatMap(response => FutureConversions.javaFluxToScalaFlux(response.rows())
            .collectSeq()
            .flatMap(rows =>

              FutureConversions.javaMonoToScalaMono(response.trailer())
                .map(trailer => AnalyticsResult(
                  rows,
                  AnalyticsMeta(
                    response.header().requestId(),
                    response.header().clientContextId().asScala,
                    response.header().signature.asScala.map(bytes => AnalyticsSignature(bytes)),
                    Some(AnalyticsMetrics.fromBytes(trailer.metrics)),
                    trailer.warnings.asScala.map(bytes => AnalyticsWarnings(bytes)),
                    trailer.status))
                )
            )
          )
          .onErrorResume(err => {
            err match {
              case e: AnalyticsServiceException => ScalaMono.error(AnalyticsError(e.content))
              case _ => ScalaMono.error(err)
            }
          }).toFuture

        ret


      case Failure(err)
      => Future.failed(err)
    }
  }

  /** Performs a Full Text Search (FTS) query against the cluster.
    *
    * This is asynchronous.  See [[Cluster.reactive]] for a reactive streaming version of this API, and
    * [[Cluster]] for a blocking version.
    *
    * @param query           the FTS query to execute.  See [[SearchQuery]] for how to construct
    * @param parentSpan      this SDK supports the [[https://opentracing.io/ Open Tracing]] initiative, which is a
    *                        way of
    *                        tracing complex distributed systems.  This field allows an OpenTracing parent span to be
    *                        provided, which will become the parent of any spans created by the SDK as a result of this
    *                        operation.  Note that if a span is not provided then the SDK will try to access any
    *                        thread-local parent span setup by a Scope.  Much of time this will `just work`, but it's
    *                        recommended to provide the parentSpan explicitly if possible, as thread-local is not a
    *                        100% reliable way of passing parameters.
    * @param timeout         when the operation will timeout.  This will default to `timeoutConfig().searchTimeout()` in the
    *                        provided [[com.couchbase.client.scala.env.ClusterEnvironment]].
    * @param retryStrategy   provides some control over how the SDK handles failures.  Will default to `retryStrategy()`
    *                        in the provided [[com.couchbase.client.scala.env.ClusterEnvironment]].
    *
    * @return a `Future` containing a `Success(SearchResult)` (which includes any returned rows) if successful,
    *         else a `Failure`
    */
  def searchQuery(query: SearchQuery,
                  parentSpan: Option[Span] = None,
                  timeout: Duration = searchTimeout,
                  retryStrategy: RetryStrategy = retryStrategy): Future[SearchResult] = {

    searchHandler.request(query, parentSpan, timeout, retryStrategy, core, environment) match {
      case Success(request) =>
        core.send(request)

        val ret: Future[SearchResult] =
          FutureConversions.javaCFToScalaMono(request, request.response(), propagateCancellation = true)
            .flatMap(response => FutureConversions.javaFluxToScalaFlux(response.rows())
              .map(row => SearchQueryRow.fromResponse(row))
              .collectSeq()
              .flatMap(rows =>

                FutureConversions.javaMonoToScalaMono(response.trailer())
                  .map(trailer => {

                    val rowsConverted = RowTraversalUtil.traverse(rows.iterator)
                    val rawStatus = response.header.getStatus
                    val errors = SearchHandler.parseSearchErrors(rawStatus)
                    val meta = SearchHandler.parseSearchMeta(response, trailer)

                    SearchResult(
                      rowsConverted,
                      errors,
                      meta
                    )
                  })
              )
            )
            .toFuture

        ret

      case Failure(err) => Future.failed(err)
    }

  }

  /** Shutdown all cluster resources.
    *
    * This should be called before application exit.
    */
  def shutdown(): Future[Unit] = {
    FutureConversions.javaMonoToScalaMono(core.shutdown())
      .flatMap(_ => {
        if (env.owned) {
          Mono.fromRunnable(new Runnable {
            override def run(): Unit = env.shutdown()
          })
        }
        else {
          Mono.empty[Unit]
        }
      })
      .toFuture
  }
}

/** Functions to allow creating an `AsyncCluster`, which represents a connection to a Couchbase cluster.
  *
  * @define DeferredErrors Note that during opening of resources, all errors will be deferred until the first
  *                        attempted operation.
  */
object AsyncCluster {

  /** Connect to a Couchbase cluster with a username and a password as credentials.
    *
    * $DeferredErrors
    *
    * @param connectionString connection string used to locate the Couchbase cluster.
    * @param username         the name of a user with appropriate permissions on the cluster.
    * @param password         the password of a user with appropriate permissions on the cluster.
    *
    * @return a [[AsyncCluster]] representing a connection to the cluster
    */
  def connect(connectionString: String, username: String, password: String): Future[AsyncCluster] = {
    val cluster = Cluster.connect(connectionString, username, password)
    implicit val ec = cluster.ec
    Future {
      cluster.async
    }
  }

  /** Connect to a Couchbase cluster with custom [[Credentials]].
    *
    * $DeferredErrors
    *
    * @param connectionString connection string used to locate the Couchbase cluster.
    * @param credentials      custom credentials used when connecting to the cluster.
    *
    * @return a [[AsyncCluster]] representing a connection to the cluster
    */
  def connect(connectionString: String, credentials: Credentials): Future[AsyncCluster] = {
    val env = ClusterEnvironment.create(connectionString, credentials, true)
    implicit val ec = env.ec
    Future {
      Cluster.connect(env).async
    }
  }

  /** Connect to a Couchbase cluster with a custom [[ClusterEnvironment]].
    *
    * $DeferredErrors
    *
    * @param environment the custom environment with its properties used to connect to the cluster.
    *
    * @return a [[AsyncCluster]] representing a connection to the cluster
    */
  def connect(environment: ClusterEnvironment): Future[AsyncCluster] = {
    val cluster = Cluster.connect(environment)
    implicit val ec = cluster.ec
    Future {
      cluster.async
    }
  }
}