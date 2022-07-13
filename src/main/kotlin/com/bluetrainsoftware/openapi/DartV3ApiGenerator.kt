package com.bluetrainsoftware.openapi

import com.fasterxml.jackson.databind.node.ObjectNode
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.media.ComposedSchema
import io.swagger.v3.oas.models.media.Discriminator
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.parser.ObjectMapperFactory
import io.swagger.v3.parser.util.OpenAPIDeserializer
import org.openapitools.codegen.*
import org.openapitools.codegen.config.GlobalSettings
import org.openapitools.codegen.languages.DartClientCodegen
import org.openapitools.codegen.model.ModelMap
import org.openapitools.codegen.model.ModelsMap
import org.openapitools.codegen.model.OperationsMap
import org.openapitools.codegen.utils.ModelUtils
import org.openapitools.codegen.utils.StringUtils
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.function.Consumer
import java.util.regex.Pattern
import java.util.stream.Collectors

class DartV3ApiGenerator : DartClientCodegen() {
  private var arraysThatHaveADefaultAreNullSafe = false
  private var isNullSafeEnabled = false

  private var extraAnyOfClasses: MutableMap<String, AnyOfClass> = HashMap()
  private var extraAnyParts: MutableSet<String> = HashSet()
  private var listAnyOf = false
  private var use5xStyleNullable = false

  companion object {
    private const val LIBRARY_NAME = "dart2-api"
    private const val DART2_TEMPLATE_FOLDER = "dart2-v3template"
    private const val ARRAYS_WITH_DEFAULT_VALUES_ARE_NULLSAFE = "nullSafe-array-default"
    private const val LIST_ANY_OF = "listAnyOf"

    private val log = LoggerFactory.getLogger(DartV3ApiGenerator::class.java)
  }

  init {
    // we don't need form "inline" models, but the code in the OpenAPI library is incorrect
    // it drops component schema models used by the parameters sent to the API methods
    // so we have to go through and delete them afterwards
    GlobalSettings.setProperty("skipFormModel", "false")
    library = LIBRARY_NAME
    supportedLibraries.clear()
    supportedLibraries[LIBRARY_NAME] = LIBRARY_NAME
  }

  override fun getName(): String {
    return LIBRARY_NAME
  }

  override fun getHelp(): String {
    return "dart2 api generator. generates all classes and interfaces with jax-rs annotations with jersey2 extensions" +
      " as necessary"
  }

  // we keep track of this for the serialiser/deserialiser
  private var extraInternalEnumProperties: MutableList<CodegenProperty> = ArrayList()

  // when external classes are used for the "type" field
  private var extraPropertiesThatUseExternalFormatters: MutableList<CodegenProperty> = ArrayList()

  override fun processOpts() {
    super.processOpts()

    // replace Object with dynamic
    typeMapping["object"] = "dynamic"
    typeMapping["AnyType"] = "dynamic"
    typeMapping["file"] = "ApiResponse"
    typeMapping["binary"] = "ApiResponse"

    // override the location
    templateDir = DART2_TEMPLATE_FOLDER
    embeddedTemplateDir = templateDir
    val libFolder = sourceFolder + File.separator + "lib"
    supportingFiles.clear()

    // none of these are useful.
    apiTestTemplateFiles.clear()
    apiDocTemplateFiles.clear()
    modelTestTemplateFiles.clear()
    modelDocTemplateFiles.clear()
    supportingFiles.add(SupportingFile("pubspec.mustache", "", "pubspec.yaml"))
    supportingFiles.add(SupportingFile("api_client.mustache", libFolder, "api_client.dart"))
    supportingFiles.add(SupportingFile("apilib.mustache", libFolder, "api.dart"))
    additionalProperties["x-internal-enums"] = extraInternalEnumProperties
    additionalProperties["x-external-formatters"] = extraPropertiesThatUseExternalFormatters
    arraysThatHaveADefaultAreNullSafe = additionalProperties.containsKey(ARRAYS_WITH_DEFAULT_VALUES_ARE_NULLSAFE)
    isNullSafeEnabled = additionalProperties.containsKey("nullSafe")
    additionalProperties["x-dart-anyparts"] = extraAnyParts
    listAnyOf = additionalProperties.containsKey(LIST_ANY_OF)
    use5xStyleNullable = additionalProperties.containsKey("x-use-5x-nullable")
  }

