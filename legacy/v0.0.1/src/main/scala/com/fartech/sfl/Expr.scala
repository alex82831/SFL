package com.fartech.sfl

import com.fartech.sfl.JsonUtils.{jsonArrayString, jsonString}
import com.fartech.sfl.{Environment, Scope}
import sun.jvm.hotspot.HelloWorld.e

import java.util.UUID
import scala.annotation.tailrec
import scala.collection.immutable.List
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.sys.env
import scala.util.{Failure, Success}

sealed trait Expr:
  def simplify: Expr = throw IllegalStateException("default simplify called. this should not happen")

  def compute: Option[Expr] = throw IllegalStateException("default compute called. this should not happen")

  @tailrec final def forceComputeToPrimitiveValue[N >: Any](expr: Option[Expr]): Option[ValueExpr[N]] = {
    expr match
      case Some(e: ValueExpr[N]) =>
        e.value match
          case _: NumberLiteral => expr.asInstanceOf[Option[ValueExpr[N]]]
          case _: String => expr.asInstanceOf[Option[ValueExpr[N]]]
          case _: mutable.HashMap[String, Any] => expr.asInstanceOf[Option[ValueExpr[N]]]
          case _: ListBuffer[Any] => expr.asInstanceOf[Option[ValueExpr[N]]]
          case funDef: FunDefExpr => Some(ValueExpr(funDef))
          case _ => forceComputeToPrimitiveValue(e.compute)
      case Some(e: Expr) => forceComputeToPrimitiveValue(e.compute)
      case _ => throw IllegalArgumentException(s"Expr $expr cannot compute to value")
  }

  def checkValid: Boolean = throw IllegalStateException("default checkValid called. this should not happen")

  @tailrec final def getValueIfValid[N >: Expr, M >: Any](e: Option[N]): ValueExpr[M] = {
    if (e.isEmpty || !e.get.isInstanceOf[ValueExpr[M]]) {
      throw IllegalStateException(s"Error: Expr $e cannot compute to value")
    } else {
      val result = e.get.asInstanceOf[ValueExpr[M]]
      result match
        case ValueExpr(v: ValueExpr[Any]) => getValueIfValid(Some(v))
        case _ => result
    }
  }

case class FunExpr(funName: String, args: List[Expr], scope: Scope) extends Expr :
  override def simplify: FunExpr = FunExpr(funName, args.map(_.simplify), scope)

  override def checkValid: Boolean = args.forall(_.checkValid)

  override def toString: String = s"(FunCall - $funName: $args)"

  override def compute: Option[Expr] = {
    val funDefOption = Environment.lookupFun((funName, Environment.currentScope))
    if funDefOption.isDefined then
      val funDef = funDefOption.get
      if args.length != funDef.args.length then
        throw IllegalStateException(s"Error: Function $funName argument length (${args.length}) does not match with definition (${funDef.args.length})")
      val argPairs = funDef.args.indices.map(i => {
        if i > args.length - 1 then (funDef.args(i), NoneExpr)
        else (funDef.args(i), args(i))
      })
      val tempScope = Scope(UUID.randomUUID().toString, Some(scope))
      argPairs.foldLeft(NoneExpr) {
        (_, argPair) =>
          val v = argPair._2.compute
          v match
            case Some(FunDefExpr(_, funDefArgs, _, body)) =>
              Environment.addFunction(argPair._1, funDefArgs, tempScope, body)
            case Some(j: JsonValueExpr) =>
              Environment.addNewVariable((argPair._1, tempScope), j.compute)
            case Some(j: JsonObjExpr) => Environment.addNewVariable((argPair._1, tempScope), j.json)
            case Some(j: JsonArrayExpr) => Environment.addNewVariable((argPair._1, tempScope), j.array)
            case _ =>
              // Some of ValueExpr is nested, so we need to remove all outer wrappers
              val argValue = getValueIfValid(v)
              (argPair._1, argValue) match
                case (name, ValueExpr(v)) =>
                  Environment.addNewVariable((name, tempScope), v)
          NoneExpr
      }
      Environment.enterNewScope(tempScope)
      val ret = funDef.body().compute
      Environment.cleanupVariable()
      // remove temp functions if needed
      Environment.cleanTempFunctions(tempScope)
      Environment.exitScope
      ret
    else
      throw IllegalStateException(s"Error: Function $funName does not define")
  }

