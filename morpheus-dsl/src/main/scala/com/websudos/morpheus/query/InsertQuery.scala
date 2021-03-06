/*
 * Copyright 2013-2015 Websudos, Limited.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * - Explicit consent must be obtained from the copyright owner, Websudos Limited before any redistribution is made.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.websudos.morpheus.query

import com.websudos.morpheus.builder.{SQLBuiltQuery, AbstractSyntaxBlock, DefaultSQLSyntax, AbstractSQLSyntax}
import com.websudos.morpheus.column.AbstractColumn
import com.websudos.morpheus.dsl.BaseTable
import com.websudos.morpheus.query.parts.{Defaults, LightweightPart, ValuePart, ColumnsPart}
import com.websudos.morpheus.sql.DefaultRow
import com.websudos.morpheus.{Row, SQLPrimitive}
import shapeless.{HNil, HList}

import scala.annotation.implicitNotFound

private[morpheus] class RootInsertSyntaxBlock(query: String, tableName: String) extends AbstractSyntaxBlock {

  protected[this] val qb = SQLBuiltQuery(query)

  def into: SQLBuiltQuery = {
    qb.forcePad.append(syntax.into)
      .forcePad.appendEscape(tableName)
  }

  override val syntax: AbstractSQLSyntax = DefaultSQLSyntax
}

/**
 * This is the implementation of a root UPDATE query, a wrapper around an abstract syntax block.
 *
 * This is used as the entry point to an SQL query, and it requires the user to provide "one more method" to fully specify a SELECT query.
 * The implicit conversion from a RootSelectQuery to a SelectQuery will automatically pick the "all" strategy below.
 *
 * @param table The table owning the record.
 * @param st The Abstract syntax block describing the possible decisions.
 * @param rowFunc The function used to map a result to a type-safe record.
 * @tparam T The type of the owning table.
 * @tparam R The type of the record.
 */
private[morpheus] class RootInsertQuery[T <: BaseTable[T, _, TableRow], R, TableRow <: Row](val table: T, val st: RootInsertSyntaxBlock, val rowFunc:
TableRow => R) {

  def fromRow(r: TableRow): R = rowFunc(r)

  private[morpheus] def into: InsertQuery[T, R, TableRow, Ungroupped, Unordered, Unlimited, Unchainned, AssignUnchainned, HNil] = {
    new InsertQuery(table, st.into, rowFunc)
  }
}

private[morpheus] class DefaultRootInsertQuery[T <: BaseTable[T, _, DefaultRow], R]
(table: T, st: RootInsertSyntaxBlock, rowFunc: DefaultRow => R)
  extends RootInsertQuery[T, R, DefaultRow](table, st, rowFunc) {}



class InsertQuery[
  T <: BaseTable[T, _, TableRow],
  R,
  TableRow <: Row,
  Group <: GroupBind,
  Order <: OrderBind,
  Limit <: LimitBind,
  Chain <: ChainBind,
  AssignChain <: AssignBind,
  Status <: HList
](table: T,
  val init: SQLBuiltQuery,
  rowFunc: TableRow => R,
  columnsPart: ColumnsPart = Defaults.EmptyColumnsPart,
  valuePart: ValuePart = Defaults.EmptyValuePart,
  lightweightPart: LightweightPart = Defaults.EmptyLightweightPart
) extends Query[T, R, TableRow, Group, Order, Limit, Chain, AssignChain, Status](table, init, rowFunc) {

  protected[this] type QueryType[
    G <: GroupBind,
    O <: OrderBind,
    L <: LimitBind,
    S <: ChainBind,
    C <: AssignBind,
    P <: HList
  ] = InsertQuery[T, R, TableRow, G, O, L, S, C, P]

  override protected[this] def create[
    G <: GroupBind,
    O <: OrderBind,
    L <: LimitBind,
    S <: ChainBind,
    C <: AssignBind,
    P <: HList
  ](t: T, q: SQLBuiltQuery, r: TableRow => R): QueryType[G, O, L, S, C, P] = {
    new InsertQuery(t, q, r, columnsPart, valuePart, lightweightPart)
  }

  /**
   * At this point you may be reading and thinking "WTF", but fear not, it all makes sense. Every call to a "value method" will generate a new Insert Query,
   * but the list of statements in the new query will include a new (String, String) pair, where the first part is the column name and the second one is the
   * serialised value. This is a very simple accumulator that will eventually allow calling the "insert" method on a queryBuilder to produce the final
   * serialisation result, a hopefully valid MySQL insert query.
   *
   * @param insertion The insert condition is a pair of a column with the value to use for it. It looks like this: value(_.someColumn, someValue),
   *                  where the assignment is of course type safe.
   * @param obj The object is the value to use for the column.
   * @tparam RR The SQL primitive or rather it's Scala correspondent to use at this time.
   * @return A new InsertQuery, where the list of statements in the Insert has been chained and updated for serialisation.
   */
  @implicitNotFound(msg = "To use the value method this query needs to be an insert query and the query needs to be HNil. You probably have more " +
    "value calls than columns in your table, which would result in an invalid MySQL query.")
  def value[RR : SQLPrimitive](
    insertion: T => AbstractColumn[RR],
    obj: RR
  ): QueryType[Group, Order, Limit, Chain, AssignChain, Status] = {
    new InsertQuery[T, R, TableRow, Group, Order, Limit, Chain, AssignChain, Status](
      table,
      init,
      fromRow,
      columnsPart append SQLBuiltQuery(insertion(table).name),
      valuePart append SQLBuiltQuery(implicitly[SQLPrimitive[RR]].toSQL(obj)),
      lightweightPart
    )
  }

  override val query: SQLBuiltQuery = (columnsPart merge valuePart merge lightweightPart) build init
}
