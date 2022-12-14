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

package com.salesforce.op

import java.io.File
import java.nio.charset.StandardCharsets

import com.salesforce.op.features.FeatureJsonHelper
import com.salesforce.op.filters.RawFeatureFilterResults
import com.salesforce.op.stages.{OPStage, OpPipelineStageWriter}
import enumeratum._
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.ml.util.MLWriter
import org.json4s.JsonAST.{JArray, JObject, JString}
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.json4s.{DefaultFormats, Formats}
import org.zeroturnaround.zip.ZipUtil

/**
 * Writes the [[OpWorkflowModel]] to json format.
 * For now we will not serialize the parent of the model
 *
 * @note The features/stages must be sorted in topological order
 *
 * @param model workflow model to save
 */
class OpWorkflowModelWriter(val model: OpWorkflowModel) extends MLWriter {

  implicit val jsonFormats: Formats = DefaultFormats

  protected var modelStagingDir: String = WorkflowFileReader.modelStagingDir

  /**
   * Set the local folder to copy and unpack stored model to for loading
   */
  def setModelStagingDir(localDir: String): this.type = {
    modelStagingDir = localDir
    this
  }

  override protected def saveImpl(path: String): Unit = {
    val conf = this.sparkSession.sparkContext.hadoopConfiguration
    val localFileSystem = FileSystem.getLocal(conf)
    val localPath = localFileSystem.makeQualified(new Path(modelStagingDir))
    localFileSystem.delete(localPath, true)
    val raw = new Path(localPath, WorkflowFileReader.rawModel)

    val rawPathStr = raw.toString
    val modelJson = toJsonString(rawPathStr)
    val jsonPath = OpWorkflowModelReadWriteShared.jsonPath(rawPathStr)
    val os = localFileSystem.create(new Path(jsonPath))
    try {
      os.write(modelJson.getBytes(StandardCharsets.UTF_8.toString))
    } finally {
      os.close()
    }

    val compressed = new Path(localPath, WorkflowFileReader.zipModel)
    ZipUtil.pack(new File(raw.toUri.getPath), new File(compressed.toUri.getPath))

    val finalPath = new Path(path, WorkflowFileReader.zipModel)
    val destinationFileSystem = finalPath.getFileSystem(conf)
    destinationFileSystem.moveFromLocalFile(compressed, finalPath)
  }

  /**
   * Json serialize model instance
   *
   * @param path to save the model and its stages
   * @return model json string
   */
  def toJsonString(path: String): String = compact(render(toJson(path)))

  /**
   * Json serialize model instance
   *
   * @param path to save the model and its stages
   * @return model json
   */
  def toJson(path: String): JObject = {
    val FN = OpWorkflowModelReadWriteShared.FieldNames
    (FN.Uid.entryName -> model.uid) ~
      (FN.ResultFeaturesUids.entryName -> resultFeaturesJArray) ~
      (FN.BlocklistedFeaturesUids.entryName -> blocklistFeaturesJArray()) ~
      (FN.BlocklistedMapKeys.entryName -> blocklistMapKeys()) ~
      (FN.BlocklistedStages.entryName -> blocklistedStagesJArray(path)) ~
      (FN.Stages.entryName -> stagesJArray(path)) ~
      (FN.AllFeatures.entryName -> allFeaturesJArray) ~
      (FN.Parameters.entryName -> model.getParameters().toJson(pretty = false)) ~
      (FN.TrainParameters.entryName -> model.trainingParams.toJson(pretty = false)) ~
      (FN.RawFeatureFilterResultsFieldName.entryName ->
        RawFeatureFilterResults.toJson(model.getRawFeatureFilterResults()))
  }

  private def resultFeaturesJArray(): JArray =
    JArray(model.getResultFeatures().map(_.uid).map(JString).toList)

  private def blocklistFeaturesJArray(): JArray =
    JArray(model.getBlocklist().map(_.uid).map(JString).toList)

  private def blocklistMapKeys(): JObject =
    JObject(model.getBlocklistMapKeys().map { case (k, vs) => k -> JArray(vs.map(JString).toList) }.toList)