  /**
   * People because they are able to include custom mappings, may need to include 3rd party libraries in their pubspec.
   */
  private fun processPubspecMappings() {
    splitSpecifiedExtraDependenciesIntoList("pubspec-dependencies", "x-dart-deps")
    splitSpecifiedExtraDependenciesIntoList("pubspec-dev-dependencies", "x-dart-devdeps")
  }

  // this allows a user to specify additional dependencies required for this artifact, it spits
  // the comma separated list into a proper list, removing empty ones and makes it available in the appropriate
  // vendor-prefix to allow the mustache template to pick it up
  private fun splitSpecifiedExtraDependenciesIntoList(addnPropertiesTag: String, vendorPrefix: String) {
    val depsKey = additionalProperties[addnPropertiesTag] as String?
    val deps: MutableList<String> = ArrayList()

    additionalProperties[vendorPrefix] = deps

    depsKey?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { deps.add(it) }
  }

  /**
   * Because Dart is generating a library, some of the files may be included in the Maven project to be local libs and
   * thus need to have a "part" reference. Others will need to be imported.
   */
  private fun processImportMappings() {
    val partImports: MutableList<String> = ArrayList() // if they have included them locally, no "package" names
    val dartImports: MutableList<String> = ArrayList()

    additionalProperties["x-dart-imports"] = dartImports
    additionalProperties["x-dart-parts"] = partImports

    importMapping.forEach { (_: String, value: String) ->
      if (!value.startsWith("package:")) {
        partImports.add(value)
      } else {
        dartImports.add(value)
      }
    }
  }

  // don't just add stuff to the end of the name willy nilly, check it is a reserved word first
  override fun escapeReservedWord(name: String): String {
    return if (isReservedWord(name)) name + "_" else name
  }

  override fun toVarName(originalName: String): String? {
    var name = originalName

    if (reservedWordsMappings.containsKey(name)) {
      return reservedWordsMappings()[name]
    }

    name = name.replace("-".toRegex(), "_")
      .replace(" ".toRegex(), "_")
      .replace("\\$".toRegex(), "__")
      .replace("\\^".toRegex(), "__")
      .replace("\\+".toRegex(), "_")
      .replace("=".toRegex(), "__").trim { it <= ' ' }

    if (!name.matches("^[A-Z_]*$".toRegex())) {
      name = StringUtils.camelize(name, true)
      if (name.matches("^\\d.*".toRegex())) {
        name = "n$name"
      }
      if (isReservedWord(name)) {
        name = escapeReservedWord(name)
      }
    }
    return name
  }

  override fun toModelFilename(name: String): String? {
    return if (name.contains(".")) {
      val lastIndex = name.lastIndexOf(".")
      val path = name.substring(0, lastIndex).replace(".", File.separator).replace('-', '_')
      val modelName = name.substring(lastIndex + 1)
      path + File.separator + StringUtils.underscore(toModelName(modelName))
    } else {
      StringUtils.underscore(toModelName(name))
    }
  }

  private fun listOrMapSuffix(cp: CodegenProperty): String {
    return if (cp.isNullable && isNullSafeEnabled && "dynamic" != cp.dataType) {
      "?"
    } else ""
  }

  private fun correctPropertyForBinary(model: CodegenModel, cp: CodegenProperty, classLevelField: Boolean): Boolean {
    if (cp.isArray) {
      return correctPropertyForBinary(model, cp.items, classLevelField)
    }

    if (cp.isFile || cp.isBinary) {
      cp.dataType = "MultipartFile"
      cp.baseType = cp.dataType

      return true
    }

    return false
  }