case class NestedCallExpr(first: Expr, args: List[List[Expr]]) extends Expr :
  override def simplify: NestedCallExpr = NestedCallExpr(first.simplify, args.map(_.map(_.simplify)))

  override def checkValid: Boolean = first.checkValid && args.forall(_.forall(_.checkValid))

  override def toString: String = s"(NestedCall - $first: $args)"

  override def compute: Option[Expr] =
    def nestedCompute(first: Expr, args: List[List[Expr]]): Option[Expr] = {
      @inline def handleFunDef(fn: String, argList: List[String], defScope: Scope, body: () => Expr): Option[Expr] = {
        val argValues = args.foldLeft((List.empty[Expr], List.empty[List[Expr]])) {
          (result, next) =>
            if result._1.length < argList.length then
              if result._1.length + next.length > argList.length then
                throw IllegalStateException(s"Nested call argument length mismatch. Required: ${argList.length}")
              (result._1 ::: next, result._2)
            else if result._1.length == argList.length then
              (result._1, result._2 ::: List(next))
            else
              throw IllegalStateException(s"Nested call argument length mismatch. Required: ${argList.length}")
        }
        if argValues._2.nonEmpty then
          if Environment.lookupFun((fn, Environment.currentScope)).isDefined then
            nestedCompute(FunExpr(fn, argValues._1, Environment.currentScope), argValues._2)
          else
            Environment.addFunction(fn, argList, Environment.currentScope, body)
            nestedCompute(FunExpr(fn, argValues._1, Environment.currentScope), argValues._2)
        else
          if Environment.lookupFun((fn, Environment.currentScope)).isDefined then
            FunExpr(fn, argValues._1, defScope).compute
          else
            val tempName = UUID.randomUUID().toString
            Environment.addFunction(tempName, argList, Environment.currentScope, body)
            FunExpr(tempName, argValues._1, defScope).compute
      }
      val nextLayer = first.compute
      nextLayer match
        case Some(FunDefExpr(fn, argList, defScope, body)) =>
          handleFunDef(fn, argList, defScope, body)
        case Some(ValueExpr(FunDefExpr(fn, argList, defScope, body))) =>
          handleFunDef(fn, argList, defScope, body)
        case Some(FunExpr(_, _, _)) =>
          throw IllegalStateException(s"Illegal state. $first is a FunExpr")
        case Some(_) =>
          nextLayer
        case None =>
          throw IllegalStateException(s"Expr $first is not a callable expr (Do you write the correct function name?)")
    }
    nestedCompute(first, args)

case class FunExExpr(name: Expr, args: List[Expr], scope: Scope) extends Expr :
  override def simplify: FunExExpr = FunExExpr(name, args.map(_.simplify), scope)

  override def checkValid: Boolean = args.forall(_.checkValid)

  override def toString: String = s"(FunExCall - $name: $args)"

  override def compute: Option[Expr] =
    val fn = name.compute
    fn match
      case Some(ValueExpr(FunDefExpr(fname, _, _, _))) =>
        FunExpr(fname, args, scope).compute
      case _ => throw IllegalArgumentException(s"Expr $name cannot compute to value")

case class FunDefExpr(funName: String, args: List[String], scope: Scope, body: () => Expr) extends Expr :
  override def simplify: Expr = FunDefExpr(funName, args, scope, body)

  override def checkValid: Boolean = true

  override def compute: Option[Expr] = Some(this)

  override def toString: String = s"(FunDef - $funName: $args - ${body.apply()})"

