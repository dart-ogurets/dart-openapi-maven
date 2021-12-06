package cd.connect.openapi;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.commons.lang3.StringUtils;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.SupportingFile;
import org.openapitools.codegen.config.GlobalSettings;
import org.openapitools.codegen.languages.DartClientCodegen;
import org.openapitools.codegen.utils.ModelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Discriminator;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.ObjectMapperFactory;
import io.swagger.v3.parser.util.OpenAPIDeserializer;

public class DartV3ApiGenerator extends DartClientCodegen {
  private static final Logger log = LoggerFactory.getLogger(DartV3ApiGenerator.class);
  private static final String LIBRARY_NAME = "dart2-api";
  private static final String DART2_TEMPLATE_FOLDER = "dart2-v3template";
  private static final String ARRAYS_WITH_DEFAULT_VALUES_ARE_NULLSAFE = "nullSafe-array-default";
  private static final String LIST_ANY_OF = "listAnyOf";
  protected boolean arraysThatHaveADefaultAreNullSafe;
  protected boolean isNullSafeEnabled;

  Map<String, AnyOfClass> extraAnyOfClasses = new HashMap<String, AnyOfClass>();
  Set<String> extraAnyParts = new HashSet<String>();
  boolean listAnyOf = false;

  public DartV3ApiGenerator() {
    super();

    // we don't need form "inline" models, but the code in the OpenAPI library is incorrect
    // it drops component schema models used by the parameters sent to the API methods
    // so we have to go through and delete them afterwards
    GlobalSettings.setProperty("skipFormModel", "false");

    library = LIBRARY_NAME;
    supportedLibraries.clear();
    supportedLibraries.put(LIBRARY_NAME, LIBRARY_NAME);
  }

  public String getName() {
    return LIBRARY_NAME;
  }

  public String getHelp() {
    return "dart2 api generator. generates all classes and interfaces with jax-rs annotations with jersey2 extensions" +
      " as necessary";
  }

  // we keep track of this for the serialiser/deserialiser
  List<CodegenProperty> extraInternalEnumProperties = new ArrayList<>();
  // when external classes are used for the "type" field
  List<CodegenProperty> extraPropertiesThatUseExternalFormatters = new ArrayList<>();

  @Override
  public void processOpts() {
    super.processOpts();

    // replace Object with dynamic
    this.typeMapping.put("object", "dynamic");
    this.typeMapping.put("AnyType", "dynamic");
    this.typeMapping.put("file", "ApiResponse");
    this.typeMapping.put("binary", "ApiResponse");

    // override the location
    embeddedTemplateDir = templateDir = DART2_TEMPLATE_FOLDER;

    String libFolder = this.sourceFolder + File.separator + "lib";
    this.supportingFiles.clear();

    // none of these are useful.
    this.apiTestTemplateFiles.clear();
    this.apiDocTemplateFiles.clear();
    this.modelTestTemplateFiles.clear();
    this.modelDocTemplateFiles.clear();

    this.supportingFiles.add(new SupportingFile("pubspec.mustache", "", "pubspec.yaml"));
    this.supportingFiles.add(new SupportingFile("api_client.mustache", libFolder, "api_client.dart"));
    this.supportingFiles.add(new SupportingFile("apilib.mustache", libFolder, "api.dart"));

    this.additionalProperties.put("x-internal-enums", extraInternalEnumProperties);
    this.additionalProperties.put("x-external-formatters", extraPropertiesThatUseExternalFormatters);

    arraysThatHaveADefaultAreNullSafe =
      additionalProperties.containsKey(ARRAYS_WITH_DEFAULT_VALUES_ARE_NULLSAFE);

    isNullSafeEnabled = additionalProperties.containsKey("nullSafe");

    this.additionalProperties.put("x-dart-anyparts", extraAnyParts);
    listAnyOf = additionalProperties.containsKey(LIST_ANY_OF);
  }

  /**
   * People because they are able to include custom mappings, may need to include 3rd party libraries in their pubspec.
   */
  private void processPubspecMappings() {
    deps("pubspec-dependencies", "x-dart-deps");
    deps("pubspec-dev-dependencies", "x-dart-devdeps");
  }

