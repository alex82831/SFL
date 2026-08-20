package com.fartech.sfl

import java.io.File
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.io.Source

sealed class Utils

object FileUtils {
  def readFile(filename: String): String = {
    val source = Source.fromFile(filename)
    val src = source.mkString
    source.close()
    src
  }

  def exists(filename: String): Boolean = new File(filename).exists
}

object JsonUtils {
  def jsonString(m: mutable.HashMap[String, Any]): String = {
    val sb = mutable.StringBuilder()
    sb.append("{")
    val l = m.toList
    for (i <- l.indices) {
      val vstr =
        l(i)._2 match
          case s: String => s"\"$s\""
          case l: ListBuffer[Any] => jsonArrayString(l)
          case nm: mutable.HashMap[String, Any] => jsonString(nm)
          case _ => l(i)._2.toString
      sb.append(s"\"${l(i)._1}\": $vstr")
      if i < l.length - 1 then sb.append(s", ")
    }
    sb.append("}")
    sb.toString
  }

  def jsonArrayString(a: ListBuffer[Any]): String =
    val sb = mutable.StringBuilder()
    sb.append("[")
    for (i <- a.indices) {
      val vstr =
        a(i) match
          case s: String => s"\"$s\""
          case l: ListBuffer[Any] => jsonArrayString(l)
          case nm: mutable.HashMap[String, Any] => jsonString(nm)
          case _ => a(i).toString
      sb.append(vstr)
      if i < a.length - 1 then sb.append(",")
    }
    sb.append("]")
    sb.toString
}