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

package org.apache.spark.sql.execution.datasources

import org.apache.spark.sql.catalyst.InternalRow

import scala.util.control.NonFatal

import org.apache.spark.sql.{AnalysisException, SaveMode, SparkSession}
import org.apache.spark.sql.catalyst.analysis._
import org.apache.spark.sql.catalyst.catalog.{CatalogRelation, SessionCatalog}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.logical
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.execution.command.CreateHiveTableAsSelectLogicalPlan
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.sources.{BaseRelation, InsertableRelation}

/**
 * Try to replaces [[UnresolvedRelation]]s with [[ResolveDataSource]].
 */
class ResolveDataSource(sparkSession: SparkSession) extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan resolveOperators {
    case u: UnresolvedRelation if u.tableIdentifier.database.isDefined =>
      try {
        val dataSource = DataSource(
          sparkSession,
          paths = u.tableIdentifier.table :: Nil,
          className = u.tableIdentifier.database.get)

        val notSupportDirectQuery = try {
          !classOf[FileFormat].isAssignableFrom(dataSource.providingClass)
        } catch {
          case NonFatal(e) => false
        }
        if (notSupportDirectQuery) {
          throw new AnalysisException("Unsupported data source type for direct query on files: " +
            s"${u.tableIdentifier.database.get}")
        }
        val plan = LogicalRelation(dataSource.resolveRelation())
        u.alias.map(a => SubqueryAlias(u.alias.get, plan)).getOrElse(plan)
      } catch {
        case e: ClassNotFoundException => u
        case e: Exception =>
          // the provider is valid, but failed to create a logical plan
          u.failAnalysis(e.getMessage)
      }
  }
}

/**
 * Analyze the query in CREATE TABLE AS SELECT (CTAS). After analysis, [[PreWriteCheck]] also
 * can detect the cases that are not allowed.
 */
case class AnalyzeCreateTableAsSelect(sparkSession: SparkSession) extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case c: CreateTableUsingAsSelect if !c.query.resolved =>
      c.copy(query = analyzeQuery(c.query))
    case c: CreateHiveTableAsSelectLogicalPlan if !c.query.resolved =>
      c.copy(query = analyzeQuery(c.query))
  }

  private def analyzeQuery(query: LogicalPlan): LogicalPlan = {
    val qe = sparkSession.sessionState.executePlan(query)
    qe.assertAnalyzed()
    qe.analyzed
  }
}

/**
 * Preprocess the [[InsertIntoTable]] plan. Throws exception if the number of columns mismatch, or
 * specified partition columns are different from the existing partition columns in the target
 * table. It also does data type casting and field renaming, to make sure that the columns to be
 * inserted have the correct data type and fields have the correct names.
 */
