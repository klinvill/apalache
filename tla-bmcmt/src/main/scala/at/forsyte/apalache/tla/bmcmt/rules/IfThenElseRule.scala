package at.forsyte.apalache.tla.bmcmt.rules

import at.forsyte.apalache.tla.bmcmt._
import at.forsyte.apalache.tla.bmcmt.implicitConversions._
import at.forsyte.apalache.tla.bmcmt.rules.aux.CherryPick
import at.forsyte.apalache.tla.bmcmt.types._
import at.forsyte.apalache.tla.lir.control.TlaControlOper
import at.forsyte.apalache.tla.lir.convenience._
import at.forsyte.apalache.tla.lir.{NameEx, OperEx, TlaEx}

/**
  * Implements the rules: SE-ITE[1-6].
  *
  * @author Igor Konnov
  */
class IfThenElseRule(rewriter: SymbStateRewriter) extends RewritingRule {
  private val pickFrom = new CherryPick(rewriter)

  override def isApplicable(symbState: SymbState): Boolean = {
    symbState.ex match {
      case OperEx(TlaControlOper.ifThenElse, _, _, _) => true
      case _ => false
    }
  }

  override def apply(state: SymbState): SymbState = {
    state.ex match {
      case OperEx(TlaControlOper.ifThenElse, predEx, thenEx, elseEx)
          if state.theory == BoolTheory() =>
        // the result is expected to be Boolean => we directly work at the propositional level
        val predState = rewriter.rewriteUntilDone(state.setTheory(BoolTheory()).setRex(predEx))
        val thenState = rewriter.rewriteUntilDone(predState.setTheory(BoolTheory()).setRex(thenEx))
        val elseState = rewriter.rewriteUntilDone(thenState.setTheory(BoolTheory()).setRex(elseEx))
        val result = rewriter.solverContext.introBoolConst()
        val iffIte = tla.equiv(NameEx(result), tla.ite(predState.ex, thenState.ex, elseState.ex))
        rewriter.solverContext.assertGroundExpr(iffIte)
        if (rewriter.introFailures) {
          coverFailurePredicates(predState, thenState, elseState)
        }
        elseState.setRex(NameEx(result)).setTheory(BoolTheory())

      case OperEx(TlaControlOper.ifThenElse, predEx, thenEx, elseEx) =>
        // in the general case, the both branches return cells
        val predState = rewriter.rewriteUntilDone(state.setTheory(BoolTheory()).setRex(predEx))
        val thenState = rewriter.rewriteUntilDone(predState.setTheory(CellTheory()).setRex(thenEx))
        val elseState = rewriter.rewriteUntilDone(thenState.setTheory(CellTheory()).setRex(elseEx))
        if (rewriter.introFailures) {
          coverFailurePredicates(predState, thenState, elseState)
        }
        val thenCell = elseState.arena.findCellByNameEx(thenState.ex)
        val elseCell = elseState.arena.findCellByNameEx(elseState.ex)
        val pred = predState.ex
        val resultType = rewriter.typeFinder.compute(state.ex, BoolT(), thenCell.cellType, elseCell.cellType)
        resultType match {
          // ITE[1-4]
          case BoolT() | IntT() | ConstT() =>
            val finalState = iteBasic(elseState, resultType, pred, thenCell, elseCell)
            rewriter.coerce(finalState, state.theory) // coerce to the source theory

          // ITE5
          case FinSetT(_) =>
            val finalState = iteSet(elseState, resultType, pred, thenCell, elseCell)
            rewriter.coerce(finalState, state.theory) // coerce to the source theory

          // ITE6
          case _ =>
            val finalState = iteGeneral(elseState, resultType, pred, thenCell, elseCell)
            rewriter.coerce(finalState, state.theory) // coerce to the source theory
        }

      case _ =>
        throw new RewriterException("%s is not applicable".format(getClass.getSimpleName))
    }
  }

