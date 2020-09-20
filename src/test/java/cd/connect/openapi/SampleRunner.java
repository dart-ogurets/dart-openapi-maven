package cd.connect.openapi;

import org.junit.Test;
import org.openapitools.codegen.OpenAPIGenerator;

import java.util.Arrays;

public class SampleRunner {
  @Test
  public void runGenerator() {
    String location = getClass().getResource("/test.yaml").getFile();
    OpenAPIGenerator.main(Arrays.asList("generate",
      "--input-spec", location,
      "--generator-name", "dart2-api",
      "--additional-properties", "pubName=sample_app",
      "--output", "target/" + getClass().getSimpleName())
      .toArray(new String[0]));
  }
}
