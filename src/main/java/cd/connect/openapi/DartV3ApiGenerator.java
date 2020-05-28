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
import java.net.URLDecoder;
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
		return "dart2 api generator. generates all classes and interfaces with jax-rs annotations with jersey2 extensions as necessary";
	}

  @Override
  public void processOpts() {
    additionalProperties.put(SUPPORT_DART2, Boolean.TRUE);

    super.processOpts();

    // replace Object with dynamic
    this.typeMapping.put("object", "dynamic");

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
  }

  /**
   * People because they are able to include custom mappings, may need to include 3rd party libraries in their pubspec.
   */
  private void processPubspecMappings() {
    deps("pubspec-dependencies", "x-dart-deps");
    deps("pubspec-dev-dependencies", "x-dart-devdeps");
  }

  private void deps(String addnPropertiesTag, String vendorPrefix) {
    String depsKey = (String)additionalProperties.get(addnPropertiesTag);
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
  public String toVarName(String name) {
    name = name.replaceAll("-", "_").replaceAll("\\$", "__");

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
	    String modelName = name.substring(lastIndex+1);
      return path + File.separator + org.openapitools.codegen.utils.StringUtils.underscore(this.toModelName(modelName));
    } else {
      return org.openapitools.codegen.utils.StringUtils.underscore(this.toModelName(name));
    }
  }

  // for debugging inevitable weirdness in the model generated
  @Override
  public Map<String, Object> updateAllModels(Map<String, Object> objs) {
    Map<String, CodegenModel> allModels = new HashMap<>();
	  objs.values().forEach(o -> {
	    Map<String, Object> modelData = (Map<String, Object>)o;
	    List<Map<String, Object>> models = (List<Map<String, Object>>) modelData.get("models");
	    if (models != null) {
	      models.forEach(modelMap -> {
          CodegenModel cm = (CodegenModel)modelMap.get("model");
          if (cm == null) {
            return;
          }
          if (!cm.getName().endsWith("_allOf")) {
            allModels.put(cm.getName(), cm);
          }
          cm.vendorExtensions.put("dartClassName", org.openapitools.codegen.utils.StringUtils.camelize(cm.getClassname()));
          if (cm.vars != null) {
            cm.vars.forEach(cp -> {
              if (cp.items != null && cp.items.allowableValues != null && cp.items.allowableValues.get("enumVars") != null) {
                cp.items.enumName = cp.items.complexType;
              }
              if (cp.allowableValues != null && cp.allowableValues.get("enumVars") != null) {
                cp.enumName = cp.complexType;
              }
              if ("DateTime".equals(cp.complexType)) {
                cp.vendorExtensions.put("x-dart-datetime", Boolean.TRUE);
              }
              if ("dynamic".equals(cp.complexType)) {
                cp.vendorExtensions.put("x-dart-dynamic", Boolean.TRUE);
              }
              if (cp.items != null && "dynamic".equals(cp.items.complexType)) {
                cp.items.vendorExtensions.put("x-dart-dynamic", Boolean.TRUE);
              }
              if (cp.items != null && "DateTime".equals(cp.items.complexType)) {
                cp.items.vendorExtensions.put("x-dart-datetime", Boolean.TRUE);
              }

              if (cp.isMapContainer && cp.items != null && cp.items.items != null) {
                if ("DateTime".equals(cp.items.items.complexType)) {
                  cp.items.items.vendorExtensions.put("x-dart-datetime", Boolean.TRUE);
                }
                if ("dynamic".equals(cp.items.items.complexType)) {
                  cp.items.items.vendorExtensions.put("x-dart-dynamic", Boolean.TRUE);
                }
                if (cp.items.items.allowableValues != null && cp.items.items.allowableValues.size() > 0) {
                  cp.items.items.vendorExtensions.put("x-dart-isenum", Boolean.TRUE);
                }
              }

              if (cp.isListContainer && cp.getComplexType() != null) {
                if ("dynamic".equals(cp.complexType)) {
                  cp.vendorExtensions.put("x-dart-dynamic", Boolean.TRUE);
                  if (cp.items != null) {
                    // it seems to have a bug in the openapi parser where it doesn't put it at the right level (dynamic)
                    cp.items.vendorExtensions.put("x-dart-dynamic", Boolean.TRUE);
                  }
                }
              }

              if (cp.isListContainer && cp.items != null) {
                if ("dynamic".equals(cp.items.complexType)) {
                  cp.items.vendorExtensions.put("x-dart-dynamic", Boolean.TRUE);
                }
                if (cp.items.allowableValues != null && cp.items.allowableValues.size() > 0) {
                  cp.items.vendorExtensions.put("x-dart-isenum", Boolean.TRUE);
                }
                if ("DateTime".equals(cp.items.complexType)) {
                  cp.items.vendorExtensions.put("x-dart-datetime", Boolean.TRUE);
                }
              }
            });
          }
        });

      }
    });

	  updateModelsWithAllOfInheritance(allModels);

    return super.updateAllModels(objs);
  }

  // TODO: check with multiple levels of hierarchy
  private void updateModelsWithAllOfInheritance(Map<String, CodegenModel> models) {
    models.values().forEach(cm -> {
      if (cm.getParent() != null) {
        CodegenModel parent = models.get(cm.getParent());
        if (parent == null) {
          log.info("Cannot find parent for class {}:{}", cm.getName(), cm.getParent());
          return;
        }

        Map<String, CodegenProperty> props = parent.getVars().stream().collect(Collectors.toMap(CodegenProperty::getName, f -> f));

        cm.getVars().forEach((v) -> {
          CodegenProperty matchingName = props.get(v.getName());
          if (matchingName != null) {
            v.vendorExtensions.put("x-dart-inherited", Boolean.TRUE);
          }
        });
      }
    });
  }

  // for debugging inevitable weirdness in the operations generated. DO NOT MODIFY THE MODEL - it has already been generated to the file
  // system
  @Override
  public Map<String, Object> postProcessOperationsWithModels(Map<String, Object> objs, List<Object> allModels) {
    processImportMappings();
    processPubspecMappings();

    final Map<String, Object> som = super.postProcessOperationsWithModels(objs, allModels);
    return som;
  }

  @Override
  public String toDefaultValue(Schema schema) {
    final String s = super.toDefaultValue(schema);

    if ("null".equals(s)) {
      return null;
    }

    return s;
  }

  @Override
  protected void addAdditionPropertiesToCodeGenModel(CodegenModel codegenModel, Schema schema) {
    //super.addAdditionPropertiesToCodeGenModel(codegenModel, schema);
    codegenModel.additionalPropertiesType = getSchemaType(ModelUtils.getAdditionalProperties(schema));
    addImport(codegenModel, codegenModel.additionalPropertiesType);
  }

  @Override
  public void addOperationToGroup(String tag, String resourcePath, Operation operation, CodegenOperation co, Map<String, List<CodegenOperation>> operations) {
    super.addOperationToGroup(tag, resourcePath, operation, co, operations);

    tagOperationWithExtension(co.consumes, co.vendorExtensions, "consumes");
    tagOperationWithExtension(co.produces, co.vendorExtensions, "produces");
  }

  private void tagOperationWithExtension(List<Map<String, String>> mediaTypes, Map<String, Object> extensions, String midfix) {
    List<String> lowerCaseContentTypes = mediaTypes != null ? mediaTypes.stream().map(p -> p.get("mediaType").toLowerCase()).collect(Collectors.toList()) : new ArrayList<>();
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
    return escapeReservedWord(var);
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
//        log.warn(name + " (reserved word) cannot be used as model filename. Renamed to " + org.openapitools.codegen.utils.StringUtils.camelize("model_" + name));
//        name = "model_" + name;
//      }
//
//      if (name.matches("^\\d.*")) {
//        log.warn(name + " (model name starts with number) cannot be used as model name. Renamed to " + org.openapitools.codegen.utils.StringUtils.camelize("model_" + name));
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
