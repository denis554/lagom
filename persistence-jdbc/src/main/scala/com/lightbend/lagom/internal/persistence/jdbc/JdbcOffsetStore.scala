/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package com.lightbend.lagom.internal.persistence.jdbc

import java.util.UUID
import javax.inject.{ Inject, Singleton }

import com.lightbend.lagom.javadsl.persistence.Offset
import play.api.Configuration

import scala.concurrent.ExecutionContext
import scala.util.Try

@Singleton
class OffsetTableConfiguration @Inject() (config: Configuration) {
  private val cfg = config.underlying.getConfig("lagom.persistence.read-side.jdbc.tables.offset")
  val tableName: String = cfg.getString("tableName")
  val schemaName: Option[String] = Option(cfg.getString("schemaName")).filter(_.trim != "")
  private val columnsCfg = cfg.getConfig("columnNames")
  val idColumnName: String = columnsCfg.getString("readSideId")
  val tagColumnName: String = columnsCfg.getString("tag")
  val sequenceOffsetColumnName: String = columnsCfg.getString("sequenceOffset")
  val timeUuidOffsetColumnName: String = columnsCfg.getString("timeUuidOffset")
  override def toString: String = s"OffsetTableConfiguration($tableName,$schemaName)"
}

private[jdbc] case class OffsetRow(id: String, tag: String, sequenceOffset: Option[Long], timeUuidOffset: Option[String]) {
  val offset = sequenceOffset.map(Offset.sequence).orElse(
    timeUuidOffset.flatMap(uuid => Try(UUID.fromString(uuid)).toOption)
      .filter(_.version == 1)
      .map(Offset.timeBasedUUID)
  ).getOrElse(Offset.NONE)
}

@Singleton
class JdbcOffsetStore @Inject() (val slick: SlickProvider, tableConfig: OffsetTableConfiguration)(implicit ec: ExecutionContext) {

  import slick.profile.api._

  private class OffsetStore(_tag: Tag) extends Table[OffsetRow](_tag, _schemaName = tableConfig.schemaName, _tableName = tableConfig.tableName) {
    def * = (id, tag, sequenceOffset, timeUuidOffset) <> (OffsetRow.tupled, OffsetRow.unapply)

    // Technically these two columns shouldn't have the primary key options, but they need it to work around
    // https://github.com/slick/slick/issues/966
    val id = column[String](tableConfig.idColumnName, O.Length(255, varying = true), O.PrimaryKey)
    val tag = column[String](tableConfig.tagColumnName, O.Length(255, varying = true), O.PrimaryKey)
    val sequenceOffset = column[Option[Long]](tableConfig.sequenceOffsetColumnName)
    val timeUuidOffset = column[Option[String]](tableConfig.timeUuidOffsetColumnName, O.Length(36, varying = false))
    val pk = primaryKey(s"${tableConfig.tableName}_pk", (id, tag))
  }

  private val offsets = TableQuery[OffsetStore]

  def getOffsetQuery(id: String, tag: String): DBIOAction[Offset, NoStream, Effect.Read] = {
    (for {
      offset <- offsets if offset.id === id && offset.tag === tag
    } yield {
      offset
    }).result.headOption.map(_.fold(Offset.NONE)(_.offset))
  }

  def updateOffsetQuery(id: String, tag: String, offset: Offset) = {
    val offsetRow = offset match {
      case sequence: Offset.Sequence      => OffsetRow(id, tag, Some(sequence.value), None)
      case timeUuid: Offset.TimeBasedUUID => OffsetRow(id, tag, None, Some(timeUuid.value.toString))
      case Offset.NONE                    => OffsetRow(id, tag, None, None)
    }
    offsets.insertOrUpdate(offsetRow)
  }

  def createTables() = {
    // The schema will be wrong due to our work around for https://github.com/slick/slick/issues/966 above, so need to
    // remove the primary key declarations from those columns
    val statements = offsets.schema.createStatements.map(_.replace(" PRIMARY KEY,", ",")).toSeq
    slick.createTable(statements, slick.tableExists(tableConfig.schemaName, tableConfig.tableName))
  }

}
