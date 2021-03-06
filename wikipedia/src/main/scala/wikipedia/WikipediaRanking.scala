package wikipedia

import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._

import org.apache.spark.rdd.RDD

case class WikipediaArticle(title: String, text: String) {
  /**
    * @return Whether the text of this article mentions `lang` or not
    * @param lang Language to look for (e.g. "Scala")
    */
  def mentionsLanguage(lang: String): Boolean = text.split(' ').contains(lang)
}

// Master kick-off SPARK_MASTER_IP=192.168.0.8 ./sbin/start-master.sh
// Slaves          ./sbin/start-slave.sh  spark://192.168.0.8:7077


object WikipediaRanking {

  val langs = List(
    "JavaScript", "Java", "PHP", "Python", "C#", "C++", "Ruby", "CSS",
    "Objective-C", "Perl", "Scala", "Haskell", "MATLAB", "Clojure", "Groovy")

  val conf: SparkConf = new SparkConf()
                              .setAppName("wikipedia")
                              .set("spark.cores.max","4")
                              //.set("spark.driver.bindAddress", "1")
                              //.set("spark.driver.host", "192.168.0.8")
                              //.set("spark.driver.port","9929")
                              //.set("spark.driver.port", "51810")
                              //.set("spark.fileserver.port", "51811")
                               //.set("spark.broadcast.port", "51812")
                              //.set("spark.replClassServer.port", "51813")
                              //.set("spark.blockManager.port", "51814")
                              //.set("spark.executor.port", "51815")
                              //.set("spark.local.ip","192.168.0.8")
                              .setMaster("spark://192.168.0.8:7077")
                              //.setMaster("local[5]")

  val sc: SparkContext = new SparkContext(conf)

  // Hint: use a combination of `sc.textFile`, `WikipediaData.filePath` and `WikipediaData.parse`
  val wikiRdd: RDD[WikipediaArticle] = sc.textFile(WikipediaData.filePath).map(WikipediaData.parse).persist()


  /** Returns the number of articles on which the language `lang` occurs.
    * Hint1: consider using method `aggregate` on RDD[T].
    * Hint2: consider using method `mentionsLanguage` on `WikipediaArticle`
    */
  def occurrencesOfLang(lang: String, rdd: RDD[WikipediaArticle]): Int =
    rdd.aggregate(0)( (occurences, article) =>
      if (article.text.split(" ").indexOf(lang) >= 0) occurences + 1 else occurences, (a, b) => a + b)

  /* (1) Use `occurrencesOfLang` to compute the ranking of the languages
   *     (`val langs`) by determining the number of Wikipedia articles that
   *     mention each language at least once. Don't forget to sort the
   *     languages by their occurrence, in decreasing order!
   *
   *   Note: this operation is long-running. It can potentially run for
   *   several seconds.
   */
  def rankLangs(langs: List[String], rdd: RDD[WikipediaArticle]): List[(String, Int)] =
    langs.map(lan => (lan, occurrencesOfLang(lan, rdd))).sortBy( - _._2 )


  /* Compute an inverted index of the set of articles, mapping each language
   * to the Wikipedia pages in which it occurs.
   */
  def makeIndex(langs: List[String], rdd: RDD[WikipediaArticle]): RDD[(String, Iterable[WikipediaArticle])] = {
    val articles = rdd.flatMap(article => {

      val langsThatMentioned = for {
        lang <- langs
        if (article.text.split(" ").contains(lang))
      } yield lang

      langsThatMentioned.map(lang => (lang, article))
    })
    articles.groupByKey
  }

  /* (2) Compute the language ranking again, but now using the inverted index. Can you notice
   *     a performance improvement?
   *
   *   Note: this operation is long-running. It can potentially run for
   *   several seconds.
   */
  def rankLangsUsingIndex(index: RDD[(String, Iterable[WikipediaArticle])]): List[(String, Int)] = {
    // sort by size
    val sortedBySize = index.mapValues(_.size)
    // invert order
    sortedBySize.sortBy(-_._2).collect().to
  }

  /* (3) Use `reduceByKey` so that the computation of the index and the ranking are combined.
   *     Can you notice an improvement in performance compared to measuring *both* the computation of the index
   *     and the computation of the ranking? If so, can you think of a reason?
   *
   *   Note: this operation is long-running. It can potentially run for
   *   several seconds.
   */
  def rankLangsReduceByKey(langs: List[String], rdd: RDD[WikipediaArticle]): List[(String, Int)] = {
    rdd.flatMap(
      article => langs.filter(lang => article.text.split(" ").indexOf(lang) >= 0).map( lg => (lg, 1) )
    ).reduceByKey(_ + _)
      .collect().sortBy( - _._2 )
        .toList
  }


  def main(args: Array[String]) {

    /* Languages ranked according to (1) */
    val langsRanked: List[(String, Int)] = timed("Part 1: naive ranking", rankLangs(langs, wikiRdd))

    /* An inverted index mapping languages to wikipedia pages on which they appear */
    def index: RDD[(String, Iterable[WikipediaArticle])] = makeIndex(langs, wikiRdd)

    /* Languages ranked according to (2), using the inverted index */
    val langsRanked2: List[(String, Int)] = timed("Part 2: ranking using inverted index", rankLangsUsingIndex(index))

    /* Languages ranked according to (3) */
    val langsRanked3: List[(String, Int)] = timed("Part 3: ranking using reduceByKey", rankLangsReduceByKey(langs, wikiRdd))

    /* Output the speed of each ranking */
    println(timing)

//    println("Press enter to stop server ...")
//    val s = scala.io.StdIn.readLine()

    sc.stop()
  }

  val timing = new StringBuffer

  def timed[T](label: String, code: => T): T = {
    val start = System.currentTimeMillis()
    val result = code
    val stop = System.currentTimeMillis()
    timing.append(s"Processing $label took ${stop - start} ms.\n")
    result
  }
}
