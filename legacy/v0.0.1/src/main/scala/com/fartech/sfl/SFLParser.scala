package com.fartech.sfl

import com.fartech.sfl
import com.fartech.sfl.SFLParser.sflPath
import com.fartech.sfl.{Environment, Scope}

import java.io.File
import java.nio.file.{Files, Paths}
import java.util.UUID
import scala.::
import scala.annotation.{tailrec, targetName}
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.compiletime.ops.float
import scala.io.Source
import scala.language.postfixOps
import scala.sys.env
import scala.util.control.Exception.*
import scala.util.parsing.combinator.JavaTokenParsers
import scala.util.parsing.input.Reader
import scala.util.{Failure, Random, Success, Try}

case class ParseException(msg: String, line: String) extends IllegalStateException(msg)

case class SFLParser(line: String) extends JavaTokenParsers {

  //
  // SFL Reserved Keywords
  //
  private val keywords = Set(
    "if", "elsif", "else", "def", "while",
    "select", "case", "var", "val",
    "default", "import", "return"
  )

  //
  // Numbers
  //
  def number: Parser[NumberLiteral] = floatingPointNumber ^^ {
    f => {
      val f1 = f.toDouble
      val f2 = f1.longValue
      if f1 == f2 then
        if !f.contains(".") then IntegerLiteral(f.toLong)
        else FloatLiteral(f.toDouble)
      else
        FloatLiteral(f.toDouble)
    }
  }

  def funNames: Parser[String] = ident

  //
  // left brace in the block expr is very important. it will generate a new scope
  //
  private def leftBrace =
    "{" ^^ (_ => ScopeStartExpr(Environment.enterNewScope))

  private def rightBrace =
    "}" ^^ (_ => ScopeEndExpr(Environment.exitScope))

  private def comment =
    "(/\\*([^*]|[\\r\\n]|(\\*+([^*/]|[\\r\\n])))*\\*+/)|(//.*)".r ^^ {
      _ => CommentExpr
    }

  //
  // TODO: Support val
  //
  def variable[N >: Any](str: String, immutable: Boolean): VariableExpr[N] = {
    if keywords.contains(str) then
      err(s"Error - use keyword $str as variable")
      throw IllegalStateException(s"Error - use keyword $str as variable")
    else if Environment.isDefined(str) then
      throw ParseException(s"variable name $str already exists", line)
    else
      VariableExpr(str, immutable)
  }

  def sExp: Parser[Expr] =
    lambda | mapValue ~ call ^^ {
      case nameExpr ~ list => NestedCallExpr(nameExpr, list)
    } | mapValue | jsonObject | jsonArr
      | (number | stringLiteral | ident) ^^ {
      case "true" => ValueExpr(true)
      case "false" => ValueExpr(false)
      case str if str.isInstanceOf[String] =>
        if str.asInstanceOf[String].startsWith("\"") then
          ValueExpr(str.asInstanceOf[String].replace("\"", "").replace("\\n", "\n"))
        else
          variable(str.asInstanceOf[String], true)
      case num: NumberLiteral => ValueExpr(num)
      case pattern => throw ParseException(s"sExp -> $pattern", line)
    }

  def exp: Parser[Expr] =
    term ~ rep("+" ~ term | "-" ~ term) ^^ {
      case expr ~ extraOps =>
        if extraOps.isEmpty then
          expr
        else
          ArithmeticExpr(expr, extraOps.map(op => (op._1, op._2)))
    }

  def term: Parser[Expr] = factor ~ rep("*" ~ factor | "/" ~ factor | "%" ~ factor) ^^ {
    case expr ~ extraOps =>
      if extraOps.isEmpty then
        expr
      else
        ArithmeticExpr(expr, extraOps.map(op => (op._1, op._2)))
  }

  def factor: Parser[Expr] =
    funCall
      | sExp
      | "(" ~ exp ~ ")" ^^ { case _ ~ expr ~ _ => expr }

  def logic: Parser[Expr] = (compare | funCall | jsonValue | exp) ~ rep("&&" ~ (compare | funCall | jsonValue | exp) | "||" ~ (compare | funCall | jsonValue | exp)) ^^ {
    case expr ~ extraOps =>
      if extraOps.isEmpty then
        expr
      else
        LogicExpr(expr, extraOps.map(op => (op._1, op._2)))
  }

  def compare: Parser[CmpExpr] =
    (exp ~ ("==" | ">=" | ">" | "<=" | "<") ~ exp) ^^ {
      case op1 ~ method ~ op2 => CmpExpr(method, op1, op2)
    }

  def call: Parser[List[List[Expr]]] =
    def argList = "(" ~> repsep(exp, ",") <~ ")"

    argList ~ opt(rep(argList)) ^^ {
      case list ~ Some(listOfList) => list :: listOfList
      case list ~ None => List(list)
    }

  def funCall: Parser[NestedCallExpr] =
    ident ~ call ^^ {
      case fn ~ argList =>
        NestedCallExpr(
          variable(fn, true),
          argList
        )
    }

  def funDef: Parser[FunDefExpr] =
    def makeNewFun(name: String, args: List[String], body: () => Expr): FunDefExpr =
      val newFun = FunDefExpr(name, args, Environment.currentScope, body)
      Environment.addNewFunction((name, Environment.currentScope), newFun)
      newFun

    def argumentList = "(" ~> repsep(ident, ",") <~ ")"

    def newFun(fn: String, arg1: List[String], listOfArg: List[List[String]], expr: BlockExpr) = {
      val args = arg1 ::: listOfArg.foldLeft(List.empty[String]) { (prev, next) => prev ::: next }
      makeNewFun(fn, args, () => {
        expr
      })
    }

    //
    // The curry of function definition is fake in our language.
    // We just simply put all arguments into one list. There's no
    // temp or middle function generated in SFL
    //
    "def" ~> ident ~ argumentList ~ opt(rep(argumentList)) ~ block ^^ {
      case fn ~ arg1 ~ Some(listOfArg) ~ expr => newFun(fn, arg1, listOfArg, expr)
      case fn ~ args ~ None ~ expr => makeNewFun(fn, args, () => { expr })
    } | "def" ~> ident ~ argumentList ~ opt(rep(argumentList)) ~ "=" ~ statement ^^ {
      case fn ~ arg1 ~ Some(listOfArg) ~ _ ~ expr =>
        val scope = Scope(UUID.randomUUID().toString, Some(Environment.currentScope))
        newFun(fn, arg1, listOfArg,
          BlockExpr(
            ScopeStartExpr(scope),
            ScopeEndExpr(scope),
            expr
          )
      )
      case fn ~ args ~ None ~ _ ~ expr => makeNewFun(fn, args, () => { expr })
    }

  def block: Parser[BlockExpr] =
    leftBrace ~ program ~ rightBrace ^^ {
      case start ~ expr ~ end => BlockExpr(start, end, expr)
    }

  def returnStatement: Parser[Expr] =
    "return" ~> exp ^^ (expr => ReturnExpr(expr))
    | "return" ~ "(" ~> exp <~ ")" ^^ (expr => ReturnExpr(ValueExpr(expr)))
    | "return" ^^ (_ => ReturnExpr(ValueExpr(None)))

  def equalExp[N >: Any]: Parser[EqualExpr[N]] =
    def handleEqual[N >: Any](left: (String, Boolean), right: Expr): EqualExpr[N] = EqualExpr(variable(left._1, left._2), right)

    def equalRight: Parser[Expr] =
      "(" ~> logic <~ ")" | (ifExpr | selectExpr | whileCond | exp)

    leftMapValue ~ "=" ~ equalRight ^^ {
      case lExpr ~ _ ~ rExpr => EqualExpr(lExpr, rExpr)
    } | opt("val" | "var") ~ (ident ~ "=" ~ equalRight) ^^ {
      case attr ~ (varName ~ _ ~ expr) => handleEqual((varName, if attr.isDefined then attr.get == "val" else false), expr)
    } | ident ~ ("+"|"-"|"*"|"/"|"%") ~ "=" ~ (ifExpr | selectExpr | whileCond | exp) ^^ {
      case varName ~ sym ~ _ ~ expr => handleEqual((varName, true), ArithmeticExpr(variable(varName, true), List((sym, expr))))
    }

  def thenExp: Parser[Expr] =
    block
      | "then" ~ exp ^^ { case _ ~ exp => exp }
      | "then" ~ block ^^ { case _ ~ block => block }

  def ifCond: Parser[CondExpr] =
    "if" ~ "(" ~ logic ~ ")" ~ thenExp ^^ {
      case _ ~ _ ~ expr1 ~ _ ~ expr2 => CondExpr(Map(expr1 -> expr2), None)
    } | "if" ~ logic ~ thenExp ^^ {
      case _ ~ expr1 ~ expr2 => CondExpr(Map(expr1 -> expr2), None)
    }

  def elseIfCond: Parser[CondExpr] =
    rep("elsif" ~ "(" ~> logic ~ ")" ~ thenExp) ^^ {
      exprList => {
        CondExpr(
          exprList.foldLeft(Map.empty[Expr, Expr]) { (m, exprPair) => m + (exprPair._1._1 -> exprPair._2) },
          None
        )
      }
    } | rep("elsif" ~> logic ~ thenExp) ^^ {
      exprList => {
        CondExpr(
          exprList.foldLeft(Map.empty[Expr, Expr]) { (m, exprPair) => m + (exprPair._1 -> exprPair._2) },
          None
        )
      }
    }

  def elseCond: Parser[CondExpr] =
    "else" ~ "(" ~> (thenExp | exp) <~ ")" ^^ (expr => CondExpr(Map(), Some(expr)))
    | "else" ~> (thenExp | exp) ^^ (expr => CondExpr(Map(), Some(expr)))

  def ifExpr: Parser[CondExpr] =
    ifCond ~ elseIfCond ~ elseCond ^^ { case CondExpr(m1, _) ~ CondExpr(m2, _) ~ CondExpr(_, expr3) => CondExpr(m1 ++ m2, expr3) }
      | ifCond ~ elseIfCond ^^ { case CondExpr(m1, _) ~ CondExpr(m2, _) => CondExpr(m1 ++ m2, None) }
      | ifCond ~ elseCond ^^ { case CondExpr(m1, _) ~ CondExpr(_, exp) => CondExpr(m1, exp) }
      | ifCond

  def whileCond: Parser[LoopExpr] =
    "while" ~ "(" ~> compare ~ ")" ~ block ^^ {
      case expr ~ _ ~ expr1 => LoopExpr(expr, expr1)
    } | "while" ~> compare ~ block ^^ {
      case expr ~ expr1 => LoopExpr(expr, expr1)
    }

  def selectExpr: Parser[SelectExpr] =
    def commonPattern = "select" ~ "(" ~ exp ~ ")" ~ "{" ~ rep("case" ~ (sExp | funCall) ~ ":" ~ exp)

    def getCaseListExpr(caseList: List[String ~ Expr ~ String ~ Expr]) = caseList.foldLeft(Map.empty[Expr, Expr]) { (mid, item) => mid + (item._1._1._2 -> item._2) }

    commonPattern ~ "}" ^^ {
      case _ ~ _ ~ expr ~ _ ~ _ ~ caseList ~ _ =>
        SelectExpr(expr, getCaseListExpr(caseList), None)
    } | commonPattern ~ "default" ~ ":" ~ exp ~ "}" ^^ {
      case _ ~ _ ~ expr ~ _ ~ _ ~ caseList ~ _ ~ _ ~ defaultExpr ~ _ =>
        SelectExpr(expr, getCaseListExpr(caseList), Some(defaultExpr))
    }

  def importExpr: Parser[Expr] =
    "import" ~ stringLiteral ^^ {
      case _ ~ file =>
        val filename = file.replace("\"", "")
        val sflName = if filename.contains(".sfl") then filename else s"$filename.sfl"
        var fullPathName = sflName
        if !FileUtils.exists(fullPathName) then
          fullPathName = new File(new File(SFLParser.sflPath, "libs"), sflName).getAbsolutePath
        val result = parseAll(program, FileUtils.readFile(fullPathName))
        if result.successful then
          result.get
        else
          NoneExpr
    }

  private def mapKey: Parser[Expr] =
    funCall
      | ident ^^ { s => variable(s, false) }
      | wholeNumber ^^ { n => ValueExpr(n.toLong) }
      | stringLiteral ^^ { s => ValueExpr(s.replace("\"", "")) }

  private def consListExpr(firstKey: Expr, list: List[Expr]) =
    firstKey :: list.foldLeft(List.empty[Expr]) { (list, expr) => list ::: List(expr) }

  def mapValue: Parser[JsonValueExpr] =
    ident ~ "[" ~ mapKey ~ "]" ~ opt(rep("[" ~> mapKey <~ "]")) ^^ {
      case varName ~ _ ~ firstKey ~ _ ~ None =>
        JsonValueExpr(
          varName,
          List(firstKey)
        )
      case varName ~ _ ~ firstKey ~ _ ~ Some(list) =>
        JsonValueExpr(
          varName,
          consListExpr(firstKey, list)
        )
    }

  def leftMapValue: Parser[JsonLeftValueExpr] =
    ident ~ "[" ~ mapKey ~ "]" ~ opt(rep("[" ~> mapKey <~ "]")) ^^ {
      case varName ~ _ ~ firstKey ~ _ ~ None =>
        JsonLeftValueExpr(
          varName,
          List(firstKey)
        )
      case varName ~ _ ~ firstKey ~ _ ~ Some(list) =>
        JsonLeftValueExpr(
          varName,
          consListExpr(firstKey, list)
        )
    }

  def statement: Parser[Expr] =
    comment
      | returnStatement ~ opt(";") ^^ { case expr ~ _ => expr }
      | importExpr ~ opt(";") ^^ { case expr ~ _ => expr }
      | lambda ~ opt(";") ^^ { case expr ~ _ => expr }
      | funDef ~ opt(";") ^^ { case expr ~ _ => expr }
      | equalExp ~ opt(";") ^^ { case expr ~ _ => expr }
      | ifExpr ~ opt(";") ^^ { case expr ~ _ => expr }
      | whileCond ~ opt(";") ^^ { case expr ~ _ => expr }
      | selectExpr ~ opt(";") ^^ { case expr ~ _ => expr }
      | logic ~ opt(";") ^^ { case expr ~ _ => expr }
      | compare ~ opt(";") ^^ { case expr ~ _ => expr }
      | block ~ opt(";") ^^ { case expr ~ _ => expr }
      | exp ~ opt(";") ^^ { case expr ~ _ => expr }

  def lambda: Parser[FunDefExpr] =
    def argsList = "(" ~ repsep(ident, ",") ~ ")" ~ "->" ^^ {
      case _ ~ list ~ _ ~ _ => list
    }

    def lambdaFun(args: List[String], body: Option[BodyExpr], scope: Scope) =
      FunDefExpr(
        s"lambda_${UUID.randomUUID()}",
        args,
        scope,
        if body.isEmpty then { () => BodyExpr(List.empty[Expr]) } else { () => body.get }
      )

    leftBrace ~ argsList ~ opt(program) ~ rightBrace ^^ {
      case start ~ list ~ body ~ _ => lambdaFun(list, body, start.scope)
    } | argsList ~ opt(program) ^^ {
      case list ~ body => lambdaFun(list, body, Scope(UUID.randomUUID().toString, Some(Environment.currentScope)))
    }

  def program: Parser[BodyExpr] =
    rep(statement) ^^ (exprs => BodyExpr(exprs))

  def jsonObject: Parser[JsonObjExpr] =
    "{" ~> repsep(jsonMember, ",") <~ "}" ^^ (p => JsonObjExpr(mutable.HashMap[String, Expr]() ++ p))

  def jsonArr: Parser[JsonArrayExpr] =
    "[" ~> repsep(jsonValue, ",") <~ "]" ^^ (list => JsonArrayExpr(ListBuffer[Expr]() ++ list))

  def jsonMember: Parser[(String, Expr)] =
    stringLiteral ~ ":" ~ jsonValue ^^ {
      case name ~ ":" ~ expr => (name.replace("\"", ""), expr)
      case _ => throw IllegalArgumentException("illegal json format")
    }

  def jsonValue: Parser[Expr] =
    exp
      | funCall
      | jsonObject
      | jsonArr
      | mapValue ~ call ^^ { case nameExpr ~ list => NestedCallExpr(nameExpr, list) }
      | mapValue
      | "null" ^^ (_ => ValueExpr(null))
      | "true" ^^ (_ => ValueExpr(true))
      | "false" ^^ (_ => ValueExpr(true))
      | stringLiteral ^^ (s => ValueExpr(s.replace("\"", "")))
      | number ^^ (f => ValueExpr(f))

  def parse: ParseResult[Expr] = parseAll(program, line)

  def parseByLine(f: (Expr, Input) => Unit)(err: (String, Input) => Unit): Unit =
    @tailrec def handleResult(result: ParseResult[Expr])(f: (Expr, Input) => Unit)(err: (String, Input) => Unit): Unit =
      result match
        case Success(expr: Expr, next: Input) =>
          f(expr, next)
          if !next.atEnd then handleResult(parse(statement, next))(f)(err)
        case Failure(msg: String, next: Input) =>
          err(msg, next)
        case Error(msg: String, next: Input) =>
          err(msg, next)

    handleResult(parse(statement, line))(f)(err)
}

object SFLParser {
  def parse(filename: String): Option[Expr] = {
    val parser = SFLParser(Files.readString(Paths.get(filename)))
    allCatch.opt {
      val parsed = parser.parse
      if !parsed.successful then ErrorExpr(IllegalStateException(parsed.toString)) else parsed.get
    }
  }
  var sflPath: String = sys.env.getOrElse("SFL_HOME", "libs")
}