case class ArithmeticExpr(firstOp: Expr, extras: List[(String, Expr)]) extends Expr :
  override def simplify: Expr = ArithmeticExpr(firstOp.simplify, extras.map(extra => (extra._1, extra._2.simplify)))

  override def checkValid: Boolean = firstOp.checkValid && extras.forall(_._2.checkValid)

  override final def compute: Option[Expr] = {
    Some(extras.foldLeft(getValueIfValid(firstOp.compute)) {
      (op1, op) => {
        val right = getValueIfValid(op._2.compute)
        op._1 match
          case "+" =>
            (op1.value, right.value) match
              case (s1: String, s2: String) => ValueExpr(s1 + s2)
              case (s1: String, s2: NumberLiteral) => ValueExpr(s1 + s2.toString)
              case (s1: NumberLiteral, s2: String) => ValueExpr(s1.toString + s2)
              case (d1: NumberLiteral, d2: NumberLiteral) => ValueExpr(d1 + d2)
              case (d1: Any, d2: Any) => ValueExpr(d1.toString + d2.toString)
          case "-" =>
            (op1.value, right.value) match
              case (d1: NumberLiteral, d2: NumberLiteral) => ValueExpr(d1 - d2)
              case _ => throw IllegalStateException("You cannot perform basic arithmetic operation on two different types")
          case "*" =>
            (op1.value, right.value) match
              case (d1: NumberLiteral, d2: NumberLiteral) => ValueExpr(d1 * d2)
              case _ => throw IllegalStateException("You cannot perform basic arithmetic operation on two different types")
          case "/" =>
            (op1.value, right.value) match
              case (d1: NumberLiteral, d2: NumberLiteral) => ValueExpr(d1 / d2)
              case _ => throw IllegalStateException("You cannot perform basic arithmetic operation on two different types")
          case "%" =>
            (op1.value, right.value) match
              case (d1: NumberLiteral, d2: NumberLiteral) => ValueExpr(d1 % d2)
              case _ => throw IllegalStateException("You cannot perform basic arithmetic operation on two different types")
          case _ => throw IllegalStateException(s"Unsupported operation ${op._1} found")
      }
    })
  }

  override def toString: String = s"(Arithmetic - $firstOp - $extras)"

case class LogicExpr(firstOp: Expr, extras: List[(String, Expr)]) extends Expr :
  override def simplify: Expr = LogicExpr(firstOp.simplify, extras.map(extra => (extra._1, extra._2.simplify)))

  override def checkValid: Boolean = firstOp.checkValid && extras.forall(_._2.checkValid)

  override final def compute: Option[Expr] = {
    Some(extras.foldLeft(getValueIfValid(firstOp.compute)) {
      (op1, op) => {
        val right = getValueIfValid(op._2.compute)
        op._1 match
          case "&&" =>
            (op1.value, right.value) match
              case (d1: Boolean, d2: Boolean) => ValueExpr(d1 && d2)
              case _ => throw IllegalStateException("You cannot perform logic operation on two different types")
          case "||" =>
            (op1.value, right.value) match
              case (d1: Boolean, d2: Boolean) => ValueExpr(d1 || d2)
              case _ => throw IllegalStateException("You cannot perform logic operation on two different types")
          case _ => throw IllegalStateException(s"Unsupported operation ${op._1} found")
      }
    })
  }

  override def toString: String = s"(Logic - $firstOp - $extras)"

case class ValueExpr[N >: Any](value: N) extends Expr :
  override def simplify: ValueExpr[N] = this

  override def compute: Option[ValueExpr[N]] =
    value match
      case FunExpr(_, _, _) => forceComputeToPrimitiveValue(value.asInstanceOf[FunExpr].compute)
      case EqualExpr(_, _) => forceComputeToPrimitiveValue(value.asInstanceOf[FunExpr].compute)
      case JsonValueExpr(_, _) => forceComputeToPrimitiveValue(value.asInstanceOf[FunExpr].compute)
      case _ => Some(this)

  override def checkValid = true

  override def toString: String = s"(Value - $value)"

case class BodyExpr(body: List[Expr]) extends Expr :
  override def simplify: BodyExpr = BodyExpr(body.filterNot(_ == CommentExpr).map(_.simplify))

  override def checkValid: Boolean = body.forall(_.checkValid)

  /*
  * the value of the last expr is the return value of the entire single expr unless we met return statement explicitly
  */
  override def compute: Option[Expr] =
    val result = body.foldLeft((false, None.asInstanceOf[Option[Expr]])) {
      (prev, expr) =>
        if !prev._1 then
          expr match
            case ReturnExpr(value) => (true, value.compute)
            case _ => (false, expr.compute)
        else
          prev
    }
    result._2

  override def toString: String = s"(Body - $body)"

