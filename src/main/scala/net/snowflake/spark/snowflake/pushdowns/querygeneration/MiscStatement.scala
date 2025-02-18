package net.snowflake.spark.snowflake.pushdowns.querygeneration

import net.snowflake.spark.snowflake.{
  ConstantString,
  EmptySnowflakeSQLStatement,
  IntVariable,
  SnowflakeFailMessage,
  SnowflakePushdownUnsupportedException,
  SnowflakeSQLStatement
}
import org.apache.spark.sql.catalyst.expressions.{
  Alias,
  Ascending,
  Attribute,
  CaseWhen,
  Cast,
  Coalesce,
  Descending,
  Expression,
  If,
  In,
  InSet,
  Literal,
  MakeDecimal,
  ScalarSubquery,
  ShiftLeft,
  ShiftRight,
  SortOrder,
  UnscaledValue
}
import org.apache.spark.sql.types.{Decimal, _}
import org.apache.spark.unsafe.types.UTF8String

/** Extractors for everything else. */
private[querygeneration] object MiscStatement {

  def unapply(
    expAttr: (Expression, Seq[Attribute])
  ): Option[SnowflakeSQLStatement] = {
    val expr = expAttr._1
    val fields = expAttr._2

    Option(expr match {
      case Alias(child: Expression, name: String) =>
        blockStatement(convertStatement(child, fields), name)
      case Cast(child, t, _) =>
        getCastType(t) match {
          case Some(cast) =>
            /** For known unsupported data conversion, raise exception to break the pushdown process.
              * For example, snowflake doesn't support to convert DATE/TIMESTAMP to NUMBER
              */
            (child.dataType, t) match {
              case (_: DateType | _: TimestampType,
              _: IntegerType | _: LongType | _: FloatType | _: DoubleType | _: DecimalType) => {
                // This exception will not break the connector. It will be caught in
                // QueryBuilder.treeRoot and a telemetry message will be sent if
                // there are any snowflake tables in the query.
                throw new SnowflakePushdownUnsupportedException(
                  SnowflakeFailMessage.FAIL_PUSHDOWN_UNSUPPORTED_CONVERSION,
                  s"Don't support to convert ${child.dataType} column to $t type",
                  "",
                  false)
              }
              case _ =>
            }

            ConstantString("CAST") +
              blockStatement(convertStatement(child, fields) + "AS" + cast)
          case _ => convertStatement(child, fields)
        }
      case If(child, trueValue, falseValue) =>
        ConstantString("IFF") +
          blockStatement(
            convertStatements(fields, child, trueValue, falseValue)
          )

      case In(child, list) =>
        blockStatement(
          convertStatement(child, fields) + "IN" +
            blockStatement(convertStatements(fields, list: _*))
        )

      case InSet(child, hset) =>
        convertStatement(In(child, setToExpr(hset)), fields)

      case MakeDecimal(child, precision, scale, _) =>
        ConstantString("TO_DECIMAL") + blockStatement(
          blockStatement(
            convertStatement(child, fields) + "/ POW(10," +
              IntVariable(Some(scale)) + ")"
          ) + "," + IntVariable(Some(precision)) + "," +
            IntVariable(Some(scale))
        )
      case ShiftLeft(col, num) =>
        ConstantString("BITSHIFTLEFT") +
          blockStatement(convertStatements(fields, col, num))

      case ShiftRight(col, num) =>
        ConstantString("BITSHIFTRIGHT") +
          blockStatement(convertStatements(fields, col, num))

      case SortOrder(child, Ascending, _, _) =>
        blockStatement(convertStatement(child, fields)) + "ASC"
      case SortOrder(child, Descending, _, _) =>
        blockStatement(convertStatement(child, fields)) + "DESC"

      case ScalarSubquery(subquery, _, _) =>
        blockStatement(new QueryBuilder(subquery).statement)

      case UnscaledValue(child) =>
        child.dataType match {
          case d: DecimalType =>
            blockStatement(
              convertStatement(child, fields) + "* POW(10," + IntVariable(
                Some(d.scale)
              ) + ")"
            )
          case _ => null
        }

      case CaseWhen(branches, elseValue) =>
        ConstantString("CASE") +
          mkStatement(branches.map(conditionValue => {
            ConstantString("WHEN") + convertStatement(conditionValue._1, fields) +
              ConstantString("THEN") + convertStatement(conditionValue._2, fields)
          }
          ), " ") + { elseValue match {
          case Some(value) => ConstantString("ELSE") + convertStatement(value, fields)
          case None => EmptySnowflakeSQLStatement()
        }} + ConstantString("END")

      case Coalesce(columns) =>
        ConstantString(expr.prettyName.toUpperCase) +
          blockStatement(
            mkStatement(
              columns.map(convertStatement(_, fields)),
              ", "
            )
          )

      case _ => null
    })
  }

  private final def setToExpr(set: Set[Any]): Seq[Expression] = {
    set.map {
      case d: Decimal => Literal(d, DecimalType(d.precision, d.scale))
      case s @ (_:String | _:UTF8String) => Literal(s, StringType)
      case d: Double => Literal(d, DoubleType)
      case e: Expression => e
      case default =>
        // This exception will not break the connector. It will be caught in
        // QueryBuilder.treeRoot and a telemetry message will be sent if
        // there are any snowflake tables in the query.
        throw new SnowflakePushdownUnsupportedException(
          SnowflakeFailMessage.FAIL_PUSHDOWN_SET_TO_EXPR,
          s"${default.getClass.getSimpleName} @ MiscStatement.setToExpr",
          "Class " + default.getClass.getName + " is not supported in the 'in()' expression",
          false
        )
    }.toSeq
  }

  /** Attempts a best effort conversion from a SparkType
    * to a Snowflake type to be used in a Cast.
    */
  private[querygeneration] final def getCastType(t: DataType): Option[String] =
    Option(t match {
      case StringType => "VARCHAR"
      case BinaryType => "BINARY"
      case DateType => "DATE"
      case TimestampType => "TIMESTAMP"
      case d: DecimalType =>
        "DECIMAL(" + d.precision + ", " + d.scale + ")"
      case IntegerType | LongType => "NUMBER"
      case FloatType => "FLOAT"
      case DoubleType => "DOUBLE"
      case _ => null
    })

}
