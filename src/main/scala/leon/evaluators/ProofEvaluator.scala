
package leon
package evaluators

import purescala.Common._
import purescala.Constructors._
import purescala.Expressions._
import purescala.Definitions._
import purescala.Types._

import leon.verification.VerificationContext
import leon.verification.theorem._

import leon.utils.DebugSection
import leon.utils.DebugSectionProof

/** Evaluator of proof functions.
  *
  * Adds support for all "magic" functions defined in the leon.theorem library.
  */
class ProofEvaluator(ctx: VerificationContext, prog: Program)
  extends DefaultEvaluator(ctx, prog) {

  // Set of verification conditions generated during evaluations.
  private var vcs: Seq[Expr] = Seq()

  private implicit val debugSection: DebugSection = DebugSectionProof
  private val library = new Library(prog)
  private val encoder = new ExprEncoder(ctx)

  override protected[evaluators] def e(expr: Expr)(implicit rctx: RC, gctx: GC): Expr = expr match {
    // Invocation of prove.
    case FunctionInvocation(TypedFunDef(fd, Seq()), Seq(arg)) if (fd == library.prove.get) => {
      ctx.reporter.debug("Called prove.")
      val evaluatedArg = e(arg).setPos(expr)
      vcs = vcs :+ evaluatedArg
      super.e(FunctionInvocation(TypedFunDef(fd, Seq()), Seq(evaluatedArg)))
    }
    // Invocation of fresh.
    case FunctionInvocation(TypedFunDef(fd, Seq()), Seq(arg)) if (fd == library.fresh.get) => {
      ctx.reporter.debug("Called fresh.")
      val StringLiteral(name) = e(arg)
      val freshName = FreshIdentifier(name, Untyped, true).uniqueName
      encoder.caseClass(library.Identifier, StringLiteral(freshName))
    }
    // Invocation of getType.
    case FunctionInvocation(TypedFunDef(fd, Seq(tp)), Seq()) if (fd == library.getType.get) => {
      ctx.reporter.debug("Called getType.")
      encoder.caseClass(library.Type, StringLiteral(encoder.encodeType(tp)))
    }
    // Invocation of contract.
    case FunctionInvocation(TypedFunDef(fd, Seq()), Seq(arg)) if (fd == library.contract.get) => {
      ctx.reporter.debug("Called contract.")
      val StringLiteral(name) = e(arg)
      val fd = ctx.program.lookupFunDef(name).getOrElse {
        ctx.reporter.fatalError("Called contract on unknown function: " + name)
      }
      val expr = Forall(fd.params, implies(fd.precOrTrue, application(fd.postcondition.get, Seq(fd.body.get))))
      val contract = encoder.caseClass(library.Theorem, encoder.encodeExpr(expr, Map()))
      ctx.reporter.debug("Generated contract for " + fd.qualifiedName(ctx.program) + ": " + contract)
      contract
    }
    // Invocation of toTheorem.
    case FunctionInvocation(TypedFunDef(fd, Seq()), Seq(arg)) if (fd == library.toTheorem.get) => {
      ctx.reporter.debug("Called toTheorem.")
      val evaluatedArg = e(arg)
      encoder.caseClass(library.Theorem, evaluatedArg)
    }
    // Any other expressions.
    case _ => super.e(expr)
  }

  /** Pops the list of verification condition expressions generated. */
  def popVCExprs: Seq[Expr] = { 
    val ret = vcs
    vcs = Seq()
    ret
  }
}