  /**
    * <p>This function adds the constraints that allow us to properly treat side effects such as Assert(..).
    * It essentially says that the failure predicates generated for each branch can be only activated,
    * if the branch condition is satisfied. Without this condition the expressions such as
    * "IF e \in DOMAIN f THEN f[e] ELSE default" would report a false runtime error.</p>
    *
    * TODO: This method generates an enormous number of constraints on large benchmarks. Find a better solution.
    *
    * @param predState the state after rewriting the condition
    * @param thenState the state after rewriting the then branch
    * @param elseState the state after rewriting the else branch
    */
  private def coverFailurePredicates(predState: SymbState, thenState: SymbState, elseState: SymbState): Unit = {
    // XXX: future self, the operations on the maps and sets are probably expensive. Optimize.
    val predsBefore = Set(predState.arena.findCellsByType(FailPredT()) :_*)
    val thenPreds = Set(thenState.arena.findCellsByType(FailPredT()) :_*) -- predsBefore
    val elsePreds = Set(elseState.arena.findCellsByType(FailPredT()) :_*) -- thenPreds
    val cond = predState.ex
    // for each failure fp on the then branch, fp => cond
    thenPreds.foreach(fp => rewriter.solverContext.assertGroundExpr(tla.or(tla.not(fp), cond)))
    // for each failure fp on the else branch, fp => ~cond
    elsePreds.foreach(fp => rewriter.solverContext.assertGroundExpr(tla.or(tla.not(fp), tla.not(cond))))
  }

  private def iteBasic(state: SymbState, commonType: CellT, pred: TlaEx, thenCell: ArenaCell, elseCell: ArenaCell) = {
    val newArena = state.arena.appendCell(commonType)
    val newCell = newArena.topCell
    // it's OK to use the SMT equality and ite, as we are dealing with the basic types here
    val iffIte = tla.eql(newCell, tla.ite(pred, thenCell, elseCell))
    rewriter.solverContext.assertGroundExpr(iffIte)
    state.setArena(newArena).setRex(newCell.toNameEx)
  }

  // TODO: why don't we use iteGeneral instead?
  private def iteSet(state: SymbState, commonType: CellT, pred: TlaEx, thenCell: ArenaCell, elseCell: ArenaCell) = {
    var newArena = state.arena.appendCell(commonType)
    val newSetCell = newArena.topCell
    // make a union and introduce conditional membership
    val thenElems = Set(state.arena.getHas(thenCell) :_*)
    val elseElems = Set(state.arena.getHas(elseCell) :_*)
    newArena = (thenElems ++ elseElems).foldLeft(newArena)((a, e) => a.appendHas(newSetCell, e))
    // x \in NewSet <=> (~p \/ x \in S1) /\ (p \/ x \in S2)
    def addCellCons(elemCell: ArenaCell): Unit = {
      val inUnion = tla.in(elemCell, newSetCell)
      val inThenSet = if (thenElems.contains(elemCell)) tla.in(elemCell, thenCell) else tla.bool(false)
      val inElseSet = if (elseElems.contains(elemCell)) tla.in(elemCell, elseCell) else tla.bool(false)
      val inLeftOrRight = tla.ite(pred, inThenSet, inElseSet)
      rewriter.solverContext.assertGroundExpr(tla.eql(inUnion, inLeftOrRight))
    }

    // add SMT constraints
    for (cell <- thenElems ++ elseElems)
      addCellCons(cell)

    state.setTheory(CellTheory()).setArena(newArena).setRex(newSetCell.toNameEx)
  }

  // just use PICK FROM { thenValue, elseValue } to pick one of the two values
  private def iteGeneral(state: SymbState, commonType: CellT, pred: TlaEx, thenCell: ArenaCell, elseCell: ArenaCell) = {
    val setState = rewriter.rewriteUntilDone(state.setRex(tla.enumSet(thenCell.toNameEx, elseCell.toNameEx)))
    val pickState = pickFrom.pick(setState.asCell, setState)
    val pickedCell = pickState.asCell
    // cache the equalities
    val eqState = rewriter.lazyEq.cacheEqConstraints(pickState, (thenCell, pickedCell) +: (elseCell, pickedCell) +: Nil)
    // assert that the picked value is equal to the branches only when the respective condition holds
    val thenCond = tla.or(tla.not(pred), tla.eql(pickedCell.toNameEx, thenCell.toNameEx))
    val elseCond = tla.or(pred, tla.eql(pickedCell.toNameEx, elseCell.toNameEx))
    rewriter.solverContext.assertGroundExpr(tla.and(thenCond, elseCond))
    eqState.setRex(pickedCell.toNameEx)
  }


}