  private fun correctInternals(model: CodegenModel, cp: CodegenProperty, classLevelField: Boolean) {
    if (("DateTime" == cp.complexType) || ("Date" == cp.complexType)) {
      cp.isDateTime = "date-time" == cp.getDataFormat()
      cp.isDate = "date" == cp.getDataFormat()
    }

    if ("dynamic" == cp.complexType) {
      cp.isAnyType = true
      cp.isPrimitiveType = true
    }

    // if the var is binary, fix it to be a MultipartFile
    if (correctPropertyForBinary(model, cp, classLevelField)) {
      cp.vendorExtensions.put("x-var-is-binary", "true")
    }

    var isComposedEnum = if (cp.isModel) {
      detectHiddenEnum(cp, model)
    } else {
      false
    }

    if (cp.allowableValues != null && cp.allowableValues["enumVars"] != null || isComposedEnum) {
      // "internal" enums
      if (cp.getMin() == null && cp.complexType == null) {
        cp.enumName = model.classname + cp.nameInCamelCase + "Enum"
        if (cp.isArray) {
          cp.dataType = "List<" + cp.enumName + listOrMapSuffix(cp) + ">"
        } else if (cp.isMap) {
          cp.dataType = "Map<String, " + cp.enumName + listOrMapSuffix(cp) + ">"
        } else {
          cp.dataType = cp.enumName + if (isNullSafeEnabled) (if (cp.isNullable) "?" else "") else ""
          if (cp.defaultValue != null) {
            if (cp.defaultValue.startsWith("'") && cp.defaultValue.endsWith("'")) {
              cp.defaultValue = cp.defaultValue.substring(1, cp.defaultValue.length - 1)
            }
            cp.defaultValue = cp.dataType + "." + cp.defaultValue
          }
        }

        // we need to add this to the serialiser otherwise it will not correctly serialize
        if (extraInternalEnumProperties.stream().noneMatch { p: CodegenProperty -> p.enumName == cp.enumName }) {
          extraInternalEnumProperties.add(cp)
        }
      } else {
        cp.enumName = cp.complexType
        if (isNullSafeEnabled) {
          cp.dataType = cp.enumName + if (cp.isNullable) "?" else ""
        }
      }
      cp.datatypeWithEnum = cp.dataType
      cp.isEnum = true
      cp.isPrimitiveType = false
    }

    // rewrite the entire map/list thing
    if (classLevelField && !cp.isEnum) {
      if (cp.isMap && cp.items != null) {
        val inner = nullGenChild(cp.items)
        cp.vendorExtensions["x-innerMapType"] = inner
        cp.dataType = "Map<String, " + inner + ">" + if (isNullSafeEnabled) (if (cp.isNullable) "?" else "") else ""
      } else if (cp.isArray && cp.items != null) {
        val inner = nullGenChild(cp.items)
        cp.dataType =
          "List<" + inner + ">" + if (isNullSafeEnabled) (if (cp.isNullable) "?" else "") else ""
        if (!isNullSafeEnabled || !(!cp.isNullable || arraysThatHaveADefaultAreNullSafe)) {
          cp.vendorExtensions["x-list-null"] = java.lang.Boolean.TRUE
        }
      } else if (cp.isNullable && isNullSafeEnabled && "dynamic" != cp.dataType) {
        cp.dataType = cp.dataType + "?"
      }
      cp.vendorExtensions["x-innerType"] = cp.dataType
    }

    // if we have a weird format which is a String/int/etc with an unknown format
    if (cp.isString && cp.complexType != null) {
//      cp.vendorExtensions.put("x-extendedType", Boolean.TRUE);
      cp.isModel = true
      if (extraPropertiesThatUseExternalFormatters.stream()
          .noneMatch { c: CodegenProperty -> c.complexType == cp.complexType }
      ) {
        extraPropertiesThatUseExternalFormatters.add(cp)
      }
    }

    if ((cp.defaultValue == "[]" && cp.isArray) || (cp.defaultValue == "{}" && cp.isMap)) {
      cp.defaultValue = "const " + cp.defaultValue
    }

    if (use5xStyleNullable && !cp.required) {
      cp.isNullable = true
    }

    if (!cp.required && cp.isNullable) {
      cp.vendorExtensions["x-dont-tojson-null"] = "true"
    }

    // now allow arrays to be non nullable by making them empty. Only operates on 1st level because
    // it affects the constructor and defaults of top level fields
    if (classLevelField && cp.isArray && arraysThatHaveADefaultAreNullSafe && cp.defaultValue != null) {
      cp.defaultValue = "const []"
      cp.vendorExtensions["x-ns-default-val"] = java.lang.Boolean.TRUE
      if (cp.items != null) { // it should be not null, its an array
        cp.items.vendorExtensions["x-ns-default-val"] = java.lang.Boolean.TRUE
      }
      var props: MutableList<CodegenProperty>? // type-unsafe upstream api
        = model.vendorExtensions["x-ns-default-vals"] as MutableList<CodegenProperty>?

      if (props == null) {
        props = mutableListOf()
        model.vendorExtensions["x-ns-default-vals"] = props
        model.vendorExtensions["x-has-ns-default-vals"] = java.lang.Boolean.TRUE
      }

      props.add(cp)
    }

    if ((cp.required || cp.defaultValue == null) && !cp.isNullable) {
      cp.vendorExtensions["x-dart-required"] = "true"
    }
  }

