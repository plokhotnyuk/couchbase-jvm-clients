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

package com.couchbase.client.scala.kv.handlers

import com.couchbase.client.core.config.CouchbaseBucketConfig
import com.couchbase.client.core.error.CommonExceptions
import com.couchbase.client.core.msg.kv.{GetRequest, ReplicaGetRequest}
import com.couchbase.client.core.retry.RetryStrategy
import com.couchbase.client.scala.HandlerParams
import com.couchbase.client.scala.util.Validate
import io.opentracing.Span

import scala.util.{Failure, Success, Try}


/**
  * Handles requests and responses for KV get-from-replica operations.
  *
  * @author Graham Pople
  * @since 1.0.0
  */
private[scala] class GetFromReplicaHandler(hp: HandlerParams) {

  def requestAll[T](id: String,
                    parentSpan: Option[Span] = None,
                    timeout: java.time.Duration,
                    retryStrategy: RetryStrategy)
  : Try[Seq[GetRequest]] = {
    val validations: Try[Seq[GetRequest]] = for {
      _ <- Validate.notNullOrEmpty(id, "id")
      _ <- Validate.notNull(parentSpan, "parentSpan")
      _ <- Validate.notNull(timeout, "timeout")
      _ <- Validate.notNull(retryStrategy, "retryStrategy")
    } yield null

    if (validations.isFailure) {
      validations
    }
    else {
      hp.core.clusterConfig().bucketConfig(hp.bucketName) match {
        case config: CouchbaseBucketConfig =>
          val numReplicas = config.numberOfReplicas()

          val replicaRequests: Seq[ReplicaGetRequest] = Range(0, numReplicas)
            .map(replicaIndex => new ReplicaGetRequest(id,
              hp.collectionIdEncoded,
              timeout,
              hp.core.context(),
              hp.bucketName,
              retryStrategy,
              (replicaIndex + 1).shortValue()))

          val activeRequest = new GetRequest(id,
            hp.collectionIdEncoded,
            timeout,
            hp.core.context(),
            hp.bucketName,
            retryStrategy)

          val requests: Seq[GetRequest] = activeRequest +: replicaRequests

          Success(requests)

        case _ =>
          Failure(CommonExceptions.getFromReplicaNotCouchbaseBucket)
      }
    }
  }
}