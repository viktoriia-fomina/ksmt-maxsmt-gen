import java.io.File
import kotlin.io.path.Path

fun main(args: Array<String>) {
    require(args.size == 3) { "Must be passed three command line arguments, but was [${args.size}]" }

    val theoryName = args[0]
    val benchmarkPath = File(args[1])
    val reportsPath = File(args[2])

    require(benchmarkPath.exists()) { "Benchmark path must exist: [$benchmarkPath]" }
    require(reportsPath.exists()) { "Reports path must exist: [$reportsPath]" }

    convertFromDirectoryZ3API(theoryName, benchmarkPath, reportsPath)
}

fun convertFromDirectoryZ3API(theoryName: String, benchmarkPath: File, reportsDir: File) {
    val testConverter = TestConverter()

    benchmarkPath.walk().forEach {
        if (it.extension == "smt2") {
            testConverter.convertTestZ3API(
                it.toPath(),
                Path(it.toString().removeSuffix("smt2") + "maxsmt"),
                theoryName,
                reportsDir,
            )
        }
    }
}

fun convertFromDirectory() {
    TestConverter.initWorkerPools()

    val testConverter = TestConverter()

    File("C:\\Users\\fWX1139906\\github\\MAXSMT_BENCHMARK\\QF_UFBV").walk().forEach {
        if (it.extension == "smt2") {
            testConverter.convertTest(it.toPath(), Path(it.toString().removeSuffix("smt2") + "maxsmt"))
        }
    }

    TestConverter.closeWorkerPools()
}

fun convertFromNotSucceededSuite() {
    TestConverter.initWorkerPools()

    val testConverter = TestConverter()

    File("C:\\Users\\fWX1139906\\github\\ksmt\\ksmt-maxsat-gen\\src\\main\\resources\\not_succeeded.txt").forEachLine {
        if (it.contains("smt2")) {
            testConverter.convertTest(Path(it), Path(it.removeSuffix("smt2") + "maxsmt"))
        }
    }

    TestConverter.closeWorkerPools()
}