  // detect whether the schema follows the pattern:
  // "{ "nullable" : true, "allOf" : [ { "$ref" : <SomeEnum> } ]}",
  // if yes, treat it like an enum.
  // this is a fix for https://github.com/dart-ogurets/dart-openapi-maven/issues/48
  private fun detectHiddenEnum(
      cp: CodegenProperty,
      model: CodegenModel
  ): Boolean {
    var isComposedEnum = false
    try {
      val schemaNode = ObjectMapperFactory.createJson().readTree(cp.jsonSchema) as ObjectNode
      val parsedSchema = OpenAPIDeserializer().getSchema(schemaNode, "", null)
      if (parsedSchema is ComposedSchema) {
        val allOfArray// cannot be fixed unless upstream changes its signatures to Schema<?>
          = parsedSchema.allOf
        val oneOfArray// cannot be fixed unless upstream changes its signatures to Schema<?>
          = parsedSchema.oneOf
        val anyOfArray// cannot be fixed unless upstream changes its signatures to Schema<?>
          = parsedSchema.anyOf
        if (allOfArray.size == 1 && anyOfArray == null || anyOfArray!!.isEmpty() && oneOfArray == null || oneOfArray!!.isEmpty()) {
          val singleRef = allOfArray[0].`$ref`
          if (singleRef != null) {
            val extractLastPathComponent = Pattern.compile("#/components\\/schemas\\/([a-zA-Z\\d\\.\\-_]+)")
              .matcher(singleRef)
            if (extractLastPathComponent.matches()) {
              val referencedEnum = openAPI.components.schemas[extractLastPathComponent.group(1)]!!.enum
              if (referencedEnum != null && referencedEnum.isNotEmpty()) {
                isComposedEnum = true
                cp.isModel = false
                cp.isEnum = true
                cp.isAnyType = false
              }
            }
          }
        }
      }
    } catch (e: Exception) {
      log.error(
        "An Exception was thrown attempting to determine if model {} is an enum wrapped in an allOf composition: {}",
        model.name, e.toString()
      )
      // continue Execution
    }
    return isComposedEnum
  }

  private fun nullGenChild(cp: CodegenProperty): String {
    val `val`: String
    if (cp.isMap) {
      `val` = "Map<String, " + nullGenChild(cp.items) + ">" + listOrMapSuffix(cp)
    } else if (cp.isArray) {
      `val` =
        "List<" + nullGenChild(cp.items) + ">" + if (isNullSafeEnabled) (if (!cp.isNullable || arraysThatHaveADefaultAreNullSafe) "" else "?") else ""
      if (!isNullSafeEnabled) {
        cp.vendorExtensions["x-list-null"] = java.lang.Boolean.TRUE
      }
    } else {
      `val` = cp.dataType + listOrMapSuffix(cp)
    }
    cp.vendorExtensions["x-innerType"] = `val`
    return `val`
  }