case class PreprocessTableInsertion(conf: SQLConf) extends Rule[LogicalPlan] {
  private def preprocess(
      insert: InsertIntoTable,
      tblName: String,
      partColNames: Seq[String]): InsertIntoTable = {

    val expectedColumns = insert.expectedColumns
    ///
    val specfiedColumns = insert.specfiedColumns

    validateSpecifiedColumns(specfiedColumns, expectedColumns)

  if (specfiedColumns.isDefined && specfiedColumns.get.length !=insert.child.schema.length
    && expectedColumns.isDefined &&
    expectedColumns.get.length != insert.child.schema.length ) {
    throw new AnalysisException(
      s"Cannot insert into table $tblName because the number of columns are different: " +
        s"need ${expectedColumns.get.length} columns, " +
        s"but query has ${insert.child.schema.length} columns.")
  }

    val insert1 = if (insert.partition.nonEmpty) {
      // the query's partitioning must match the table's partitioning
      // this is set for queries like: insert into ... partition (one = "a", two = <expr>)
      val samePartitionColumns =
        if (conf.caseSensitiveAnalysis) {
          insert.partition.keySet == partColNames.toSet
        } else {
          insert.partition.keySet.map(_.toLowerCase) == partColNames.map(_.toLowerCase).toSet
        }
      if (!samePartitionColumns) {
        throw new AnalysisException(
          s"""
             |Requested partitioning does not match the table $tblName:
             |Requested partitions: ${insert.partition.keys.mkString(",")}
             |Table partitions: ${partColNames.mkString(",")}
           """.stripMargin)
      }
      expectedColumns.map(castAndRenameChildOutput(insert, _)).getOrElse(insert)
    } else {
      // All partition columns are dynamic because because the InsertIntoTable command does
      // not explicitly specify partitioning columns.
      expectedColumns.map(castAndRenameChildOutput(insert, _)).getOrElse(insert)
        .copy(partition = partColNames.map(_ -> None).toMap)
    }

    if(specfiedColumns.isDefined && specfiedColumns.get.nonEmpty) {
      insert1.child match {
        case localRelation: logical.LocalRelation =>
          val columnNames = specfiedColumns.get.map(_.name)
          val columnNamesIndex = columnNames.zipWithIndex.toMap
          val fill = fillRows(columnNames, columnNamesIndex, expectedColumns.get)
          localRelation.data.map(fill)
          insert1.withNewChildren(Seq(localRelation)).asInstanceOf[InsertIntoTable]
        case _ => insert1
      }
    }
    else insert1
  }

  def validateSpecifiedColumns(specfiedColumns: Option[Seq[NamedExpression]],
                               expectedColumns: Option[Seq[Attribute]]): Unit = {
    if (specfiedColumns.isDefined)
    {
      val specfiedColumnNames = specfiedColumns.get.map(_.name)
      if(specfiedColumnNames.distinct.length != specfiedColumnNames.length)
        {
            throw new AnalysisException(s"Cannot insert into table " +
              s"because there are Repeated columns in the specfied columns")
        }
      val expectedColumnNames = expectedColumns.get.map(_.name)
      if(specfiedColumnNames.exists(!expectedColumnNames.contains(_)))
        {
          throw new AnalysisException(s"Cannot insert into table " +
            s"because there are columns in the specfied columns not existed in expected Columns")
        }
    }
  }
  def fillRows(columnNames: Seq[String], columnNamesIndex: Map[String, Int],
               expectedColumns: Seq[Attribute])
              (row: InternalRow): InternalRow = {
    var data = Seq[AnyRef]()
    expectedColumns.foreach(column => {
      val line = if (columnNames.contains(column.name) &&
        !row.isNullAt(columnNamesIndex(column.name))) {
        row.get(columnNamesIndex(column.name), column.dataType)
      }
      else {
        null
      }
      data = data.:+(line)
    })
    InternalRow(data: _*)
  }

  def castAndRenameChildOutput(
      insert: InsertIntoTable,
      expectedOutput: Seq[Attribute]): InsertIntoTable = {
    val newChildOutput = expectedOutput.zip(insert.child.output).map {
      case (expected, actual) =>
        if (expected.dataType.sameType(actual.dataType) &&
          expected.name == actual.name &&
          expected.metadata == actual.metadata) {
          actual
        } else {
          // Renaming is needed for handling the following cases like
          // 1) Column names/types do not match, e.g., INSERT INTO TABLE tab1 SELECT 1, 2
          // 2) Target tables have column metadata
          Alias(Cast(actual, expected.dataType), expected.name)(
            explicitMetadata = Option(expected.metadata))
        }
    }

    if (newChildOutput == insert.child.output) {
      insert
    } else {
      insert.copy(child = Project(newChildOutput, insert.child))
    }
  }

  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case i @ InsertIntoTable(table, partition, child, _, _, _)
      if table.resolved && child.resolved =>
      table match {
        case relation: CatalogRelation =>
          val metadata = relation.catalogTable
          preprocess(i, metadata.identifier.quotedString, metadata.partitionColumnNames)
        case LogicalRelation(h: HadoopFsRelation, _, identifier) =>
          val tblName = identifier.map(_.quotedString).getOrElse("unknown")
          preprocess(i, tblName, h.partitionSchema.map(_.name))
        case LogicalRelation(_: InsertableRelation, _, identifier) =>
          val tblName = identifier.map(_.quotedString).getOrElse("unknown")
          preprocess(i, tblName, Nil)
        case other => i
      }
  }
}

