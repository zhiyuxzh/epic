package epic.logo
import scala.collection.mutable.Buffer
import scala.runtime.DoubleRef

class PegasosUpdater[W](C : Double) extends Updater[W] {

  def update(constraints : IndexedSeq[(FeatureVector[W], Double)], alphas : Buffer[Double], slack : DoubleRef, w : Weights[W], n : Int, iter : Int) : Boolean = {
    assert(constraints.length == 2)
    val (df, _) = constraints(0)
    if ((df ^ 2) == 0.0) return false
    val t = iter + 1
    val lambda = 2.0 / C
    val eta = 1.0 / lambda / t

    w *= (1.0 - eta * lambda)
    w += df * (eta * 1.0 / n)
    val projScale = Math.min(1, 1.0 / Math.sqrt(lambda) / Math.sqrt(w ^ 2))
    w *= projScale

    return true

  }

  def currentSlack(i : Instance[_, W], w : Weights[W]) : Double = {
    throw new UnsupportedOperationException(this.getClass().getName() + " should be only be used in online mode.")
  }

}