package org.allenai.ari.solvers.textilp.solvers

import edu.illinois.cs.cogcomp.core.datastructures.ViewNames
import edu.illinois.cs.cogcomp.core.datastructures.textannotation.Constituent
import edu.illinois.cs.cogcomp.infer.ilp.{GurobiHook, OJalgoHook}
import org.allenai.ari.solvers.textilp.alignment.{AlignmentFunction, KeywordTokenizer}
import org.allenai.ari.solvers.textilp._
import org.allenai.ari.solvers.textilp.ilpsolver._
import org.allenai.ari.solvers.textilp.utils.AnnotationUtils

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

import org.allenai.ari.solvers.bioProccess.ProcessBankReader._

object TextILPSolver {
  val epsilon = 0.001
  val oneActiveSentenceConstraint = true

  val scienceTermsBoost = false
  val interSentenceAlignments = false
  val stopWords = false
  val essentialTerms = false
  val minQuestionToParagraphAlignmentScore = 0.0
  val minParagraphToQuestionAlignmentScore = 0.0
  val minInterSentenceAlignmentScore = 0.0
  val activeSentenceAlignmentCoeff = -1.0 // penalizes extra sentence usage
  val constituentAlignmentCoeff = -0.1
  val activeScienceTermBoost = 1d
  val minActiveParagraphConstituentAggrAlignment = 0.1
  val minActiveQuestionConstituentAggrAlignment = 0.1
  val minAlignmentWhichTerm = 0.6d
  val minPConsToPConsAlignment = 0.6
  val minPConsTQChoiceAlignment = 0.2
  val whichTermAddBoost = 1.5d
  val whichTermMulBoost = 1d

  val essentialTermsMturkConfidenceThreshold = 0.9
  val essentialClassifierConfidenceThreshold = 0.9
  val essentialTermsFracToCover = 1.0 // a number in [0,1]
  val essentialTermsSlack = 1 // a non-negative integer
  val essentialTermWeightScale = 1.0
  val essentialTermWeightBias = 0.0
  val essentialTermMinimalSetThreshold = 0.8
  val essentialTermMaximalSetThreshold = 0.2
  val essentialTermMinimalSetTopK = 3
  val essentialTermMaximalSetBottomK = 0
  val essentialTermMinimalSetSlack = 1
  val essentialTermMaximalSetSlack = 0
  val trueFalseThreshold = 5.5 // this has to be tuned

  lazy val keywordTokenizer = KeywordTokenizer.Default
  lazy val aligner = new AlignmentFunction("Entailment", 0.2, keywordTokenizer, useRedisCache = false, useContextInRedisCache = false)
}

class TextILPSolver(annotationUtils: AnnotationUtils) extends TextSolver {
  def solve(question: String, options: Seq[String], snippet: String): (Seq[Int], EntityRelationResult) = {
    val ilpSolver = new ScipSolver("textILP", ScipParams.Default)
//    val ilpSolver = new IllinoisInference(new OJalgoHook)
//    val ilpSolver = new IllinoisInference(new GurobiHook)
    val answers = options.map(o => Answer(o, -1, Some(annotationUtils.annotate(o))))
//    println("Tokenizing question .... ")
    val qTA = annotationUtils.pipelineService.createBasicTextAnnotation("", "", question)
    if(question.trim.nonEmpty) {
      annotationUtils.pipelineService.addView(qTA, ViewNames.SHALLOW_PARSE)
    }
    val q = Question(question, "", answers, Some(qTA))
//    println("Tokenizing paragraph .... ")
    val pTA = annotationUtils.pipelineService.createBasicTextAnnotation("", "", snippet) // AnnotationUtils.annotate(snippet, withQuantifier = false)
    if(snippet.trim.nonEmpty) {
      annotationUtils.pipelineService.addView(pTA, ViewNames.SHALLOW_PARSE)
    }
    val p = Paragraph(snippet, Seq(q), Some(pTA))
    createILPModel(q, p, ilpSolver, TextILPSolver.aligner)
  }