  /**
   * Serialize all the model stages
   *
   * @param path path to store the spark params for stages
   * @return array of serialized stages
   */
  private def stagesJArray(path: String): JArray = {
    val stages = model.getRawFeatures().map(_.originStage) ++ model.getStages()
    stagesJArray(stages, path)
  }

  /**
   * Serialize all the blocklisted model stages
   *
   * @param path path to store the spark params for stages
   * @return array of serialized stages
   */
  private def blocklistedStagesJArray(path: String): JArray = {
    val blocklistStages = model.getBlocklist().map(_.originStage)
    stagesJArray(blocklistStages, path)
  }

  /**
   * Serialize the stages
   *
   * @param stages path to store the spark params for stages
   * @param path path to store the spark params for stages
   * @return array of serialized stages
   */
  private def stagesJArray(stages: Array[OPStage], path: String): JArray = {
    val stagesJson = stages
      .map(_.write.asInstanceOf[OpPipelineStageWriter].writeToJson(path))
      .filter(_.children.nonEmpty)
    JArray(stagesJson.toList)
  }

  /**
   * Gets all features to be serialized.
   *
   * @note Features should be topologically sorted
   * @return all features to be serialized
   */
  private def allFeaturesJArray: JArray =
    JArray(model.getAllFeatures().map(FeatureJsonHelper.toJson).toList)

}

/**
 * Shared functionality between [[OpWorkflowModelWriter]] and [[OpWorkflowModelReader]]
 */
private[op] object OpWorkflowModelReadWriteShared {
  def jsonPath(path: String): String = new Path(path, "op-model.json").toString

  /**
   * Model json field names
   */
  sealed abstract class FieldNames(override val entryName: String) extends EnumEntry

  /**
   * Model json field names
   */
  object FieldNames extends Enum[FieldNames] {
    val values = findValues
    case object Uid extends FieldNames("uid")
    case object ResultFeaturesUids extends FieldNames("resultFeaturesUids")
    case object BlocklistedFeaturesUids extends FieldNames("blocklistedFeaturesUids")
    case object BlocklistedMapKeys extends FieldNames("blocklistedMapKeys")
    case object BlocklistedStages extends FieldNames("blocklistedStages")
    case object Stages extends FieldNames("stages")
    case object AllFeatures extends FieldNames("allFeatures")
    case object Parameters extends FieldNames("parameters")
    case object TrainParameters extends FieldNames("trainParameters")
    case object RawFeatureFilterResultsFieldName extends FieldNames("rawFeatureFilterResults")
  }

  object DeprecatedFieldNames extends Enum[FieldNames] {
    val values = findValues
    case object OldBlocklistedFeaturesUids extends FieldNames("blacklistedFeaturesUids")
    case object OldBlocklistedMapKeys extends FieldNames("blacklistedMapKeys")
    case object OldBlocklistedStages extends FieldNames("blacklistedStages")
  }

}


/**
 * Writes the OpWorkflowModel into a specified path
 */
object OpWorkflowModelWriter {
  /**
   * Save [[OpWorkflowModel]] to path
   *
   * @param model workflow model instance
   * @param path      path to save the model and its stages
   * @param overwrite should overwrite the destination
   * @param modelStagingDir local folder to copy and unpack stored model to for loading
   */
  def save(
    model: OpWorkflowModel,
    path: String,
    overwrite: Boolean = true,
    modelStagingDir: String = WorkflowFileReader.modelStagingDir
  ): Unit = {
    val w = new OpWorkflowModelWriter(model).setModelStagingDir(modelStagingDir)
    val writer = if (overwrite) w.overwrite() else w
    writer.save(path)
  }

  /**
   * Serialize [[OpWorkflowModel]] to json
   *
   * @param model workflow model instance
   * @param path  path to save the model and its stages
   */
  def toJson(model: OpWorkflowModel, path: String): String = {
    new OpWorkflowModelWriter(model).toJsonString(path)
  }
}