case class ReturnExpr(expr: Expr) extends Expr :
  override def simplify: ReturnExpr = ReturnExpr(expr.simplify)

  override def checkValid: Boolean = expr.checkValid

  override def compute: Option[Expr] = expr.compute

  override def toString: String = s"(Return - $expr)"

case class EqualExpr[N >: Any](left: Expr, right: Expr) extends Expr :
  override def simplify: EqualExpr[N] = EqualExpr(left, right.simplify)

  override def checkValid: Boolean = left.isInstanceOf[VariableExpr[Any]] || left.isInstanceOf[JsonLeftValueExpr]

  override def compute: Option[Expr] = {
    val v = right.compute
    (left, v) match
      case (VariableExpr(name, _), Some(value: ValueExpr[Any])) =>
        Environment.addNewVariable((name, Environment.currentScope), value)
        Some(value)
      case (jLeft: JsonLeftValueExpr, Some(value)) => jLeft.setValue(value)
      case (VariableExpr(name, _), Some(FunDefExpr(_, args, _, body))) =>
        Some(Environment.addFunction(name, args, Environment.currentScope, body))
      case _ =>
        throw IllegalStateException(s"Expr $right cannot compute to value or tuple or $left isn't variable or tuple element")
  }

  override def toString: String = s"(Equal - $left = $right)"

case class VariableExpr[N >: Any](name: String, immutable: Boolean) extends Expr :
  override def simplify: VariableExpr[N] = this

  override def checkValid: Boolean = true

  override def compute: Option[Expr] =
    val fun = Environment.lookupFun((name, Environment.currentScope))
    fun match
      case Some(funDef: FunDefExpr) => Some(funDef)
      case _ =>
        val v = Environment.lookupVariable((name, Environment.currentScope))
        v match
          case Some(ValueExpr(n: NumberLiteral)) => Some(ValueExpr(n))
          case Some(ValueExpr(n: String)) => Some(ValueExpr(n))
          case Some(funDef: FunDefExpr) => Some(funDef)
          case Some(_) => Some(ValueExpr(v.get))
          case _ =>
            val fun = Environment.lookupFun((name, Environment.currentScope))
            if fun.isDefined then
              fun
            else
              None


  override def toString: String = s"(Variable - $name - $immutable)"

case class CondExpr(conds: Map[Expr, Expr], elseCond: Option[Expr]) extends Expr :
  override def simplify: Expr = CondExpr(conds.map(pair => pair._1.simplify -> pair._2.simplify), elseCond.map(_.simplify))

  override def checkValid: Boolean = conds.forall(pair => pair._1.checkValid && pair._2.checkValid) && elseCond.forall(_.checkValid)

  override def compute: Option[Expr] = {
    val cond = conds.find(pair => {
      pair._1.compute match
        case Some(v: ValueExpr[Any]) if v.value.isInstanceOf[Boolean] => if v.value.asInstanceOf[Boolean] then true else false
        case None => throw IllegalStateException(s"Error: Expr ${pair._1} is not a boolean")
        case _ => false
    })
    if cond.isDefined then
      cond.get._2.compute
    else if elseCond.isDefined then
      elseCond.get.compute
    else
      None
  }

  override def toString: String = s"(Cond - $conds - $elseCond)"

