package com.fartech.sfl

import scala.annotation.targetName

sealed trait NumberLiteral :
	type N
	def toLong: Long
	def toInt: Int
	def toDouble: Double
	@targetName("add") def +(that: NumberLiteral): NumberLiteral =
		(this, that) match
			case (d1: IntegerLiteral, d2: IntegerLiteral) => IntegerLiteral(d1.value + d2.value)
			case (d1: IntegerLiteral, d2: FloatLiteral) => FloatLiteral(d1.value + d2.value)
			case (d1: FloatLiteral, d2: IntegerLiteral) => FloatLiteral(d1.value + d2.value)
			case (d1: FloatLiteral, d2: FloatLiteral) => FloatLiteral(d1.value + d2.value)
	@targetName("minus") def -(that: NumberLiteral): NumberLiteral =
		(this, that) match
			case (d1: IntegerLiteral, d2: IntegerLiteral) => IntegerLiteral(d1.value - d2.value)
			case (d1: IntegerLiteral, d2: FloatLiteral) => FloatLiteral(d1.value - d2.value)
			case (d1: FloatLiteral, d2: IntegerLiteral) => FloatLiteral(d1.value - d2.value)
			case (d1: FloatLiteral, d2: FloatLiteral) => FloatLiteral(d1.value - d2.value)
	@targetName("product") def *(that: NumberLiteral): NumberLiteral =
		(this, that) match
			case (d1: IntegerLiteral, d2: IntegerLiteral) => IntegerLiteral(d1.value * d2.value)
			case (d1: IntegerLiteral, d2: FloatLiteral) => FloatLiteral(d1.value * d2.value)
			case (d1: FloatLiteral, d2: IntegerLiteral) => FloatLiteral(d1.value * d2.value)
			case (d1: FloatLiteral, d2: FloatLiteral) => FloatLiteral(d1.value * d2.value)
	@targetName("divide") def /(that: NumberLiteral): NumberLiteral =
		(this, that) match
			case (d1: IntegerLiteral, d2: IntegerLiteral) => IntegerLiteral(d1.value / d2.value)
			case (d1: IntegerLiteral, d2: FloatLiteral) => FloatLiteral(d1.value / d2.value)
			case (d1: FloatLiteral, d2: IntegerLiteral) => FloatLiteral(d1.value / d2.value)
			case (d1: FloatLiteral, d2: FloatLiteral) => FloatLiteral(d1.value / d2.value)
	@targetName("mod") def %(that: NumberLiteral): NumberLiteral =
		(this, that) match
			case (d1: IntegerLiteral, d2: IntegerLiteral) => IntegerLiteral(d1.value % d2.value)
			case (d1: IntegerLiteral, d2: FloatLiteral) => FloatLiteral(d1.value % d2.value)
			case (d1: FloatLiteral, d2: IntegerLiteral) => FloatLiteral(d1.value % d2.value)
			case (d1: FloatLiteral, d2: FloatLiteral) => FloatLiteral(d1.value % d2.value)
	@targetName("greater") def >(that: NumberLiteral): Boolean =
		(this, that) match
			case (d1: IntegerLiteral, d2: IntegerLiteral) => d1.value > d2.value
			case (d1: IntegerLiteral, d2: FloatLiteral) => d1.value > d2.value
			case (d1: FloatLiteral, d2: IntegerLiteral) => d1.value > d2.value
			case (d1: FloatLiteral, d2: FloatLiteral) => d1.value > d2.value
	@targetName("greaterOrEqual") def >=(that: NumberLiteral): Boolean =
		(this, that) match
			case (d1: IntegerLiteral, d2: IntegerLiteral) => d1.value >= d2.value
			case (d1: IntegerLiteral, d2: FloatLiteral) => d1.value >= d2.value
			case (d1: FloatLiteral, d2: IntegerLiteral) => d1.value >= d2.value
			case (d1: FloatLiteral, d2: FloatLiteral) => d1.value >= d2.value
	@targetName("equals") def ==(that: NumberLiteral): Boolean =
		(this, that) match
			case (d1: IntegerLiteral, d2: IntegerLiteral) => d1.value == d2.value
			case (d1: IntegerLiteral, d2: FloatLiteral) => d1.value == d2.value
			case (d1: FloatLiteral, d2: IntegerLiteral) => d1.value == d2.value
			case (d1: FloatLiteral, d2: FloatLiteral) => d1.value == d2.value
	@targetName("less") def <(that: NumberLiteral): Boolean =
		(this, that) match
			case (d1: IntegerLiteral, d2: IntegerLiteral) => d1.value < d2.value
			case (d1: IntegerLiteral, d2: FloatLiteral) => d1.value < d2.value
			case (d1: FloatLiteral, d2: IntegerLiteral) => d1.value < d2.value
			case (d1: FloatLiteral, d2: FloatLiteral) => d1.value < d2.value
	@targetName("lessOrEqual") def <=(that: NumberLiteral): Boolean =
		(this, that) match
			case (d1: IntegerLiteral, d2: IntegerLiteral) => d1.value <= d2.value
			case (d1: IntegerLiteral, d2: FloatLiteral) => d1.value <= d2.value
			case (d1: FloatLiteral, d2: IntegerLiteral) => d1.value <= d2.value
			case (d1: FloatLiteral, d2: FloatLiteral) => d1.value <= d2.value

sealed case class IntegerLiteral(value: Long) extends NumberLiteral:
	type N = Long
	def getValue: Long = value
	override def toLong = value
	override def toInt = value.toInt
	override def toDouble = value.toDouble
	override def toString: String = value.toString

sealed case class FloatLiteral(value: Double) extends NumberLiteral:
	type N = Double
	def getValue: Double = value
	override def toLong = value.longValue
	override def toInt = value.toInt
	override def toDouble = value

	override def toString: String = value.toString