  // this allows us to keep track of files to remove at the end
  // because of a bug in the OpenAPI generator around Form models
  private val modelFilesNotToDeleted: MutableList<String> = ArrayList()

  // for debugging inevitable weirdness in the model generated
  override fun updateAllModels(originalModels: MutableMap<String, ModelsMap>): Map<String, ModelsMap> {
    super.updateAllModels(originalModels)

    if (listAnyOf) {
      // add models for List<AnyOf<*>> classes
      extraAnyOfClasses.values.forEach { anyOfClass: AnyOfClass ->
        val modelMap = anyOfClass.toModelMap(this)
        modelMap["isUsedModel"] = "true"
        modelFilesNotToDeleted.add(anyOfClass.fileName + ".dart")
        originalModels[anyOfClass.fileName] = modelMap
      }
    }

    val allModels = mutableMapOf<String, CodegenModel>()

    originalModels.values.forEach { modelData: ModelsMap ->
      if (modelData.models != null) {
        updateModelMaps(modelData.models, allModels)
      }
    }

    updateModelsWithAllOfInheritance(allModels)

    return originalModels
  }

  private fun updateModelMaps(models: List<ModelMap>, allModels: MutableMap<String, CodegenModel>) {
    models.forEach { modelMap: ModelMap ->
      val cm = modelMap.model ?: return@forEach

      // there is no way way to detect these classes other than by name
      if (cm.name.endsWith("_allOf") || cm.vendorExtensions.containsKey("x-skip-generation")) {
        return@forEach // skip this one, it isn't used at all
      }

      // if NOT an inline object we will definitely use it. Only BODY based inline
      // objects will be otherwise used.
      if (!cm.getName().startsWith("inline_object")) {
        cm.vendorExtensions["isUsedModel"] = "true"
        modelMap["isUsedModel"] = "true"
        modelFilesNotToDeleted.add(cm.classFilename + ".dart")
      }

      if (!cm.getName().endsWith("_allOf")) {
        allModels[cm.getName()] = cm
      }

      cm.vendorExtensions["dartClassName"] = StringUtils.camelize(cm.getClassname())

      if (cm.vars != null) {
        cm.vars.forEach { cp: CodegenProperty ->
          var correctingSettings: CodegenProperty? = cp
          var classLevelField = true

          while (correctingSettings != null) {
            correctInternals(cm, correctingSettings, classLevelField)

            classLevelField = false

            correctingSettings = correctingSettings.items
          }
        }
      }
    }
  }


  private fun updateModelsWithAllOfInheritance(models: Map<String, CodegenModel>) {
    // Workaround for https://github.com/OpenAPITools/openapi-generator/issues/11846
    for (cm in models.values) {
      var parent: CodegenModel? = cm.parentModel

      val properties = cm.vars.map { obj: CodegenProperty -> obj.getName()!! }.toMutableSet()

      while (parent != null) {

        for (cp in parent.vars) {
          if (!properties.contains(cp.getName())) {
            properties.add(cp.getName())
            cm.vars.add(cp.clone())
            cm.hasVars = true
          }
        }

        parent = parent.parentModel
      }
    }

    for (cm in models.values) {
      if (cm.getParent() != null) {
        val parent = models[cm.getParent()]
        if (parent == null) {
          log.info("Cannot find parent for class {}:{}", cm.getName(), cm.getParent())
          continue
        }
        val props = parent.vars.associateBy { it.name }

        cm.vars.forEach { property ->
          val matchingName = props[property.getName()]
          if (matchingName != null) {
            property.vendorExtensions["x-dart-inherited"] = java.lang.Boolean.TRUE
          }
        }

        val cmp = Comparator.comparing { cp: CodegenProperty ->
          cp.vendorExtensions.containsKey(
            "x-dart-inherited"
          )
        }

        cm.vars.sortWith(cmp.reversed())
      }

      if (cm.classname == "Application_allOf") {
        print("hello")
      }

      val ownVars = cm.vars.filter { cp: CodegenProperty ->
          !cp.vendorExtensions.containsKey(
            "x-dart-inherited"
          )
        }

      cm.vendorExtensions["x-dart-ownVars"] = ownVars
      cm.vendorExtensions["x-dart-hasOwnVars"] = ownVars.isNotEmpty()
      val ownDefaultVals = ownVars.filter { cp: CodegenProperty ->
        cp.vendorExtensions.containsKey(
          "x-ns-default-val"
        )
      }

      cm.vendorExtensions["x-own-ns-default-vals"] = ownDefaultVals
      cm.vendorExtensions["x-has-own-ns-default-vals"] = ownDefaultVals.isNotEmpty()
    }
  }

