package cd.connect.openapi;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.openapitools.codegen.CodegenConfig;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.SupportingFile;
import org.openapitools.codegen.languages.DartClientCodegen;
import org.openapitools.codegen.utils.ModelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class DartV3ApiGenerator extends DartClientCodegen implements CodegenConfig {
  private static final Logger log = LoggerFactory.getLogger(DartV3ApiGenerator.class);
  private static final String LIBRARY_NAME = "dart2-api";
  private static final String DART2_TEMPLATE_FOLDER = "dart2-v3template";
  private static final String API_PRODUCES_RAW_STREAM = "vendorExtensions.x-dart-produces-raw";
  private static final String ARRAYS_WITH_DEFAULT_VALUES_ARE_NULLSAFE = "nullSafe-array-default";
  protected boolean arraysThatHaveADefaultAreNullSafe;
  protected boolean isNullSafeEnabled;

  public DartV3ApiGenerator() {
    super();
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
  public CodegenModel fromModel(String name, Schema schema) {
    return super.fromModel(name, schema);
  }

  @Override
  public String toVarName(String name) {
    if (reservedWordsMappings.containsKey(name)) {
      return reservedWordsMappings().get(name);
    }

    name = name.replaceAll("-", "_")
      .replaceAll("\\$", "__")
      .replaceAll("\\^", "__")
      .replaceAll("\\=", "__");

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
    if (cp.isNullable && isNullSafeEnabled) {
      return "?";
    }

    return "";
  }

  private void correctInternals(CodegenModel model, CodegenProperty cp, boolean classLevelField) {
    if ("DateTime".equals(cp.complexType) || "Date".equals(cp.complexType)) {
      cp.isDateTime = "date-time".equals(cp.getDataFormat());
      cp.isDate = "date".equals(cp.getDataFormat());
    }

    if ("dynamic".equals(cp.complexType)) {
      cp.isAnyType = true;
      cp.isPrimitiveType = true;
    }

    if (cp.allowableValues != null && cp.allowableValues.get("enumVars") != null) {
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

        cp.dataType = "Map<String, " + inner + ">" +  (isNullSafeEnabled ? ((cp.required) ? "" : "?") : "");
      } else if (cp.isArray && cp.items != null) {
        String inner = nullGenChild(cp.items);
        cp.dataType = "List<" + inner + ">" + (isNullSafeEnabled ?
          ((cp.required || arraysThatHaveADefaultAreNullSafe) ? "" : "?") : "");
        if (!isNullSafeEnabled || (!(cp.required || arraysThatHaveADefaultAreNullSafe))) {
          cp.vendorExtensions.put("x-list-null", Boolean.TRUE);
        }
      } else if (!cp.required && isNullSafeEnabled) {
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

  // for debugging inevitable weirdness in the model generated
  @Override
  public Map<String, Object> updateAllModels(Map<String, Object> objs) {
    super.updateAllModels(objs);

    Map<String, CodegenModel> allModels = new HashMap<>();

    objs.values().forEach(o -> {
      Map<String, Object> modelData = (Map<String, Object>) o;
      List<Map<String, Object>> models = (List<Map<String, Object>>) modelData.get("models");
      if (models != null) {
        models.forEach(modelMap -> {
          CodegenModel cm = (CodegenModel) modelMap.get("model");
          if (cm == null) {
            return;
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
    final List<CodegenOperation> ops = (List<CodegenOperation>) ((Map<String, Object>) objs.get("operations")).get(
      "operation");
    for (CodegenOperation co : ops) {
      final Object richOp = co.vendorExtensions.get("x-dart-rich-operationId");
      if (richOp != null) {
        co.vendorExtensions.put("x-dart-extension-name", toVarName(richOp.toString()));
      }
    }
    return som;
  }

  @Override
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
//    return modelNameCache.computeIfAbsent(modelName, name -> {
//      if ("dynamic".equals(name)) {
//        return name;
//      }
//
//      String prefix = "";
//      if (name.contains(".")) {
//        final int endIndex = name.lastIndexOf(".");
//        prefix = name.substring(0, endIndex).replace('-', '_');
//        if (prefix.length() > 0) {
//          prefix = prefix + ".";
//        }
//        name = name.substring(endIndex+1);
//      }
//
//      if (this.isReservedWord(name)) {
//        log.warn(name + " (reserved word) cannot be used as model filename. Renamed to " + org.openapitools.codegen
//        .utils.StringUtils.camelize("model_" + name));
//        name = "model_" + name;
//      }
//
//      if (name.matches("^\\d.*")) {
//        log.warn(name + " (model name starts with number) cannot be used as model name. Renamed to " + org
//        .openapitools.codegen.utils.StringUtils.camelize("model_" + name));
//        name = "model_" + name;
//      }
//
//      return prefix + org.openapitools.codegen.utils.StringUtils.camelize(name);
//    });
  }

  // if $FLUTTER is set, format the file.
  @Override
  public void postProcessFile(File file, String fileType) {
    String flutterDir = System.getenv("FLUTTER");

    if (file == null || flutterDir == null) {
      System.out.println("Cannot post process file");
      return;
    }

    String dartPostProcessFile = String.format("%s/bin/cache/dart-sdk/bin/dartfmt -w", flutterDir);

    if (StringUtils.isEmpty(dartPostProcessFile)) {
      return; // skip if DART_POST_PROCESS_FILE env variable is not defined
    }

    // only process files with dart extension
    if ("dart".equals(FilenameUtils.getExtension(file.toString()))) {
      String command = dartPostProcessFile + " " + file.toString();
      try {
        log.info("Executing: " + command);
        Process p = Runtime.getRuntime().exec(command);
//        int exitValue = p.waitFor();
//        if (exitValue != 0) {
//          log.error("Error running the command ({}). Exit code: {}", command, exitValue);
//        } else {
//          log.info("Successfully executed: " + command);
//        }
      } catch (Exception e) {
        log.error("Error running the command ({}). Exception: {}", command, e.getMessage());
      }
    }
  }
}
