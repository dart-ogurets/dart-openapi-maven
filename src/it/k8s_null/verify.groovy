
// a wrapper closure around executing a string
// can take either a string or a list of strings (for arguments with spaces)
// prints all output, complains and halts on error
def runCommand = { strList ->
  assert ( strList instanceof String ||
    ( strList instanceof List && strList.each{ it instanceof String } ) \
)
  def proc = strList.execute(null, new File("target/it/k8s_null"))
  proc.in.eachLine { line -> println line }
  proc.out.close()
  proc.waitFor()

  print "[INFO] ( "
  if(strList instanceof List) {
    strList.each { print "${it} " }
  } else {
    print strList
  }
  println " )"

  if (proc.exitValue()) {
    println "gave the following error: "
    println "[ERROR] ${proc.getErrorStream()}"
  }
  assert !proc.exitValue()
}

runCommand("pub get")
runCommand("dartanalyzer lib")
runCommand("pub run test test/api_tests.dart")

//System.exit(0)
