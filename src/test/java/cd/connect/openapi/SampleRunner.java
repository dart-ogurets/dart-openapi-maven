package cd.connect.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.Test;
import org.openapitools.codegen.OpenAPIGenerator;

import java.util.Arrays;

public class SampleRunner {
  @Test
  public void runGenerator() {
//    String location = getClass().getResource("/test.yaml").getFile();
    String location = getClass().getResource("/k8s_null_test.yml").getFile();
//    OpenAPIGenerator.main(Arrays.asList("help", "generate").toArray(new String[0]));

    OpenAPIGenerator.main(Arrays.asList("generate",
      "--input-spec", location,
      "--generator-name", "dart2-api",
      "--additional-properties", "pubName=sample_app",
      "--type-mappings", "int-or-string=IntOrString",
      "--import-mappings", "IntOrString=./int_or_string.dart",
      "--global-property", "skipFormModel=false",
      "--additional-properties", "nullSafe=true",
      "--additional-properties", "nullSafe-array-default=true",
      "--output", "target/" + getClass().getSimpleName())
      .toArray(new String[0]));
  }

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
}
