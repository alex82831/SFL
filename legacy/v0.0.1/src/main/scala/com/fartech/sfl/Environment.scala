package com.fartech.sfl

import com.fartech.sfl.{FunDefExpr, FunExpr, VariableExpr}

import java.util.UUID
import scala.annotation.tailrec
import scala.collection.mutable

sealed case class Scope(name: String, parent: Option[Scope])

object Environment {
	def isDefined(name: String): Boolean =
		lookupVariable((name, currentScope)).isDefined && lookupFun((name, currentScope)).isDefined

	private val functions: mutable.Map[(String, Scope), FunDefExpr] = mutable.HashMap[(String, Scope), FunDefExpr]()
	def addNewFunction(fun: (String, Scope), funDef: FunDefExpr): Unit = functions.put(fun, funDef)

	@tailrec def lookupFun(fun: (String, Scope)): Option[FunDefExpr] = {
		if(functions.contains(fun)) {
			Some(functions(fun))
		} else {
			if (fun._2.name != "Global") {
				lookupFun((fun._1, fun._2.parent.get))
			} else None
		}
	}

	val tempFunctions: mutable.Map[Scope, FunDefExpr] = mutable.Map[Scope, FunDefExpr]()

	def addFunction(name: String, args: List[String], scope: Scope, body: () => Expr): FunDefExpr =
		//
		// we need to lookup existing functions first because there may be user defined ones already existed
		//
		lookupFun((name, scope)) match
			case Some(fun: FunDefExpr) => fun
			case _ =>
				//
				// insert temp functions, will be removed in the clean up stage for a function call
				//
				val newDef = FunDefExpr(name, args, scope, body)
				Environment.addNewFunction((name, scope), newDef)
				tempFunctions += scope -> newDef
				newDef

	//
	// Clean up temp functions
	//
	def cleanTempFunctions(scope: Scope): Unit = tempFunctions.filter(_._1 == scope).foreach(f => tempFunctions -= f._1)

	val variables: mutable.Map[(String, Scope), Any] = mutable.HashMap[(String, Scope), Any]()
	def addNewVariable[N >: Any](variable: (String, Scope), value: N): Unit = {
		val av = findActualVariable(variable)
		av match
			case Some(v) => variables.put(v, value)
			case None => variables.put(variable, value)
	}

	private def findActualVariable(variable: (String, Scope)): Option[(String, Scope)] =
		@tailrec def findActualVariable(variable: (String, Scope), parent: String): Option[(String, Scope)] = {
			if variable._2.name != parent then
				if variables.contains(variable) then
					Some(variable)
				else
					if variable._2.name != "Global" then
						findActualVariable((variable._1, variable._2.parent.get), parent)
					else None
			else None
		}
		if variables.contains(variable) then
			Some(variable)
		else
			if variable._2 == Environment.GLOBAL then
				None
			else
				findActualVariable((variable._1, variable._2.parent.get), variable._2.name)

	def lookupVariable[N >: Any](variable: (String, Scope)): Option[N] = {
		val v = findActualVariable(variable)
		v match
			case Some(v) => Some(variables(v))
			case _ => None
	}
	def cleanupVariable(): Unit = Environment.variables.filter(_._1._2 == currentScope).foreach(_ => Environment.variables.remove)

	val GLOBAL: Scope = Scope("Global", None)
	private val scopes: mutable.Stack[Scope] = mutable.Stack[Scope](GLOBAL)
	def enterNewScope: Scope = enterNewScope(UUID.randomUUID().toString)
	def enterNewScope(name: String): Scope = {
		if scopes.exists(_.name == name) then
			throw IllegalStateException(s"scope name $name already exists")
		val scope = Scope(name, if scopes.isEmpty then None else Some(currentScope))
		enterNewScope(scope)
	}
	def enterNewScope(scope: Scope): Scope = {
		scopes.push(scope)
		scope
	}
	def exitScope: Scope = scopes.pop()
	def currentScope: Scope = scopes.top

	def execute(result: Expr): Option[Expr] = {
		val simplified = result.simplify
		if simplified.checkValid then
			simplified.compute
		else
			println("\nprogram check valid failed")
			None
	}
}