case class CmpExpr(method: String, op1: Expr, op2: Expr) extends Expr :
  override def simplify: Expr = CmpExpr(method, op1.simplify, op2.simplify)

  override def checkValid: Boolean = true

  override def compute: Option[Expr] = {
    val r1 = op1.compute
    val r2 = op2.compute
    (method, getValueIfValid(r1), getValueIfValid(r2)) match
      case (">", ValueExpr(d1: NumberLiteral), ValueExpr(d2: NumberLiteral)) => Some(ValueExpr(d1 > d2))
      case (">=", ValueExpr(d1: NumberLiteral), ValueExpr(d2: NumberLiteral)) => Some(ValueExpr(d1 >= d2))
      case ("==", ValueExpr(d1: NumberLiteral), ValueExpr(d2: NumberLiteral)) => Some(ValueExpr(d1 == d2))
      case ("<", ValueExpr(d1: NumberLiteral), ValueExpr(d2: NumberLiteral)) => Some(ValueExpr(d1 < d2))
      case ("<=", ValueExpr(d1: NumberLiteral), ValueExpr(d2: NumberLiteral)) => Some(ValueExpr(d1 <= d2))
      case (">", ValueExpr(s1: String), ValueExpr(s2: String)) => Some(ValueExpr(s1 > s2))
      case (">=", ValueExpr(s1: String), ValueExpr(s2: String)) => Some(ValueExpr(s1 >= s2))
      case ("==", ValueExpr(s1: String), ValueExpr(s2: String)) => Some(ValueExpr(s1 == s2))
      case ("<", ValueExpr(s1: String), ValueExpr(s2: String)) => Some(ValueExpr(s1 < s2))
      case ("<=", ValueExpr(s1: String), ValueExpr(s2: String)) => Some(ValueExpr(s1 <= s2))
      case (">=", ValueExpr(d1: Boolean), ValueExpr(d2: Boolean)) => Some(ValueExpr(d1 >= d2))
      case ("==", ValueExpr(d1: Boolean), ValueExpr(d2: Boolean)) => Some(ValueExpr(d1 == d2))
      case ("<", ValueExpr(d1: Boolean), ValueExpr(d2: Boolean)) => Some(ValueExpr(d1 < d2))
      case ("<=", ValueExpr(d1: Boolean), ValueExpr(d2: Boolean)) => Some(ValueExpr(d1 <= d2))
      case ("==", ValueExpr(d1: Comparable[Any]), ValueExpr(d2: Comparable[Any])) if d1.getClass == d2.getClass => Some(ValueExpr(d1 == d2))
      case ("==", ValueExpr(l1: ListBuffer[Any]), ValueExpr(l2: ListBuffer[Any])) => Some(ValueExpr(l1 == l2))
      case ("==", ValueExpr(l1: mutable.HashMap[String, Any]), ValueExpr(l2: mutable.HashMap[String, Any])) => Some(ValueExpr(l1 == l2))
      case _ => throw IllegalStateException(s"Error: Expr $op1 or $op2 is not comparable")
  }

  override def toString: String = s"(Compare - $op1 $method $op2)"

case class JsonObjExpr(json: mutable.HashMap[String, Expr]) extends Expr :
  override def simplify: Expr = JsonObjExpr(json.map((k: String, v: Expr) => (k, v.simplify)))

  override def compute: Option[Expr] =
    Some(
      ValueExpr(
        json.map((k, v) => {
          v match
            case _ =>
              val result = v.compute
              result match
                case Some(ValueExpr(value: NumberLiteral)) => k -> value
                case Some(ValueExpr(value: String)) => k -> value
                case Some(ValueExpr(value: mutable.HashMap[String, Any])) => k -> value
                case Some(ValueExpr(value: ListBuffer[Any])) => k -> value
                case Some(fun: FunDefExpr) => k -> fun
                case _ => throw IllegalArgumentException(s"Expr $v cannot compute to value")
        })))

  override def checkValid: Boolean = true

  override def toString: String = s"(JsonObjExpr - $json)"

case class JsonArrayExpr(array: mutable.ListBuffer[Expr]) extends Expr :
  override def simplify: Expr = JsonArrayExpr(array.map(_.simplify))

  override def compute: Option[Expr] =
    Some(
      ValueExpr(
        array.map(
          expr =>
            expr match
              case _ =>
                expr.compute match
                  case Some(ValueExpr(value: NumberLiteral)) => value
                  case Some(ValueExpr(value: String)) => value
                  case Some(ValueExpr(value: mutable.HashMap[String, Any])) => value
                  case Some(ValueExpr(value: ListBuffer[Any])) => value
                  case _ => throw IllegalArgumentException(s"Expr $expr cannot compute to value")
        )))

  override def checkValid: Boolean = true

  override def toString: String = s"(JsonArrayExpr - $array)"