  // for debugging inevitable weirdness in the operations generated. DO NOT MODIFY THE MODEL - it has already been
  // generated to the file
  // system
  override fun postProcessOperationsWithModels(operationsMap: OperationsMap, models: List<ModelMap>): OperationsMap? {
    processImportMappings()
    processPubspecMappings()
    val som = super.postProcessOperationsWithModels(operationsMap, models)
    val ops = operationsMap.operations.operation

    // at this point, all the model files have actually already been generated to disk, that horse has bolted. What
    // we need to do is figured out which models are "form" based and are not required. Basically anything that is
    // directly used by by a form post
    val modelMap: MutableMap<String, CodegenModel> = HashMap()
    val modelMetaMap: MutableMap<String, ModelMap> = HashMap()
    models.forEach(Consumer { m: ModelMap ->
      val cm = m.model
      modelMap[cm.getClassname()] = cm
      modelMetaMap[cm.getClassname()] = m
    })

    for (co in ops) {
      val richOp = co.vendorExtensions["x-dart-rich-operationId"]
      if (richOp != null) {
        co.vendorExtensions["x-dart-extension-name"] = toVarName(richOp.toString())
      }
      val bodyIsFile =
        (co.consumes != null) && (co.consumes.size == 1) && ("application/octet-stream" == co.consumes[0]["mediaType"])

      if (co.bodyParam != null) {
        val cm = modelMap[co.bodyParam.baseType]
        if (cm != null) {
          cm.vendorExtensions["isUsedModel"] = "true"
          modelFilesNotToDeleted.add(cm.classFilename + ".dart")
          modelMetaMap[co.bodyParam.baseType]!!["isUsedModel"] = "true"
        }
      }

      co.allParams.forEach(Consumer { p: CodegenParameter ->
        if (p.isFile || p.isBinary || p.isBodyParam && bodyIsFile) {
          if (p.isArray) {
            p.dataType = "List<MultipartFile>"
          } else {
            p.dataType = "MultipartFile"
          }
          p.baseType = p.dataType
        }
      })
    }
    return som
  }

  override fun toDefaultValue(schema: Schema<*>): String? {
    // default inherited one uses const, and we can't use that anymore as they are inmodifiable
    return if (ModelUtils.isMapSchema(schema)) {
      "{}"
    } else if (ModelUtils.isArraySchema(schema)) {
      "[]"
    } else if (schema.default != null) {
      val s = if (ModelUtils.isStringSchema(schema)) "'" + schema.default.toString()
        .replace("'", "\\'") + "'" else schema.default.toString()
      if ("null" == s) null else s
    } else {
      null
    }
  }

  override fun addAdditionPropertiesToCodeGenModel(codegenModel: CodegenModel, schema: Schema<*>?) {
    //super.addAdditionPropertiesToCodeGenModel(codegenModel, schema);
    codegenModel.additionalPropertiesType = getSchemaType(ModelUtils.getAdditionalProperties(openAPI, schema))
    addImport(codegenModel, codegenModel.additionalPropertiesType)
  }

