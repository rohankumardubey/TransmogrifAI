/*
 * Copyright (c) 2017, Salesforce.com, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name of the copyright holder nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.salesforce.op.stages.impl.feature

import com.salesforce.op._
import com.salesforce.op.features.types._
import com.salesforce.op.test.{TestFeatureBuilder, TestSparkContext}
import com.salesforce.op.utils.spark.RichDataset._
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner


@RunWith(classOf[JUnitRunner])
class TextVectorizerTest extends FlatSpec with TestSparkContext with AttributeAsserts {
  // scalastyle:off
  lazy val (data, f1, f2) = TestFeatureBuilder(
    Seq[(Text, Text)](
      (Text("Hamlet: To be or not to be - that is the question."), Text("Enter Hamlet")),
      (Text("????????????: ???????? ?????? ???? ???????? - ?????? ?? ?????? ????????????."), Text("???????????? ????????????")),
      (Text("????????: ?????????? ???? ???? ?????????? - ?????? ??????????."), Text("???????? ????????"))
    )
  )
  // scalastyle:on

  "TextVectorizer" should "work correctly out of the box" in {
    val vectorized = f1.vectorize(numHashes = TransmogrifierDefaults.DefaultNumOfFeatures,
      autoDetectLanguage = TextTokenizer.AutoDetectLanguage,
      minTokenLength = TextTokenizer.MinTokenLength,
      toLowercase = TextTokenizer.ToLowercase
    )
    vectorized.originStage shouldBe a[VectorsCombiner]
    vectorized.parents.head.originStage shouldBe a[OPCollectionHashingVectorizer[_]]
    val hasher = vectorized.parents.head.originStage.asInstanceOf[OPCollectionHashingVectorizer[_]].hashingTF()
    val transformed = new OpWorkflow().setResultFeatures(vectorized).transform(data)
    val result = transformed.collect(vectorized)
    val f1NameHash = hasher.indexOf(vectorized.parents.head.originStage.getInputFeatures().head.name)
    val field = transformed.schema(vectorized.name)
    assertNominal(field, Array.fill(result.head.value.size - 1)(false) :+ true, result)
    // scalastyle:off
    result(0).value(hasher.indexOf(s"${f1NameHash}_" + "hamlet")) should be >= 1.0
    result(0).value(hasher.indexOf(s"${f1NameHash}_" + "question")) should be >= 1.0
    result(1).value(hasher.indexOf(s"${f1NameHash}_" + "????????????")) should be >= 1.0
    result(1).value(hasher.indexOf(s"${f1NameHash}_" + "????????????")) should be >= 1.0
    result(1).value(hasher.indexOf(s"${f1NameHash}_" + "????????")) should be >= 2.0
    result(2).value(hasher.indexOf(s"${f1NameHash}_" + "????????")) should be >= 1.0
    result(2).value(hasher.indexOf(s"${f1NameHash}_" + "??????????")) should be >= 2.0
    // scalastyle:on
  }

  it should "allow forcing hashing into a shared hash space" in {
    val vectorized = f1.vectorize(numHashes = TransmogrifierDefaults.DefaultNumOfFeatures,
      autoDetectLanguage = TextTokenizer.AutoDetectLanguage,
      minTokenLength = TextTokenizer.MinTokenLength,
      toLowercase = TextTokenizer.ToLowercase,
      binaryFreq = true,
      others = Array(f2))
    val hasher = vectorized.parents.head.originStage.asInstanceOf[OPCollectionHashingVectorizer[_]].hashingTF()
    val transformed = new OpWorkflow().setResultFeatures(vectorized).transform(data)
    val result = transformed.collect(vectorized)
    val f1NameHash = hasher.indexOf(vectorized.parents.head.originStage.getInputFeatures().head.name)
    val field = transformed.schema(vectorized.name)
    assertNominal(field, Array.fill(result.head.value.size - 2)(false) ++ Array(true, true), result)
    // scalastyle:off
    result(0).value(hasher.indexOf(s"${f1NameHash}_" + "hamlet")) shouldBe 1.0
    result(0).value(hasher.indexOf(s"${f1NameHash}_" + "hamlet")) shouldBe 1.0
    result(1).value(hasher.indexOf(s"${f1NameHash}_" + "????????????")) shouldBe 1.0
    result(1).value(hasher.indexOf(s"${f1NameHash}_" + "????????????")) shouldBe 1.0
    result(2).value(hasher.indexOf(s"${f1NameHash}_" + "????????")) shouldBe 1.0
    result(2).value(hasher.indexOf(s"${f1NameHash}_" + "????????")) shouldBe 1.0
    // scalastyle:on
  }
}
