import com.fartech.sfl.*
import com.sun.tools.attach.VirtualMachine.list


import java.nio.file.{Files, Paths}
import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import scala.compiletime.ops.string
import scala.io.StdIn.readLine
import scala.sys.exit

object Main {
  def main(args: Array[String]): Unit = {
    println(Console.RED + "SFL " + Console.RESET + "- " + Console.BLUE + "Scripting for Fun " + Console.RED)
    println("SFL is a Functional Script Programming Language. Designed & Implemented by Alex Xin" + Console.RESET)
    println(Console.GREEN + "Version: 0.0.1-Alpha Build 20220524 Technical Preview\nalex82831gm@gmail.com" + Console.RESET)
    val option = nextOption(args.toList)
    if option.contains("repl") then
      repl(option)
    else
      if !option.contains("source") then
        println("Missing source code")
      else
        parseSource(option) match
          case Some(parsed) =>
            val body = BodyExpr(parsed)
            Environment.execute(PreDefs.add(body)) match
              case None => println("Program cannot compute to value")
              case _ =>
          case None =>
            println(Console.RED + "Unknown option" + Console.RESET)
  }

  private def repl(option: Map[String, Any]): Unit = {
    PreDefs.addToEnv()
    println("System Ready. Please input expr and press enter to compute:")
    while(true) {
      print(Console.RED + "SFL-> " + Console.RESET)
      val line = readLine()
      try {
        SFLParser(line).parseByLine(
          (expr, _) => {
            if option.contains("tasty") then println(expr)
            val result = expr.compute
            result match
              case Some(e: Expr) => println(e)
              case None => println("None")
          }
        )(
          (msg, _) => {
            println(Console.RED + msg + Console.RESET)
          }
        )
      } catch {
        case e: Throwable =>
          println(Console.RED + e.getMessage + Console.RESET)
      }
    }
  }

  def parseSource(option: Map[String, Any]): Option[List[Expr]] =
    val list = ListBuffer.empty[Expr]
    var success = true
    SFLParser(FileUtils.readFile(option("source").asInstanceOf[String])).parseByLine {
      (expr, _) =>
        if option.contains("tasty") then println(expr)
        list += expr
    } {
      (msg, _) =>
        println(msg + "\n")
        success = false
    }
    if success then
      Some(list.toList)
    else
      None

  private def nextOption(list: List[String]) : Map[String, Any] = {
    @tailrec
    def nextOption(map: Map[String, Any], list: List[String]): Map[String, Any] =
      list match {
        case Nil => map
        case "-source" :: value :: tail =>
          nextOption(map + ("source" -> value), tail)
        case "-repl" :: tail =>
          nextOption(map + ("repl" -> true), tail)
        case "-tasty" :: tail =>
          nextOption(map + ("tasty" -> true), tail)
        case _ =>
          println(Console.RED + "Unknown option" + Console.RESET)
          exit(-1)
      }
    nextOption(Map.empty, list)
  }
}