  def createILPModel[V <: IlpVar](
    q: Question,
    p: Paragraph,
    ilpSolver: IlpSolver[V, _],
    alignmentFunction: AlignmentFunction
  ): (Seq[Int], EntityRelationResult) = {

    println("starting to create the model  . . . ")
    val isTrueFalseQuestion = q.isTrueFalse

    val isTemporalQuestions = q.isTemporal
    require(q.qTAOpt.isDefined, "the annotatins for the question is not defined")
    require(p.contextTAOpt.isDefined, "the annotatins for the paragraph is not defined")
    val qTA = q.qTAOpt.getOrElse(throw new Exception("The annotation for the question not found . . . "))
    val pTA = p.contextTAOpt.getOrElse(throw new Exception("The annotation for the paragraph not found . . . "))
    val qTokens = if(qTA.hasView(ViewNames.SHALLOW_PARSE)) qTA.getView(ViewNames.SHALLOW_PARSE).getConstituents.asScala else Seq.empty
    val pTokens = if(pTA.hasView(ViewNames.SHALLOW_PARSE)) pTA.getView(ViewNames.SHALLOW_PARSE).getConstituents.asScala else Seq.empty


    ilpSolver.setAsMaximization()

    // whether to create the model with the tokenized version of the answer options
    val tokenizeAnswers = true //if(q.isTemporal) true else false
    val aTokens = if(tokenizeAnswers) {
      q.answers.map(_.aTAOpt.get.getView(ViewNames.SHALLOW_PARSE).getConstituents.asScala.map(_.getSurfaceForm))
    }
    else {
      q.answers.map(a => Seq(a.answerText))
    }

    // create questionToken-paragraphToken alignment edges
    val questionParagraphAlignments = for {
      qCons <- qTokens
      pCons <- pTokens
      score = alignmentFunction.scoreCellCell(qCons.getSurfaceForm, pCons.getSurfaceForm)
      x = ilpSolver.createBinaryVar("", score)
    } yield (qCons, pCons, x)

    // create paragraphToken-answerOption alignment edges
    val paragraphAnswerAlignments = if(!isTrueFalseQuestion) {
        // create only multiple nodes at each answer option
        for {
          pCons <- pTokens
          ansIdx <- aTokens.indices
          ansConsIdx <- aTokens(ansIdx).indices
          ansConsString = aTokens(ansIdx).apply(ansConsIdx)
          score = alignmentFunction.scoreCellCell(pCons.getSurfaceForm, ansConsString)
          x = ilpSolver.createBinaryVar("", score)
        } yield (pCons, ansIdx, ansConsIdx, x)
    } else {
      List.empty
    }

    // high-level variables
    // active answer options
    val activeAnswerOptions = if(!isTrueFalseQuestion) {
      for {
        ansIdx <- q.answers.indices
        x = ilpSolver.createBinaryVar("", 0.0)
      } yield (ansIdx, x)
    }
    else {
      List.empty
    }

    def getVariablesConnectedToOption(ansIdx: Int): Seq[V] = {
      paragraphAnswerAlignments.filter { case (_, ansTmp, _, _) => ansTmp == ansIdx }.map(_._4)
    }

    def getVariablesConnectedToParagraphToken(c: Constituent): Seq[V] = {
      questionParagraphAlignments.filter { case (_, cTmp, _) => cTmp == c }.map(_._3) ++
        paragraphAnswerAlignments.filter { case (cTmp, _, _, _) => cTmp == c }.map(_._4)
    }

    def getVariablesConnectedToParagraphSentence(sentenceId: Int): Seq[V] = {
      pTokens.filter(_.getSentenceId == sentenceId).flatMap(getVariablesConnectedToParagraphToken)
    }

    def getVariablesConnectedToQuestionToken(qCons: Constituent): Seq[V] = {
      questionParagraphAlignments.filter { case (cTmp, _, _) => cTmp == qCons }.map(_._3)
    }

    // Answer option must be active if anything connected to it is active
    activeAnswerOptions.foreach {
      case (ansIdx, x) =>
        val connectedVariables = getVariablesConnectedToOption(ansIdx)
        val allVars = connectedVariables :+ x
        val coeffs = Seq.fill(connectedVariables.length)(-1.0) :+ 1.0
        ilpSolver.addConsBasicLinear("activeOptionVar", allVars, coeffs, None, Some(0.0))
        connectedVariables.foreach { connectedVar =>
          val vars = Seq(connectedVar, x)
          val coeffs = Seq(1.0, -1.0)
          ilpSolver.addConsBasicLinear("activeOptionVar", vars, coeffs, None, Some(0.0))
        }
    }

    // active paragraph constituent
    val activeParagraphConstituents = for {
      t <- pTokens
      x = ilpSolver.createBinaryVar("", 0.0)
    } yield (t, x)
    // the paragraph token is active if anything connected to it is active
    activeParagraphConstituents.foreach {
      case (ans, x) =>
        val connectedVariables = getVariablesConnectedToParagraphToken(ans)
        val allVars = connectedVariables :+ x
        val coeffs = Seq.fill(connectedVariables.length)(-1.0) :+ 1.0
        ilpSolver.addConsBasicLinear("activeOptionVar", allVars, coeffs, None, Some(0.0))
        connectedVariables.foreach { connectedVar =>
          val vars = Seq(connectedVar, x)
          val coeffs = Seq(1.0, -1.0)
          ilpSolver.addConsBasicLinear("activeParagraphConsVar", vars, coeffs, None, Some(0.0))
        }
    }

    // active sentences for the paragraph
    val activeSentences = for {
      s <- 0 until pTA.getNumberOfSentences
      x = ilpSolver.createBinaryVar("", 0.0)
    } yield (s, x)
    // the paragraph constituent variable is active if anything connected to it is active
    activeSentences.foreach {
      case (ans, x) =>
        val connectedVariables = getVariablesConnectedToParagraphSentence(ans)
        val allVars = connectedVariables :+ x
        val coeffs = Seq.fill(connectedVariables.length)(-1.0) :+ 1.0
        ilpSolver.addConsBasicLinear("activeOptionVar", allVars, coeffs, None, Some(0.0))
        connectedVariables.foreach { connectedVar =>
          val vars = Seq(connectedVar, x)
          val coeffs = Seq(1.0, -1.0)
          ilpSolver.addConsBasicLinear("activeParagraphConsVar", vars, coeffs, None, Some(0.0))
        }
    }

    // active questions cons
    val activeQuestionConstituents = for {
      t <- qTokens
      x = ilpSolver.createBinaryVar("activeQuestionCons", 0.1) //TODO: add weight for this?
    } yield (t, x)
    // the question token is active if anything connected to it is active
    activeQuestionConstituents.foreach {
      case (c, x) =>
        val connectedVariables = getVariablesConnectedToQuestionToken(c)
        val allVars = connectedVariables :+ x
        val coeffs = Seq.fill(connectedVariables.length)(-1.0) :+ 1.0
        ilpSolver.addConsBasicLinear("activeQuestionIneq1", allVars, coeffs, None, Some(0.0))
        connectedVariables.foreach { connectedVar =>
          val vars = Seq(connectedVar, x)
          val coeffs = Seq(1.0, -1.0)
          ilpSolver.addConsBasicLinear("activeQuestionIneq2", vars, coeffs, None, Some(0.0))
        }
    }

    // constraints
    // alignment to only one option, i.e. there must be only one single active option
    if(activeAnswerOptions.nonEmpty) {
      val activeAnsVars = activeAnswerOptions.map { case (ans, x) => x }
      val activeAnsVarsCoeffs = Seq.fill(activeAnsVars.length)(1.0)
      ilpSolver.addConsBasicLinear("onlyOneActiveOption", activeAnsVars, activeAnsVarsCoeffs, Some(1.0), Some(1.0))
    }

    // have at most one active sentence
    val (_, sentenceVars) = activeSentences.unzip
    val sentenceVarsCoeffs = Seq.fill(sentenceVars.length)(1.0)
    ilpSolver.addConsBasicLinear("activeParagraphConsVar", sentenceVars, sentenceVarsCoeffs, Some(0.0), Some(1.0))

    // sparsity parameters
    // alignment is preferred for lesser sentences

    // use at least k constituents in the question
    // TODO: make this parameterized
    val (_, questionVars) = activeQuestionConstituents.unzip
    val questionVarsCoeffs = Seq.fill(questionVars.length)(1.0)
    ilpSolver.addConsBasicLinear("activeQuestionConsVar", questionVars, questionVarsCoeffs, Some(1.0), Some(3.0))

    println("created the ilp model. Now solving it  . . . ")
//    println("Number of binary variables: " + ilpSolver.getNBinVars)
//    println("Number of continuous variables: " + ilpSolver.getNContVars)
//    println("Number of integer variables: " + ilpSolver.getNIntVars)
//    println("Number of constraints: " + ilpSolver.getNConss)

    // solving and extracting the answer
    ilpSolver.solve()

    println("Done solving the model  . . . ")

//    // extracting the solution
//    val questionAlignments = qTokens.map { c => c -> TermAlignment(c.getSurfaceForm) }.toMap
//    val choiceAlignments = q.answers.map { c => c -> TermAlignment(c.answerText) }.toMap
//    val paragraphAlignments = pTokens.map { c => c -> TermAlignment(c.getSurfaceForm) }.toMap

//    var iter = 0
//    questionParagraphAlignments.foreach {
//      case (c1, c2, x) =>
//        if (ilpSolver.getSolVal(x) > 1.0 - epsilon) {
//          questionAlignments(c1).alignmentIds.+=(iter)
//          paragraphAlignments(c2).alignmentIds.+=(iter)
//          iter = iter + 1
//        }
//    }

    def stringifyVariableSequence(seq: Seq[(Int, V)]): String = {
      seq.map{ case(id, x) => "id: " + id + " : " + ilpSolver.getSolVal(x) }.mkString(" / ")
    }

    def stringifyVariableSequence3(seq: Seq[(Constituent, V)])(implicit d: DummyImplicit): String = {
      seq.map{ case(id, x) => "id: " + id.getSurfaceForm + " : " + ilpSolver.getSolVal(x) }.mkString(" / ")
    }

    def stringifyVariableSequence2(seq: Seq[(Constituent, Constituent, V)])(implicit d: DummyImplicit, d2: DummyImplicit): String = {
      seq.map{ case(c1, c2, x) => "c1: " + c1.getSurfaceForm + ", c2: " + c2.getSurfaceForm + " -> " + ilpSolver.getSolVal(x) }.mkString(" / ")
    }

    def stringifyVariableSequence4(seq: Seq[(Constituent, Int, Int, V)]): String = {
      seq.map{ case(c, i, j, x) => "c: " + c.getSurfaceForm + ", ansIdx: " + i + ", ansConsIdx: " + j + " -> " + ilpSolver.getSolVal(x) }.mkString(" / ")
    }

    if(ilpSolver.getStatus == IlpStatusOptimal) {
      println("Primal score: " + ilpSolver.getPrimalbound)
      val trueIdx = q.trueIndex
      val falseIdx = q.falseIndex
      val selectedIndex = if(isTrueFalseQuestion) {
        if(ilpSolver.getPrimalbound > TextILPSolver.trueFalseThreshold ) Seq(trueIdx) else Seq(falseIdx)
      }
      else {
        println(">>>>>>> not true/false . .. ")
        activeAnswerOptions.zipWithIndex.collect { case ((ans, x), idx) if ilpSolver.getSolVal(x) > 1.0 - TextILPSolver.epsilon => idx }
      }
      val questionBeginning = "Question: "
      val paragraphBeginning = "|Paragraph: "
      val questionString = questionBeginning + q.questionText
      val choiceString = "|Options: " + q.answers.zipWithIndex.map{case (ans, key) => s" (${key+1}) " + ans.answerText}.mkString(" ")
      val paragraphString = paragraphBeginning + p.context

      val entities = ArrayBuffer[Entity]()
      val relations = ArrayBuffer[Relation]()
      var eIter = 0
      var rIter = 0

      val entityMap = scala.collection.mutable.Map[(Int, Int), String]()
      val relationSet = scala.collection.mutable.Set[(String, String)]()

      questionParagraphAlignments.foreach {
        case (c1, c2, x) =>
          if (ilpSolver.getSolVal(x) > 1.0 - TextILPSolver.epsilon) {
            //          val qBeginIndex = questionString.indexOf(c1.getSurfaceForm)
            val qBeginIndex = questionBeginning.length + c1.getStartCharOffset
            val qEndIndex = qBeginIndex + c1.getSurfaceForm.length
            val span1 = (qBeginIndex, qEndIndex)
            val t1 = if(!entityMap.contains(span1)) {
              val t1 = "T" + eIter
              eIter = eIter + 1
              entities += Entity(t1, c1.getSurfaceForm, Seq(span1))
              entityMap.put(span1, t1)
              t1
            }
            else {
              entityMap(span1)
            }

            // val pBeginIndex = paragraphString.indexOf(c2.getSurfaceForm) + questionString.length
            val pBeginIndex = c2.getStartCharOffset + questionString.length + paragraphBeginning.length
            val pEndIndex = pBeginIndex + c2.getSurfaceForm.length
            val span2 = (pBeginIndex, pEndIndex)
            val t2 = if(!entityMap.contains(span2)) {
              val t2 = "T" + eIter
              eIter = eIter + 1
              entities += Entity(t2, c2.getSurfaceForm, Seq(span2))
              entityMap.put(span2, t2)
              t2
            }
            else {
              entityMap(span2)
            }

            if(!relationSet.contains((t1, t2))) {
              relations += Relation("R" + rIter, t1, t2, ilpSolver.getVarObjCoeff(x))
              rIter = rIter + 1
              relationSet.add((t1, t2))
            }
          }
      }

      //    paragraphAnswerAlignments.foreach {
      //      case (c1, c2, x) =>
      //        if (ilpSolver.getSolVal(x) > 1.0 - epsilon) {
      //          paragraphAlignments(c1).alignmentIds.+=(iter)
      //          choiceAlignments(c2).alignmentIds.+=(iter)
      //          iter = iter + 1
      //        }
      //    }

      paragraphAnswerAlignments.foreach {
        case (c1, ansIdx, ansConsIdx, x) =>
          if (ilpSolver.getSolVal(x) > 1.0 - TextILPSolver.epsilon) {
            //          val pBeginIndex = paragraphString.indexOf(c1.getSurfaceForm) + questionString.length
            //          val pEndIndex = pBeginIndex + c1.getSurfaceForm.length
            val pBeginIndex = c1.getStartCharOffset + questionString.length + paragraphBeginning.length
            val pEndIndex = pBeginIndex + c1.getSurfaceForm.length
            val span1 = (pBeginIndex, pEndIndex)
            val t1 = if(!entityMap.contains(span1)) {
              val t1 = "T" + eIter
              entities += Entity(t1, c1.getSurfaceForm, Seq(span1))
              entityMap.put(span1, t1)
              eIter = eIter + 1
              t1
            } else {
              entityMap(span1)
            }

            val ansString = aTokens(ansIdx)(ansConsIdx)
            val oBeginIndex = choiceString.indexOf(ansString) + questionString.length + paragraphString.length
            val oEndIndex = oBeginIndex + ansString.length
            val span2 = (oBeginIndex, oEndIndex)
            val t2 = if(!entityMap.contains(span2)) {
              val t2 = "T" + eIter
              entities += Entity(t2, ansString, Seq(span2))
              eIter = eIter + 1
              entityMap.put(span2, t2)
              t2
            }
            else {
              entityMap(span2)
            }

            if(!relationSet.contains((t1, t2))) {
              relations += Relation("R" + rIter, t1, t2, ilpSolver.getVarObjCoeff(x))
              rIter = rIter + 1
              relationSet.add((t1, t2))
            }
          }
      }

      if(isTrueFalseQuestion) {
        // add the answer option span manually
        selectedIndex.foreach{ idx =>
          val ansText = q.answers(idx).answerText
          val oBeginIndex = choiceString.indexOf(ansText) + questionString.length + paragraphString.length
          val oEndIndex = oBeginIndex + ansText.length
          val span2 = (oBeginIndex, oEndIndex)
          val t2 = if(!entityMap.contains(span2)) {
            val t2 = "T" + eIter
            entities += Entity(t2, ansText, Seq(span2))
            eIter = eIter + 1
            entityMap.put(span2, t2)
            t2
          }
          else {
            entityMap(span2)
          }
        }
      }

      println("returning the answer  . . . ")

      //    val alignmentResult = AlignmentResults(
      //      questionAlignments.values.toList,
      //      choiceAlignments.values.toList,
      //      paragraphAlignments.values.toList
      //    )

      val solvedAnswerLog = "activeAnswerOptions: " + stringifyVariableSequence(activeAnswerOptions) +
        "  activeQuestionConstituents: " + stringifyVariableSequence3(activeQuestionConstituents) +
        "  questionParagraphAlignments: " + stringifyVariableSequence2(questionParagraphAlignments) +
        "  paragraphAnswerAlignments: " + stringifyVariableSequence4(paragraphAnswerAlignments) +
        "  aTokens: " + aTokens.toString

      val erView = EntityRelationResult(questionString + paragraphString + choiceString, entities, relations,
        confidence = ilpSolver.getPrimalbound, log = solvedAnswerLog)
      selectedIndex -> erView
    }
    else {
      println("Not optimal . . . ")
      println("Status is not optimal. Status: "  + ilpSolver.getStatus)
      // if the program is not solver, say IDK
      Seq.empty -> EntityRelationResult("INFEASIBLE", List.empty, List.empty)
    }
  }

}