case class JsonValueExpr(jsonName: String, keys: List[Expr]) extends Expr :
  override def simplify: Expr = this
  override def checkValid: Boolean = keys.forall(_.checkValid)
  override def compute: Option[Expr] = forceComputeToPrimitiveValue(internalCompute)

  private def internalCompute: Option[Expr] = {
    @tailrec def getValue(next: Any, keys: List[Expr]): Option[Expr] =
      next match
        case m: mutable.HashMap[String, Any] =>
          keys match
            case k :: Nil =>
              k.compute match
                case Some(ValueExpr(key: String)) =>
                  if m.contains(key) then
                    Some(ValueExpr(m(key)))
                  else throw IllegalArgumentException(s"Key $key does not exist")
                case Some(ValueExpr(_: Long)) =>
                  throw IllegalArgumentException(s"It is an object but provided was array position")
                case _ => throw IllegalArgumentException("Json Value cannot compute to value")
            case Nil => throw IllegalArgumentException("Json Value cannot compute to value")
            case _ =>
              keys.head.compute match
                case Some(ValueExpr(key: String)) =>
                  if !m.contains(key) then throw IllegalArgumentException(s"Key $key does not exist")
                  m(key) match
                    case nm: mutable.HashMap[String, Any] => getValue(nm, keys.tail)
                    case na: ListBuffer[Any] => getValue(na, keys.tail)
                    case _ => throw IllegalArgumentException("Json Value cannot compute to value")
                case _ => throw IllegalArgumentException("Json Value cannot compute to value")
        case a: ListBuffer[Any] =>
          keys match
            case k :: Nil =>
              k.compute match
                case Some(ValueExpr(pos: Long)) =>
                  if pos < a.length then Some(ValueExpr(a(pos.toInt)))
                  else throw IllegalArgumentException(s"Index $pos out of array bounds")
                case Some(ValueExpr(pos: NumberLiteral)) =>
                  if pos.toInt < a.length then Some(ValueExpr(a(pos.toInt)))
                  else throw IllegalArgumentException(s"Index $pos out of array bounds")
                case Some(ValueExpr(key: String)) =>
                  throw IllegalArgumentException(s"It is an array but provided was object key")
                case _ => throw IllegalArgumentException("Json Value cannot compute to value")
            case Nil => throw IllegalArgumentException("Json Value cannot compute to value")
            case _ =>
              val result = keys.head.compute
              result match
                case Some(ValueExpr(pos: NumberLiteral)) =>
                  if pos.toLong >= a.length then throw IllegalArgumentException(s"Index $pos out of array bounds")
                  a(pos.toInt) match
                    case nm: mutable.HashMap[String, Any] => getValue(nm, keys.tail)
                    case na: ListBuffer[Any] => getValue(na, keys.tail)
                    case _ => throw IllegalArgumentException("Json Value cannot compute to value")
                case _ => throw IllegalArgumentException("Json Value cannot compute to value")
        case _ => throw IllegalArgumentException("Json Value cannot compute to value")

    val v = Environment.lookupVariable((jsonName, Environment.currentScope))
    v match
      case Some(ValueExpr(m: mutable.HashMap[String, Any])) => getValue(m, keys)
      case Some(ValueExpr(m: ListBuffer[Any])) => getValue(m, keys)
      case Some(m: mutable.HashMap[String, Any]) => getValue(m, keys)
      case Some(m: ListBuffer[Any]) => getValue(m, keys)
      case _ => throw IllegalArgumentException("Json Value cannot compute to value")
  }

  override def toString: String = s"(JsonValueExpr - $jsonName[$keys])"