  private void deps(String addnPropertiesTag, String vendorPrefix) {
    String depsKey = (String) additionalProperties.get(addnPropertiesTag);
    List<String> deps = new ArrayList<>();
    additionalProperties.put(vendorPrefix, deps);
    if (depsKey != null) {
      Arrays.stream(depsKey.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(deps::add);
    }
  }

  /**
   * Because Dart is generating a library, some of the files may be included in the Maven project to be local libs and
   * thus need to have a "part" reference. Others will need to be imported.
   */
  private void processImportMappings() {
    List<String> partImports = new ArrayList<>(); // if they have included them locally, no "package" names
    List<String> dartImports = new ArrayList<>();

    additionalProperties.put("x-dart-imports", dartImports);
    additionalProperties.put("x-dart-parts", partImports);

    importMapping.forEach((key, value) -> {
      if (!value.startsWith("package:")) {
        partImports.add(value);
      } else {
        dartImports.add(value);
      }
    });
  }

  // don't just add stuff to the end of the name willy nilly, check it is a reserved word first
  @Override
  public String escapeReservedWord(String name) {
    return (isReservedWord(name)) ? name + "_" : name;
  }

  @Override
  @SuppressWarnings("rawtypes") // cannot be fixed unless upstream changes its signatures to Schema<?>
  public CodegenModel fromModel(String name, Schema schema) {
    return super.fromModel(name, schema);
  }

  @Override
  public String toVarName(String name) {
    if (reservedWordsMappings.containsKey(name)) {
      return reservedWordsMappings().get(name);
    }

    name = name.replaceAll("-", "_")
      .replaceAll(" ", "_")
      .replaceAll("\\$", "__")
      .replaceAll("\\^", "__")
      .replaceAll("\\+", "_")
      .replaceAll("\\=", "__").trim();

    if (name.matches("^[A-Z_]*$")) {
      return name;
    } else {
      name = org.openapitools.codegen.utils.StringUtils.camelize(name, true);
      if (name.matches("^\\d.*")) {
        name = "n" + name;
      }

      if (this.isReservedWord(name)) {
        name = this.escapeReservedWord(name);
      }

      return name;
    }
  }

  @Override
  public String toModelFilename(String name) {
    if (name.contains(".")) {
      int lastIndex = name.lastIndexOf(".");
      String path = name.substring(0, lastIndex).replace(".", File.separator).replace('-', '_');
      String modelName = name.substring(lastIndex + 1);
      return path + File.separator + org.openapitools.codegen.utils.StringUtils.underscore(this.toModelName(modelName));
    } else {
      return org.openapitools.codegen.utils.StringUtils.underscore(this.toModelName(name));
    }
  }

  private String listOrMapSuffix(CodegenProperty cp) {
    if (cp.isNullable && isNullSafeEnabled && !"dynamic".equals(cp.dataType)) {
      return "?";
    }

    return "";
  }

  private void correctInternals(CodegenModel model, CodegenProperty cp, boolean classLevelField) {
    if ("otherDeps".equals(cp.baseName)) {
      System.out.println("here");
    }

    if ("DateTime".equals(cp.complexType) || "Date".equals(cp.complexType)) {
      cp.isDateTime = "date-time".equals(cp.getDataFormat());
      cp.isDate = "date".equals(cp.getDataFormat());
    }

    if ("dynamic".equals(cp.complexType)) {
      cp.isAnyType = true;
      cp.isPrimitiveType = true;
    }
    // detect wether the schema follows the pattern:
    // "{ "nullable" : true, "allOf" : [ { "$ref" : <SomeEnum> } ]}",
    // if yes, treat it like an enum.
    // this is a fix for https://github.com/dart-ogurets/dart-openapi-maven/issues/48
    boolean isComposedEnum = false;
    if (cp.isModel) {
      try {
        ObjectNode schemaNode = (ObjectNode) ObjectMapperFactory.createJson().readTree(cp.jsonSchema);
        Schema<?> parsedSchema = new OpenAPIDeserializer().getSchema(schemaNode, "", null);
        if (parsedSchema instanceof ComposedSchema) {
          ComposedSchema composedSchema = (ComposedSchema) parsedSchema;
          @SuppressWarnings("rawtypes") // cannot be fixed unless upstream changes its signatures to Schema<?>
          List<Schema> allOfArray = composedSchema.getAllOf();
          @SuppressWarnings("rawtypes") // cannot be fixed unless upstream changes its signatures to Schema<?>
          List<Schema> oneOfArray = composedSchema.getOneOf();
          @SuppressWarnings("rawtypes") // cannot be fixed unless upstream changes its signatures to Schema<?>
          List<Schema> anyOfArray = composedSchema.getAnyOf();
          if (allOfArray.size() == 1 && anyOfArray == null || anyOfArray.isEmpty() && oneOfArray == null
            || oneOfArray.isEmpty()) {
            String singleRef = allOfArray.get(0).get$ref();
            if (singleRef != null) {
              Matcher extractLastPathComponent = Pattern.compile("#\\/components\\/schemas\\/([a-zA-Z0-9\\.\\-_]+)")
                .matcher(singleRef);
              if (extractLastPathComponent.matches()) {
                List<?> referencedEnum = openAPI.getComponents().getSchemas().get(extractLastPathComponent.group(1))
                  .getEnum();
                if (referencedEnum != null && !referencedEnum.isEmpty()) {
                  isComposedEnum = true;
                  cp.isModel = false;
                }
              }
            }
          }
        }
      } catch (Exception e) {
        log.error(
          "An Exception was thrown attempting to determine if model {} is an enum wrapped in an allOf composition: {}",
          model.name, e.toString());
        // continue Execution
      }
    }
    if ((cp.allowableValues != null && cp.allowableValues.get("enumVars") != null) || isComposedEnum) {
      // "internal" enums
      if (cp.getMin() == null && cp.complexType == null) {
        cp.enumName = model.classname + cp.nameInCamelCase + "Enum";
        if (cp.isArray) {
          cp.dataType = "List<" + cp.enumName + listOrMapSuffix(cp) + ">";
        } else if (cp.isMap) {
          cp.dataType = "Map<String, " + cp.enumName + listOrMapSuffix(cp) + ">";
        } else {
          cp.dataType = cp.enumName + (isNullSafeEnabled ? (cp.required ? "" : "?") : "");

          if (cp.defaultValue != null) {
            if (cp.defaultValue.startsWith("'") && cp.defaultValue.endsWith("'")) {
              cp.defaultValue = cp.defaultValue.substring(1, cp.defaultValue.length() - 1);
            }
            cp.defaultValue = cp.dataType + "." + cp.defaultValue;
          }
        }

        // we need to add this to the serialiser otherwise it will not correctly serialize
        if (extraInternalEnumProperties.stream().noneMatch(p -> p.enumName.equals(cp.enumName))) {
          extraInternalEnumProperties.add(cp);
        }
      } else {
        cp.enumName = cp.complexType;
        if (isNullSafeEnabled) {
          cp.dataType = cp.enumName + (cp.required ? "" : "?");
        }
      }
      cp.datatypeWithEnum = cp.dataType;
      cp.isEnum = true;
      cp.isPrimitiveType = false;
    }

    // now push the required down to items if it is on the parent
    if (cp.required && cp.items != null) {
      cp.items.required = cp.required;
    }

    // rewrite the entire map/list thing
    if (classLevelField && !cp.isEnum) {
      if (cp.isMap && cp.items != null) {
        String inner = nullGenChild(cp.items);
        cp.vendorExtensions.put("x-innerMapType", inner);

        cp.dataType = "Map<String, " + inner + ">" + (isNullSafeEnabled ? ((cp.required) ? "" : "?") : "");
      } else if (cp.isArray && cp.items != null) {
        String inner = nullGenChild(cp.items);
        cp.dataType = "List<" + inner + ">" + (isNullSafeEnabled ?
          ((cp.required || arraysThatHaveADefaultAreNullSafe) ? "" : "?") : "");
        if (!isNullSafeEnabled || (!(cp.required || arraysThatHaveADefaultAreNullSafe))) {
          cp.vendorExtensions.put("x-list-null", Boolean.TRUE);
        }
      } else if (!cp.required && isNullSafeEnabled && !"dynamic".equals(cp.dataType)) {
        cp.dataType = cp.dataType + "?";
      }

      cp.vendorExtensions.put("x-innerType", cp.dataType);
    }

    if (classLevelField && cp.items == null) {
      cp.vendorExtensions.put("x-not-nullable", cp.required || !cp.isNullable);
    }

    // if we have a weird format which is a String/int/etc with an unknown format
    if (cp.isString && cp.complexType != null) {
//      cp.vendorExtensions.put("x-extendedType", Boolean.TRUE);
      cp.isModel = true;
      if (extraPropertiesThatUseExternalFormatters.stream().noneMatch(c -> c.complexType.equals(cp.complexType))) {
        extraPropertiesThatUseExternalFormatters.add(cp);
      }
    }

    // now allow arrays to be non nullable by making them empty. Only operates on 1st level because
    // it affects the constructor and defaults of top level fields
    if (classLevelField && cp.isArray && arraysThatHaveADefaultAreNullSafe && cp.defaultValue != null) {
      cp.vendorExtensions.put("x-ns-default-val", Boolean.TRUE);
      if (cp.items != null) { // it should be not null, its an array
        cp.items.vendorExtensions.put("x-ns-default-val", Boolean.TRUE);
      }
      @SuppressWarnings("unchecked") // type-unsafe upstream api
      List<CodegenProperty> props = (List<CodegenProperty>) model.vendorExtensions.get("x-ns-default-vals");
      if (props == null) {
        props = new ArrayList<>();
        model.vendorExtensions.put("x-ns-default-vals", props);
        model.vendorExtensions.put("x-has-ns-default-vals", Boolean.TRUE);
      }
      props.add(cp);
    }
  }

  private String nullGenChild(CodegenProperty cp) {
    String val;

    if (cp.isMap) {
      val = "Map<String, " + nullGenChild(cp.items) + ">" + listOrMapSuffix(cp);
    } else if (cp.isArray) {
      val = "List<" + nullGenChild(cp.items) + ">" + (isNullSafeEnabled ?
        ((cp.required || arraysThatHaveADefaultAreNullSafe) ? "" : "?") : "");
      if (!isNullSafeEnabled) {
        cp.vendorExtensions.put("x-list-null", Boolean.TRUE);
      }
    } else {
      val = cp.dataType + listOrMapSuffix(cp);
      cp.vendorExtensions.put("x-not-nullable", cp.required || !cp.isNullable);
    }

    cp.vendorExtensions.put("x-innerType", val);

    return val;
  }

  // this allows us to keep track of files to remove at the end
  // because of a bug in the OpenAPI generator around Form models
  private List<String> modelFilesNotToDeleted = new ArrayList<>();

  // for debugging inevitable weirdness in the model generated
  @Override
  public Map<String, Object> updateAllModels(Map<String, Object> objs) {
    super.updateAllModels(objs);

    if (listAnyOf) {
      // add models for List<AnyOf<*>> classes
      extraAnyOfClasses.values().forEach(anyOfClass -> {
        final Map<String, Object> modelMap = anyOfClass.toModelMap(this);

        modelMap.put("isUsedModel", "true");
        modelFilesNotToDeleted.add(anyOfClass.fileName + ".dart");

        objs.put(anyOfClass.fileName, modelMap);
      });
    }

    Map<String, CodegenModel> allModels = new HashMap<>();

    objs.values().forEach(o -> {
      @SuppressWarnings("unchecked") // type-unsafe upstream api
      Map<String, Object> modelData = (Map<String, Object>) o;
      @SuppressWarnings("unchecked") // type-unsafe upstream api
      List<Map<String, Object>> models = (List<Map<String, Object>>) modelData.get("models");

      if (models != null) {
        models.forEach(modelMap -> {
          CodegenModel cm = (CodegenModel) modelMap.get("model");
          if (cm == null) {
            return;
          }

          // if NOT an inline object we will definitely use it. Only BODY based inline
          // objects will be otherwise used.
          if (!cm.getName().startsWith("inline_object")) {
            cm.vendorExtensions.put("isUsedModel", "true");
            modelMap.put("isUsedModel", "true");
            modelFilesNotToDeleted.add(cm.classFilename + ".dart");
          }

          if (!cm.getName().endsWith("_allOf")) {
            allModels.put(cm.getName(), cm);
          }
          cm.vendorExtensions.put("dartClassName",
            org.openapitools.codegen.utils.StringUtils.camelize(cm.getClassname()));
          if (cm.vars != null) {
            cm.vars.forEach(cp -> {
              CodegenProperty correctingSettings = cp;

              boolean classLevelField = true;
              while (correctingSettings != null) {
                correctInternals(cm, correctingSettings, classLevelField);
                classLevelField = false;
                correctingSettings = correctingSettings.items;
              }
            });
          }
        });
      }
    });

    updateModelsWithAllOfInheritance(allModels);

    return objs;
  }



  // TODO: check with multiple levels of hierarchy
  private void updateModelsWithAllOfInheritance(Map<String, CodegenModel> models) {
    for (CodegenModel cm : models.values()) {
      if (cm.getParent() != null) {
        CodegenModel parent = models.get(cm.getParent());
        if (parent == null) {
          log.info("Cannot find parent for class {}:{}", cm.getName(), cm.getParent());
          continue;
        }

        Map<String, CodegenProperty> props =
          parent.getVars().stream().collect(Collectors.toMap(CodegenProperty::getName, f -> f));

        cm.getVars().forEach((v) -> {
          CodegenProperty matchingName = props.get(v.getName());
          if (matchingName != null) {
            v.vendorExtensions.put("x-dart-inherited", Boolean.TRUE);
          }
        });
      }
    }
  }

  // for debugging inevitable weirdness in the operations generated. DO NOT MODIFY THE MODEL - it has already been
  // generated to the file
  // system
  @Override
  public Map<String, Object> postProcessOperationsWithModels(Map<String, Object> objs, List<Object> allModels) {
    processImportMappings();
    processPubspecMappings();

    final Map<String, Object> som = super.postProcessOperationsWithModels(objs, allModels);
    @SuppressWarnings("unchecked") // type-unsafe upstream api
    final List<CodegenOperation> ops = (List<CodegenOperation>) ((Map<String, Object>) objs.get("operations")).get(
      "operation");

    // at this point, all the model files have actually already been generated to disk, that horse has bolted. What
    // we need to do is figured out which models are "form" based and are not required. Basically anything that is
    // directly used by by a form post
    @SuppressWarnings("unchecked") // type-unsafe upstream api
    final List<Map<String, Object>> models = allModels.stream().map(m -> (Map<String, Object>)m).collect(Collectors.toList());
    Map<String, CodegenModel> modelMap = new HashMap<>();
    Map<String, Map<String, Object>> modelMetaMap = new HashMap<>();
    models.forEach(m -> {
      CodegenModel cm = (CodegenModel) m.get("model");
      modelMap.put(cm.getClassname(), cm);
      modelMetaMap.put(cm.getClassname(), m);
    });

    for (CodegenOperation co : ops) {
      final Object richOp = co.vendorExtensions.get("x-dart-rich-operationId");
      if (richOp != null) {
        co.vendorExtensions.put("x-dart-extension-name", toVarName(richOp.toString()));
      }

      boolean bodyIsFile =
        (co.consumes != null && co.consumes.size() == 1 && "application/octet-stream".equals(co.consumes.get(0).get(
          "mediaType")));

      if (co.bodyParam != null) {
        CodegenModel cm = modelMap.get(co.bodyParam.baseType);
        if (cm != null) {
          cm.vendorExtensions.put("isUsedModel", "true");
          modelFilesNotToDeleted.add(cm.classFilename + ".dart");
          modelMetaMap.get(co.bodyParam.baseType).put("isUsedModel", "true");
        }
      }

      co.allParams.forEach((p) -> {
        if (p.isFile || p.isBinary || (p.isBodyParam && bodyIsFile)) {
          if (p.isArray) {
            p.dataType = "List<MultipartFile>";
          } else {
            p.dataType = "MultipartFile";
          }

          p.baseType = p.dataType;
        }
      });
    }


    return som;
  }

  @Override
  @SuppressWarnings("rawtypes") // cannot be fixed unless upstream changes its signatures to Schema<?>
  public String toDefaultValue(Schema schema) {
    // default inherited one uses const, and we can't use that anymore as they are inmodifiable
    if (ModelUtils.isMapSchema(schema)) {
      return "{}";
    } else if (ModelUtils.isArraySchema(schema)) {
      return "[]";
    } else if (schema.getDefault() != null) {
      String s = ModelUtils.isStringSchema(schema) ? "'" + schema.getDefault().toString().replace("'", "\\'") + "'" :
        schema.getDefault().toString();
      return ("null".equals(s)) ? null : s;
    } else {
      return null;
    }
  }

  @Override
  @SuppressWarnings("rawtypes") // cannot be fixed unless upstream changes its signatures to Schema<?>
  protected void addAdditionPropertiesToCodeGenModel(CodegenModel codegenModel, Schema schema) {
    //super.addAdditionPropertiesToCodeGenModel(codegenModel, schema);
    codegenModel.additionalPropertiesType = getSchemaType(ModelUtils.getAdditionalProperties(openAPI, schema));
    addImport(codegenModel, codegenModel.additionalPropertiesType);
  }

  @Override
  public void addOperationToGroup(String tag, String resourcePath, Operation operation, CodegenOperation co,
                                  Map<String, List<CodegenOperation>> operations) {
    super.addOperationToGroup(tag, resourcePath, operation, co, operations);

    tagOperationWithExtension(co.consumes, co.vendorExtensions, "consumes");
    tagOperationWithExtension(co.produces, co.vendorExtensions, "produces");

    final String codes = co.responses.stream().map(r -> r.code).collect(Collectors.joining(","));
    co.vendorExtensions.put("x-valid-status-codes", codes);

    if (co.returnType == null && co.responses.stream().anyMatch(r -> "204".equals(r.code))) {
      co.vendorExtensions.put("x-return-no-content", Boolean.TRUE);
    }
  }

  private void tagOperationWithExtension(List<Map<String, String>> mediaTypes, Map<String, Object> extensions,
                                         String midfix) {
    List<String> lowerCaseContentTypes = mediaTypes != null ?
      mediaTypes.stream().map(p -> p.get("mediaType").toLowerCase()).collect(Collectors.toList()) : new ArrayList<>();
    String prefix = "x-dart-" + midfix + "-";
    if (lowerCaseContentTypes.contains("application/json")) {
      extensions.put(prefix + "json", "application/json");
    } else if (lowerCaseContentTypes.contains("application/x-www-form-urlencoded")) {
      extensions.put(prefix + "form", "application/x-www-form-urlencoded");
    } else if (lowerCaseContentTypes.contains("multipart/form-data")) {
      extensions.put(prefix + "multipartform", "multipart/form-data");
    } else if (lowerCaseContentTypes.contains("application/xml")) {
      extensions.put(prefix + "xml", "application/xml");
    } else if (lowerCaseContentTypes.contains("application/yaml")) {
      extensions.put(prefix + "yaml", "application/yaml");
    } else if (lowerCaseContentTypes.contains("application/octet-stream")) {
      extensions.put(prefix + "raw", "application/octet-stream");
    } else {
      extensions.put(prefix + "json", "application/json"); // fallback
    }
  }

  // existing one is unpleasant to use.
  @Override
  public String toEnumVarName(String value, String datatype) {
    if (value.length() == 0) {
      return "empty";
    }
    String var = value;
    if ("number".equalsIgnoreCase(datatype) ||
      "int".equalsIgnoreCase(datatype)) {
      var = "Number" + var;
    }
    return toVarName(escapeReservedWord(var));
  }

//  Map<String, String> modelNameCache = new HashMap<>();

  @Override
  public String toModelName(String modelName) {
    return ("dynamic".equals(modelName) ? modelName : super.toModelName(modelName));
  }

  @Override
  public void postProcess() {
    String flutterDir = System.getenv("FLUTTER") == null ? System.getenv("DART_BIN") : System.getenv("FLUTTER");

    // we have to do this at the end because we don't know which files to fully delete until
    // after the Operations have been processed, and the post process per file thing happens
    // after each step. We have to do it before the Dart Formatting otherwise it will explode
    // with unreferenced files.
    try {
      final int stripLen = (getOutputDir() + "/lib/model/").length();
      Files.walk(Paths.get(getOutputDir() + "/lib/model"))
        .filter(Files::isRegularFile)
        .forEach(p -> {
          String modelFilename = p.toFile().getAbsolutePath().substring(stripLen);
          if (!modelFilesNotToDeleted.contains(modelFilename)) {
            p.toFile().delete();
          }
        });
    } catch (IOException ignored) {}

    if (flutterDir != null && isEnablePostProcessFile()) {
//      String dartPostProcessFixFile = String.format("%s/bin/cache/dart-sdk/bin/dart fix --apply %s", flutterDir,
//        getOutputDir());
      String dartPostProcessFile = String.format("%s/bin/cache/dart-sdk/bin/dartfmt -w %s", flutterDir, getOutputDir());

      try {
//        log.info("auto-fixing generated issues");
//        final Process fix = Runtime.getRuntime().exec(dartPostProcessFixFile);
//        outputStreamToConsole(fix);
//        fix.waitFor();
        log.info("formatting");
        final Process fmt = Runtime.getRuntime().exec(dartPostProcessFile);
        outputStreamToConsole(fmt);
        fmt.waitFor();
      } catch (Exception e) {
        log.error("Unable to run dart fix command");
      }
    }
  }

  private void outputStreamToConsole(Process proc) throws Exception {
    String line;
    InputStreamReader isr = new InputStreamReader(proc.getInputStream());
    BufferedReader rdr = new BufferedReader(isr);
    while ((line = rdr.readLine()) != null) {
      System.out.println(line);
    }

    isr = new InputStreamReader(proc.getErrorStream());
    rdr = new BufferedReader(isr);
    while ((line = rdr.readLine()) != null) {
      System.out.println(line);
    }
  }

  @Override
  public String toAnyOfName(List<String> names, ComposedSchema composedSchema) {
    if (listAnyOf) {
      // create models for List<anyOf> classes that have discriminator
      if (composedSchema.getDiscriminator() != null) {
        // ensure alphabetical sorting
        names = new ArrayList<String>(names);
        Collections.sort(names);
        List<String> namesFilename = names.stream().map(this::toModelFilename).collect(Collectors.toList());
        List<String> namesCapitalized = names.stream().map(StringUtils::capitalize).collect(Collectors.toList());
        String className = "AnyOf" + String.join("", namesCapitalized);
        String enumClassName = "AnyOfDiscriminator" + String.join("", namesCapitalized);
        String fileName = "any_of_" + String.join("_", namesFilename);
        String filePart = "model/" + fileName + ".dart";
        // collect any of classes
        extraAnyOfClasses.put(className, new AnyOfClass(fileName, filePart, toModelName(className),
          toModelName(enumClassName), composedSchema));
        extraAnyParts.add(filePart);
        return className;
      }
    }
    return super.toAnyOfName(names, composedSchema);
  }

  static class AnyOfClass {
    final String fileName;
    final String filePart;
    final String className;
    final String enumClassName;
    final ComposedSchema composedSchema;
    final Discriminator discriminatorProperty;

    AnyOfClass(String fileName, String filePart, String className, String enumClassName,
               ComposedSchema composedSchema) {
      this.fileName = fileName;
      this.filePart = filePart;
      this.className = className;
      this.enumClassName = enumClassName;
      this.composedSchema = composedSchema;
      discriminatorProperty = composedSchema.getDiscriminator();
      assert (discriminatorProperty != null);
    }

    Map<String, Object> toModelMap(DartV3ApiGenerator generator) {
      Map<String, Object> data = new HashMap<>();
      List<Map<String, Object>> innerTypes = new ArrayList<>();
      List<String> _types = new ArrayList<String>();
      data.put("pubName", generator.pubName);
      data.put("isAnyOfClass", true);
      data.put("classname", className);
      data.put("enumClassname", enumClassName);
      data.put("discriminatorProperty", discriminatorProperty.getPropertyName());
      data.put("innerTypes", innerTypes);
      data.put("nullSafe", generator.additionalProperties.get("nullSafe"));

      composedSchema.getAnyOf().stream().forEach(schema -> {
        String ref = schema.get$ref();
        String type = generator.getTypeDeclaration(schema);
        _types.add(type);
        String discriminatorValue = ModelUtils.getSimpleRef(ref);
        // lookup discriminator mappings if there any
        if (discriminatorProperty.getMapping() != null) {
          for (Entry<String, String> e : discriminatorProperty.getMapping().entrySet()) {
            if (e.getValue().equals(ref)) {
              discriminatorValue = e.getKey();
            }
          }
        }
        innerTypes.add(createType(type, discriminatorValue));
      });

      Collections.sort(_types);
      data.put("anyOfTemplate", String.join(",", _types));

      return data;
    }

    private static Map<String, Object> createType(String classname, String discriminatorValue) {
      assert (classname != null);
      assert (discriminatorValue != null);
      Map<String, Object> data = new HashMap<>();
      data.put("classname", classname);
      data.put("discriminatorValue", discriminatorValue);
      return data;
    }
  }
}