/**
 * A rule to do various checks before inserting into or writing to a data source table.
 */
case class PreWriteCheck(conf: SQLConf, catalog: SessionCatalog)
  extends (LogicalPlan => Unit) {

  def failAnalysis(msg: String): Unit = { throw new AnalysisException(msg) }

  def apply(plan: LogicalPlan): Unit = {
    plan.foreach {
      case i @ logical.InsertIntoTable(
        l @ LogicalRelation(t: InsertableRelation, _, _),
        partition, query, overwrite, ifNotExists, _) =>
        // Right now, we do not support insert into a data source table with partition specs.
        if (partition.nonEmpty) {
          failAnalysis(s"Insert into a partition is not allowed because $l is not partitioned.")
        } else {
          // Get all input data source relations of the query.
          val srcRelations = query.collect {
            case LogicalRelation(src: BaseRelation, _, _) => src
          }
          if (srcRelations.contains(t)) {
            failAnalysis(
              "Cannot insert overwrite into table that is also being read from.")
          } else {
            // OK
          }
        }

      case logical.InsertIntoTable(
        LogicalRelation(r: HadoopFsRelation, _, _), part, query, overwrite, _, _) =>
        // We need to make sure the partition columns specified by users do match partition
        // columns of the relation.
        val existingPartitionColumns = r.partitionSchema.fieldNames.toSet
        val specifiedPartitionColumns = part.keySet
        if (existingPartitionColumns != specifiedPartitionColumns) {
          failAnalysis(s"Specified partition columns " +
            s"(${specifiedPartitionColumns.mkString(", ")}) " +
            s"do not match the partition columns of the table. Please use " +
            s"(${existingPartitionColumns.mkString(", ")}) as the partition columns.")
        } else {
          // OK
        }

        PartitioningUtils.validatePartitionColumn(
          r.schema, part.keySet.toSeq, conf.caseSensitiveAnalysis)

        // Get all input data source relations of the query.
        val srcRelations = query.collect {
          case LogicalRelation(src: BaseRelation, _, _) => src
        }
        if (srcRelations.contains(r)) {
          failAnalysis(
            "Cannot insert overwrite into table that is also being read from.")
        } else {
          // OK
        }

      case logical.InsertIntoTable(l: LogicalRelation, _, _, _, _, _) =>
        // The relation in l is not an InsertableRelation.
        failAnalysis(s"$l does not allow insertion.")

      case c: CreateTableUsingAsSelect =>
        // When the SaveMode is Overwrite, we need to check if the table is an input table of
        // the query. If so, we will throw an AnalysisException to let users know it is not allowed.
        if (c.mode == SaveMode.Overwrite && catalog.tableExists(c.tableIdent)) {
          // Need to remove SubQuery operator.
          EliminateSubqueryAliases(catalog.lookupRelation(c.tableIdent)) match {
            // Only do the check if the table is a data source table
            // (the relation is a BaseRelation).
            case l @ LogicalRelation(dest: BaseRelation, _, _) =>
              // Get all input data source relations of the query.
              val srcRelations = c.query.collect {
                case LogicalRelation(src: BaseRelation, _, _) => src
              }
              if (srcRelations.contains(dest)) {
                failAnalysis(
                  s"Cannot overwrite table ${c.tableIdent} that is also being read from.")
              } else {
                // OK
              }

            case _ => // OK
          }
        } else {
          // OK
        }

        PartitioningUtils.validatePartitionColumn(
          c.query.schema, c.partitionColumns, conf.caseSensitiveAnalysis)

        for {
          spec <- c.bucketSpec
          sortColumnName <- spec.sortColumnNames
          sortColumn <- c.query.schema.find(_.name == sortColumnName)
        } {
          if (!RowOrdering.isOrderable(sortColumn.dataType)) {
            failAnalysis(s"Cannot use ${sortColumn.dataType.simpleString} for sorting column.")
          }
        }

      case _ => // OK
    }
  }
}