case class JsonLeftValueExpr(jsonName: String, keys: List[Expr]) extends Expr :
  override def simplify: Expr = this
  override def checkValid: Boolean = keys.forall(_.checkValid)
  override def toString: String = s"(JsonLeftValueExpr - $jsonName[$keys])"

  override def compute: Option[Expr] = Some(this)

  def setValue(value: Any): Option[Expr] =
    @tailrec def setValue(next: Any, keys: List[Expr], value: Any): Option[Expr] =
      next match
        case m: mutable.HashMap[String, Any] =>
          keys match
            case k :: Nil =>
              k.compute match
                case Some(ValueExpr(key: String)) =>
                  value match
                    case ValueExpr(n: NumberLiteral) => m.addOne(key -> n)
                    case ValueExpr(s: String) => m.addOne(key -> s)
                    case ValueExpr(a: Any) => m.addOne(key -> a)
                    case _ => m.addOne(key -> value)
                  Some(ValueExpr(m(key)))
                case Some(ValueExpr(_: Long)) =>
                  throw IllegalArgumentException(s"It is an object but provided was array position")
                case _ => throw IllegalArgumentException("Json Value cannot compute to value")
            case Nil => throw IllegalArgumentException("Json Value cannot compute to value")
            case _ =>
              keys.head.compute match
                case Some(ValueExpr(key: String)) =>
                  if !m.contains(key) then throw IllegalArgumentException(s"Key $key does not exist")
                  m(key) match
                    case nm: mutable.HashMap[String, Any] =>
                      setValue(nm, keys.tail, value)
                    case na: ListBuffer[Any] =>
                      setValue(na, keys.tail, value)
                    case _ => throw IllegalArgumentException("Json Value cannot compute to value")
                case _ => throw IllegalArgumentException("Json Value cannot compute to value")
        case a: ListBuffer[Any] =>
          keys match
            case k :: Nil =>
              val r = k.compute
              r match
                case Some(ValueExpr(pos: Int)) =>
                  if pos < a.length then
                    a(pos) = value
                    Some(ValueExpr(a(pos)))
                  else
                    throw IllegalArgumentException(s"Index $pos out of array bounds")
                case Some(ValueExpr(pos: Long)) =>
                  if pos < a.length then
                    a(pos.toInt) = value
                    Some(ValueExpr(a(pos.toInt)))
                  else
                    throw IllegalArgumentException(s"Index $pos out of array bounds")
                case Some(ValueExpr(pos: NumberLiteral)) =>
                  if pos.toInt < a.length then
                    a(pos.toInt) = value
                    Some(ValueExpr(a(pos.toInt)))
                  else
                    throw IllegalArgumentException(s"Index $pos out of array bounds")
                case Some(ValueExpr(_: String)) =>
                  throw IllegalArgumentException(s"It is an array but provided was object key")
                case _ => throw IllegalArgumentException("Json Value cannot compute to value")
            case Nil => throw IllegalArgumentException("Json Value cannot compute to value")
            case _ =>
              val result = keys.head.compute
              result match
                case Some(ValueExpr(pos: NumberLiteral)) =>
                  if pos.toLong >= a.length then
                    throw IllegalArgumentException(s"Index $pos out of array bounds")
                  a(pos.toInt) match
                    case nm: mutable.HashMap[String, Any] =>
                      setValue(nm, keys.tail, value)
                    case na: ListBuffer[Any] =>
                      setValue(na, keys.tail, value)
                    case _ =>
                      throw IllegalArgumentException("Json Value cannot compute to value")
                case _ => throw IllegalArgumentException("Json Value cannot compute to value")
        case _ => throw IllegalArgumentException("Json Value cannot compute to value")

    val actualValue = value match
      case ValueExpr(n: NumberLiteral) => n
      case ValueExpr(s: String) => s
      case ValueExpr(ValueExpr(_)) =>
        val inner = getValueIfValid(Some(value))
        inner match
          case ValueExpr(n: NumberLiteral) => n
          case ValueExpr(s: String) => s
          case _ => inner
      case _ => value

    val v = Environment.lookupVariable((jsonName, Environment.currentScope))
    v match
      case Some(ValueExpr(m: mutable.HashMap[String, Any])) => setValue(m, keys, actualValue)
      case Some(ValueExpr(m: ListBuffer[Any])) => setValue(m, keys, actualValue)
      case Some(m: mutable.HashMap[String, Any]) => setValue(m, keys, actualValue)
      case Some(m: ListBuffer[Any]) => setValue(m, keys, actualValue)
      case _ => throw IllegalArgumentException("Json Value cannot compute to value")

