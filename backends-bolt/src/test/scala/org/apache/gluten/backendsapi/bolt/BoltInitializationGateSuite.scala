/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.gluten.backendsapi.bolt

import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

class BoltInitializationGateSuite extends AnyFunSuite {
  test("initialization gate runs the initializer only once") {
    val gate = new BoltListenerApi.InitializationGate
    var runs = 0

    assert(
      gate.initialize {
        runs += 1
      })
    val initializedAgain = gate.initialize {
      runs += 1
    }

    assert(!initializedAgain)
    assert(runs == 1)
    assert(gate.currentState == BoltListenerApi.READY)
  }

  test("initialization gate preserves the first failure and does not retry") {
    val gate = new BoltListenerApi.InitializationGate
    val firstFailure = new RuntimeException("native initialization failed")
    var runs = 0

    val thrownByInitializer = intercept[RuntimeException] {
      gate.initialize {
        runs += 1
        throw firstFailure
      }
    }
    val thrownByLaterCaller = intercept[RuntimeException] {
      gate.initialize {
        runs += 1
      }
    }

    assert(thrownByInitializer eq firstFailure)
    assert(thrownByLaterCaller eq firstFailure)
    assert(runs == 1)
    assert(gate.currentState == BoltListenerApi.FAILED)
    assert(gate.failure.contains(firstFailure))
  }

  test("concurrent initialization callers share one in-flight attempt") {
    val gate = new BoltListenerApi.InitializationGate
    val initializerEntered = new CountDownLatch(1)
    val allowInitializerToFinish = new CountDownLatch(1)
    val runs = new AtomicInteger(0)
    val executor = Executors.newFixedThreadPool(2)

    try {
      val first = executor.submit(
        () =>
          gate.initialize {
            runs.incrementAndGet()
            initializerEntered.countDown()
            assert(allowInitializerToFinish.await(10, TimeUnit.SECONDS))
          })
      assert(initializerEntered.await(10, TimeUnit.SECONDS))

      val second = executor.submit(
        () =>
          gate.initialize {
            runs.incrementAndGet()
          })
      allowInitializerToFinish.countDown()

      assert(
        Set(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)) ==
          Set(true, false))
      assert(runs.get() == 1)
      assert(gate.currentState == BoltListenerApi.READY)
    } finally {
      allowInitializerToFinish.countDown()
      executor.shutdownNow()
    }
  }

  test("initialization gate rejects same-thread re-entry") {
    val gate = new BoltListenerApi.InitializationGate

    val error = intercept[IllegalStateException] {
      gate.initialize {
        gate.initialize {}
      }
    }

    assert(error.getMessage.contains("re-entered"))
    assert(gate.currentState == BoltListenerApi.FAILED)
    assert(gate.failure.contains(error))
  }
}
