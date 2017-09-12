package utest

import utest.framework._

import scala.concurrent.Future
import scala.concurrent.duration.Deadline
import scala.util.{Failure, Success}

/**
  * Created by lihaoyi on 12/9/17.
  */
object TestRunner {

  /**
    * Runs this `Tree[Test]` asynchronously and returns a `Future` containing
    * the tree of the results.
    *
    * @param onComplete Called each time a single [[Test]] finishes
    * @param ec Used to
    */
  def runAsync(tests: Tests,

               onComplete: (Seq[String], Result) => Unit = (_, _) => (),
               query: Query#Trees = Nil,
               executor: Executor = Executor,
               ec: concurrent.ExecutionContext = utest.framework.ExecutionContext.RunNow): Future[HTree[String, Result]] = {
    implicit val ec0 = ec
    resolveQueryIndices(tests.nameTree, query, Nil) match{
      case Left(errors) => throw new utest.NoSuchTestException(errors:_*)
      case Right(resolution) =>
        val thunkTree = collectQueryTerminals(tests.nameTree, resolution, Nil, Nil)

        val forced = thunkTree.mapLeaves{case (revStringPath, revIntPath) => () =>
          val head = revStringPath.headOption.getOrElse("")

          val start = Deadline.now
          val res: Future[Any] = executor.utestWrap(revStringPath.reverse,
            try tests.callTree.run(revIntPath.reverse) match{
              case x: Future[_] => x
              case notFuture => Future.successful(notFuture)
            } catch{
              case e: Throwable => Future.failed(e)
            }
          )

          def millis = (Deadline.now-start).toMillis
          res.map(v => Result(head, Success(v), millis))
            .recover{case e: Throwable =>
              Result(head, Failure(unbox(e)), millis)
            }
            .map{r =>
              onComplete(revStringPath.reverse, r)
              r
            }
        }

        evaluateFutureTree(forced)
    }
  }

  def run(tests: Tests,
          onComplete: (Seq[String], Result) => Unit = (_, _) => (),
          query: Seq[Tree[String]] = Nil,
          executor: Executor = utest.framework.Executor,
          ec: concurrent.ExecutionContext = utest.framework.ExecutionContext.RunNow): HTree[String, Result] = {

    PlatformShims.await(runAsync(tests, onComplete, query, executor, ec))
  }
  def runAndPrintAsync(tests: Tests,
                       label: String,
                       query: Seq[Tree[String]] = Nil,
                       printStream: java.io.PrintStream = System.out,
                       executor: Executor = Executor,
                       formatter: utest.framework.Formatter = utest.framework.Formatter,
                       ec: concurrent.ExecutionContext = utest.framework.ExecutionContext.RunNow): Future[HTree[String, Result]] = {
    implicit val ec0 = ec
    runAsync(
      tests,
      onComplete = (subpath, res) => {
        formatter.formatSingle(label +: subpath, res)
          .foreach(printStream.println)
      },
      query,
      executor,
      ec
    )
  }
  def runAndPrint(tests: Tests,
                  label: String,
                  query: Seq[Tree[String]] = Nil,
                  printStream: java.io.PrintStream = System.out,
                  executor: Executor = Executor,
                  formatter: utest.framework.Formatter = utest.framework.Formatter,
                  ec: concurrent.ExecutionContext = utest.framework.ExecutionContext.RunNow): HTree[String, Result] = {
    PlatformShims.await(runAndPrintAsync(tests, label, query, printStream, executor, formatter, ec))
  }

  /**
    * For some reason Scala futures boxes `Error`s into `ExecutionException`s,
    * so un-box them to show the user since he probably doesn't care about
    * this boxing
    */
  def unbox(res: Throwable) = res match{
    case e: java.util.concurrent.ExecutionException
      if e.getMessage == "Boxed Error" =>
      e.getCause
    case r => r
  }


  def evaluateFutureTree[N, L](t: HTree[N, () => Future[L]])
                              (implicit ec: concurrent.ExecutionContext): Future[HTree[N, L]] = {
    t match{
      case HTree.Leaf(f) => f().map(HTree.Leaf(_))
      case HTree.Node(v, children @ _*) =>
        for{
          childValues <- Future.traverse(children.toSeq)(evaluateFutureTree(_))
        } yield HTree.Node(v, childValues:_*)
    }

  }

  /**
    * Takes a query tree and resolves the strings against the test tree of a
    * test suite, either returning the query trees strings converted into
    * integer indices into the test tree, or the list of paths which the query
    * tree tried to access but did not exist.
    */
  def resolveQueryIndices(test: Tree[String],
                          query: Seq[Tree[String]],
                          revStringPath: List[String]): Either[Seq[Seq[String]], Seq[Tree[Int]]] = {
    val strToIndex = test.children.map(_.value).zipWithIndex.toMap

    val childResults = for(q <- query) yield {

      strToIndex.get(q.value) match{
        case None => Left(Seq((q.value :: revStringPath).reverse))
        case Some(index) =>
          resolveQueryIndices(test.children(index), q.children, revStringPath) match{
            case Right(res) => Right(Tree(index, res:_*))
            case Left(l) => Left(l)
          }
      }
    }

    val left = collection.mutable.Buffer.empty[Seq[String]]
    val right = collection.mutable.Buffer.empty[Tree[Int]]
    childResults.foreach{
      case Left(l) => left.appendAll(l)
      case Right(r) => right.append(r)
    }

    if (left.nonEmpty) Left(left)
    else Right(right)
  }

  /**
    * Recurses into the test tree using a pre-[[resolveQueryIndices]]ed query tree, going
    * straight to the terminal nodes of the query tree before handing over to
    * [[collectTestNodes]] to collect all the tests within those nodes.
    */
  def collectQueryTerminals(test: Tree[String],
                            query: Seq[Tree[Int]],
                            revIntPath: List[Int],
                            revStringPath: List[String]): HTree[String, (List[String], List[Int])] = {

    if (query.isEmpty) collectTestNodes(test, revIntPath, revStringPath)
    else{
      val children = for(subquery <- query) yield {
        val testChild = test.children(subquery.value)
        collectQueryTerminals(
          testChild,
          subquery.children,
          subquery.value :: revIntPath,
          testChild.value :: revStringPath
        )
      }

      HTree.Node(revStringPath.headOption.getOrElse(""), children:_*)
    }
  }

  /**
    * Pick up all terminal nodes within the given test tree, so they can be
    * executed and their results recorded.
    */
  def collectTestNodes(test: Tree[String],
                       revIntPath: List[Int],
                       revStringPath: List[String]): HTree[String, (List[String], List[Int])] = {
    if (test.children.isEmpty) {
      HTree.Leaf((revStringPath, revIntPath))
    }
    else{
      HTree.Node(
        test.value,
        test.children.zipWithIndex.map{case (c, i) =>
          collectTestNodes(c, i :: revIntPath, c.value :: revStringPath)
        }:_*
      )
    }
  }
}