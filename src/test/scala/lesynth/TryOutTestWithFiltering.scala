//package lesynth
//
//import org.junit.Assert._
//import org.junit.{ Test, Ignore }
//
//import scala.collection.mutable.{ Map => MutableMap }
//import scala.collection.mutable.{ Set => MutableSet }
//import scala.collection.mutable.{ LinkedList => MutableList }
//import scala.util.matching.Regex
//import scala.collection.mutable.PriorityQueue
//
//import scala.tools.nsc.{ Settings => NSCSettings, MainGenericRunner }
//
//import leon.{ Main => LeonMain, DefaultReporter, Settings, LeonContext }
//import leon.solvers.{ Solver, TimeoutSolver }
//import leon.solvers.z3.{ FairZ3Solver }
//import leon.verification.AnalysisPhase
//import leon.purescala.TypeTrees.{ TypeTree => LeonType, _ }
//import leon.purescala.Trees.{ Variable => LeonVariable, _ }
//import leon.purescala.Definitions.{ FunDef, VarDecl, Program, ObjectDef }
//import leon.purescala.Common.{ Identifier, FreshIdentifier }
//import leon.purescala.TreeOps
//import leon.plugin.ExtractionPhase
//
//import insynth.util.logging.HasLogger
//import insynth.interfaces.Declaration
//import insynth.InSynth
//import insynth.reconstruction.codegen.CodeGenerator
//import insynth.leon.loader.LeonLoader
//import insynth.leon.LeonDeclaration
//import insynth.leon.ImmediateExpression
//import insynth.engine.InitialEnvironmentBuilder
//import insynth.leon.LeonQueryBuilder
//import insynth.interfaces.{ Loader, Declaration, QueryBuilder }
//import insynth.engine.{ Engine, InitialEnvironmentBuilder }
//import insynth.engine.scheduler.WeightScheduler
//import insynth.structures.ContainerNode
//import insynth.util.TimeOut
//import insynth.Config
//import insynth.reconstruction.Reconstructor
//import insynth.leon.TypeTransformer
//import insynth.leon.HoleFinder
//
//class TryOutTest extends HasLogger {
//
//  def analyzeProgram(program: Program) {
//    import leon.Main._
//
//    val temp = System.currentTimeMillis
//    Globals.allSolved = Some(true)
//
//    val reporter = new DefaultReporter()
//    val args = Array("no file!", "--timeout=2")
//    val ctx = processOptions(reporter, args.toList)
//
//    AnalysisPhase.run(ctx)(program)
//
//    val time = System.currentTimeMillis - temp
//    println("analysis took " + time)
//    verTime += time
//  }
//
//  def generateCounterexamples(program: Program, funDef: FunDef, number: Int): (Seq[Map[Identifier, Expr]], Expr) = {
//
//    fine("generate counter examples with funDef.prec= " + funDef.precondition.getOrElse(BooleanLiteral(true)))
//
//    // get current precondition
//    var precondition = funDef.precondition.getOrElse(BooleanLiteral(true))
//    // where we will accumulate counterexamples as sequence of maps
//    var maps: Seq[Map[Identifier, Expr]] = Seq.empty
//
//    // iterate specific number of times or until no further counterexample can be generated
//    var changed = true
//    var ind = 0
//    while (ind < number && changed) {
//
//      // analyze the program
//      analyzeProgram(program)
//
//      // check if solver could solved this instance
//      if (Globals.allSolved == Some(false) && !Globals.asMap.isEmpty) {
//
//        fine("found coounterexample: " + Globals.asMap)
//
//        // add current counterexample
//        maps :+= Globals.asMap
//        // prevent this counterexample to reoccur
//        val precAddition = Not(And(Globals.asMap map { case (id, value) => Equals(LeonVariable(id), value) } toSeq))
//        precondition = And(Seq(precondition, precAddition))
//        funDef.precondition = Some(precondition)
//      } else
//        changed = false
//
//      ind += 1
//    }
//
//    // return found counterexamples and the formed precondition
//    (maps, precondition)
//  }
//
//  // assert that there is not function that returns a function
//  def checkDeclarations(inSynth: InSynth) = {
//    assertFalse(
//      "We should not have a declaration which returns a function (not valid in Leon)",
//      (false /: inSynth.getCurrentBuilder.getAllDeclarations) {
//        case (res, dec: LeonDeclaration) =>
//          res ||
//            (dec.getDomainType match {
//              case FunctionType(_, _: FunctionType) => true
//              case _ => false
//            })
//      })
//  }
//
//  def assertL1L2Refinements(variableRefinements: MutableMap[Identifier, MutableSet[ClassType]]) =
//    for (
//      pair @ (varName, nameSet) <- List(
//        ("l2", Set("Nil", "Cons")),
//        ("l1", Set("Nil", "Cons")))
//    ) assertTrue(
//      "pair " + pair + " not found. Found: " + variableRefinements.mkString(", "),
//      (false /: variableRefinements) {
//        (res, extr) =>
//          extr match {
//            case (id, classTypeSet) => res ||
//              (
//                id.name == varName &&
//                (true /: nameSet) {
//                  (res, innerName) => res && classTypeSet.exists(_.classDef.id.name == innerName)
//                })
//            case _ => false
//          }
//      })
//
//  private var program: Program = _
//
//  private var hole: Hole = _
//  private var holeFunDef: FunDef = _
//
//  private var allDeclarations: List[Declaration] = _
//
//  private var loader: LeonLoader = _
//
//  private var inSynth: InSynth = _
//  private var codeGenerator: CodeGenerator = _
//
//  private var refiner: Refiner = _
//
//  // accumulate precondition for the remaining branch to synthesize 
//  private var accumulatingPrecondition: Expr = _
//  // accumulate the final expression of the hole
//  private var accumulatingExpression: Expr => Expr = _
//  private var accumulatingExpressionMatch: Expr => Expr = _
//
//  var solver: Solver = _
//  //  
//  var ctx: LeonContext = _
//  //  
//  var variableRefinements: MutableMap[Identifier, MutableSet[ClassType]] = _
//
//  var initialPrecondition: Expr = _
//
//  var startTime: Long = _
//
//  var verTime: Long = 0
//  var synTime: Long = 0
//
//  // number of condition expressions to try before giving up on that branch expression
//  val numberOfBooleanSnippets = 20
//
//  def introduceCounterExamples = {
//    var argumentIds = holeFunDef.args.map(_.id)
//    assertEquals(2, argumentIds.size)
//
//    Globals.hole.getType match {
//      case ct: ClassType =>
//        val leonLoader = LeonLoader(program, hole, false)
//
//        leonLoader.load
//
//        val setSubclasses = leonLoader.directSubclassesMap(ct).map(_.asInstanceOf[CaseClassType].classDef)
//        assertEquals(2, setSubclasses.size)
//
//        val (nilClassSet, consClassSet) = setSubclasses.partition(_.fieldsIds.size == 0)
//        assertEquals(1, nilClassSet.size)
//
//        val nilClass = nilClassSet.head
//        val consClass = consClassSet.head
//
//        var counter = 0
//        def getIntLiteral = { counter += 1; IntLiteral(counter) }
//
//        val list0 = () => CaseClass(nilClass, Nil)
//        val list1 = () => CaseClass(consClass, getIntLiteral :: list0() :: Nil)
//        val list2 = () => CaseClass(consClass, getIntLiteral :: list1() :: Nil)
//        val list3 = () => CaseClass(consClass, getIntLiteral :: list2() :: Nil)
//        val list4 = () => CaseClass(consClass, getIntLiteral :: list3() :: Nil)
//
//        val lists = List(list0, list1, list2, list3 /*, list4*/ )
//
//        for (l1 <- lists; l2 <- lists) {
//          ExampleRunner.counterExamples :+=
//            Map(argumentIds(0) -> l1(),
//              argumentIds(1) -> l2())
//        }
//      case _ =>
//        fail
//    }
//
//  }
//
//  def synthesizeExpressions =
//    inSynth.getExpressions
//
//  def initialize(fileName: String) = {
//
//    import leon.Main._
//
//    val reporter = new DefaultReporter
//    val args = Array(fileName, "--timeout=2")
//    ctx = processOptions(reporter, args.toList)
//
//    // extract information about the program
//    extractInformation(ctx, fileName)
//
//    codeGenerator = new CodeGenerator
//
//    // program and hole objects should be extracted
//    (Globals.program, Globals.hole) match {
//      case (Some(matchProgram), matchHole: Hole) =>
//        program = matchProgram
//        hole = matchHole
//
//        solver = //new FairZ3Solver(ctx)
//          new TimeoutSolver(new FairZ3Solver(ctx), Globals.timeout)
//
//        loader = new LeonLoader(program, hole, false)
//        // create new insynth object
//        inSynth = new InSynth(loader, true)
//
//        // save all declarations seen
//        allDeclarations = inSynth.getCurrentBuilder.getAllDeclarations
//
//        // funDef of the hole
//        holeFunDef = Globals.holeFunDef
//        println("postcondition is: " + holeFunDef.getPostcondition)
//
//        // accumulate precondition for the remaining branch to synthesize 
//        accumulatingPrecondition = holeFunDef.precondition.getOrElse(BooleanLiteral(true))
//        // save initial precondition
//        initialPrecondition = accumulatingPrecondition
//        // accumulate the final expression of the hole
//        accumulatingExpression = (finalExp: Expr) => finalExp
//        accumulatingExpressionMatch = accumulatingExpression
//
//        // each variable of super type can actually have a subtype
//        // get sine declaration maps to be able to refine them
//        val directSubclassMap = loader.directSubclassesMap
//        val variableDeclarations = loader.variableDeclarations
//
//        // map from identifier into a set of possible subclasses
//        variableRefinements = MutableMap.empty
//        for (varDec <- variableDeclarations) {
//          varDec match {
//            case LeonDeclaration(_, _, typeOfVar: ClassType, ImmediateExpression(_, LeonVariable(id))) =>
//              variableRefinements += (id -> MutableSet(directSubclassMap(typeOfVar).toList: _*))
//            case _ =>
//          }
//        }
//
//        // calculate cases that should not happen
//        refiner = new Refiner(program, hole)
//        fine("Refiner recursive call: " + refiner.recurentExpression)
//      case _ => fail()
//    }
//  }
//
//  @Test
//  def test1 {
//    synthesize("testcases/insynth/ListOperationsHole.scala")
//  }
//
//  def synthesize(fileName: String): Unit = {
//    // get start time
//    startTime = System.currentTimeMillis
//
//    initialize(fileName)
//
//    // keeps status of validity
//    var keepGoing = Globals.allSolved match {
//      case Some(true) => false
//      case _ => true
//    }
//
//    var snippets = synthesizeExpressions
//
//    introduceCounterExamples
//    println("counterexamples after introduce: " + ExampleRunner.counterExamples.mkString("\n"))
//
//    // iterate while the program is not valid
//    import scala.util.control.Breaks._
//    var iteration = 0
//    breakable {
//      while (keepGoing) {
//        // next iteration
//        iteration += 1
//        println("####################################")
//        println("######Iteration #" + iteration + " ###############")
//        println("####################################")
//
//        // just printing of expressions
//        //            for ((snippetTree, ind) <- (snippets map { _.getSnippet }) zip Iterator.range(0, 20).toStream) {
//        //              println(ind + " snippetTree is: " + snippetTree)
//        //            }
//        println("precondition is: " + holeFunDef.precondition.getOrElse(BooleanLiteral(true)))
//        println("accumulatingPrecondition is: " + accumulatingPrecondition)
//        println("accumulatingExpression(Unit) is: " + accumulatingExpression(UnitLiteral))
//        //            System.out.println("Press Any Key To Continue...");
//        //            new java.util.Scanner(System.in).nextLine();
//
//        var pq = new PriorityQueue[(Expr, Int)]()(
//          new Ordering[(Expr, Int)] {
//            def compare(pair1: (Expr, Int), pair2: (Expr, Int)) =
//              pair1._2.compare(pair2._2)
//          })
//        var tested = 0
//
//        // found precondition?
//        var found = false
//        // try to find it
//        breakable {
//          // go through all snippets
//          for (
//            snippet <- snippets /*filter { _.getSnippet.toString == "concat(Nil(), Nil())" }*/ ;
//            val snippetTree = snippet.getSnippet
//          ) {
//            // debugging
//            println("snippetTree is: " + snippetTree)
//            //                System.out.println("Press Any Key To Continue...");
//            //                new java.util.Scanner(System.in).nextLine();
//
//            // TODO is never empty now with introduce
//            val passCount =
//              //if (ExampleRunner.counterExamples.isEmpty)
//              if (iteration == 1)
//                ExampleRunner.counterExamples.size
//              else {
//
//                val oldPreconditionSaved = holeFunDef.precondition
//                val oldBodySaved = holeFunDef.body
//
//                // restore initial precondition
//                holeFunDef.precondition = Some(initialPrecondition)
//
//                assertEquals(BooleanLiteral(true), initialPrecondition)
//
//                // get the whole body (if else...)
//                val accumulatedExpression = accumulatingExpression(snippetTree)
//                // set appropriate body to the function for the correct evaluation
//                holeFunDef.body = Some(accumulatedExpression)
//
//                //    if (snippetTree.toString == "Cons(l2.head, concat(l1, l2.tail))") {
//                //      System.out.println("Press Any Key To Continue...");
//                //      new java.util.Scanner(System.in).nextLine();
//                //    }
//                val count = ExampleRunner.countPassed(accumulatedExpression)
//
//                //    if (snippetTree.toString == "Cons(l2.head, concat(l1, l2.tail))") {
//                //      System.out.println("Press Any Key To Continue...");
//                //      new java.util.Scanner(System.in).nextLine();
//                //    }
//
//                holeFunDef.precondition = oldPreconditionSaved
//                holeFunDef.body = oldBodySaved
//
//                count
//              }
//
//            val okay =
//              passCount == ExampleRunner.counterExamples.size
//            if (!okay) {
//              //                println("snippet with pass count filtered: " + (snippet, passCount))
//              pq.enqueue((snippetTree, 100 + (passCount * (iteration - 1)) - snippet.getWeight.toInt))
//            }
//
//            // skip pure recursive call
//            if (!refiner.isAvoidable(snippetTree) && okay) {
//              if (tryToSynthesizeBranch(snippetTree)) {
//                found = true
//                pq.clear
//                break
//              }
//            } else {
//              //                  println("We are skipping this snippetTree since it is the recursive call: " + Refiner.recurentExpressions)
//            }
//
//            if (tested >= 500) {
//              println("queue is: " + pq.mkString("\n"))
//              println("head of queue is: " + pq.head)
//              //			            System.out.println("Press Any Key To Continue...");
//              //			            new java.util.Scanner(System.in).nextLine();
//              for (i <- 1 to 5) {
//                val nextSnippet = pq.dequeue._1
//                println("nextSnippet is: " + nextSnippet)
//
//                if (tryToSynthesizeBranch(nextSnippet)) {
//                  found = true
//                  pq.clear
//                  break
//                }
//              }
//              tested = 0
//            } else
//              tested += 1
//          } // for (snippet <- snippets
//        } // breakable { for (snippet <- snippets
//
//        // if did not found for any of the branch expressions
//        if (!found) {
//          info("we are done :(")
//          return
//        }
//
//        // debug
//        assertTrue(found)
//        info("variable declarations now:")
//        info(allDeclarations.filter(
//          _ match {
//            case LeonDeclaration(_, _, _, imex @ ImmediateExpression(_, _: LeonVariable)) =>
//              true
//            case _ =>
//              false
//          }).mkString(" ,"))
//
//        info("okay we found one branch")
//
//        // synthesize snippets for next branch
//        info("synthesizing new snippets")
//
//        val newBuilder = new InitialEnvironmentBuilder
//        newBuilder.addDeclarations(allDeclarations)
//        fine("all declarations now, size: " + newBuilder.getAllDeclarations.size)
//        val queryBuilder = new LeonQueryBuilder(hole.desiredType)
//        val query = queryBuilder.getQuery
//        val engine = new Engine(newBuilder, query, new WeightScheduler(), TimeOut(Config.getTimeOutSlot))
//        val proofTree = engine.run()
//        assertNotNull(proofTree)
//        assertEquals(1, proofTree.getNodes.size)
//
//        println("filtering based on: " + holeFunDef.precondition.get)
//        println("counterexamples before filter: " + ExampleRunner.counterExamples.size)
//        ExampleRunner.filter(holeFunDef.precondition.get)
//        println("counterexamples after filter: " + ExampleRunner.counterExamples.size)
//        println("counterexamples after filter: " + ExampleRunner.counterExamples.mkString("\n"))
//
//        //              System.out.println("Press Any Key To Continue...");
//        //              new java.util.Scanner(System.in).nextLine();
//
//        snippets = Reconstructor(proofTree.getNodes.head, codeGenerator)
//
//        // ADHOC filter out counterexamples that have Nil (adhoc!)              
//        //	            ExampleRunner.counterExamples = ExampleRunner.counterExamples filterNot {
//        //	              map =>
//        //	                (false /: map.values) {
//        //	                  (res, value) =>
//        //	                    res || value.toString.startsWith("Nil")
//        //	                }
//        //	            }      
//
//      }
//    } //breakable { while (!keepGoing) {
//
//    // get end time
//    val endTime = System.currentTimeMillis
//    println("whole process took time: " + (endTime - startTime))
//
//  }
//
//  def tryToSynthesizeBranch(snippetTree: Expr): Boolean = {
//    import leon.purescala.TreeOps.searchAndReplaceDFS
//    // replace hole in the body with current snippet tree
//
//    //    val oldBody = holeFunDef.getBody
//    //    val newBody = searchAndReplaceDFS(
//    //      _ match {
//    //        case _: Hole => Some(snippetTree)
//    //        case _ => None
//    //      })(oldBody)
//    val oldBody = holeFunDef.getBody
//    val newBody = accumulatingExpressionMatch(snippetTree)
//
//    holeFunDef.body = Some(newBody)
//    // save old precondition
//    val oldPrecondition = holeFunDef.precondition.getOrElse(BooleanLiteral(true))
//
//    holeFunDef.precondition = Some(initialPrecondition)
//
//    // analyze the program
//    analyzeProgram(program)
//
//    // check if solver could solved this instance
//    if (Globals.allSolved == Some(true)) {
//      // mark the branch found
//      //found = true
//
//      println("Wooooooow we have a winner!")
//      println("************************************")
//      println("*********And the winner is**********")
//      println(accumulatingExpression(snippetTree))
//      println("************************************")
//
//      // get end time
//      val endTime = System.currentTimeMillis
//      println("whole process took time: " + (endTime - startTime))
//      //println("verTime, testTime" + verTime + ", " + ExampleRunner.testTime) 
//
//      System.out.println("Press Any Key To Continue...");
//      new java.util.Scanner(System.in).nextLine();
//
//      return true
//    }
//
//    // store appropriate values here, will be update in a finally branch
//    var bodyToRestore = oldBody
//    var preconditionToRestore = Some(oldPrecondition)
//
//    // because first initial test
//    holeFunDef.precondition = preconditionToRestore
//
//    // debug
//    println("Going to generating counterexamples: " + holeFunDef)
//    //                  System.out.println("Press Any Key To Continue...");
//    //                  new java.util.Scanner(System.in).nextLine();
//
//    //                  println("Counterexample cheking: ")
//    //                  println("Counterexamples: " + ExampleRunner.counterExamples.mkString("\n"))
//    //                  val counterExampleCheck = ExampleRunner.check(BooleanLiteral(true), newBody, holeFunDef.getPostcondition)
//    //                  println("Result of check: " + counterExampleCheck )
//    //                  
//    val temp = System.currentTimeMillis
//    // get counterexamples
//    val (maps, precondition) = generateCounterexamples(program, holeFunDef, 5)
//    val temptime = System.currentTimeMillis - temp
//    println("gen counterexamples took " + temptime)
//    verTime += temptime
//
//    // collect (add) counterexamples
//    //ExampleRunner.counterExamples ++= maps
//
//    val keepGoing = Globals.allSolved match {
//      case Some(true) => false
//      case _ => true
//    }
//
//    // if no counterexamples and all are solved, we are done
//    if (maps.isEmpty && !keepGoing) {
//      // mark the branch found
//      //found = true
//
//      println("Wooooooow we have a winner!")
//      println("************************************")
//      println("*********And the winner is**********")
//      println(accumulatingExpression(snippetTree))
//      println("************************************")
//
//      return true
//    }
//
//    // will modify funDef body and precondition, restore it later
//    try {
//      // TODO is this okay?
//      // if there are no returned counterexamples just go to the next snippet
//      // and so far counter examples passed
//      if (!maps.isEmpty /*&& counterExampleCheck */ ) {
//        // proceed with synthesizing boolean expressions		      
//        assertEquals(5, maps.size)
//        // set program to the solver
//        solver.setProgram(program)
//
//        // initialize builder with previously seen declarations
//        val newBuilder = new InitialEnvironmentBuilder
//        newBuilder.addDeclarations(allDeclarations)
//
//        fine("all declarations now, size: " + newBuilder.getAllDeclarations.size)
//
//        // synthesize solution
//        val queryBuilder = new LeonQueryBuilder(BooleanType)
//        val query = queryBuilder.getQuery
//        val engine = new Engine(newBuilder, query, new WeightScheduler(), TimeOut(Config.getTimeOutSlot))
//        val solution = engine.run()
//        assertNotNull(solution)
//
//        assertNotNull(codeGenerator)
//        assertNotNull(allDeclarations)
//        // reconstruct (only defined number of boolean expressions)
//        val innerSnippets = Reconstructor(solution.getNodes.head, codeGenerator) take numberOfBooleanSnippets
//        checkDeclarations(inSynth)
//
//        // debugging
//        //	                    for ((snippetTree, ind) <- (innerSnippets map { _.getSnippet }) zip Iterator.range(0, 20).toStream) {
//        //	                      println(ind + " boolean snippetTree is: " + snippetTree)
//        //	                    }
//        //	                    System.out.println("Press Any Key To Continue...");
//        //	                    new java.util.Scanner(System.in).nextLine();
//
//        // precondition found?
//        var found = false
//
//        // iterate over boolean snippets
//        val iterator = innerSnippets.iterator
//        while (!found && iterator.hasNext) {
//          val innerSnippetTree = iterator.next.getSnippet
//
//          // debug
//          println("boolean snippet is: " + innerSnippetTree)
//          //          System.out.println("Press Any Key To Continue...");
//          //          new java.util.Scanner(System.in).nextLine();
//
//          val (innerFound, innerPrec) = tryToSynthesizeBooleanCondition(snippetTree, innerSnippetTree, precondition)
//
//          found = innerFound
//          innerPrec match {
//            case s @ Some(_) =>
//              preconditionToRestore = s
//            case _ =>
//          }
//
//          if (found)
//            return true
//
//        } // iterating over all boolean solutions
//
//        // if none of boolean solutions work, restore body (in finally)
//        //holeFunDef.body = Some(oldBody)
//
//        assertTrue(!found)
//        info("not found :(, we give up on this branch expression")
//        return false
//
//      } // if ( !maps.isEmpty ) {
//
//      false
//    } // try
//    finally {
//      // set these to the FunDef
//      holeFunDef.precondition = preconditionToRestore
//      // restore old body (we accumulate expression)                                
//      holeFunDef.body = Some(oldBody)
//    }
//  }
//
//  def tryToSynthesizeBooleanCondition(snippetTree: Expr, innerSnippetTree: Expr, precondition: Expr): (Boolean, Option[Expr]) = {
//
//    // debug
//    //	                      println("boolean snippet is: " + innerSnippetTree)
//    //		                    System.out.println("Press Any Key To Continue...");
//    //		                    new java.util.Scanner(System.in).nextLine();
//
//    // new condition together with existing precondition
//    val newCondition = And(Seq(accumulatingPrecondition, innerSnippetTree))
//
//    // new expression should not be false
//    val notFalseEquivalence = Not(newCondition)
//    val notFalseSolveReturn = solver.solve(notFalseEquivalence)
//    fine("solve for not false returned: " + notFalseSolveReturn)
//    notFalseSolveReturn match {
//      case Some(true) =>
//        (false, None)
//      case None =>
//        (false, None)
//      // nothing here
//      // here, our expression is not contradictory, continue
//      case Some(false) =>
//        // check if synthesized boolean expression implies precondition (counterexamples)
//        val implyExpression = Implies(newCondition, precondition)
//        fine("implyExpression is: " + implyExpression)
//
//        // check if synthesized condition implies counter-examples
//        val solveReturn = solver.solve(implyExpression)
//        fine("solve returned: " + solveReturn)
//
//        solveReturn match {
//          case Some(true) =>
//            // old precondition is not here, it is before counterexamples are derived!
//            //val oldPrecondition = holeFunDef.precondition.getOrElse(BooleanLiteral(true))
//
//            // if expression implies counterexamples add it to the precondition and try to validate program
//            holeFunDef.precondition = Some(newCondition)
//            // do analysis
//            analyzeProgram(program)
//            // program is valid, we have a branch
//            if (Globals.allSolved == Some(true)) {
//              // we found a branch
//              info("Wow! we found a branch!")
//
//              // update accumulating expression
//              val oldAccumulatingExpression = accumulatingExpression
//              val newAccumulatingExpression =
//                (finalExpr: Expr) =>
//                  oldAccumulatingExpression(IfExpr(innerSnippetTree, snippetTree, finalExpr))
//
//              accumulatingExpression = newAccumulatingExpression
//
//              // update accumulating precondition
//              fine("updating accumulatingPrecondition")
//              accumulatingPrecondition = And(Seq(accumulatingPrecondition, Not(innerSnippetTree)))
//              fine("updating hole fun precondition and body (to be hole)")
//
//              // set to set new precondition
//              val preconditionToRestore = Some(accumulatingPrecondition)
//
//              // check for refinements
//              checkRefinements(innerSnippetTree) match {
//                case Some(refinementPair @ (id, classType)) =>
//                  fine("And now we have refinement type: " + refinementPair)
//                  fine("variableRefinements(id) before" + variableRefinements(id))
//                  variableRefinements(id) -= loader.classMap(classType.id)
//                  fine("variableRefinements(id) after" + variableRefinements(id))
//
//                  // if we have a single subclass possible to refine
//                  if (variableRefinements(id).size == 1) {
//                    fine("wow we an do variable refinement for " + id)
//
//                    val newType = variableRefinements(id).head
//                    fine("new type is: " + newType)
//
//                    // update declarations
//                    allDeclarations =
//                      for (dec <- allDeclarations)
//                        yield dec match {
//                        case LeonDeclaration(inSynthType, _, decClassType, imex @ ImmediateExpression(_, LeonVariable(`id`))) =>
//                          LeonDeclaration(
//                            imex, TypeTransformer(newType), newType)
//                        case _ =>
//                          dec
//                      }
//
//                  } else
//                    fine("we cannot do variable refinement :(")
//                case _ =>
//              }
//              // found a boolean snippet, break
//              (true, preconditionToRestore)
//            } else {
//              // reset funDef and continue with next boolean snippet
//              val preconditionToRestore = Some(accumulatingPrecondition)
//              (false, preconditionToRestore)
//            }
//
//          case _ =>
//            println("precondition was not applied")
//            (false, None)
//        } //solveReturn match { (for implying counterexamples)
//    } // notFalseSolveReturn match {
//  }
//
//  // inspect the expression if some refinements can be done
//  def checkRefinements(expr: Expr) = expr match {
//    case CaseClassInstanceOf(classDef, LeonVariable(id)) =>
//      Some((id, classDef))
//    case _ =>
//      None
//  }
//
//  def extractInformation(ctx: LeonContext, file: String) =
//    new HoleFinder(file).extract
//
//}