case class SelectExpr(selectExpr: Expr, caseConds: Map[Expr, Expr], defaultExpr: Option[Expr]) extends Expr :
  override def simplify: Expr = SelectExpr(selectExpr.simplify, caseConds.map(m => m._1.simplify -> m._2.simplify), defaultExpr.map(_.simplify))

  override def checkValid: Boolean = selectExpr.checkValid && caseConds.forall(pair => pair._1.checkValid && pair._2.checkValid) && defaultExpr.forall(_.checkValid)

  override def compute: Option[Expr] = {
    val select = selectExpr.compute
    select match {
      case Some(value: ValueExpr[Any]) => if select.isDefined then
        val which = caseConds.find {
          pair =>
            val caseResult = pair._1.compute
            if caseResult.isDefined && caseResult.get.isInstanceOf[ValueExpr[Any]] then
              if caseResult.get.asInstanceOf[ValueExpr[Any]].value == value.value then
                true
              else false
            else false
        }
        which match
          case Some((_, e2)) => e2.compute
          case None =>
            if defaultExpr.isDefined then defaultExpr.get.compute
            else Some(NoneExpr)
      else
        throw IllegalStateException(s"Error: Expr $selectExpr cannot compute")
      case _ => throw IllegalStateException(s"Error: Expr $selectExpr cannot compute")
    }
  }

  override def toString: String = s"(Select - $selectExpr - $caseConds - $defaultExpr)"

case class LoopExpr(cond: Expr, body: Expr) extends Expr :
  override def simplify: Expr = LoopExpr(cond.simplify, body.simplify)

  override def checkValid: Boolean = cond.checkValid && body.checkValid

  override def compute: Option[Expr] =
    @tailrec
    def _compute(lastResult: Option[Expr]): Option[Expr] =
      getValueIfValid(cond.compute) match
        case ValueExpr(v: Boolean) => if v then _compute(body.compute) else lastResult
        case ValueExpr(v: Double) => if v > 0 then _compute(body.compute) else lastResult
        case ValueExpr(v: Long) => if v > 0 then _compute(body.compute) else lastResult

    _compute(None)

  override def toString: String = s"(Loop - $cond - $body)"

abstract class ScopeExpr(scope: Scope) extends Expr :
  override def simplify: Expr = this

  override def checkValid: Boolean = true

  override def compute: Option[Expr] = None

case class ScopeStartExpr(scope: Scope) extends ScopeExpr(scope) :
  override def toString: String = s"(ScopeStart $scope)"

case class ScopeEndExpr(scope: Scope) extends ScopeExpr(scope) :
  override def toString: String = s"(ScopeEnd $scope)"

case class BlockExpr(start: ScopeStartExpr, end: ScopeEndExpr, expr: Expr) extends Expr :
  override def simplify: BlockExpr = this

  override def checkValid: Boolean = true

  override def compute: Option[Expr] = {
    Environment.enterNewScope(Scope(start.scope.name, Some(Environment.currentScope)))
    val result = expr.compute
    Environment.cleanupVariable()
    Environment.exitScope
    result match
      case Some(ValueExpr(_)) => Some(getValueIfValid(result))
      case _ => result
  }

  override def toString: String = s"(Block $start - $expr - $end)"

case class ImportExpr(filename: String) extends Expr :
  override def simplify: ImportExpr = this

  override def compute: Option[Expr] = Some(this)

  override def checkValid = true

  override def toString: String = s"(Import - $filename)"

case class IteratorExpr[N](obj: mutable.Iterable[N]) extends Expr {
  val iterator: Iterator[N] = obj.iterator
  override def simplify: Expr = this
  override def checkValid = true
  override def toString: String = s"(Iterator - $iterator)"
  override def compute: Option[Expr] = Some(this)
}

case class ErrorExpr(e: Throwable) extends Expr :
  override def simplify: ErrorExpr = this

  override def checkValid: Boolean = true

  override def compute: Option[Expr] = {
    println(e.getMessage)
    Some(NoneExpr)
  }

  override def toString: String = s"(Error ${e.getMessage})"

case class CommentExpr() extends Expr :
  override def simplify: Expr = NoneExpr

  override def checkValid: Boolean = true

  override def compute: Option[Expr] = None

  override def toString: String = s"(Comment)"

case class NoneExpr() extends Expr :
  override def simplify: Expr = this

  override def checkValid: Boolean = true

  override def compute: Option[Expr] = Some(ValueExpr(None))

  override def toString: String = s"(None)"

object CommentExpr extends CommentExpr()

object NoneExpr extends NoneExpr()
