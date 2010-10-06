package scalanlp.parser.discrim

import scalala.tensor.counters.Counters._
import scalanlp.collection.mutable.{SparseArrayMap, SparseArray, ArrayMap}

import scalala.tensor.sparse.SparseVector;
import scalanlp.parser._;
import scalanlp.trees._;
import scalanlp.util.Encoder;
import scalanlp.util.Index;


trait Feature[+L,+W];

/**
 * 
 * @author dlwh
 */
trait Featurizer[L,W] {
  def featuresFor(r: Rule[L]):DoubleCounter[Feature[L,W]];
  def featuresFor(l: L, w: W):DoubleCounter[Feature[L,W]];

  /** should return 0.0 if we don't care about this feature. */
  def initialValueForFeature(f: Feature[L,W]):Double;
}


case class RuleFeature[L](r: Rule[L]) extends Feature[L,Nothing];
case class LexicalFeature[L,W](l: L, w: W) extends Feature[L,W];

class SimpleFeaturizer[L,W] extends Featurizer[L,W] {
  def featuresFor(r: Rule[L]) = aggregate(RuleFeature(r) -> 1.0);
  def featuresFor(l: L, w: W) = aggregate(LexicalFeature(l,w) -> 1.0);

  def initialValueForFeature(f: Feature[L,W]) = 0.0;
}

class SmartLexFeaturizer[L](lexicon: PairedDoubleCounter[L,String]) extends Featurizer[L,String] {
  val wordshape = new WordShapeFeaturizer(lexicon);
  def featuresFor(r: Rule[L]) = aggregate(RuleFeature(r) -> 1.0);
  def featuresFor(l: L, w: String)  = wordshape.featuresFor(l,w);

  def initialValueForFeature(f: Feature[L,String]) = f match {
    case r: RuleFeature[L] => -1.0;
    case _ => wordshape.initialValueForFeature(f);
  }
}

trait FeatureIndexer[L,W] extends Encoder[Feature[L,W]] {
  val index:Index[Feature[L,W]];
  val labelIndex: Index[L];
  val featurizer: Featurizer[L,W];

  // a -> b c -> SparseVector of feature weights
  val binaryRuleCache: Array[SparseArray[SparseArray[SparseVector]]]
  // a -> b SparseVector
  val unaryRuleCache: Array[SparseArray[SparseVector]]
  // a -> W map
  val lexicalCache: Array[Map[W,SparseVector]]


  def featuresFor(a: Int, b: Int, c: Int) = {
    if(binaryRuleCache(a)(b)(c) == null)
      stripEncode(featurizer.featuresFor(BinaryRule(labelIndex.get(a),labelIndex.get(b), labelIndex.get(c))));
    else binaryRuleCache(a)(b)(c);
  }

  def featuresFor(a: Int, b: Int) = {
    if(unaryRuleCache(a)(b) == null) stripEncode(featurizer.featuresFor(UnaryRule(labelIndex.get(a),labelIndex.get(b))));
    else unaryRuleCache(a)(b);
  }

  def featuresFor(a: Int, w: W) = {
    if(!lexicalCache(a).contains(w)) stripEncode(featurizer.featuresFor(labelIndex.get(a),w));
    else lexicalCache(a)(w);
  }

  def initialValueFor(f: Feature[L,W]):Double = featurizer.initialValueForFeature(f);

  def initialValueFor(f: Int):Double = initialValueFor(index.get(f));

  // strips out features we haven't seen before.
  private def stripEncode(ctr: DoubleCounter[Feature[L,W]]) = {
    val res = mkSparseVector();
    for( (k,v) <- ctr) {
      val ind = index(k);
      if(ind != -1) {
        res(ind) = v;
      }
    }
    res;
  }
}

object FeatureIndexer {
  def apply[L,W](f: Featurizer[L,W], trees: Iterable[(BinarizedTree[L],Seq[W])]) = {
    val labelIndex = Index[L]();
    val featureIndex = Index[Feature[L,W]]();

    // a -> b c -> SparseVector of feature weights
    val binaryRuleCache = new ArrayMap(new SparseArrayMap(new SparseArrayMap[DoubleCounter[Feature[L,W]]](null)));
    // a -> b SparseVector
    val unaryRuleCache = new ArrayMap(new SparseArrayMap[DoubleCounter[Feature[L,W]]](null));
    // a -> W map
    val lexicalCache = new ArrayMap(collection.mutable.Map[W,DoubleCounter[Feature[L,W]]]());

    for {
      (t,words) <- trees;
      t2 <- t.allChildren
    } {
      t2 match {
        case BinaryTree(a,Tree(b,_),Tree(c,_)) =>
          val ia = labelIndex.index(a);
          val ib = labelIndex.index(b);
          val ic = labelIndex.index(c);
          if(binaryRuleCache(ia)(ib)(ic) eq null) {
            val feats = f.featuresFor(new BinaryRule(a,b,c));
            binaryRuleCache(ia)(ib)(ic) = feats;
            feats.keysIterator.foreach {featureIndex.index _ };
          }
        case UnaryTree(a,Tree(b,_)) =>
          val ia = labelIndex.index(a);
          val ib = labelIndex.index(b);
          if(unaryRuleCache(ia)(ib) eq null) {
            val feats = f.featuresFor(new UnaryRule(a,b));
            unaryRuleCache(ia)(ib) = feats;
            feats.keysIterator.foreach {featureIndex.index _ };
          }
        case n@NullaryTree(a) =>
          val w = words(n.span.start);
          val ia = labelIndex.index(a);
          if(!lexicalCache(ia).contains(w)) {
            val feats = f.featuresFor(a,w);
            lexicalCache(ia)(w) = feats;
            feats.keysIterator.foreach {featureIndex.index _ };
          }

      }

    }
    val lI = labelIndex;
    val featureEncoder = Encoder.fromIndex(featureIndex);
    val brc =  Array.tabulate(lI.size){ a =>
      val bArray = new SparseArray(lI.size, new SparseArray[SparseVector](lI.size, null));
      for((b,cArrayMap) <- binaryRuleCache(a)) {
        for( (c,ctr) <- cArrayMap) {
           bArray(b)(c) = featureEncoder.encodeSparse(ctr);
        }
      }
      bArray;
    };

    val urc = Array.tabulate(lI.size){ a =>
      val bArray =  new SparseArray[SparseVector](lI.size, null);
      for( (b,ctr) <- unaryRuleCache(a))
         bArray(b) = featureEncoder.encodeSparse(ctr);
      bArray;
    }

    val lrc = Array.tabulate(lI.size){ (a) =>
      lexicalCache(a).mapValues(featureEncoder.encodeSparse _).toMap;
    }
    new FeatureIndexer[L,W] {
      val index = featureIndex;
      val labelIndex = lI;
      val featurizer = f;

      // a -> b c -> SparseVector of feature weights
      val binaryRuleCache: Array[SparseArray[SparseArray[SparseVector]]] = brc;
      // a -> b SparseVector
      val unaryRuleCache: Array[SparseArray[SparseVector]] = urc
      // a -> W map
      val lexicalCache: Array[Map[W,SparseVector]] = lrc;

    }
  }

}