  override fun addOperationToGroup(
    tag: String?, resourcePath: String?, operation: Operation?, co: CodegenOperation,
    operations: Map<String?, List<CodegenOperation?>?>?
  ) {
    super.addOperationToGroup(tag, resourcePath, operation, co, operations)
    tagOperationWithExtension(co.consumes, co.vendorExtensions, "consumes")
    tagOperationWithExtension(co.produces, co.vendorExtensions, "produces")
    val codes = co.responses.stream().map { r: CodegenResponse -> r.code }.collect(Collectors.joining(","))
    co.vendorExtensions["x-valid-status-codes"] = codes
    if (co.returnType == null && co.responses.stream().anyMatch { r: CodegenResponse -> "204" == r.code }) {
      co.vendorExtensions["x-return-no-content"] = java.lang.Boolean.TRUE
    }
  }

  private fun tagOperationWithExtension(
    mediaTypes: List<Map<String, String>>?, extensions: MutableMap<String, Any>,
    midfix: String
  ) {
    val lowerCaseContentTypes = if (mediaTypes != null) mediaTypes.stream().map { p: Map<String, String> ->
      p["mediaType"]!!
        .lowercase(Locale.getDefault())
    }.collect(Collectors.toList()) else ArrayList()
    val prefix = "x-dart-$midfix-"
    if (lowerCaseContentTypes.contains("application/json")) {
      extensions[prefix + "json"] = "application/json"
    } else if (lowerCaseContentTypes.contains("application/x-www-form-urlencoded")) {
      extensions[prefix + "form"] = "application/x-www-form-urlencoded"
    } else if (lowerCaseContentTypes.contains("multipart/form-data")) {
      extensions[prefix + "multipartform"] = "multipart/form-data"
    } else if (lowerCaseContentTypes.contains("application/xml")) {
      extensions[prefix + "xml"] = "application/xml"
    } else if (lowerCaseContentTypes.contains("application/yaml")) {
      extensions[prefix + "yaml"] = "application/yaml"
    } else if (lowerCaseContentTypes.contains("application/octet-stream")) {
      extensions[prefix + "raw"] = "application/octet-stream"
    } else {
      extensions[prefix + "json"] = "application/json" // fallback
    }
  }

  // existing one is unpleasant to use.
  override fun toEnumVarName(value: String, datatype: String?): String? {
    if (value.isEmpty()) {
      return "empty"
    }
    var `var` = value
    if ("number".equals(datatype, ignoreCase = true) ||
      "int".equals(datatype, ignoreCase = true)
    ) {
      `var` = "Number$`var`"
    }
    return toVarName(escapeReservedWord(`var`))
  }

//  Map<String, String> modelNameCache = new HashMap<>();

  //  Map<String, String> modelNameCache = new HashMap<>();
  override fun toModelName(modelName: String): String {
    return if ("dynamic" == modelName) modelName else super.toModelName(modelName)
  }

  override fun postProcess() {
    val flutterDir = if (System.getenv("FLUTTER") == null) System.getenv("DART_BIN") else System.getenv("FLUTTER")

    // we have to do this at the end because we don't know which files to fully delete until
    // after the Operations have been processed, and the post process per file thing happens
    // after each step. We have to do it before the Dart Formatting otherwise it will explode
    // with unreferenced files.
    try {
      val stripLen = "$outputDir/lib/model/".length

      Files.walk(Paths.get("$outputDir/lib/model"))
        .filter { path: Path -> Files.isRegularFile(path) }
        .forEach { p: Path ->
          val modelFilename = p.toFile().absolutePath.substring(stripLen)
          if (!modelFilesNotToDeleted.contains(modelFilename)) {
            p.toFile().delete()
          }
        }
    } catch (ignored: IOException) {
    }

    if (flutterDir != null && isEnablePostProcessFile) {
      val dartPostProcessFile = String.format(
        "%s/bin/cache/dart-sdk/bin/dart format -o write %s", flutterDir,
        outputDir
      )
      try {
//        log.info("auto-fixing generated issues");
//        final Process fix = Runtime.getRuntime().exec(dartPostProcessFixFile);
//        outputStreamToConsole(fix);
//        fix.waitFor();
        log.info("formatting")
        val fmt = Runtime.getRuntime().exec(dartPostProcessFile)
        outputStreamToConsole(fmt)
        fmt.waitFor()
      } catch (e: Exception) {
        log.error("Unable to run dart fix command", e)
      }
    }
  }

