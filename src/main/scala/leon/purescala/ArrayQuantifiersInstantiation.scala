/* Copyright 2009-2015 EPFL, Lausanne */

package leon
package purescala

import Common._
import Definitions._
import Expressions._
import Extractors._
import ExprOps._
import Constructors._
import TreeNormalizations._

object ArrayQuantifiersInstantiation extends TransformationPhase {

  val name = "Array Quantifiers Instantiation"
  val description = "Instantiate array quantifiers"

  def apply(ctx: LeonContext, program: Program): Program = {
    program
  }


  def instantiate(expr: Expr): Expr = {
    val nnfForm = nnf(expr)

    //TODO
    val noUpdated = nnfForm

    //TODO remove existential quantification
    val noExistential = noUpdated

    val indexSet = 
      collect[Expr](e => e match {
        case ArraySelect(_, i) => Set(i)
        case _ => Set()
      })(noExistential) ++
      collect[Expr](e => e match {
        case ArrayForall(_, from, to, _) => Set(from, to)
        case _ => Set()
      })(noExistential)

    val noForall = simplePostTransform(e => e match {
      case ArrayForall(arr, from, to, Lambda(Seq(el), body)) => {
        val clauses: Set[Expr] = indexSet.map(e => {
          Implies(
            And(LessEquals(from, e), LessThan(e, to)),
            replace(Map(el.id.toVariable -> ArraySelect(arr, e)), body)
          )
        })
        And(clauses.toSeq)
      }
      case e => e
    })(noExistential)


    println("no existential: " + noExistential)
    println("index set: " + indexSet)
    println("no forall: " + noForall)

    noForall
  }

}
