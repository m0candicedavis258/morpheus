/*
 *
 *  * Copyright 2014 websudos ltd.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.websudos.morpheus.query

import scala.annotation.implicitNotFound
import scala.concurrent.{ Future => ScalaFuture}
import scala.concurrent.ExecutionContext.Implicits.global
import com.twitter.finagle.exp.mysql.{Client, Row}
import com.twitter.util.Future
import com.websudos.morpheus.dsl.Table


trait BaseSelectQuery[T <: Table[T, _], R] extends SQLResultsQuery[T, R] {

  def fetch()(implicit client: Client): ScalaFuture[Seq[R]] = {
    twitterToScala(client.select(query.queryString)(fromRow))
  }

  def collect()(implicit client: Client): Future[Seq[R]] = {
    client.select(query.queryString)(fromRow)
  }

  def one()(implicit client: Client): ScalaFuture[Option[R]] = {
    fetch.map(_.headOption)
  }

  def get()(implicit client: Client): Future[Option[R]] = {
    collect().map(_.headOption)
  }

}

private[morpheus] abstract class AbstractSelectSyntaxBlock(
  query: String, tableName: String,
  columns: List[String] = List("*")) extends AbstractSyntaxBlock {

  protected[this] val qb = SQLBuiltQuery(query)

  def `*`: SQLBuiltQuery = {
    qb.pad.append(columns.mkString(" "))
      .pad.append(syntax.from)
      .pad.append(tableName)
  }

  def all: SQLBuiltQuery = this.`*`

  def distinct: SQLBuiltQuery = {
    qb.pad.append(syntax.distinct)
      .pad.append(columns.mkString(", "))
      .pad.append(syntax.from)
      .pad.append(tableName)
  }
}




/**
 * This is the implementation of a root select query, a wrapper around an abstract syntax block.
 * The basic select of select methods can be seen in {@link com.websudos.morpheus.dsl.SelectTable}
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
private[morpheus] abstract class AbstractRootSelectQuery[T <: Table[T, _], R](val table: T, val st: AbstractSelectSyntaxBlock, val rowFunc: Row => R) {

  def fromRow(r: Row): R = rowFunc(r)

  def distinct: Query[T, R, SelectType, Ungroupped, Unordered, Unlimited, Unchainned, AssignUnchainned, Unterminated] = {
    new Query(table, st.distinct, rowFunc)
  }

  def all: Query[T, R, SelectType, Ungroupped, Unordered, Unlimited, Unchainned, AssignUnchainned, Unterminated] = {
    new Query(table, st.*, rowFunc)
  }
}




/**
 * This bit of magic allows all extending sub-classes to implement the "set" and "and" SQL clauses with all the necessary operators,
 * in a type safe way. By providing the third type argument and a custom way to subclass with the predetermined set of arguments,
 * all DSL representations of an UPDATE query can use the implementation without violating DRY.
 *
 * @tparam T The type of the table owning the record.
 * @tparam R The type of the record held in the table.
 */
class SelectQuery[
  T <: Table[T, _],
  R,
  Type <: QueryType,
  Group <: GroupBind,
  Order <: OrderBind,
  Limit <: LimitBind,
  Chain <: ChainBind,
  AssignChain <: AssignBind,
  Status <: StatusBind
](val query: Query[T, R, Type, Group, Order, Limit, Chain, AssignChain, Status]) {

  @implicitNotFound("You can't use 2 SET parts on a single UPDATE query")
  final def having(condition: T => QueryAssignment)(implicit tp: Type =:= SelectType, ev: AssignChain =:= AssignUnchainned): SelectQuery[T, R, Type, Group,
    Order,
    Limit,
    Chain,
    AssignChainned, Status] = {
    new SelectQuery[T, R, Type, Group, Order, Limit, Chain, AssignChainned, Status](
      new Query(
        query.table,
        query.table.queryBuilder.having(query.query, condition(query.table).clause),
        query.rowFunc
      )
    )
  }


  final def leftJoin[
    Owner <: Table[Owner, Record],
    Record,
    G <: GroupBind,
    O <: OrderBind,
    L <: LimitBind,
    C <: ChainBind,
    AC <: AssignBind
  ](join: Query[Owner, Record, SelectType, G, O, L, C, AC, Unterminated]): SelectQuery[T, (Record, R), SelectType, G, O, L, C, AC, Unterminated] = {

    def rowFunc(row: Row): (Record, R) = (join.rowFunc(row), query.rowFunc(row))

    new SelectQuery[T, (Record, R), SelectType, G, O, L, C, AC, Unterminated](
      new Query(
        query.table,
        query.table.queryBuilder.leftJoin(query.query, join.query),
        rowFunc
      )
    )
  }

  final def rightJoin[
    Owner <: Table[Owner, Record],
    Record,
    G <: GroupBind,
    O <: OrderBind,
    L <: LimitBind,
    C <: ChainBind,
    AC <: AssignBind
  ](join: Query[Owner, Record, SelectType, G, O, L, C, AC, Unterminated]): SelectQuery[T, (R, Record), SelectType, G, O, L, C, AC, Unterminated] = {

    def rowFunc(row: Row): (R, Record) = (query.rowFunc(row), join.rowFunc(row))

    new SelectQuery[T, (R, Record), SelectType, G, O, L, C, AC, Unterminated](
      new Query(
        query.table,
        query.table.queryBuilder.leftJoin(query.query, join.query),
        rowFunc
      )
    )
  }

  private[morpheus] final def terminate: Query[T, R, SelectType, Group, Order, Limit, Chain, AssignChain, Terminated] = {
    new Query(
      query.table,
      query.query,
      query.fromRow
    )
  }


}