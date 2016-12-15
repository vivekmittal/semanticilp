package org.allenai.ari.solvers.textilp

import edu.illinois.cs.cogcomp.core.datastructures.textannotation.TextAnnotation

import scala.collection.mutable.ArrayBuffer

case class TopicGroup(title: String, paragraphs: Seq[Paragraph])
case class Paragraph(context: String, questions: Seq[Question], contextTAOpt: Option[TextAnnotation])
case class Question(questionText: String, questionId: String, answers: Set[Answer], qTAOpt: Option[TextAnnotation])
case class Answer(answerText: String, answerStart: Int)

/** The alignment of a basic textual alignment unit (a term) in the ILP solution.
  * @param term A basic alignment unit (a word, a chunk, a string associated with a cell, etc)
  * @param alignmentIds A sequence of alignment IDs that connect this term with other terms
  */
case class TermAlignment(term: String, alignmentIds: ArrayBuffer[Int] = ArrayBuffer.empty)

case class AlignmentResults(
   questionAlignments: List[TermAlignment] = List.empty,
   choiceAlignments: List[TermAlignment] = List.empty,
   paragraphAlignments: List[TermAlignment] = List.empty
)

case class Entity(entityName: String, surface: String, boundaries: Seq[(Int, Int)])
case class Relation(relationName: String, entity1: String, entity2: String)
case class EntityRelationResult(fullString: String, entities: Seq[Entity], relations: Seq[Relation], explanation: String = "")

import play.api.libs.json._

object ResultJson {
  val staticAlignmentResult = AlignmentResults(
    List(
      TermAlignment("In"), TermAlignment("New York State", ArrayBuffer(0)), TermAlignment("the longest period"),
      TermAlignment("of"), TermAlignment("daylight"), TermAlignment("occurs"), TermAlignment("during"),
      TermAlignment("which"), TermAlignment("month"), TermAlignment("?")
    ),
    List(TermAlignment("June", ArrayBuffer(3)), TermAlignment("March"), TermAlignment("December"), TermAlignment("September")),
    List(
      TermAlignment("New York", ArrayBuffer(0)), TermAlignment("is"), TermAlignment("located", ArrayBuffer(2)), TermAlignment("in"),
      TermAlignment("the United States", ArrayBuffer(1)), TermAlignment("of"), TermAlignment("America"), TermAlignment("."),
      TermAlignment("The"), TermAlignment("USA", ArrayBuffer(1)), TermAlignment("is"), TermAlignment("located", ArrayBuffer(2)), TermAlignment("in"),
      TermAlignment("the northern hemisphere", ArrayBuffer(3)), TermAlignment(".")
    )
  )

  val staticEntityRelationResults = EntityRelationResult(
    "Question: In New York State, the longest period of daylight occurs during which month? |Options: (A) June  (B) March  (C) December  (D) September " +
    "|Paragraph: New York is located in United States. USA is located in northern hemisphere. The summer solstice happens during summer, in northern hemisphere.",
    List(Entity("T1", "New York State", Seq((1,5))), Entity("T2", "United States", Seq((10,15))),
      Entity("T3", "USA", Seq((20,25))), Entity("T4", "northern hemisphere", Seq((30,35)))),
    List(Relation("R1", "T1", "T2"), Relation("R2", "T2", "T3"), Relation("R3", "T3", "T4"))
  )

  val emptyEntityRelation = EntityRelationResult("", List.empty, List.empty)

  implicit val entityWrites = new Writes[Entity] {
    def writes(entity: Entity) = Json.arr(entity.entityName, entity.surface, entity.boundaries.map(pair => Json.arr(pair._1, pair._2)))
  }

  implicit val relationWrites = new Writes[Relation] {
    def writes(relation: Relation) = Json.arr(relation.relationName, "  ", Json.arr(Json.arr("  ", relation.entity1), Json.arr("  ", relation.entity2)))
  }

  implicit val entityRelationWrites = new Writes[EntityRelationResult] {
    def writes(er: EntityRelationResult) = Json.obj(
      "overalString" -> er.fullString,
      "entities" -> er.entities,
      "relations" -> er.relations,
      "explanation" -> er.explanation
    )
  }

  implicit val termWrites = new Writes[TermAlignment] {
    def writes(term: TermAlignment) = Json.obj(
      "term" -> term.term,
      "alignmentIds" -> term.alignmentIds
    )
  }

  implicit val results = new Writes[AlignmentResults] {
    def writes(term: AlignmentResults) = Json.obj(
      "questionAlignments" -> term.questionAlignments,
      "choiceAlignments" -> term.choiceAlignments,
      "paragraphAlignments" -> term.paragraphAlignments
    )
  }
}

