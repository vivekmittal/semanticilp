package controllers

import javax.inject._

import models.StaticContent
import org.allenai.ari.solvers.textilp.ResultJson
import org.allenai.ari.solvers.textilp.ResultJson._
import org.allenai.ari.solvers.textilp.solvers.{SalienceSolver, TextILPSolver}

import play.api.mvc._
import play.api.libs.json._

/** This controller creates an `Action` to handle HTTP requests to the
  * application's home page.
  */
@Singleton
class SolveQuestion @Inject() extends Controller {

  lazy val salienceSolver = new SalienceSolver()
  lazy val textilpSolver = new TextILPSolver()

  /** Create an Action to render an HTML page with a welcome message.
    * The configuration in the `routes` file means that this method
    * will be called when the application receives a `GET` request with
    * a path of `/`.
    */
  def index = Action {
    Ok(views.html.main("", "", "", "", StaticContent.initialFormContent, Json.toJson(ResultJson.staticEntityRelationResults).toString))
  }

  def solve = Action (parse.json) { request =>
    val solverType = (request.body \ "solverType").as[JsString].value
    val question = (request.body \ "question").as[JsString].value
    val options = (request.body \ "options").as[JsString].value
    val snippet = (request.body \ "snippet").as[JsString].value

    println("solver type : " + solverType)
    val solverContent = if(solverType.toLowerCase.contains("salience")) {
      println("Calling salience . . . ")
      val (_, out) = salienceSolver.solve(question, options.split("//").toSet, snippet)
      println("Salience solver response ..... ")
      println(out)
      out
    }
    else if(solverType.toLowerCase.contains("textilp")) {
      println("Calling textilp. . . ")
      val (_, out) = textilpSolver.solve(question, options.split("//").toSet, snippet)
      println("textilp solver response ..... ")
      println(out)
      out
    }
    else {
      throw new Exception("the solver not found :/")
    }

    println("Sending new resultls ")

    Ok(Json.toJson(solverContent).toString())
  }

  def getPrefilledQuestion(index: Int) = Action { request =>
    val question = StaticContent.getContentWithPrefilled(index).questionOpt.get.str
    val options = StaticContent.getContentWithPrefilled(index).questionOpt.get.questionChoice
    val snippet = StaticContent.getContentWithPrefilled(index).questionOpt.get.snippet.str
    Ok(views.html.main("", question, options, snippet, StaticContent.getContentWithPrefilled(index), Json.toJson(ResultJson.emptyEntityRelation).toString))
  }

}