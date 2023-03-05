package cd.connect.openapi;

import org.junit.Test;
import org.openapitools.codegen.OpenAPIGenerator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;

public class SampleRunner {
  @Test
  public void runGenerator() {
//    String location = getClass().getResource("/test.yaml").getFile();
    // integration_test.yaml is the same as we release with, so tests must match
    String location = getClass().getResource("/integration_test.yaml").getFile();
//    OpenAPIGenerator.main(Arrays.asList("help", "generate").toArray(new String[0]));

    OpenAPIGenerator.main(Arrays.asList("generate",
      "--input-spec", location,
      "--generator-name", "dart2-api",
      "--enable-post-process-file",
      "--additional-properties", "pubName=sample_app",
//      "--additional-properties", "disableCopyWith",
//      "--type-mappings", "int-or-string=IntOrString",
//      "--import-mappings", "IntOrString=./int_or_string.dart",
//      "--global-property", "skipFormModel=false",
//      "--additional-properties", "x-use-5x-nullable=true",
      "--additional-properties", "nullSafe=true",
      "--additional-properties", "nullSafe-array-default=true",
      "--additional-properties", "localDev=/Users/richard/projects/dart/openapi_dart_common",
        "--additional-properties", "listAnyOf=true",
      "--output", "sample-app/" + getClass().getSimpleName())
      .toArray(new String[0]));
  }

//  @Test
//  public void runGeneratorWithListAnyOfNNBD() {
//    // given
//    String schemaLocation = getClass().getResource("/list_any_of_test.yml").getFile();
//    String outputFolder = "target/" + getClass().getSimpleName()+"_list_any_of_test_NNBD";
//
//    // when
//    OpenAPIGenerator.main(Arrays.asList("generate",
//      "--input-spec", schemaLocation,
//      "--generator-name", "dart2-api",
//      "--additional-properties", "pubName=sample_app",
//      "--additional-properties", "nullSafe=true",
//      "--additional-properties", "nullSafe-array-default=true",
//      "--additional-properties", "listAnyOf=true",
//      "--output", outputFolder)
//      .toArray(new String[0]));
//
//    // then
//    verifyIsDartPackage(outputFolder);
//  }
//
//  @Test
//  public void runGeneratorWithListAnyOfNoNullSafety() {
//    // given
//    String schemaLocation = getClass().getResource("/list_any_of_test.yml").getFile();
//    String outputFolder = "target/" + getClass().getSimpleName()+"_list_any_of_test_no_null_safety";
//
//    // when
//    OpenAPIGenerator.main(Arrays.asList("generate",
//      "--input-spec", schemaLocation,
//      "--generator-name", "dart2-api",
//      "--additional-properties", "pubName=sample_app",
//      // "--additional-properties", "nullSafe=false", // passing anything turns null safety on
//      "--additional-properties", "listAnyOf=true",
//      "--output", outputFolder)
//      .toArray(new String[0]));
//
//    // then
//    verifyIsDartPackage(outputFolder);
//  }

//  @Test
//  public void runExpedia() {
//    String location = getClass().getResource("/expedia.yaml").getFile();
//    OpenAPIGenerator.main(Arrays.asList("generate",
//      "--input-spec", location,
//      "--generator-name", "dart2-api",
//      "--additional-properties", "pubName=sample_app",
//      "--global-property", "skipFormModel=false",
//      "--output", "target/" + getClass().getSimpleName())
//      .toArray(new String[0]));
//  }
//
//  @Test
//  public void runIssue19() {
//    String location = getClass().getResource("/issue-19.json").getFile();
//    OpenAPIGenerator.main(Arrays.asList("generate",
//      "--input-spec", location,
//      "--generator-name", "dart2-api",
//      "--additional-properties", "pubName=sample_app",
//      "--global-property", "skipFormModel=false",
//      "--output", "target/" + getClass().getSimpleName())
//      .toArray(new String[0]));
//  }
//
//  @Test
//  public void runTypeCheck() {
//    String location = getClass().getResource("/sample-types.yaml").getFile();
//    OpenAPIGenerator.main(Arrays.asList("generate",
//      "--input-spec", location,
//      "--generator-name", "dart2-api",
//      "--additional-properties", "pubName=sample_app",
//      "--global-property", "skipFormModel=false",
//      "--output", "target/" + getClass().getSimpleName())
//      .toArray(new String[0]));
//  }

//  @Test
//  public void runFH() {
//    String location = "/Users/richard/projects/fh/featurehub/admin-frontend/app_mr_layer/final.yaml";
//    OpenAPIGenerator.main(Arrays.asList("generate",
//      "--input-spec", location,
//      "--generator-name", "dart2-api",
//      "--additional-properties", "pubName=mrapi",
//      "--additional-properties", "nullSafe=true",
//      "--additional-properties", "nullSafe-array-default=true",
//      "--output", "/Users/richard/projects/fh/featurehub/admin-frontend/app_mr_layer")
//      .toArray(new String[0]));
//  }

  private static void verifyIsDartPackage(String libFolder) {
    String verifyScriptLocation = SampleRunner.class.getResource("/verify_package.sh").getFile();
    int value = execute("sh -e " + verifyScriptLocation + " " + libFolder);
    assert (value == 0);
  }

  private static int execute(String cmd)  {
    try {
      Runtime rt = Runtime.getRuntime();
      Process pr = rt.exec(cmd);
      new Thread(() -> {
        BufferedReader input = new BufferedReader(new InputStreamReader(pr.getInputStream()));
        String line;

        try {
          while ((line = input.readLine()) != null)
            System.out.println(line);
        } catch (IOException e) {
          e.printStackTrace();
        }
      }).start();
      return pr.waitFor();
    } catch (Throwable t) {
      return -1;
    }
  }
}