  @Throws(Exception::class)
  private fun outputStreamToConsole(proc: Process) {
    var line: String?
    var isr = InputStreamReader(proc.inputStream)
    var rdr = BufferedReader(isr)
    while (rdr.readLine().also { line = it } != null) {
      println(line)
    }
    isr = InputStreamReader(proc.errorStream)
    rdr = BufferedReader(isr)
    while (rdr.readLine().also { line = it } != null) {
      println(line)
    }
  }

  override fun toAnyOfName(originalNames: List<String>, composedSchema: ComposedSchema): String? {
    val names = ArrayList(originalNames)

    if (listAnyOf) {
      // create models for List<anyOf> classes that have discriminator
      if (composedSchema.discriminator != null) {
        names.sort()
        val namesFilename = names.stream().map { name: String ->
          toModelFilename(
            name
          )
        }.collect(Collectors.toList())
        val namesCapitalized = names.stream().map { str: String? ->
          org.apache.commons.lang3.StringUtils.capitalize(
            str
          )
        }.collect(Collectors.toList())
        val className = "AnyOf" + java.lang.String.join("", namesCapitalized)
        val enumClassName = "AnyOfDiscriminator" + java.lang.String.join("", namesCapitalized)
        val fileName = "any_of_" + java.lang.String.join("_", namesFilename)
        val filePart = "model/$fileName.dart"
        // collect any of classes
        extraAnyOfClasses[className] = AnyOfClass(
          fileName, toModelName(className), toModelName(enumClassName),
          composedSchema
        )
        extraAnyParts.add(filePart)
        return className
      }
    }
    return super.toAnyOfName(names, composedSchema)
  }

  class AnyOfClass(
    val fileName: String,
    private val className: String,
    private val enumClassName: String,
    private val composedSchema: ComposedSchema
  ) {
    val discriminatorProperty: Discriminator? = composedSchema.discriminator

    init {
      assert(discriminatorProperty != null)
    }

    fun toModelMap(generator: DartV3ApiGenerator): ModelsMap {
      val data = ModelsMap()
      val innerTypes: MutableList<Map<String, Any?>> = ArrayList()
      val collectedTypes: MutableList<String> = ArrayList()

      data["pubName"] = generator.pubName
      data["isAnyOfClass"] = true
      data["classname"] = className
      data["enumClassname"] = enumClassName
      data["discriminatorProperty"] = discriminatorProperty!!.propertyName
      data["innerTypes"] = innerTypes
      data["nullSafe"] = generator.additionalProperties["nullSafe"]

      composedSchema.anyOf.forEach(Consumer { schema: Schema<*> ->
        val ref = schema.`$ref`
        val type = generator.getTypeDeclaration(schema)
        collectedTypes.add(type)
        var discriminatorValue = ModelUtils.getSimpleRef(ref)
        // lookup discriminator mappings if there any
        if (discriminatorProperty.mapping != null) {
          for ((key, value) in discriminatorProperty.mapping) {
            if (value == ref) {
              discriminatorValue = key
            }
          }
        }
        innerTypes.add(createType(type, discriminatorValue))
      })

      collectedTypes.sort()

      data["anyOfTemplate"] = java.lang.String.join(",", collectedTypes)
      return data
    }

    companion object {
      private fun createType(classname: String, discriminatorValue: String): Map<String, Any?> {
        return mapOf(Pair("classname", classname), Pair("discriminatorValue", discriminatorValue))
      }
    }
  }
}
