Plain-JVM logic tests (no Android needed). From the repo root, with kotlinc (JAVA_OPTS=-Xmx2g):
  kotlinc tests/Stubs.kt app/src/main/java/com/pragon/mobile/{Calc,Typer,VoiceParser,V9Voice}.kt tests/ParserTest.kt  -include-runtime -d p1.jar && java -jar p1.jar
  kotlinc tests/Stubs.kt app/src/main/java/com/pragon/mobile/{Calc,Typer,VoiceParser,V9Voice}.kt tests/ParserTest2.kt -include-runtime -d p2.jar && java -jar p2.jar
  kotlinc app/src/main/java/com/pragon/mobile/Calc.kt tests/CalcTest.kt -include-runtime -d calc.jar && java -jar calc.jar
GuardTest.kt (privacy-shield host rules) also needs android.jar + okhttp on the classpath, so it is run in CI only if you add them.
TimeExprTest.kt (reminder / calendar time phrases, v8): kotlinc app/src/main/java/com/pragon/mobile/TimeExpr.kt tests/TimeExprTest.kt -include-runtime -d te.jar && java -jar te.jar   (set TZ if you like; it uses the machine's zone)
V10 tests: kotlinc tests/Stubs.kt app/src/main/java/com/pragon/mobile/Calc.kt .../Typer.kt .../VoiceParser.kt .../V9Voice.kt .../V10Voice.kt .../ToolCatalog.kt tests/V10Test.kt -include-runtime -d v10.jar && java -jar v10.jar
V11 tests: kotlinc tests/Stubs.kt app/src/main/java/com/pragon/mobile/Calc.kt .../Typer.kt .../VoiceParser.kt .../V9Voice.kt .../V11Voice.kt tests/V11Test.kt -include-runtime -d v11.jar && java -jar v11.jar
V12 tests: kotlinc tests/Stubs.kt app/src/main/java/com/pragon/mobile/Calc.kt .../Typer.kt .../VoiceParser.kt .../V9Voice.kt .../V11Voice.kt .../SherpaFiles.kt tests/V12Test.kt -include-runtime -d v12.jar && java -jar v12.jar
