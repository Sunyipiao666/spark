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

package org.apache.spark.sql.execution.streaming.state

import org.apache.hadoop.fs.Path

import org.apache.spark.sql.Column
import org.apache.spark.sql.execution.streaming.MemoryStream
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.{OutputMode, StreamTest}
import org.apache.spark.sql.streaming.OutputMode.Complete
import org.apache.spark.sql.test.SharedSparkSession


class OperatorStateMetadataSuite extends StreamTest with SharedSparkSession {
  import testImplicits._

  private lazy val hadoopConf = spark.sessionState.newHadoopConf()

  private def numShufflePartitions = spark.sessionState.conf.numShufflePartitions

  private def checkOperatorStateMetadata(
      checkpointDir: String,
      operatorId: Int,
      expectedMetadata: OperatorStateMetadataV1): Unit = {
    val statePath = new Path(checkpointDir, s"state/$operatorId")
    val operatorMetadata = new OperatorStateMetadataReader(statePath, hadoopConf).read()
      .asInstanceOf[OperatorStateMetadataV1]
    assert(operatorMetadata.operatorInfo == expectedMetadata.operatorInfo &&
      operatorMetadata.stateStoreInfo.sameElements(expectedMetadata.stateStoreInfo))
  }

  test("Serialize and deserialize stateful operator metadata") {
    withTempDir { checkpointDir =>
      val statePath = new Path(checkpointDir.toString, "state/0")
      val stateStoreInfo = (1 to 4).map(i => StateStoreMetadataV1(s"store$i", 1, 200))
      val operatorInfo = OperatorInfoV1(1, "Join")
      val operatorMetadata = OperatorStateMetadataV1(operatorInfo, stateStoreInfo.toArray)
      new OperatorStateMetadataWriter(statePath, hadoopConf).write(operatorMetadata)
      checkOperatorStateMetadata(checkpointDir.toString, 0, operatorMetadata)
    }
  }

  test("Stateful operator metadata for streaming aggregation") {
    withTempDir { checkpointDir =>
      val inputData = MemoryStream[Int]
      val aggregated =
        inputData.toDF()
          .groupBy($"value")
          .agg(count("*"))
          .as[(Int, Long)]

      testStream(aggregated, Complete)(
        StartStream(checkpointLocation = checkpointDir.toString),
        AddData(inputData, 3),
        CheckLastBatch((3, 1)),
        StopStream
      )

      val expectedMetadata = OperatorStateMetadataV1(OperatorInfoV1(0, "stateStoreSave"),
        Array(StateStoreMetadataV1("default", 0, numShufflePartitions)))
      checkOperatorStateMetadata(checkpointDir.toString, 0, expectedMetadata)
    }
  }

  test("Stateful operator metadata for streaming join") {
    withTempDir { checkpointDir =>
      val input1 = MemoryStream[Int]
      val input2 = MemoryStream[Int]

      val df1 = input1.toDF.select($"value" as "key", ($"value" * 2) as "leftValue")
      val df2 = input2.toDF.select($"value" as "key", ($"value" * 3) as "rightValue")
      val joined = df1.join(df2, "key")

      testStream(joined)(
        StartStream(checkpointLocation = checkpointDir.getCanonicalPath),
        AddData(input1, 1),
        CheckAnswer(),
        AddData(input2, 1, 10), // 1 arrived on input1 first, then input2, should join
        CheckNewAnswer((1, 2, 3)),
        StopStream
      )

      val expectedStateStoreInfo = Array(
        StateStoreMetadataV1("left-keyToNumValues", 0, numShufflePartitions),
        StateStoreMetadataV1("left-keyWithIndexToValue", 0, numShufflePartitions),
        StateStoreMetadataV1("right-keyToNumValues", 0, numShufflePartitions),
        StateStoreMetadataV1("right-keyWithIndexToValue", 0, numShufflePartitions))

      val expectedMetadata = OperatorStateMetadataV1(
        OperatorInfoV1(0, "symmetricHashJoin"), expectedStateStoreInfo)
      checkOperatorStateMetadata(checkpointDir.toString, 0, expectedMetadata)
    }
  }

  test("Stateful operator metadata for streaming session window") {
    withTempDir { checkpointDir =>
      val input = MemoryStream[(String, Long)]
      val sessionWindow: Column = session_window($"eventTime", "10 seconds")

      val events = input.toDF()
        .select($"_1".as("value"), $"_2".as("timestamp"))
        .withColumn("eventTime", $"timestamp".cast("timestamp"))
        .withWatermark("eventTime", "30 seconds")
        .selectExpr("explode(split(value, ' ')) AS sessionId", "eventTime")

      val streamingDf = events
        .groupBy(sessionWindow as Symbol("session"), $"sessionId")
        .agg(count("*").as("numEvents"))
        .selectExpr("sessionId", "CAST(session.start AS LONG)", "CAST(session.end AS LONG)",
          "CAST(session.end AS LONG) - CAST(session.start AS LONG) AS durationMs",
          "numEvents")

      testStream(streamingDf, OutputMode.Complete())(
        StartStream(checkpointLocation = checkpointDir.toString),
        AddData(input,
          ("hello world spark streaming", 40L),
          ("world hello structured streaming", 41L)
        ),
        CheckNewAnswer(
          ("hello", 40, 51, 11, 2),
          ("world", 40, 51, 11, 2),
          ("streaming", 40, 51, 11, 2),
          ("spark", 40, 50, 10, 1),
          ("structured", 41, 51, 10, 1)
        ),
        StopStream
      )

      val expectedMetadata = OperatorStateMetadataV1(
        OperatorInfoV1(0, "sessionWindowStateStoreSaveExec"),
        Array(StateStoreMetadataV1("default", 1, spark.sessionState.conf.numShufflePartitions))
      )
      checkOperatorStateMetadata(checkpointDir.toString, 0, expectedMetadata)
    }
  }

  test("Stateful operator metadata for multiple operators") {
    withTempDir { checkpointDir =>
      val inputData = MemoryStream[Int]

      val stream = inputData.toDF()
        .withColumn("eventTime", timestamp_seconds($"value"))
        .withWatermark("eventTime", "0 seconds")
        .groupBy(window($"eventTime", "5 seconds").as("window"))
        .agg(count("*").as("count"))
        .groupBy(window($"window", "10 seconds"))
        .agg(count("*").as("count"), sum("count").as("sum"))
        .select($"window".getField("start").cast("long").as[Long],
          $"count".as[Long], $"sum".as[Long])

      testStream(stream)(
        StartStream(checkpointLocation = checkpointDir.toString),
        AddData(inputData, 10 to 21: _*),
        CheckNewAnswer((10, 2, 10)),
        StopStream
      )
      val expectedMetadata0 = OperatorStateMetadataV1(OperatorInfoV1(0, "stateStoreSave"),
        Array(StateStoreMetadataV1("default", 0, numShufflePartitions)))
      val expectedMetadata1 = OperatorStateMetadataV1(OperatorInfoV1(1, "stateStoreSave"),
        Array(StateStoreMetadataV1("default", 0, numShufflePartitions)))
      checkOperatorStateMetadata(checkpointDir.toString, 0, expectedMetadata0)
      checkOperatorStateMetadata(checkpointDir.toString, 1, expectedMetadata1)
    }
  }
}
