/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.api.table.plan.nodes.dataset

import com.google.common.collect.ImmutableList
import org.apache.calcite.plan.{RelOptCluster, RelTraitSet}
import org.apache.calcite.rel.`type`.RelDataType
import org.apache.calcite.rel.core.Values
import org.apache.calcite.rel.{RelNode, RelWriter}
import org.apache.calcite.rex.RexLiteral
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.DataSet
import org.apache.flink.api.table.BatchTableEnvironment
import org.apache.flink.api.table.codegen.CodeGenerator
import org.apache.flink.api.table.runtime.io.ValuesInputFormat
import org.apache.flink.api.table.typeutils.TypeConverter._

import scala.collection.JavaConverters._

/**
  * DataSet RelNode for a LogicalValues.
  *
  */
class DataSetValues(
    cluster: RelOptCluster,
    traitSet: RelTraitSet,
    rowRelDataType: RelDataType,
    tuples: ImmutableList[ImmutableList[RexLiteral]],
    ruleDescription: String)
  extends Values(cluster, rowRelDataType, tuples, traitSet)
  with DataSetRel {

  override def deriveRowType() = rowRelDataType

  override def copy(traitSet: RelTraitSet, inputs: java.util.List[RelNode]): RelNode = {
    new DataSetValues(
      cluster,
      traitSet,
      getRowType,
      getTuples,
      ruleDescription
    )
  }

  override def toString: String = {
    s"Values(values: (${getRowType.getFieldNames.asScala.toList.mkString(", ")}))"
  }

  override def explainTerms(pw: RelWriter): RelWriter = {
    super.explainTerms(pw).item("values", valuesFieldsToString)
  }

  override def translateToPlan(
      tableEnv: BatchTableEnvironment,
      expectedType: Option[TypeInformation[Any]]): DataSet[Any] = {

    val config = tableEnv.getConfig

    val returnType = determineReturnType(
      getRowType,
      expectedType,
      config.getNullCheck,
      config.getEfficientTypeUsage)

    val generator = new CodeGenerator(config)

    // generate code for every record
    val generatedRecords = getTuples.asScala.map { r =>
      generator.generateResultExpression(
        returnType,
        getRowType.getFieldNames.asScala,
        r.asScala)
    }

    // generate input format
    val generatedFunction = generator.generateValuesInputFormat(
      ruleDescription,
      generatedRecords.map(_.code),
      returnType)

    val inputFormat = new ValuesInputFormat[Any](
      generatedFunction.name,
      generatedFunction.code,
      generatedFunction.returnType)

    tableEnv.execEnv.createInput(inputFormat, returnType).asInstanceOf[DataSet[Any]]
  }

  private def valuesFieldsToString: String = {
    getRowType.getFieldNames.asScala.toList.mkString(", ")
  }

}


